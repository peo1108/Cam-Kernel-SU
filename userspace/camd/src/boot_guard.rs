//! Boot guard: disables modules after repeated boots that never reached boot-completed.
//!
//! post-fs-data counts a boot attempt, boot-completed resets the count. When the
//! count reaches the threshold, the modules installed, updated or re-enabled since
//! the last good boot are disabled; with none of those left, every enabled module is.
//!
//! Boots that die before post-fs-data never reach that count; only a module init rc
//! (spliced into init.rc by the kernel) runs that early. For those, the rc itself
//! carries a queue that init shifts at early-init (`guarded_module_rc`), so the
//! boot after `threshold - 1` such failures runs without it.
#![cfg_attr(not(target_os = "android"), allow(dead_code))]

use std::fmt::Write as _;
use std::io::Write;
use std::path::Path;

use anyhow::{Context, Result};
use serde_json::{Value, json};

/// Default boot attempt that triggers the guard: two failed boots, then act on the third.
pub const BOOT_GUARD_THRESHOLD: u32 = 3;
pub const MIN_THRESHOLD: u32 = 2;
pub const MAX_THRESHOLD: u32 = 5;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct BootGuardState {
    pub fail_count: u32,
    /// sorted, unique
    pub suspects: Vec<String>,
    /// sorted, unique
    pub auto_disabled: Vec<String>,
    pub last_trigger: Option<u64>,
    pub enabled: bool,
    /// boot attempt that triggers, `MIN_THRESHOLD..=MAX_THRESHOLD`
    pub threshold: u32,
    /// disable every enabled module instead of the suspects first
    pub disable_all: bool,
    /// a trigger the user has not been notified about yet
    pub notify_pending: bool,
}

impl Default for BootGuardState {
    fn default() -> Self {
        Self {
            fail_count: 0,
            suspects: Vec::new(),
            auto_disabled: Vec::new(),
            last_trigger: None,
            enabled: true,
            threshold: BOOT_GUARD_THRESHOLD,
            disable_all: false,
            notify_pending: false,
        }
    }
}

const fn clamp_threshold(threshold: u32) -> u32 {
    if threshold < MIN_THRESHOLD {
        MIN_THRESHOLD
    } else if threshold > MAX_THRESHOLD {
        MAX_THRESHOLD
    } else {
        threshold
    }
}

const fn mode_name(disable_all: bool) -> &'static str {
    if disable_all { "all" } else { "suspects" }
}

#[derive(Debug, PartialEq, Eq)]
pub enum Decision {
    Continue,
    Disable(Vec<String>),
}

fn insert_sorted(list: &mut Vec<String>, id: &str) {
    if let Err(pos) = list.binary_search_by(|x| x.as_str().cmp(id)) {
        list.insert(pos, id.to_owned());
    }
}

fn string_list(value: &Value) -> Vec<String> {
    let mut out = Vec::new();
    for id in value
        .as_array()
        .into_iter()
        .flatten()
        .filter_map(Value::as_str)
    {
        insert_sorted(&mut out, id);
    }
    out
}

/// A missing or unreadable state file is a fresh state: it must never break boot.
pub fn load(path: &Path) -> BootGuardState {
    let Some(value) = std::fs::read_to_string(path)
        .ok()
        .and_then(|text| serde_json::from_str::<Value>(&text).ok())
    else {
        return BootGuardState::default();
    };
    BootGuardState {
        fail_count: value["failCount"]
            .as_u64()
            .and_then(|n| u32::try_from(n).ok())
            .unwrap_or(0),
        suspects: string_list(&value["suspects"]),
        auto_disabled: string_list(&value["autoDisabled"]),
        last_trigger: value["lastTrigger"].as_u64(),
        enabled: value["enabled"].as_bool().unwrap_or(true),
        threshold: value["threshold"]
            .as_u64()
            .and_then(|n| u32::try_from(n).ok())
            .map_or(BOOT_GUARD_THRESHOLD, clamp_threshold),
        disable_all: value["mode"].as_str() == Some("all"),
        notify_pending: value["notifyPending"].as_bool().unwrap_or(false),
    }
}

/// Write through a synced temp file and rename, so a hard reset mid-write
/// leaves either the old or the new state behind.
pub fn save(path: &Path, state: &BootGuardState) -> Result<()> {
    let value = json!({
        "failCount": state.fail_count,
        "suspects": state.suspects,
        "autoDisabled": state.auto_disabled,
        "lastTrigger": state.last_trigger,
        "enabled": state.enabled,
        "threshold": state.threshold,
        "mode": mode_name(state.disable_all),
        "notifyPending": state.notify_pending,
    });
    let tmp = path.with_extension("json.tmp");
    let mut file = std::fs::File::create(&tmp)
        .with_context(|| format!("Failed to create {}", tmp.display()))?;
    file.write_all(value.to_string().as_bytes())?;
    file.sync_all()?;
    drop(file);
    std::fs::rename(&tmp, path)
        .with_context(|| format!("Failed to rename {} to {}", tmp.display(), path.display()))?;
    #[cfg(unix)]
    if let Some(dir) = path.parent() {
        std::fs::File::open(dir).and_then(|d| d.sync_all()).ok();
    }
    Ok(())
}

/// Count one boot attempt. `enabled` is every module that is currently enabled.
pub fn on_boot_start(
    state: &mut BootGuardState,
    new_suspects: &[String],
    enabled: &[String],
    now: u64,
) -> Decision {
    for id in new_suspects {
        insert_sorted(&mut state.suspects, id);
    }
    if !state.enabled {
        state.fail_count = 0;
        return Decision::Continue;
    }
    state.fail_count = state.fail_count.saturating_add(1);
    if state.fail_count < state.threshold {
        return Decision::Continue;
    }
    if enabled.is_empty() {
        // nothing left to disable; do not let a stale count hit the next module enabled
        state.fail_count = 0;
        return Decision::Continue;
    }
    trigger(state, enabled, now)
}

/// The boots before this one died before post-fs-data, so this one ran without the
/// module init rc (`guarded_module_rc`). Only modules with an init rc run that early, so
/// `rc_modules` (the enabled ones that have one) are the ones to disable.
pub fn on_rc_held_back(
    state: &mut BootGuardState,
    new_suspects: &[String],
    rc_modules: &[String],
    enabled: &[String],
    now: u64,
) -> Decision {
    if !state.enabled || rc_modules.is_empty() {
        // not a module of ours (an initrc.d file, or the boots failed for another reason)
        return on_boot_start(state, new_suspects, enabled, now);
    }
    for id in new_suspects {
        insert_sorted(&mut state.suspects, id);
    }
    trigger(state, rc_modules, now)
}

/// Disables the suspects among `candidates`, or all of them when none is a suspect.
fn trigger(state: &mut BootGuardState, candidates: &[String], now: u64) -> Decision {
    let mut targets: Vec<String> = Vec::new();
    if !state.disable_all {
        for id in state.suspects.iter().filter(|id| candidates.contains(id)) {
            insert_sorted(&mut targets, id);
        }
    }
    if targets.is_empty() {
        for id in candidates {
            insert_sorted(&mut targets, id);
        }
    }
    for id in &targets {
        insert_sorted(&mut state.auto_disabled, id);
    }
    state.fail_count = 0;
    state.suspects.clear();
    state.last_trigger = Some(now);
    state.notify_pending = true;
    Decision::Disable(targets)
}

/// `None` keeps a setting. Turning the guard off also drops the running count.
pub const fn set_config(
    state: &mut BootGuardState,
    enabled: Option<bool>,
    threshold: Option<u32>,
    disable_all: Option<bool>,
) {
    if let Some(enabled) = enabled {
        state.enabled = enabled;
        if !enabled {
            state.fail_count = 0;
        }
    }
    if let Some(threshold) = threshold {
        state.threshold = clamp_threshold(threshold);
    }
    if let Some(disable_all) = disable_all {
        state.disable_all = disable_all;
    }
}

/// The modules to tell the user about, once per trigger.
pub fn take_notice(state: &mut BootGuardState) -> Option<Vec<String>> {
    if !state.notify_pending {
        return None;
    }
    state.notify_pending = false;
    Some(state.auto_disabled.clone())
}

/// Notification title and body; camd has no resources, so the two languages live here.
pub fn notice_text(ids: &[String], vietnamese: bool) -> (String, String) {
    let list = ids.join(", ");
    if vietnamese {
        (
            "SU Kernel: chống bootloop".to_owned(),
            format!(
                "Đã tắt {} module vì máy khởi động lỗi liên tục: {list}",
                ids.len()
            ),
        )
    } else {
        (
            "SU Kernel: anti bootloop".to_owned(),
            format!(
                "Disabled {} module(s) after repeated failed boots: {list}",
                ids.len()
            ),
        )
    }
}

pub fn on_boot_completed(state: &mut BootGuardState) {
    state.fail_count = 0;
    state.suspects.clear();
}

/// A module enabled again is the first one to blame if the next boot fails,
/// and is no longer the boot guard's to report.
pub fn on_module_enabled(state: &mut BootGuardState, id: &str) {
    insert_sorted(&mut state.suspects, id);
    state.auto_disabled.retain(|known| known != id);
}

/// Written next to `modules.rc` by a boot that ran without the module init rc.
pub const HELD_BACK_FLAG: &str = "modules.rc.held";

/// Name of the n-th queue file (1-based) next to `modules.rc`.
pub fn rc_queue_file(n: u32) -> String {
    format!("modules.rc.{n}")
}

/// The rc that takes the place of the module init rc once it is held back. It only
/// leaves `HELD_BACK_FLAG` behind: by post-fs-data, early-init has already moved the
/// queue up for the next boot, so what `modules.rc` holds then says nothing about
/// what this boot ran.
fn held_back_rc(dir: &str) -> Vec<u8> {
    format!(
        "# === cam boot guard: module init rc held back ===\non early-init\n    write {dir}/{HELD_BACK_FLAG} 1\n"
    )
    .into_bytes()
}

/// `modules.rc` and its queue (`modules.rc.1` ..) for the module init rc `body`.
///
/// Every queue file but the last holds the same rc as `modules.rc`; the last holds
/// `held_back_rc`. The rc starts by moving the queue up one step at early-init (init's
/// `copy`, which runs before any module action), and post-fs-data writes it all
/// again. So `threshold - 1` boots in a row that die before post-fs-data leave
/// `held_back_rc` in `modules.rc`, and the next boot, the `threshold`-th, runs that
/// instead of the module rc and writes `HELD_BACK_FLAG`.
/// `dir` is where these files live, as the kernel and init see it.
pub fn guarded_module_rc(dir: &str, body: &[u8], threshold: u32) -> (Vec<u8>, Vec<Vec<u8>>) {
    let dir = dir.trim_end_matches('/');
    let len = clamp_threshold(threshold) - 1;
    let mut rc = format!(
        "# === cam boot guard: without post-fs-data {len} boot(s) in a row, the next one skips the rc below ===\non early-init\n"
    );
    for n in 1..=len {
        let to = if n == 1 {
            "modules.rc".to_owned()
        } else {
            rc_queue_file(n - 1)
        };
        writeln!(rc, "    copy {dir}/{} {dir}/{to}", rc_queue_file(n)).ok();
    }
    rc.push('\n');
    let mut rc = rc.into_bytes();
    rc.extend_from_slice(body);

    let mut queue = vec![rc.clone(); len as usize - 1];
    queue.push(held_back_rc(dir));
    (rc, queue)
}

/// `still_disabled` filters out the modules the user has enabled again since.
pub fn status_json(state: &BootGuardState, still_disabled: &dyn Fn(&str) -> bool) -> Value {
    let auto_disabled: Vec<&String> = state
        .auto_disabled
        .iter()
        .filter(|id| still_disabled(id))
        .collect();
    json!({
        "failCount": state.fail_count,
        "threshold": state.threshold,
        "suspects": state.suspects,
        "autoDisabled": auto_disabled,
        "lastTrigger": state.last_trigger,
        "enabled": state.enabled,
        "mode": mode_name(state.disable_all),
    })
}

pub fn clear_notice(state: &mut BootGuardState) {
    state.auto_disabled.clear();
    state.last_trigger = None;
}

#[cfg(test)]
mod tests {
    use super::*;

    fn ids(v: &[&str]) -> Vec<String> {
        v.iter().map(|s| (*s).to_owned()).collect()
    }

    #[test]
    fn counts_up_below_threshold() {
        let mut s = BootGuardState::default();
        assert_eq!(
            on_boot_start(&mut s, &[], &ids(&["a"]), 1),
            Decision::Continue
        );
        assert_eq!(
            on_boot_start(&mut s, &[], &ids(&["a"]), 2),
            Decision::Continue
        );
        assert_eq!(s.fail_count, 2);
    }

    #[test]
    fn trigger_disables_suspects_only() {
        let mut s = BootGuardState::default();
        let en = ids(&["a", "b", "c"]);
        on_boot_start(&mut s, &ids(&["b"]), &en, 1);
        on_boot_start(&mut s, &[], &en, 2);
        assert_eq!(
            on_boot_start(&mut s, &[], &en, 3),
            Decision::Disable(ids(&["b"]))
        );
        assert_eq!(s.fail_count, 0);
        assert!(s.suspects.is_empty());
        assert_eq!(s.auto_disabled, ids(&["b"]));
        assert_eq!(s.last_trigger, Some(3));
    }

    #[test]
    fn no_suspects_disables_all_enabled() {
        let mut s = BootGuardState {
            fail_count: 2,
            ..Default::default()
        };
        assert_eq!(
            on_boot_start(&mut s, &[], &ids(&["a", "c"]), 9),
            Decision::Disable(ids(&["a", "c"]))
        );
    }

    #[test]
    fn stale_suspects_fall_back_to_all() {
        let mut s = BootGuardState {
            fail_count: 2,
            suspects: ids(&["gone"]),
            ..Default::default()
        };
        assert_eq!(
            on_boot_start(&mut s, &[], &ids(&["a"]), 9),
            Decision::Disable(ids(&["a"]))
        );
    }

    #[test]
    fn nothing_enabled_continues() {
        let mut s = BootGuardState {
            fail_count: 2,
            ..Default::default()
        };
        assert_eq!(on_boot_start(&mut s, &[], &[], 9), Decision::Continue);
    }

    #[test]
    fn boot_completed_resets() {
        let mut s = BootGuardState {
            fail_count: 2,
            suspects: ids(&["a"]),
            auto_disabled: ids(&["x"]),
            ..Default::default()
        };
        on_boot_completed(&mut s);
        assert_eq!(s.fail_count, 0);
        assert!(s.suspects.is_empty());
        assert_eq!(s.auto_disabled, ids(&["x"]));
    }

    #[test]
    fn enabling_forgets_auto_disabled() {
        let mut s = BootGuardState {
            auto_disabled: ids(&["a", "b"]),
            ..Default::default()
        };
        on_module_enabled(&mut s, "a");
        assert_eq!(s.suspects, ids(&["a"]));
        // a later manual disable must not read as the boot guard's doing
        assert_eq!(s.auto_disabled, ids(&["b"]));
    }

    #[test]
    fn save_load_roundtrip() {
        let dir = tempfile::tempdir().unwrap();
        let p = dir.path().join("bootguard.json");
        let s = BootGuardState {
            fail_count: 1,
            suspects: ids(&["a"]),
            auto_disabled: ids(&["b"]),
            last_trigger: Some(5),
            enabled: false,
            threshold: 4,
            disable_all: true,
            notify_pending: true,
        };
        save(&p, &s).unwrap();
        assert_eq!(load(&p), s);
    }

    #[test]
    fn load_corrupt_file_returns_default() {
        let dir = tempfile::tempdir().unwrap();
        let p = dir.path().join("bootguard.json");
        std::fs::write(&p, b"{\"failCo").unwrap();
        assert_eq!(load(&p), BootGuardState::default());
        std::fs::write(&p, b"").unwrap();
        assert_eq!(load(&p), BootGuardState::default());
    }

    #[test]
    fn load_old_file_keeps_defaults() {
        let dir = tempfile::tempdir().unwrap();
        let p = dir.path().join("bootguard.json");
        std::fs::write(&p, br#"{"failCount":1,"suspects":["a"],"autoDisabled":[]}"#).unwrap();
        let s = load(&p);
        assert_eq!(s.fail_count, 1);
        assert!(s.enabled);
        assert_eq!(s.threshold, BOOT_GUARD_THRESHOLD);
        assert!(!s.disable_all);
    }

    #[test]
    fn disabled_resets_count() {
        let mut s = BootGuardState {
            fail_count: 2,
            enabled: false,
            ..Default::default()
        };
        assert_eq!(
            on_boot_start(&mut s, &ids(&["n"]), &ids(&["a"]), 1),
            Decision::Continue
        );
        assert_eq!(s.fail_count, 0);
        assert_eq!(s.suspects, ids(&["n"]));
        // enabling again starts from zero, so the next boot does not trigger
        set_config(&mut s, Some(true), None, None);
        assert_eq!(
            on_boot_start(&mut s, &[], &ids(&["a"]), 2),
            Decision::Continue
        );
    }

    #[test]
    fn lower_threshold_triggers_next_boot() {
        let mut s = BootGuardState {
            fail_count: 3,
            threshold: 5,
            ..Default::default()
        };
        set_config(&mut s, None, Some(2), None);
        assert_eq!(
            on_boot_start(&mut s, &[], &ids(&["a"]), 1),
            Decision::Disable(ids(&["a"]))
        );
    }

    #[test]
    fn threshold_is_respected() {
        let mut s = BootGuardState {
            threshold: 4,
            ..Default::default()
        };
        let en = ids(&["a"]);
        for now in 1..4 {
            assert_eq!(on_boot_start(&mut s, &[], &en, now), Decision::Continue);
        }
        assert_eq!(
            on_boot_start(&mut s, &[], &en, 4),
            Decision::Disable(ids(&["a"]))
        );
    }

    #[test]
    fn disable_all_ignores_suspects() {
        let mut s = BootGuardState {
            fail_count: 2,
            suspects: ids(&["b"]),
            disable_all: true,
            ..Default::default()
        };
        assert_eq!(
            on_boot_start(&mut s, &[], &ids(&["a", "b"]), 1),
            Decision::Disable(ids(&["a", "b"]))
        );
    }

    #[test]
    fn set_config_clamps_threshold() {
        let mut s = BootGuardState::default();
        set_config(&mut s, None, Some(1), None);
        assert_eq!(s.threshold, MIN_THRESHOLD);
        set_config(&mut s, None, Some(9), Some(true));
        assert_eq!(s.threshold, MAX_THRESHOLD);
        assert!(s.disable_all);
    }

    #[test]
    fn status_reports_config() {
        let s = BootGuardState {
            enabled: false,
            threshold: 4,
            disable_all: true,
            ..Default::default()
        };
        let v = status_json(&s, &|_| true);
        assert_eq!(v["enabled"], serde_json::json!(false));
        assert_eq!(v["threshold"], serde_json::json!(4));
        assert_eq!(v["mode"], serde_json::json!("all"));
    }

    #[test]
    fn trigger_queues_one_notice() {
        let mut s = BootGuardState {
            fail_count: 2,
            ..Default::default()
        };
        assert_eq!(take_notice(&mut s), None);
        on_boot_start(&mut s, &[], &ids(&["a", "b"]), 1);
        assert_eq!(take_notice(&mut s), Some(ids(&["a", "b"])));
        // shown once
        assert_eq!(take_notice(&mut s), None);
    }

    #[test]
    fn notice_survives_save_load() {
        let dir = tempfile::tempdir().unwrap();
        let p = dir.path().join("bootguard.json");
        let mut s = BootGuardState {
            fail_count: 2,
            ..Default::default()
        };
        on_boot_start(&mut s, &[], &ids(&["a"]), 1);
        save(&p, &s).unwrap();
        assert_eq!(take_notice(&mut load(&p)), Some(ids(&["a"])));
    }

    #[test]
    fn notice_text_follows_language() {
        let (title, body) = notice_text(&ids(&["a", "b"]), true);
        assert_eq!(title, "SU Kernel: chống bootloop");
        assert_eq!(body, "Đã tắt 2 module vì máy khởi động lỗi liên tục: a, b");
        let (title, body) = notice_text(&ids(&["a"]), false);
        assert_eq!(title, "SU Kernel: anti bootloop");
        assert_eq!(body, "Disabled 1 module(s) after repeated failed boots: a");
    }

    #[test]
    fn held_back_rc_disables_rc_suspects_first() {
        let mut s = BootGuardState {
            suspects: ids(&["new"]),
            ..Default::default()
        };
        let en = ids(&["a", "new", "plain"]);
        assert_eq!(
            on_rc_held_back(&mut s, &ids(&["upd"]), &ids(&["a", "new"]), &en, 7),
            Decision::Disable(ids(&["new"]))
        );
        assert_eq!(s.auto_disabled, ids(&["new"]));
        assert!(s.suspects.is_empty());
        assert_eq!(s.fail_count, 0);
        assert_eq!(s.last_trigger, Some(7));
        assert!(s.notify_pending);
    }

    #[test]
    fn held_back_rc_without_rc_suspect_disables_every_rc_module() {
        let mut s = BootGuardState {
            suspects: ids(&["plain"]),
            ..Default::default()
        };
        let en = ids(&["a", "b", "plain"]);
        assert_eq!(
            on_rc_held_back(&mut s, &[], &ids(&["a", "b"]), &en, 1),
            Decision::Disable(ids(&["a", "b"]))
        );
    }

    #[test]
    fn held_back_rc_counts_a_normal_boot_without_rc_modules() {
        let mut s = BootGuardState::default();
        assert_eq!(
            on_rc_held_back(&mut s, &[], &[], &ids(&["a"]), 1),
            Decision::Continue
        );
        assert_eq!(s.fail_count, 1);
    }

    type Files = std::collections::HashMap<String, Vec<u8>>;
    const DIR: &str = "/metadata/cam";

    fn path(name: &str) -> String {
        format!("{DIR}/{name}")
    }

    /// One boot up to early-init: the kernel splices `modules.rc` in as it is now,
    /// then init runs the `copy` and `write` lines of its early-init block. Returns
    /// the rc this boot ran.
    fn boot(files: &mut Files) -> Vec<u8> {
        let parsed = files[&path("modules.rc")].clone();
        let text = String::from_utf8_lossy(&parsed).into_owned();
        for line in text.lines().skip_while(|l| *l != "on early-init").skip(1) {
            let line = line.trim();
            if let Some(args) = line.strip_prefix("copy ") {
                let (from, to) = args.split_once(' ').unwrap();
                if let Some(data) = files.get(from).cloned() {
                    files.insert(to.to_owned(), data);
                }
            } else if let Some(args) = line.strip_prefix("write ") {
                let (to, value) = args.split_once(' ').unwrap();
                files.insert(to.to_owned(), value.as_bytes().to_vec());
            } else {
                break;
            }
        }
        parsed
    }

    /// What camd writes (`module::regenerate_preinit_rc`).
    fn write_rc(files: &mut Files, body: &[u8], threshold: u32) {
        files.remove(&path(HELD_BACK_FLAG));
        for n in 1..MAX_THRESHOLD {
            files.remove(&path(&rc_queue_file(n)));
        }
        let (rc, queue) = guarded_module_rc(&format!("{DIR}/"), body, threshold);
        files.insert(path("modules.rc"), rc);
        for (n, data) in (1..).zip(queue) {
            files.insert(path(&rc_queue_file(n)), data);
        }
    }

    /// camd at post-fs-data: whether this boot ran without the module rc, then the rc
    /// written again.
    fn post_fs_data(files: &mut Files, body: &[u8], threshold: u32) -> bool {
        let held = files.contains_key(&path(HELD_BACK_FLAG));
        write_rc(files, body, threshold);
        held
    }

    const BODY: &[u8] = b"on boot\n    start x\n";

    #[test]
    fn good_boots_never_hold_the_rc_back() {
        for threshold in MIN_THRESHOLD..=MAX_THRESHOLD {
            let mut files = Files::new();
            write_rc(&mut files, BODY, threshold);
            for _ in 0..6 {
                assert!(boot(&mut files).ends_with(BODY));
                assert!(
                    !post_fs_data(&mut files, BODY, threshold),
                    "threshold {threshold}"
                );
            }
        }
    }

    #[test]
    fn fewer_failed_boots_than_needed_then_a_good_one() {
        for threshold in MIN_THRESHOLD..=MAX_THRESHOLD {
            for failed in 0..threshold - 1 {
                let mut files = Files::new();
                write_rc(&mut files, BODY, threshold);
                for _ in 0..failed {
                    assert!(boot(&mut files).ends_with(BODY));
                }
                assert!(boot(&mut files).ends_with(BODY));
                assert!(
                    !post_fs_data(&mut files, BODY, threshold),
                    "threshold {threshold}, {failed} failed"
                );
            }
        }
    }

    #[test]
    fn rc_is_held_back_on_the_threshold_boot() {
        for threshold in MIN_THRESHOLD..=MAX_THRESHOLD {
            let mut files = Files::new();
            write_rc(&mut files, BODY, threshold);
            // boots 1 .. threshold-1 run the rc and die before post-fs-data
            for _ in 1..threshold {
                assert!(boot(&mut files).ends_with(BODY));
            }
            assert!(!boot(&mut files).ends_with(BODY), "threshold {threshold}");
            // dying again changes nothing: the rc stays held back
            assert!(!boot(&mut files).ends_with(BODY));
            assert!(
                post_fs_data(&mut files, BODY, threshold),
                "threshold {threshold}"
            );
            // camd wrote it all again: the next boot runs the rc
            assert!(boot(&mut files).ends_with(BODY));
            assert!(!post_fs_data(&mut files, BODY, threshold));
        }
    }

    #[test]
    fn guarded_rc_shape() {
        let (rc, queue) = guarded_module_rc("/metadata/watchdog/cam/", b"BODY", 3);
        let rc = String::from_utf8(rc).unwrap();
        assert!(rc.contains(
            "on early-init\n    copy /metadata/watchdog/cam/modules.rc.1 /metadata/watchdog/cam/modules.rc\n    copy /metadata/watchdog/cam/modules.rc.2 /metadata/watchdog/cam/modules.rc.1\n\nBODY"
        ));
        assert_eq!(queue.len(), 2);
        assert_eq!(queue[0], rc.as_bytes());
        let held = String::from_utf8(queue[1].clone()).unwrap();
        assert!(
            held.ends_with("on early-init\n    write /metadata/watchdog/cam/modules.rc.held 1\n")
        );
        assert!(!held.contains("BODY"));
    }

    #[test]
    fn status_hides_reenabled_modules() {
        let s = BootGuardState {
            auto_disabled: ids(&["a", "b"]),
            ..Default::default()
        };
        let v = status_json(&s, &|id| id == "b");
        assert_eq!(v["autoDisabled"], serde_json::json!(["b"]));
        assert_eq!(v["threshold"], serde_json::json!(3));
    }
}

//! Boot guard: disables every module after repeated boots that never reached boot-completed,
//! and names the ones most likely to blame.
//!
//! post-fs-data counts a boot attempt, boot-completed resets the count. When the
//! count reaches the threshold, every enabled module is disabled, so the next boot is a
//! clean one. The modules installed, updated or re-enabled since the last good boot are
//! reported as the likely cause, with lines of the failed boot's logs that point at a
//! module (`boot_evidence`), so the user can enable the others again.
//!
//! Boots that die before post-fs-data never reach that count; only an init rc (a module's
//! `initrc/*.rc`, or a file in /data/adb/initrc.d, spliced into init.rc by the kernel)
//! runs that early. For those, the rc itself carries a queue that init shifts at
//! early-init (`guarded_module_rc`), so the boot after `threshold - 1` such failures runs
//! without it; then every module is disabled, the initrc.d files lose their exec bit, and
//! the ones with an rc are reported.
#![cfg_attr(not(target_os = "android"), allow(dead_code))]

use std::collections::BTreeMap;
use std::fmt::Write as _;
use std::io::Write;
use std::path::Path;

use anyhow::{Context, Result};
use serde_json::{Value, json};

/// Default boot attempt that triggers the guard: two failed boots, then act on the third.
pub const BOOT_GUARD_THRESHOLD: u32 = 3;
pub const MIN_THRESHOLD: u32 = 2;
pub const MAX_THRESHOLD: u32 = 5;

/// Culprit ids of /data/adb/initrc.d files carry this prefix, so they never clash with a module id.
pub const INITRC_PREFIX: &str = "initrc.d/";

/// Why a module (or initrc.d file) is named as a likely cause.
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord)]
pub enum Reason {
    /// installed, updated or enabled again since the last good boot
    Changed,
    /// has an init rc, the only module part that runs before post-fs-data
    InitRc,
    /// an rc file in /data/adb/initrc.d
    InitrcD,
    /// the failed boot's logs name it (`Culprit::evidence`)
    Log,
}

impl Reason {
    pub const fn as_str(self) -> &'static str {
        match self {
            Self::Changed => "changed",
            Self::InitRc => "init_rc",
            Self::InitrcD => "initrc_d",
            Self::Log => "log",
        }
    }

    fn parse(name: &str) -> Option<Self> {
        [Self::Changed, Self::InitRc, Self::InitrcD, Self::Log]
            .into_iter()
            .find(|reason| reason.as_str() == name)
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Culprit {
    /// a module id, or `INITRC_PREFIX` + a file name
    pub id: String,
    /// sorted, unique
    pub reasons: Vec<Reason>,
    /// a log line of the failed boot that names it
    pub evidence: Option<String>,
}

impl Culprit {
    fn new(id: &str, reason: Reason) -> Self {
        Self {
            id: id.to_owned(),
            reasons: vec![reason],
            evidence: None,
        }
    }

    fn add(&mut self, reason: Reason) {
        if let Err(pos) = self.reasons.binary_search(&reason) {
            self.reasons.insert(pos, reason);
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct BootGuardState {
    pub fail_count: u32,
    /// sorted, unique
    pub suspects: Vec<String>,
    /// modules the guard disabled; sorted, unique
    pub auto_disabled: Vec<String>,
    /// /data/adb/initrc.d files the guard took the exec bit from; sorted, unique
    pub initrc_disabled: Vec<String>,
    /// the likely causes of the last trigger, most likely first
    pub culprits: Vec<Culprit>,
    pub last_trigger: Option<u64>,
    pub enabled: bool,
    /// boot attempt that triggers, `MIN_THRESHOLD..=MAX_THRESHOLD`
    pub threshold: u32,
    /// a trigger the user has not been notified about yet
    pub notify_pending: bool,
}

impl Default for BootGuardState {
    fn default() -> Self {
        Self {
            fail_count: 0,
            suspects: Vec::new(),
            auto_disabled: Vec::new(),
            initrc_disabled: Vec::new(),
            culprits: Vec::new(),
            last_trigger: None,
            enabled: true,
            threshold: BOOT_GUARD_THRESHOLD,
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

#[derive(Debug, PartialEq, Eq)]
pub enum Decision {
    Continue,
    /// every enabled module, and the initrc.d files to take the exec bit from
    Disable {
        modules: Vec<String>,
        initrc: Vec<String>,
    },
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

fn culprits_from(value: &Value) -> Vec<Culprit> {
    value
        .as_array()
        .into_iter()
        .flatten()
        .filter_map(|item| {
            let mut culprit = Culprit {
                id: item["id"].as_str()?.to_owned(),
                reasons: Vec::new(),
                evidence: item["evidence"].as_str().map(str::to_owned),
            };
            for reason in item["reasons"]
                .as_array()
                .into_iter()
                .flatten()
                .filter_map(|r| Reason::parse(r.as_str()?))
            {
                culprit.add(reason);
            }
            Some(culprit)
        })
        .collect()
}

fn culprit_json(culprit: &Culprit) -> Value {
    json!({
        "id": culprit.id,
        "reasons": culprit.reasons.iter().map(|r| r.as_str()).collect::<Vec<_>>(),
        "evidence": culprit.evidence,
    })
}

/// A missing or unreadable state file is a fresh state: it must never break boot.
/// Files from before 2026-10-15 carry a `mode` key, which no longer means anything.
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
        initrc_disabled: string_list(&value["initrcDisabled"]),
        culprits: culprits_from(&value["culprits"]),
        last_trigger: value["lastTrigger"].as_u64(),
        enabled: value["enabled"].as_bool().unwrap_or(true),
        threshold: value["threshold"]
            .as_u64()
            .and_then(|n| u32::try_from(n).ok())
            .map_or(BOOT_GUARD_THRESHOLD, clamp_threshold),
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
        "initrcDisabled": state.initrc_disabled,
        "culprits": state.culprits.iter().map(culprit_json).collect::<Vec<_>>(),
        "lastTrigger": state.last_trigger,
        "enabled": state.enabled,
        "threshold": state.threshold,
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
    let culprits = state
        .suspects
        .iter()
        .filter(|id| enabled.contains(id))
        .map(|id| Culprit::new(id, Reason::Changed))
        .collect();
    trigger(state, enabled, &[], culprits, now)
}

/// The boots before this one died before post-fs-data, so this one ran without the init rc
/// (`guarded_module_rc`). Only an init rc runs that early: `rc_modules` (the enabled modules
/// that have one) and `initrc` (the active files in /data/adb/initrc.d) are the likely
/// causes. Every module is disabled all the same.
pub fn on_rc_held_back(
    state: &mut BootGuardState,
    new_suspects: &[String],
    rc_modules: &[String],
    initrc: &[String],
    enabled: &[String],
    now: u64,
) -> Decision {
    if !state.enabled || (rc_modules.is_empty() && initrc.is_empty()) {
        // no rc of ours: the boots failed for another reason
        return on_boot_start(state, new_suspects, enabled, now);
    }
    for id in new_suspects {
        insert_sorted(&mut state.suspects, id);
    }
    let mut culprits = Vec::new();
    for id in rc_modules {
        let mut culprit = Culprit::new(id, Reason::InitRc);
        if state.suspects.contains(id) {
            culprit.add(Reason::Changed);
        }
        culprits.push(culprit);
    }
    for name in initrc {
        culprits.push(Culprit::new(
            &format!("{INITRC_PREFIX}{name}"),
            Reason::InitrcD,
        ));
    }
    trigger(state, enabled, initrc, culprits, now)
}

fn trigger(
    state: &mut BootGuardState,
    modules: &[String],
    initrc: &[String],
    culprits: Vec<Culprit>,
    now: u64,
) -> Decision {
    let mut modules_sorted = Vec::new();
    for id in modules {
        insert_sorted(&mut modules_sorted, id);
        insert_sorted(&mut state.auto_disabled, id);
    }
    let mut initrc_sorted = Vec::new();
    for name in initrc {
        insert_sorted(&mut initrc_sorted, name);
        insert_sorted(&mut state.initrc_disabled, name);
    }
    state.culprits = culprits;
    sort_culprits(&mut state.culprits);
    state.fail_count = 0;
    state.suspects.clear();
    state.last_trigger = Some(now);
    state.notify_pending = true;
    Decision::Disable {
        modules: modules_sorted,
        initrc: initrc_sorted,
    }
}

/// Log evidence first, then the ones with more reasons.
fn sort_culprits(culprits: &mut [Culprit]) {
    culprits.sort_by(|a, b| {
        (
            a.evidence.is_none(),
            std::cmp::Reverse(a.reasons.len()),
            &a.id,
        )
            .cmp(&(
                b.evidence.is_none(),
                std::cmp::Reverse(b.reasons.len()),
                &b.id,
            ))
    });
}

/// Adds what the failed boot's logs say (id -> line) to the last trigger's culprits.
/// Only ids in `known` count: a module that was not disabled is not to blame.
pub fn add_evidence(
    state: &mut BootGuardState,
    evidence: &BTreeMap<String, String>,
    known: &[String],
) {
    for (id, line) in evidence.iter().filter(|(id, _)| known.contains(id)) {
        if let Some(culprit) = state.culprits.iter_mut().find(|c| &c.id == id) {
            culprit.add(Reason::Log);
            culprit.evidence = Some(line.clone());
        } else {
            let mut culprit = Culprit::new(id, Reason::Log);
            culprit.evidence = Some(line.clone());
            state.culprits.push(culprit);
        }
    }
    sort_culprits(&mut state.culprits);
}

/// `None` keeps a setting. Turning the guard off also drops the running count.
pub const fn set_config(state: &mut BootGuardState, enabled: Option<bool>, threshold: Option<u32>) {
    if let Some(enabled) = enabled {
        state.enabled = enabled;
        if !enabled {
            state.fail_count = 0;
        }
    }
    if let Some(threshold) = threshold {
        state.threshold = clamp_threshold(threshold);
    }
}

/// What to tell the user, once per trigger.
#[derive(Debug, PartialEq, Eq)]
pub struct Notice {
    pub disabled: usize,
    pub culprits: Vec<String>,
}

pub fn take_notice(state: &mut BootGuardState) -> Option<Notice> {
    if !state.notify_pending {
        return None;
    }
    state.notify_pending = false;
    Some(Notice {
        disabled: state.auto_disabled.len() + state.initrc_disabled.len(),
        culprits: state.culprits.iter().map(|c| c.id.clone()).collect(),
    })
}

/// Notification title and body; camd has no resources, so the two languages live here.
pub fn notice_text(notice: &Notice, vietnamese: bool) -> (String, String) {
    let list = notice.culprits.join(", ");
    let count = notice.disabled;
    if vietnamese {
        let cause = if list.is_empty() {
            "Chưa rõ module nào gây lỗi, bật lại từng cái để tìm.".to_owned()
        } else {
            format!("Nghi do: {list}")
        };
        (
            "SU Kernel: chống bootloop".to_owned(),
            format!("Đã tắt tất cả {count} module vì máy khởi động lỗi liên tục. {cause}"),
        )
    } else {
        let cause = if list.is_empty() {
            "No module stands out; enable them one at a time to find it.".to_owned()
        } else {
            format!("Likely cause: {list}")
        };
        (
            "SU Kernel: anti bootloop".to_owned(),
            format!("Disabled all {count} module(s) after repeated failed boots. {cause}"),
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

/// An initrc.d file that got its exec bit back.
pub fn on_initrc_enabled(state: &mut BootGuardState, name: &str) {
    state.initrc_disabled.retain(|known| known != name);
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

/// `module_off` and `initrc_off` filter out what the user has turned back on since.
pub fn status_json(
    state: &BootGuardState,
    module_off: &dyn Fn(&str) -> bool,
    initrc_off: &dyn Fn(&str) -> bool,
) -> Value {
    let auto_disabled: Vec<&String> = state
        .auto_disabled
        .iter()
        .filter(|id| module_off(id))
        .collect();
    let initrc_disabled: Vec<&String> = state
        .initrc_disabled
        .iter()
        .filter(|name| initrc_off(name))
        .collect();
    let culprits: Vec<Value> = state
        .culprits
        .iter()
        .filter(|c| {
            c.id.strip_prefix(INITRC_PREFIX).map_or_else(
                || auto_disabled.contains(&&c.id),
                |name| initrc_disabled.iter().any(|known| *known == name),
            )
        })
        .map(culprit_json)
        .collect();
    json!({
        "failCount": state.fail_count,
        "threshold": state.threshold,
        "suspects": state.suspects,
        "autoDisabled": auto_disabled,
        "initrcDisabled": initrc_disabled,
        "culprits": culprits,
        "lastTrigger": state.last_trigger,
        "enabled": state.enabled,
    })
}

pub fn clear_notice(state: &mut BootGuardState) {
    state.auto_disabled.clear();
    state.initrc_disabled.clear();
    state.culprits.clear();
    state.last_trigger = None;
}

#[cfg(test)]
mod tests {
    use super::*;

    fn ids(v: &[&str]) -> Vec<String> {
        v.iter().map(|s| (*s).to_owned()).collect()
    }

    fn disable(modules: &[&str], initrc: &[&str]) -> Decision {
        Decision::Disable {
            modules: ids(modules),
            initrc: ids(initrc),
        }
    }

    fn culprit_ids(s: &BootGuardState) -> Vec<String> {
        s.culprits.iter().map(|c| c.id.clone()).collect()
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
    fn trigger_disables_every_module_and_names_the_changed_ones() {
        let mut s = BootGuardState::default();
        let en = ids(&["a", "b", "c"]);
        on_boot_start(&mut s, &ids(&["b"]), &en, 1);
        on_boot_start(&mut s, &[], &en, 2);
        assert_eq!(
            on_boot_start(&mut s, &[], &en, 3),
            disable(&["a", "b", "c"], &[])
        );
        assert_eq!(s.fail_count, 0);
        assert!(s.suspects.is_empty());
        assert_eq!(s.auto_disabled, ids(&["a", "b", "c"]));
        assert_eq!(s.last_trigger, Some(3));
        assert_eq!(
            s.culprits,
            vec![Culprit {
                id: "b".into(),
                reasons: vec![Reason::Changed],
                evidence: None
            }]
        );
    }

    #[test]
    fn no_suspects_names_no_culprit() {
        let mut s = BootGuardState {
            fail_count: 2,
            suspects: ids(&["gone"]),
            ..Default::default()
        };
        assert_eq!(
            on_boot_start(&mut s, &[], &ids(&["a", "c"]), 9),
            disable(&["a", "c"], &[])
        );
        assert!(s.culprits.is_empty());
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
    fn evidence_ranks_culprits_and_adds_unchanged_modules() {
        let mut s = BootGuardState {
            fail_count: 2,
            suspects: ids(&["new"]),
            ..Default::default()
        };
        let en = ids(&["new", "old", "other"]);
        on_boot_start(&mut s, &[], &en, 1);
        let evidence = BTreeMap::from([
            (
                "old".to_owned(),
                "F DEBUG: #00 pc /system/lib64/libold.so".to_owned(),
            ),
            ("never-enabled".to_owned(), "x".to_owned()),
        ]);
        add_evidence(&mut s, &evidence, &en);
        assert_eq!(culprit_ids(&s), ids(&["old", "new"]));
        assert_eq!(s.culprits[0].reasons, vec![Reason::Log]);
        assert_eq!(
            s.culprits[0].evidence.as_deref(),
            Some("F DEBUG: #00 pc /system/lib64/libold.so")
        );
        // evidence on a changed module keeps both reasons
        add_evidence(
            &mut s,
            &BTreeMap::from([("new".to_owned(), "line".to_owned())]),
            &en,
        );
        assert_eq!(culprit_ids(&s), ids(&["new", "old"]));
        assert_eq!(s.culprits[0].reasons, vec![Reason::Changed, Reason::Log]);
    }

    #[test]
    fn held_back_rc_disables_everything_and_names_rc_owners() {
        let mut s = BootGuardState {
            suspects: ids(&["new"]),
            ..Default::default()
        };
        let en = ids(&["a", "new", "plain"]);
        assert_eq!(
            on_rc_held_back(
                &mut s,
                &ids(&["upd"]),
                &ids(&["a", "new"]),
                &ids(&["x.rc"]),
                &en,
                7
            ),
            disable(&["a", "new", "plain"], &["x.rc"])
        );
        assert_eq!(culprit_ids(&s), ids(&["new", "a", "initrc.d/x.rc"]));
        assert_eq!(s.culprits[0].reasons, vec![Reason::Changed, Reason::InitRc]);
        assert_eq!(s.culprits[2].reasons, vec![Reason::InitrcD]);
        assert_eq!(s.initrc_disabled, ids(&["x.rc"]));
        assert!(s.suspects.is_empty());
        assert_eq!(s.fail_count, 0);
        assert_eq!(s.last_trigger, Some(7));
        assert!(s.notify_pending);
    }

    #[test]
    fn held_back_rc_with_only_initrc_files() {
        let mut s = BootGuardState::default();
        assert_eq!(
            on_rc_held_back(&mut s, &[], &[], &ids(&["x.rc"]), &[], 1),
            disable(&[], &["x.rc"])
        );
    }

    #[test]
    fn held_back_rc_without_any_rc_counts_a_normal_boot() {
        let mut s = BootGuardState::default();
        assert_eq!(
            on_rc_held_back(&mut s, &[], &[], &[], &ids(&["a"]), 1),
            Decision::Continue
        );
        assert_eq!(s.fail_count, 1);
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
            initrc_disabled: ids(&["x.rc"]),
            ..Default::default()
        };
        on_module_enabled(&mut s, "a");
        assert_eq!(s.suspects, ids(&["a"]));
        // a later manual disable must not read as the boot guard's doing
        assert_eq!(s.auto_disabled, ids(&["b"]));
        on_initrc_enabled(&mut s, "x.rc");
        assert!(s.initrc_disabled.is_empty());
    }

    #[test]
    fn save_load_roundtrip() {
        let dir = tempfile::tempdir().unwrap();
        let p = dir.path().join("bootguard.json");
        let s = BootGuardState {
            fail_count: 1,
            suspects: ids(&["a"]),
            auto_disabled: ids(&["b"]),
            initrc_disabled: ids(&["x.rc"]),
            culprits: vec![Culprit {
                id: "b".into(),
                reasons: vec![Reason::Changed, Reason::Log],
                evidence: Some("line".into()),
            }],
            last_trigger: Some(5),
            enabled: false,
            threshold: 4,
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
        std::fs::write(
            &p,
            br#"{"failCount":1,"suspects":["a"],"autoDisabled":[],"mode":"suspects"}"#,
        )
        .unwrap();
        let s = load(&p);
        assert_eq!(s.fail_count, 1);
        assert!(s.enabled);
        assert_eq!(s.threshold, BOOT_GUARD_THRESHOLD);
        assert!(s.culprits.is_empty());
        assert!(s.initrc_disabled.is_empty());
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
        set_config(&mut s, Some(true), None);
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
        set_config(&mut s, None, Some(2));
        assert_eq!(
            on_boot_start(&mut s, &[], &ids(&["a"]), 1),
            disable(&["a"], &[])
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
        assert_eq!(on_boot_start(&mut s, &[], &en, 4), disable(&["a"], &[]));
    }

    #[test]
    fn set_config_clamps_threshold() {
        let mut s = BootGuardState::default();
        set_config(&mut s, None, Some(1));
        assert_eq!(s.threshold, MIN_THRESHOLD);
        set_config(&mut s, None, Some(9));
        assert_eq!(s.threshold, MAX_THRESHOLD);
    }

    #[test]
    fn status_reports_config() {
        let s = BootGuardState {
            enabled: false,
            threshold: 4,
            ..Default::default()
        };
        let v = status_json(&s, &|_| true, &|_| true);
        assert_eq!(v["enabled"], serde_json::json!(false));
        assert_eq!(v["threshold"], serde_json::json!(4));
        assert!(v.get("mode").is_none());
    }

    #[test]
    fn trigger_queues_one_notice() {
        let mut s = BootGuardState {
            fail_count: 2,
            suspects: ids(&["b"]),
            ..Default::default()
        };
        assert_eq!(take_notice(&mut s), None);
        on_boot_start(&mut s, &[], &ids(&["a", "b"]), 1);
        assert_eq!(
            take_notice(&mut s),
            Some(Notice {
                disabled: 2,
                culprits: ids(&["b"])
            })
        );
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
        assert_eq!(
            take_notice(&mut load(&p)),
            Some(Notice {
                disabled: 1,
                culprits: vec![]
            })
        );
    }

    #[test]
    fn notice_text_follows_language() {
        let notice = Notice {
            disabled: 3,
            culprits: ids(&["a", "b"]),
        };
        let (title, body) = notice_text(&notice, true);
        assert_eq!(title, "SU Kernel: chống bootloop");
        assert_eq!(
            body,
            "Đã tắt tất cả 3 module vì máy khởi động lỗi liên tục. Nghi do: a, b"
        );
        let (title, body) = notice_text(
            &Notice {
                disabled: 1,
                culprits: vec![],
            },
            false,
        );
        assert_eq!(title, "SU Kernel: anti bootloop");
        assert_eq!(
            body,
            "Disabled all 1 module(s) after repeated failed boots. No module stands out; enable them one at a time to find it."
        );
    }

    #[test]
    fn status_hides_what_was_turned_back_on() {
        let s = BootGuardState {
            auto_disabled: ids(&["a", "b"]),
            initrc_disabled: ids(&["x.rc", "y.rc"]),
            culprits: vec![
                Culprit::new("a", Reason::Changed),
                Culprit::new("b", Reason::Changed),
                Culprit::new("initrc.d/x.rc", Reason::InitrcD),
                Culprit::new("initrc.d/y.rc", Reason::InitrcD),
            ],
            ..Default::default()
        };
        let v = status_json(&s, &|id| id == "b", &|name| name == "y.rc");
        assert_eq!(v["autoDisabled"], serde_json::json!(["b"]));
        assert_eq!(v["initrcDisabled"], serde_json::json!(["y.rc"]));
        let shown: Vec<&str> = v["culprits"]
            .as_array()
            .unwrap()
            .iter()
            .map(|c| c["id"].as_str().unwrap())
            .collect();
        assert_eq!(shown, vec!["b", "initrc.d/y.rc"]);
        assert_eq!(v["threshold"], serde_json::json!(3));
    }

    #[test]
    fn clear_notice_forgets_the_trigger() {
        let mut s = BootGuardState {
            auto_disabled: ids(&["a"]),
            initrc_disabled: ids(&["x.rc"]),
            culprits: vec![Culprit::new("a", Reason::Changed)],
            last_trigger: Some(1),
            ..Default::default()
        };
        clear_notice(&mut s);
        assert!(
            s.auto_disabled.is_empty() && s.initrc_disabled.is_empty() && s.culprits.is_empty()
        );
        assert_eq!(s.last_trigger, None);
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
}

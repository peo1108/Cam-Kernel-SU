//! Boot guard: disables modules after repeated boots that never reached boot-completed.
//!
//! post-fs-data counts a boot attempt, boot-completed resets the count. When the
//! count reaches the threshold, the modules installed, updated or re-enabled since
//! the last good boot are disabled; with none of those left, every enabled module is.
#![cfg_attr(not(target_os = "android"), allow(dead_code))]

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

    let mut targets: Vec<String> = Vec::new();
    if !state.disable_all {
        for id in state.suspects.iter().filter(|id| enabled.contains(id)) {
            insert_sorted(&mut targets, id);
        }
    }
    if targets.is_empty() {
        for id in enabled {
            insert_sorted(&mut targets, id);
        }
    }
    for id in &targets {
        insert_sorted(&mut state.auto_disabled, id);
    }
    state.fail_count = 0;
    state.suspects.clear();
    state.last_trigger = Some(now);
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

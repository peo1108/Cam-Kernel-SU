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

pub const BOOT_GUARD_THRESHOLD: u32 = 3;

#[derive(Debug, Default, Clone, PartialEq, Eq)]
pub struct BootGuardState {
    pub fail_count: u32,
    /// sorted, unique
    pub suspects: Vec<String>,
    /// sorted, unique
    pub auto_disabled: Vec<String>,
    pub last_trigger: Option<u64>,
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
    state.fail_count = state.fail_count.saturating_add(1);
    if state.fail_count < BOOT_GUARD_THRESHOLD {
        return Decision::Continue;
    }
    if enabled.is_empty() {
        // nothing left to disable; do not let a stale count hit the next module enabled
        state.fail_count = 0;
        return Decision::Continue;
    }

    let mut targets: Vec<String> = Vec::new();
    for id in state.suspects.iter().filter(|id| enabled.contains(id)) {
        insert_sorted(&mut targets, id);
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
        "threshold": BOOT_GUARD_THRESHOLD,
        "suspects": state.suspects,
        "autoDisabled": auto_disabled,
        "lastTrigger": state.last_trigger,
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

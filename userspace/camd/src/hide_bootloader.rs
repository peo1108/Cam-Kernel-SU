//! Hide an unlocked bootloader from system properties.
//!
//! Only properties that already exist are rewritten, and only to the value a locked,
//! verified device reports: adding properties a device never had is a tell of its own.
//! `/proc/bootconfig` and `/proc/cmdline` are reported, not changed.
#![cfg_attr(not(target_os = "android"), allow(dead_code))]

use serde_json::{Value, json};

/// Matched when the name equals the suffix or ends with `.` + suffix.
const SUFFIX_RULES: [(&str, &str); 7] = [
    ("verifiedbootstate", "green"),
    ("vbmeta.device_state", "locked"),
    ("flash.locked", "1"),
    ("veritymode", "enforcing"),
    ("warranty_bit", "0"),
    ("build.tags", "release-keys"),
    ("build.type", "user"),
];

const EXACT_RULES: [(&str, &str); 4] = [
    ("sys.oem_unlock_allowed", "0"),
    ("ro.secureboot.lockstate", "locked"),
    ("ro.debuggable", "0"),
    ("ro.secure", "1"),
];

/// The value a locked, verified device reports for `name`, if it is a property we hide.
pub fn safe_value(name: &str) -> Option<&'static str> {
    if let Some((_, safe)) = EXACT_RULES.iter().find(|(exact, _)| *exact == name) {
        return Some(safe);
    }
    SUFFIX_RULES
        .iter()
        .find(|(suffix, _)| {
            name == *suffix
                || name
                    .strip_suffix(suffix)
                    .is_some_and(|head| head.ends_with('.'))
        })
        .map(|(_, safe)| *safe)
}

/// The properties to rewrite, as `(name, safe value)`.
pub fn plan(props: &[(String, String)]) -> Vec<(String, &'static str)> {
    props
        .iter()
        .filter_map(|(name, current)| {
            safe_value(name)
                .filter(|safe| current != safe)
                .map(|safe| (name.clone(), safe))
        })
        .collect()
}

/// `androidboot.*` entries we have a rule for, from `/proc/bootconfig` (`key = "value"`)
/// and `/proc/cmdline` (`key=value`).
pub fn parse_boot_args(bootconfig: &str, cmdline: &str) -> Vec<(String, String)> {
    let from_bootconfig = bootconfig.lines().filter_map(|line| {
        let (key, value) = line.split_once('=')?;
        Some((key.trim(), value.trim().trim_matches('"')))
    });
    let from_cmdline = cmdline
        .split_whitespace()
        .filter_map(|arg| arg.split_once('='));
    from_bootconfig
        .chain(from_cmdline)
        .filter(|(key, _)| key.starts_with("androidboot.") && safe_value(key).is_some())
        .map(|(key, value)| (key.to_owned(), value.to_owned()))
        .collect()
}

pub fn status_json(
    enabled: bool,
    props: &[(String, String)],
    boot_args: &[(String, String)],
) -> Value {
    let props: Vec<Value> = props
        .iter()
        .filter_map(|(name, current)| {
            safe_value(name).map(|safe| {
                json!({ "name": name, "current": current, "safe": safe, "ok": current == safe })
            })
        })
        .collect();
    let boot_args: Vec<Value> = boot_args
        .iter()
        .filter_map(|(name, value)| {
            safe_value(name).map(
                |safe| json!({ "name": name, "value": value, "safe": safe, "ok": value == safe }),
            )
        })
        .collect();
    json!({ "enabled": enabled, "props": props, "bootconfig": boot_args })
}

#[cfg(target_os = "android")]
mod android {
    use super::{parse_boot_args, plan, status_json};
    use crate::{defs, resetprop};
    use anyhow::{Context, Result};
    use log::{info, warn};
    use std::path::Path;

    pub fn is_enabled() -> bool {
        Path::new(defs::HIDE_BOOTLOADER_FLAG).exists()
    }

    /// Rewrite the properties that give the bootloader away, when the feature is on.
    pub fn apply_if_enabled() {
        if !is_enabled() {
            return;
        }
        let props = match resetprop::list_props() {
            Ok(props) => props,
            Err(e) => {
                warn!("hide bootloader: list props failed: {e}");
                return;
            }
        };
        for (name, safe) in plan(&props) {
            match resetprop::set_prop(&name, safe) {
                Ok(()) => info!("hide bootloader: {name} -> {safe}"),
                Err(e) => warn!("hide bootloader: set {name} failed: {e}"),
            }
        }
    }

    pub fn status() -> Result<()> {
        let props = resetprop::list_props()?;
        let bootconfig = std::fs::read_to_string("/proc/bootconfig").unwrap_or_default();
        let cmdline = std::fs::read_to_string("/proc/cmdline").unwrap_or_default();
        let status = status_json(
            is_enabled(),
            &props,
            &parse_boot_args(&bootconfig, &cmdline),
        );
        println!("{}", serde_json::to_string_pretty(&status)?);
        Ok(())
    }

    /// Turning it off only drops the flag: the original values come back on the next boot.
    pub fn set_enabled(enabled: bool) -> Result<()> {
        let flag = Path::new(defs::HIDE_BOOTLOADER_FLAG);
        if enabled {
            std::fs::write(flag, b"")
                .with_context(|| format!("Failed to create {}", flag.display()))?;
            apply_if_enabled();
        } else if flag.exists() {
            std::fs::remove_file(flag)
                .with_context(|| format!("Failed to remove {}", flag.display()))?;
        }
        Ok(())
    }
}

#[cfg(target_os = "android")]
pub use android::*;

#[cfg(test)]
mod tests {
    use super::*;

    fn props(v: &[(&str, &str)]) -> Vec<(String, String)> {
        v.iter()
            .map(|(k, v)| ((*k).to_owned(), (*v).to_owned()))
            .collect()
    }

    #[test]
    fn suffix_rules() {
        assert_eq!(safe_value("ro.boot.verifiedbootstate"), Some("green"));
        assert_eq!(safe_value("vendor.boot.verifiedbootstate"), Some("green"));
        assert_eq!(safe_value("ro.boot.vbmeta.device_state"), Some("locked"));
        assert_eq!(safe_value("ro.boot.flash.locked"), Some("1"));
        assert_eq!(safe_value("ro.boot.veritymode"), Some("enforcing"));
        assert_eq!(safe_value("ro.boot.warranty_bit"), Some("0"));
        assert_eq!(safe_value("ro.vendor.build.tags"), Some("release-keys"));
        assert_eq!(safe_value("ro.build.type"), Some("user"));
    }

    #[test]
    fn exact_rules() {
        assert_eq!(safe_value("sys.oem_unlock_allowed"), Some("0"));
        assert_eq!(safe_value("ro.secureboot.lockstate"), Some("locked"));
        assert_eq!(safe_value("ro.debuggable"), Some("0"));
        assert_eq!(safe_value("ro.secure"), Some("1"));
    }

    #[test]
    fn does_not_touch_lookalikes() {
        assert_eq!(safe_value("ro.boot.veritymode.managed"), None);
        assert_eq!(safe_value("debug.tracing.device_state"), None);
        assert_eq!(safe_value("ro.oem_unlock_supported"), None);
        assert_eq!(safe_value("persist.sys.verifiedbootstate_x"), None);
        assert_eq!(safe_value("ro.secure.boot"), None);
    }

    #[test]
    fn plan_only_fixes_present_props() {
        let p = props(&[
            ("ro.boot.verifiedbootstate", "orange"),
            ("ro.boot.flash.locked", "1"),
            ("ro.boot.vbmeta.device_state", "unlocked"),
            ("ro.product.model", "TB323FU"),
        ]);
        assert_eq!(
            plan(&p),
            vec![
                ("ro.boot.verifiedbootstate".to_owned(), "green"),
                ("ro.boot.vbmeta.device_state".to_owned(), "locked"),
            ]
        );
        assert!(plan(&[]).is_empty());
    }

    #[test]
    fn parses_bootconfig_and_cmdline() {
        let bootconfig = "androidboot.verifiedbootstate = \"orange\"\nandroidboot.hardware = \"qcom\"\nandroidboot.vbmeta.device_state = \"locked\"\n";
        let cmdline = "console=ttyMSM0 androidboot.flash.locked=0 quiet";
        assert_eq!(
            parse_boot_args(bootconfig, cmdline),
            props(&[
                ("androidboot.verifiedbootstate", "orange"),
                ("androidboot.vbmeta.device_state", "locked"),
                ("androidboot.flash.locked", "0"),
            ])
        );
    }

    #[test]
    fn status_shape() {
        let v = status_json(
            true,
            &props(&[
                ("ro.boot.verifiedbootstate", "orange"),
                ("ro.product.model", "x"),
            ]),
            &props(&[("androidboot.flash.locked", "1")]),
        );
        assert_eq!(
            v,
            serde_json::json!({
                "enabled": true,
                "props": [{"name": "ro.boot.verifiedbootstate", "current": "orange", "safe": "green", "ok": false}],
                "bootconfig": [{"name": "androidboot.flash.locked", "value": "1", "safe": "1", "ok": true}],
            })
        );
    }
}

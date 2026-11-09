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

/// Rewritten like the rest, but not a tell: "OEM unlocking" may be allowed on a locked
/// bootloader too. Duck Detector only shows it since `84370394`.
const NOT_TELLS: [&str; 1] = ["sys.oem_unlock_allowed"];

/// Whether an app reading a wrong value of `name` learns the bootloader is unlocked.
pub fn is_tell(name: &str) -> bool {
    !NOT_TELLS.contains(&name)
}

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

/// Set by the bootloader on a locked, verified device; left empty or all-zero by
/// some bootloaders even though the device is locked (we fill it with the real value).
pub const VBMETA_DIGEST: &str = "ro.boot.vbmeta.digest";
/// The hash of the top-level vbmeta public key. Not every bootloader sets it: the
/// TB323FU (Y700), genuinely locked, has no such property at all, and its TEE attests
/// the SHA-256 of an empty key. Where a bootloader does create it but leaves it blank,
/// a detector reading it next to a faked `device_state=locked` sees the mismatch.
pub const VBMETA_PUBLIC_KEY_DIGEST: &str = "ro.boot.vbmeta.public_key_digest";

/// Whether `prop` should be filled with the device's own computed value: only when the
/// device already has it AND it is blank (empty or all `0`). An absent property is
/// never added — that is the TB323FU case, where adding one would be the tell — and a
/// real value is never overwritten.
pub fn needs_fill(props: &[(String, String)], prop: &str) -> bool {
    props
        .iter()
        .find(|(name, _)| name == prop)
        .is_some_and(|(_, current)| current.bytes().all(|b| b == b'0'))
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
    use super::{
        VBMETA_DIGEST, VBMETA_PUBLIC_KEY_DIGEST, needs_fill, parse_boot_args, plan, status_json,
    };
    use crate::{defs, resetprop, vbmeta};
    use anyhow::{Context, Result};
    use log::{info, warn};
    use serde_json::json;
    use std::path::Path;

    pub fn is_enabled() -> bool {
        Path::new(defs::HIDE_BOOTLOADER_FLAG).exists()
    }

    /// Rewrite the properties that give the bootloader away, when the feature is on.
    pub fn apply_if_enabled() {
        if is_enabled() {
            apply();
        }
    }

    /// Rewrite the revealing properties now, regardless of the flag. Used by `apply_if_enabled` and by
    /// the attestation feature: its attested locked/Verified root of trust must agree with the live
    /// props a detector also reads. A no-op on a genuinely locked device (nothing to rewrite).
    pub fn apply() {
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
        fill_vbmeta_props(&props);
    }

    /// Fill `ro.boot.vbmeta.digest` and `ro.boot.vbmeta.public_key_digest` with the device's
    /// own computed values when the bootloader created them but left them empty or all-zero.
    ///
    /// A property that exists but is blank next to `device_state=locked` is a tell. An absent
    /// one is not: a genuinely locked TB323FU has no `public_key_digest` at all, so adding it
    /// would be the tell. Hence the rule in [`needs_fill`]: never add, never overwrite.
    /// Runs last on the standalone hide path; with attestation on, `attest::apply_vbmeta_props`
    /// runs after and forces the attested verified-boot key/hash over these.
    fn fill_vbmeta_props(props: &[(String, String)]) {
        fill_vbmeta_prop(
            props,
            VBMETA_DIGEST,
            vbmeta::computed_digest,
            "could not compute vbmeta digest",
        );
        fill_vbmeta_prop(
            props,
            VBMETA_PUBLIC_KEY_DIGEST,
            vbmeta::computed_public_key_digest,
            "could not compute vbmeta public key digest",
        );
    }

    /// Fill one `ro.boot.vbmeta.*` property from `compute` when [`needs_fill`] says so
    /// (present but empty or all-zero). A real value is never overwritten, an absent
    /// property never added; a compute failure leaves it exactly as the bootloader set it.
    fn fill_vbmeta_prop(
        props: &[(String, String)],
        prop: &str,
        compute: fn() -> Option<String>,
        on_miss: &str,
    ) {
        if !needs_fill(props, prop) {
            return;
        }
        match compute() {
            Some(value) => match resetprop::set_prop(prop, &value) {
                Ok(()) => info!("hide bootloader: {prop} -> {value}"),
                Err(e) => warn!("hide bootloader: set {prop} failed: {e}"),
            },
            None => warn!("hide bootloader: {on_miss}"),
        }
    }

    pub fn status() -> Result<()> {
        let props = resetprop::list_props()?;
        let bootconfig = std::fs::read_to_string("/proc/bootconfig").unwrap_or_default();
        let cmdline = std::fs::read_to_string("/proc/cmdline").unwrap_or_default();
        let mut status = status_json(
            is_enabled(),
            &props,
            &parse_boot_args(&bootconfig, &cmdline),
        );
        // What camd would fill ro.boot.vbmeta.digest / .public_key_digest with (for
        // verifying against the bootloader's own values on a device that reports them).
        if let Some(obj) = status.as_object_mut() {
            if let Some(digest) = vbmeta::computed_digest() {
                obj.insert("computedVbmetaDigest".to_owned(), json!(digest));
            }
            if let Some(key) = vbmeta::computed_public_key_digest() {
                obj.insert("computedVbmetaPublicKeyDigest".to_owned(), json!(key));
            }
        }
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
    fn oem_unlock_allowed_is_hidden_but_not_a_tell() {
        assert_eq!(
            plan(&props(&[("sys.oem_unlock_allowed", "1")])),
            vec![("sys.oem_unlock_allowed".to_owned(), "0")]
        );
        assert!(!is_tell("sys.oem_unlock_allowed"));
        assert!(is_tell("ro.boot.verifiedbootstate"));
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
    fn needs_fill_only_present_and_blank() {
        let p = props(&[
            (VBMETA_DIGEST, ""),
            (VBMETA_PUBLIC_KEY_DIGEST, &"0".repeat(64)),
        ]);
        assert!(needs_fill(&p, VBMETA_DIGEST));
        assert!(needs_fill(&p, VBMETA_PUBLIC_KEY_DIGEST));
        // A real value is never overwritten.
        let real = props(&[(VBMETA_PUBLIC_KEY_DIGEST, "7b34b551")]);
        assert!(!needs_fill(&real, VBMETA_PUBLIC_KEY_DIGEST));
        // An absent property is never added.
        assert!(!needs_fill(&[], VBMETA_PUBLIC_KEY_DIGEST));
    }

    #[test]
    fn genuine_tb323fu_gets_nothing_filled() {
        // The ro.boot.vbmeta.* props of the genuinely locked TB323FU (2026-11-09): a real
        // digest and no public_key_digest at all (its TEE attests SHA-256 of an empty key).
        // Hiding must leave it exactly as it is — filling either prop would differ from
        // every other locked Y700.
        let p = props(&[
            ("ro.boot.vbmeta.avb_version", "1.2"),
            ("ro.boot.vbmeta.device_state", "locked"),
            (
                VBMETA_DIGEST,
                "ab3cdba07866ac71829e75e019bafd2a5e89edd659a86486d5a8c36f5b24e207",
            ),
            ("ro.boot.vbmeta.hash_alg", "sha256"),
            ("ro.boot.vbmeta.size", "18048"),
            ("ro.boot.verifiedbootstate", "green"),
            ("ro.boot.flash.locked", "1"),
        ]);
        assert!(!needs_fill(&p, VBMETA_DIGEST));
        assert!(!needs_fill(&p, VBMETA_PUBLIC_KEY_DIGEST));
        assert!(plan(&p).is_empty());
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

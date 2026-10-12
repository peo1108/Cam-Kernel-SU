//! Block network for the keybox/RKP provisioning path and for Duck Detector.
//!
//! On a device whose TEE cannot provision RKP keys (CSR generation fails and keystore2
//! falls back to the factory keybox), keystore2 still reaches out to `rkpd` on every
//! attested key, and the resulting network round-trip is what a local timing probe reads
//! as "certificate post-processing". Denying `rkpd` network makes it give up immediately
//! (the same state as having no network), so the RKP path and the ATTEST_KEY path cost the
//! same and the timing signal disappears. Duck Detector itself is denied network too, at
//! the user's request, so it cannot phone home.
//!
//! This only hides a local signal. The real keybox check lives on the verifier's servers;
//! a genuine, un-revoked factory keybox still passes there. On a device that genuinely uses
//! RKP, denying `rkpd` would eventually exhaust its key pool and break attestation, so the
//! Manager gates this toggle behind a check that the device is not RKP-backed.
#![cfg_attr(not(target_os = "android"), allow(dead_code))]

use serde_json::{Value, json};

/// Packages whose network we deny while the feature is on.
/// - `com.android.rkpdapp`: AOSP Remote Key Provisioning daemon (the keybox/RKP path).
/// - `com.eltavine.duckdetector`: the detector app itself.
pub const TARGET_PACKAGES: [&str; 2] = ["com.android.rkpdapp", "com.eltavine.duckdetector"];

/// Parse one line of `cmd connectivity get-package-networking-enabled <pkg>`,
/// e.g. `com.android.rkpdapp:deny` -> `Some(blocked)`. `deny` means blocked.
pub fn parse_networking_state(output: &str) -> Option<bool> {
    let state = output.trim().rsplit_once(':')?.1.trim();
    match state {
        "deny" => Some(true),
        "allow" => Some(false),
        _ => None,
    }
}

pub fn status_json(enabled: bool, packages: &[(String, Option<bool>)]) -> Value {
    let packages: Vec<Value> = packages
        .iter()
        .map(|(name, blocked)| json!({ "package": name, "blocked": blocked }))
        .collect();
    json!({ "enabled": enabled, "packages": packages })
}

#[cfg(target_os = "android")]
mod android {
    use super::{TARGET_PACKAGES, parse_networking_state, status_json};
    use crate::defs;
    use anyhow::{Context, Result};
    use log::{info, warn};
    use std::path::Path;
    use std::process::Command;

    pub fn is_enabled() -> bool {
        Path::new(defs::BLOCK_RKP_FLAG).exists()
    }

    /// `false` adds the package to the OEM_DENY firewall chain (no network);
    /// `true` removes it. The chain is cleared on reboot, so this is re-applied
    /// from `on_boot_completed`.
    fn set_networking(package: &str, allowed: bool) -> Result<()> {
        let status = Command::new("cmd")
            .args([
                "connectivity",
                "set-package-networking-enabled",
                if allowed { "true" } else { "false" },
                package,
            ])
            .status()
            .with_context(|| format!("run cmd connectivity for {package}"))?;
        if !status.success() {
            anyhow::bail!("cmd connectivity exited with {status} for {package}");
        }
        Ok(())
    }

    fn networking_blocked(package: &str) -> Option<bool> {
        let output = Command::new("cmd")
            .args(["connectivity", "get-package-networking-enabled", package])
            .output()
            .ok()?;
        parse_networking_state(&String::from_utf8_lossy(&output.stdout))
    }

    /// Deny network for every target package, when the feature is on.
    pub fn apply_if_enabled() {
        if !is_enabled() {
            return;
        }
        for package in TARGET_PACKAGES {
            match set_networking(package, false) {
                Ok(()) => info!("block rkp: denied network for {package}"),
                Err(e) => warn!("block rkp: deny {package} failed: {e:#}"),
            }
        }
    }

    pub fn status() -> Result<()> {
        let packages: Vec<(String, Option<bool>)> = TARGET_PACKAGES
            .iter()
            .map(|p| ((*p).to_owned(), networking_blocked(p)))
            .collect();
        println!(
            "{}",
            serde_json::to_string_pretty(&status_json(is_enabled(), &packages))?
        );
        Ok(())
    }

    /// Turning it off restores network for the target packages right away.
    pub fn set_enabled(enabled: bool) -> Result<()> {
        let flag = Path::new(defs::BLOCK_RKP_FLAG);
        if enabled {
            std::fs::write(flag, b"")
                .with_context(|| format!("Failed to create {}", flag.display()))?;
            apply_if_enabled();
        } else {
            if flag.exists() {
                std::fs::remove_file(flag)
                    .with_context(|| format!("Failed to remove {}", flag.display()))?;
            }
            for package in TARGET_PACKAGES {
                match set_networking(package, true) {
                    Ok(()) => info!("block rkp: restored network for {package}"),
                    Err(e) => warn!("block rkp: restore {package} failed: {e:#}"),
                }
            }
        }
        Ok(())
    }
}

#[cfg(target_os = "android")]
pub use android::*;

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_deny_and_allow() {
        assert_eq!(
            parse_networking_state("com.android.rkpdapp:deny"),
            Some(true)
        );
        assert_eq!(
            parse_networking_state("com.android.rkpdapp:allow"),
            Some(false)
        );
        assert_eq!(parse_networking_state("  pkg:deny\n"), Some(true));
        assert_eq!(parse_networking_state("unexpected"), None);
        assert_eq!(parse_networking_state("pkg:unknown"), None);
    }

    #[test]
    fn target_packages_cover_rkp_and_duck() {
        assert!(TARGET_PACKAGES.contains(&"com.android.rkpdapp"));
        assert!(TARGET_PACKAGES.contains(&"com.eltavine.duckdetector"));
    }

    #[test]
    fn status_shape() {
        let v = status_json(
            true,
            &[
                ("com.android.rkpdapp".to_owned(), Some(true)),
                ("com.eltavine.duckdetector".to_owned(), None),
            ],
        );
        assert_eq!(
            v,
            serde_json::json!({
                "enabled": true,
                "packages": [
                    {"package": "com.android.rkpdapp", "blocked": true},
                    {"package": "com.eltavine.duckdetector", "blocked": null},
                ],
            })
        );
    }
}

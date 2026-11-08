//! keystore2 attestation (tier 2).
//!
//! camd drives the vendored TEESimulator interceptor: it injects `libteesim_keymint.so` into
//! `keystore2` and pushes the Manager-resolved config over the native control socket. The Manager
//! is the brain (harvest/resolve/keybox); camd is the boot-time executor. See
//! `docs/superpowers/specs/2026-11-08-attestation-keystore2-design.md`.
//!
//! Pure, host-testable helpers live at module top level; the Android runtime is in `mod android`.

use std::io;
use std::path::Path;

/// True when the enable flag file exists.
fn flag_is_set(flag: &Path) -> bool {
    flag.exists()
}

/// Create (enabled) or remove (disabled) the enable flag file. Idempotent: enabling an existing
/// flag rewrites it empty, disabling an absent one is a no-op.
fn write_flag(flag: &Path, enabled: bool) -> io::Result<()> {
    if enabled {
        std::fs::write(flag, b"")
    } else if flag.exists() {
        std::fs::remove_file(flag)
    } else {
        Ok(())
    }
}

#[cfg(target_os = "android")]
mod android {
    use super::{flag_is_set, write_flag};
    use crate::defs;
    use anyhow::Result;
    use std::path::Path;

    /// Whether the attestation feature is enabled (the flag file is present).
    pub fn is_enabled() -> bool {
        flag_is_set(Path::new(defs::ATTEST_FLAG))
    }

    /// Turn the feature on (create the flag) or off (remove it).
    pub fn set_enabled(enabled: bool) -> Result<()> {
        write_flag(Path::new(defs::ATTEST_FLAG), enabled)?;
        Ok(())
    }

    /// Print the feature's state as JSON (the inject/push status is added in M2-4).
    pub fn status() {
        println!("{}", serde_json::json!({ "enabled": is_enabled() }));
    }
}

#[cfg(target_os = "android")]
pub use android::*;

#[cfg(test)]
mod tests {
    use super::{flag_is_set, write_flag};

    #[test]
    fn write_flag_toggles_presence() {
        let dir = tempfile::tempdir().unwrap();
        let flag = dir.path().join(".attest_sim");
        assert!(!flag_is_set(&flag), "absent before enable");
        write_flag(&flag, true).unwrap();
        assert!(flag_is_set(&flag), "present after enable");
        write_flag(&flag, false).unwrap();
        assert!(!flag_is_set(&flag), "absent after disable");
        // disabling again is a no-op, not an error
        write_flag(&flag, false).unwrap();
        assert!(!flag_is_set(&flag), "still absent after redundant disable");
    }
}

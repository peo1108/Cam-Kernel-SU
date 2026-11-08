//! keystore2 attestation (tier 2).
//!
//! camd drives the vendored TEESimulator interceptor: it injects `libteesim_keymint.so` into
//! `keystore2` and pushes the Manager-resolved config over the native control socket. The Manager
//! is the brain (harvest/resolve/keybox); camd is the boot-time executor. See
//! `docs/superpowers/specs/2026-11-08-attestation-keystore2-design.md`.
//!
//! Pure, host-testable helpers live at module top level; the Android runtime is in `mod android`.

mod control;
mod inject;

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
    use super::control::{self, ControlClient};
    use super::{flag_is_set, inject, write_flag};
    use crate::defs;
    use anyhow::{Context, Result};
    use log::info;
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

    /// Connect to the interceptor and push one resolved config, then print the ack as JSON. `file`
    /// defaults to [`defs::ATTEST_PUSH`]. This is the manual path M2-5 uses to drive a hand-built
    /// push on device; the boot-time background loop (M2-4) does the same thing automatically.
    pub fn push_once(epoch: u64, file: Option<&Path>) -> Result<()> {
        let path = file.unwrap_or_else(|| Path::new(defs::ATTEST_PUSH));
        let config = control::load_push_with_epoch(path, epoch)?;
        let (mut client, hello) = ControlClient::connect(Path::new(defs::TEESIM_CTL))?;
        info!(
            "attest: connected hook={} api={} keystorePid={}",
            hello.hook, hello.android_api, hello.keystore_pid
        );
        let ack = client.push(&config)?;
        info!(
            "attest: ack epoch={} ok={} applied={} failed={}",
            ack.epoch, ack.ok, ack.profiles_applied, ack.profiles_failed
        );
        println!(
            "{}",
            serde_json::json!({
                "ok": ack.ok,
                "epoch": ack.epoch,
                "profilesApplied": ack.profiles_applied,
                "profilesFailed": ack.profiles_failed,
            })
        );
        Ok(())
    }

    /// Ping the interceptor's control socket and print the echoed epoch — a liveness probe.
    pub fn ping_once(epoch: u64) -> Result<()> {
        let (mut client, hello) = ControlClient::connect(Path::new(defs::TEESIM_CTL))?;
        info!("attest: connected hook={}", hello.hook);
        let pong = client.ping(epoch)?;
        println!("{}", serde_json::json!({ "pong": pong }));
        Ok(())
    }

    /// Inject the interceptor into the live keystore2 once, printing the pid as JSON. The manual
    /// counterpart to the boot-time watch loop (M2-4), used by M2-5 to prove injection on device.
    pub fn inject_once() -> Result<()> {
        let pid = inject::keystore2_pid().context("keystore2 process not found")?;
        inject::run_inject(pid)?;
        info!("attest: injected interceptor into keystore2 pid={pid}");
        println!("{}", serde_json::json!({ "injected": true, "pid": pid }));
        Ok(())
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

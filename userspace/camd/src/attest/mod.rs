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

/// What [`start`](android::start) decided to do, so callers can log it and tests can assert it.
#[derive(Debug, PartialEq, Eq)]
pub enum Started {
    /// The feature flag is off: no inject, no push.
    Disabled,
    /// Enabled, but no resolved `push.json` exists yet: the interceptor stays inert.
    NoConfig,
    /// Enabled and a config exists: the inject/push background loop was started.
    Running,
}

/// Decide what `start` should do from the two facts it depends on. Pure, so it is host-tested while
/// the thread it gates is not.
const fn startup_decision(enabled: bool, push_exists: bool) -> Started {
    if !enabled {
        Started::Disabled
    } else if !push_exists {
        Started::NoConfig
    } else {
        Started::Running
    }
}

#[cfg(target_os = "android")]
mod android {
    use super::control::{self, ControlClient};
    use super::{Started, flag_is_set, inject, startup_decision, write_flag};
    use crate::defs;
    use anyhow::{Context, Result};
    use log::{info, warn};
    use std::path::Path;
    use std::time::{Duration, SystemTime};

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
    /// counterpart to the boot-time watch loop, used by M2-5 to prove injection on device.
    pub fn inject_once() -> Result<()> {
        let pid = inject::keystore2_pid().context("keystore2 process not found")?;
        inject::run_inject(pid)?;
        info!("attest: injected interceptor into keystore2 pid={pid}");
        println!("{}", serde_json::json!({ "injected": true, "pid": pid }));
        Ok(())
    }

    /// Start the boot-time inject + push background loop when the feature is enabled and a config
    /// exists. Returns the decision so the caller can log it; the loop itself runs on its own thread
    /// and never blocks boot. Safe no-op when disabled or unconfigured (the interceptor stays inert).
    pub fn start() -> Started {
        let decision = startup_decision(is_enabled(), Path::new(defs::ATTEST_PUSH).exists());
        if decision == Started::Running {
            let spawned = std::thread::Builder::new()
                .name("attest".to_owned())
                .spawn(run_loop);
            if let Err(e) = spawned {
                warn!("attest: could not start background loop: {e}");
            }
        }
        decision
    }

    /// The background loop: keep the interceptor injected across keystore2 restarts, push the config
    /// after each fresh inject, and re-push when `push.json` changes.
    fn run_loop() {
        info!("attest: background loop started");
        let mut epoch: u64 = 1;
        let mut injected_pid: i32 = -1;
        let mut last_push_mtime = file_mtime(defs::ATTEST_PUSH);
        loop {
            match inject::keystore2_pid() {
                // A new (or first) keystore2: inject, then push once it checks in.
                Some(pid) if pid != injected_pid => match inject::run_inject(pid) {
                    Ok(()) => {
                        info!("attest: injected keystore2 pid={pid}");
                        injected_pid = pid;
                        if push_config(&mut epoch).is_ok() {
                            last_push_mtime = file_mtime(defs::ATTEST_PUSH);
                        }
                    }
                    Err(e) => warn!("attest: inject pid={pid} failed: {e:#}"),
                },
                // Same keystore2 still alive: re-push only if the resolved config changed.
                Some(_) => {
                    let now = file_mtime(defs::ATTEST_PUSH);
                    if now != last_push_mtime {
                        info!("attest: push.json changed; re-pushing");
                        if push_config(&mut epoch).is_ok() {
                            last_push_mtime = now;
                        }
                    }
                }
                // keystore2 gone: forget the pid so it is re-injected when it returns.
                None => injected_pid = -1,
            }
            std::thread::sleep(Duration::from_secs(2));
        }
    }

    /// Load the resolved config, connect (retrying while the interceptor binds its socket), push it,
    /// and advance the epoch on success.
    fn push_config(epoch: &mut u64) -> Result<()> {
        let config = control::load_push_with_epoch(Path::new(defs::ATTEST_PUSH), *epoch)?;
        let mut last_err = None;
        for _ in 0..12 {
            match ControlClient::connect(Path::new(defs::TEESIM_CTL)) {
                Ok((mut client, hello)) => {
                    info!(
                        "attest: control hello hook={} api={}",
                        hello.hook, hello.android_api
                    );
                    let ack = client.push(&config)?;
                    info!(
                        "attest: pushed epoch={} applied={} failed={}",
                        ack.epoch, ack.profiles_applied, ack.profiles_failed
                    );
                    *epoch += 1;
                    return Ok(());
                }
                Err(e) => {
                    last_err = Some(e);
                    std::thread::sleep(Duration::from_millis(500));
                }
            }
        }
        let e = last_err.unwrap_or_else(|| anyhow::anyhow!("control socket never appeared"));
        warn!("attest: push failed: {e:#}");
        Err(e)
    }

    /// The modified time of `path`, or `None` if it cannot be read — used to detect config edits.
    fn file_mtime(path: &str) -> Option<SystemTime> {
        std::fs::metadata(path).and_then(|m| m.modified()).ok()
    }
}

#[cfg(target_os = "android")]
pub use android::*;

#[cfg(test)]
mod tests {
    use super::{Started, flag_is_set, startup_decision, write_flag};

    #[test]
    fn startup_decision_gates_on_flag_and_config() {
        assert_eq!(startup_decision(false, false), Started::Disabled);
        assert_eq!(startup_decision(false, true), Started::Disabled);
        assert_eq!(startup_decision(true, false), Started::NoConfig);
        assert_eq!(startup_decision(true, true), Started::Running);
    }

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

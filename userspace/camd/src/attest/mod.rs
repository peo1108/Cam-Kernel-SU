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

/// One iteration's decision for the daemon loop.
#[derive(Debug, PartialEq, Eq)]
enum Action {
    /// keystore2 is gone: forget the injected/pushed pids.
    Reset,
    /// A new (or first) keystore2: inject the interceptor.
    Inject,
    /// Injected into this keystore2 but not yet pushed to it, or the config changed: push.
    Push,
    /// Steady state: nothing to do.
    Nothing,
}

/// Decide the loop's next action from what it can observe. `pushed_pid` is the keystore2 pid the
/// config was last successfully pushed to (-1 = none), so a fresh inject whose first push failed
/// keeps retrying the push every tick instead of waiting for a config edit (the re-push bug).
const fn next_action(
    current: Option<i32>,
    injected_pid: i32,
    pushed_pid: i32,
    push_changed: bool,
) -> Action {
    match current {
        None => Action::Reset,
        Some(pid) if pid != injected_pid => Action::Inject,
        Some(pid) if pid != pushed_pid => Action::Push,
        Some(_) if push_changed => Action::Push,
        Some(_) => Action::Nothing,
    }
}

/// Loop sleep in seconds: short normally, backed off after repeated failures so a wedged keystore2
/// (e.g. SELinux blocking ptrace) is not hammered with ptrace attaches every couple of seconds.
const fn backoff_secs(failures: u32) -> u64 {
    if failures > 3 { 30 } else { 2 }
}

/// The SELinux rules the interceptor needs, applied live when the feature starts. The first — keystore
/// reading the injected `.so` from /data/adb — is the one M2-5 proved strictly required on Android 16;
/// the rest cover the staging fallback and the control-socket bind for stricter devices. Best-effort:
/// a device that already allows one just no-ops it.
const ATTEST_SEPOLICY: &[&str] = &[
    "allow keystore adb_data_file file { read open getattr map execute }",
    "allow keystore shell_data_file dir { search read open getattr }",
    "allow keystore shell_data_file file { read open getattr map execute }",
    "allow keystore keystore_data_file sock_file { create bind unlink read write open getattr setattr }",
    "allow keystore self unix_stream_socket { create bind listen accept getopt setopt read write shutdown }",
];

/// The `ro.boot.vbmeta.*` properties to force so the live bootloader values an app reads agree with
/// the attested RootOfTrust in `push.json`: `public_key_digest` <- `bootInfo.verifiedBootKey`,
/// `digest` <- `bootInfo.verifiedBootHash`. A detector that compares the key attestation against
/// these properties (e.g. Duck) would otherwise catch a mismatch — most importantly a locked,
/// Verified certificate whose `public_key_digest` property is empty/zero on an unlocked device.
///
/// Only a usable value (decodes to 32 bytes, not all zero) is emitted; a missing, malformed or
/// all-zero field is skipped so we never write a degenerate property. Pure, so it is host-tested.
fn boot_vbmeta_props(push_json: &str) -> Vec<(&'static str, String)> {
    let mut out = Vec::new();
    let Ok(value) = serde_json::from_str::<serde_json::Value>(push_json) else {
        return out;
    };
    let boot = &value["bootInfo"];
    for (prop, field) in [
        ("ro.boot.vbmeta.public_key_digest", "verifiedBootKey"),
        ("ro.boot.vbmeta.digest", "verifiedBootHash"),
    ] {
        let Some(bytes) = boot
            .get(field)
            .and_then(|v| v.as_str())
            .and_then(b64_decode)
        else {
            continue;
        };
        if bytes.len() == 32 && bytes.iter().any(|&b| b != 0) {
            out.push((prop, base16ct::lower::encode_string(&bytes)));
        }
    }
    out
}

/// Decode standard (RFC 4648) base64, ignoring padding and surrounding whitespace. Returns `None`
/// on any non-alphabet byte. The `bootInfo` key/hash are base64 (written by the Manager's harvest),
/// but the properties they map to are hex, so camd decodes then re-encodes as hex.
fn b64_decode(s: &str) -> Option<Vec<u8>> {
    fn sextet(c: u8) -> Option<u32> {
        match c {
            b'A'..=b'Z' => Some(u32::from(c - b'A')),
            b'a'..=b'z' => Some(u32::from(c - b'a' + 26)),
            b'0'..=b'9' => Some(u32::from(c - b'0' + 52)),
            b'+' => Some(62),
            b'/' => Some(63),
            _ => None,
        }
    }
    let mut out = Vec::new();
    let mut buf = 0u32;
    let mut bits = 0u32;
    for &c in s.trim().as_bytes() {
        if c == b'=' || c == b'\n' || c == b'\r' {
            continue;
        }
        buf = (buf << 6) | sextet(c)?;
        bits += 6;
        if bits >= 8 {
            bits -= 8;
            out.push((buf >> bits) as u8);
        }
    }
    Some(out)
}

#[cfg(target_os = "android")]
mod android {
    use super::control::{self, ControlClient};
    use super::{
        Action, Started, backoff_secs, flag_is_set, inject, next_action, startup_decision,
        write_flag,
    };
    use crate::{defs, utils};
    use anyhow::{Context, Result};
    use log::{info, warn};
    use std::os::fd::AsRawFd;
    use std::os::unix::process::CommandExt;
    use std::path::Path;
    use std::process::{Command, Stdio};
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

    /// Start the boot-time inject + push loop when the feature is enabled and a config exists.
    /// Returns the decision so the caller can log it. The loop runs in a DAEMONIZED resident process,
    /// not a thread: the `camd services` process that calls this is a one-shot init `exec`, so a
    /// thread would die the instant it returns. Safe no-op when disabled or unconfigured.
    pub fn start() -> Started {
        let decision = startup_decision(is_enabled(), Path::new(defs::ATTEST_PUSH).exists());
        if decision == Started::Running {
            apply_attest_sepolicy();
            // The attested locked/Verified root of trust must agree with the live bootloader props a
            // detector also reads; a no-op on a genuinely locked device (Cam's EFISP targets).
            crate::hide_bootloader::apply();
            // Then force ro.boot.vbmeta.public_key_digest and .digest to the exact verified-boot
            // key/hash we attest (from push.json), so the key attestation and the live properties
            // agree even on an unlocked device. Runs last so it wins over hide_bootloader's own
            // independently computed digest.
            apply_vbmeta_props();
            let spawned = spawn_daemon();
            if let Err(e) = spawned {
                warn!("attest: could not start daemon: {e:#}");
            }
        }
        decision
    }

    /// Force ro.boot.vbmeta.public_key_digest and .digest to match the attested verified-boot
    /// key/hash in push.json (see [`super::boot_vbmeta_props`]). Best-effort: a missing config or a
    /// degenerate field is simply skipped, leaving the property as the bootloader set it.
    fn apply_vbmeta_props() {
        let push = match std::fs::read_to_string(defs::ATTEST_PUSH) {
            Ok(s) => s,
            Err(e) => {
                warn!(
                    "attest: cannot read {} for vbmeta props: {e}",
                    defs::ATTEST_PUSH
                );
                return;
            }
        };
        for (prop, hex) in super::boot_vbmeta_props(&push) {
            match crate::resetprop::set_prop(prop, &hex) {
                Ok(()) => info!("attest: {prop} -> {hex}"),
                Err(e) => warn!("attest: set {prop} failed: {e}"),
            }
        }
    }

    /// Apply the interceptor's SELinux rules live (best-effort), so the daemon can inject the
    /// interceptor and keystore can read it — persisted across reboots because `start` runs each boot.
    fn apply_attest_sepolicy() {
        for rule in super::ATTEST_SEPOLICY {
            if let Err(e) = crate::sepolicy::live_patch(rule) {
                warn!("attest: sepolicy '{rule}' failed: {e:#}");
            }
        }
    }

    /// Fork into the background and exec a resident `attest daemon` process (mirrors `spawn_sulogd`).
    fn spawn_daemon() -> Result<()> {
        if utils::create_daemon(true)? {
            let err = Command::new("/proc/self/exe")
                .arg("attest")
                .arg("daemon")
                .stdin(Stdio::null())
                .stdout(Stdio::null())
                .stderr(Stdio::null())
                .current_dir("/")
                .exec();
            log::error!("attest: failed to exec daemon: {err}");
            unsafe { libc::_exit(1) };
        }
        Ok(())
    }

    /// The resident daemon entry (the `attest daemon` subcommand). Holds a singleton `flock` so a
    /// second daemon — e.g. after a soft reboot re-runs the service stage — exits instead of
    /// double-injecting keystore2 (which the interceptor must never be).
    pub fn daemon_main() -> Result<()> {
        // `lock` is held for the whole process lifetime (run_loop never returns); the fd closing on
        // exit releases it.
        let lock = std::fs::OpenOptions::new()
            .create(true)
            .write(true)
            .truncate(false)
            .open(defs::ATTEST_LOCK)
            .with_context(|| format!("open {}", defs::ATTEST_LOCK))?;
        if unsafe { libc::flock(lock.as_raw_fd(), libc::LOCK_EX | libc::LOCK_NB) } != 0 {
            info!("attest: another attest daemon is already running; exiting");
            return Ok(());
        }
        run_loop();
        Ok(())
    }

    /// The daemon loop: keep the interceptor injected across keystore2 restarts, push after every
    /// fresh inject (retrying until it lands, not only on a config edit), and re-push when `push.json`
    /// changes. Backs off when inject or push keeps failing so a wedged keystore2 is not hammered.
    fn run_loop() {
        info!("attest: daemon loop started");
        let mut epoch: u64 = 1;
        let mut injected_pid: i32 = -1;
        let mut pushed_pid: i32 = -1;
        let mut last_push_mtime = file_mtime(defs::ATTEST_PUSH);
        let mut failures: u32 = 0;
        loop {
            let current = inject::keystore2_pid();
            // Captured before the push loads the file, so an edit during a push still trips next tick.
            let push_mtime = file_mtime(defs::ATTEST_PUSH);
            let push_changed = push_mtime != last_push_mtime;
            match next_action(current, injected_pid, pushed_pid, push_changed) {
                Action::Reset => {
                    injected_pid = -1;
                    pushed_pid = -1;
                    failures = 0;
                }
                Action::Inject => {
                    let pid = current.unwrap_or(-1);
                    match inject::run_inject(pid) {
                        Ok(()) => {
                            info!("attest: injected keystore2 pid={pid}");
                            injected_pid = pid;
                            pushed_pid = -1; // force a push to the fresh keystore2
                            failures = 0;
                        }
                        Err(e) => {
                            warn!("attest: inject pid={pid} failed: {e:#}");
                            failures += 1;
                        }
                    }
                }
                Action::Push => match push_config(&mut epoch) {
                    Ok(()) => {
                        pushed_pid = current.unwrap_or(-1);
                        last_push_mtime = push_mtime;
                        failures = 0;
                    }
                    // push_config logs the reason; just back off here.
                    Err(_) => failures += 1,
                },
                Action::Nothing => failures = 0,
            }
            std::thread::sleep(Duration::from_secs(backoff_secs(failures)));
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
    use super::{
        Action, Started, b64_decode, backoff_secs, boot_vbmeta_props, flag_is_set, next_action,
        startup_decision, write_flag,
    };

    /// Minimal standard base64 encoder, so the vbmeta-prop fixtures below are readable.
    fn b64_encode(bytes: &[u8]) -> String {
        const A: &[u8; 64] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
        let mut out = String::new();
        for chunk in bytes.chunks(3) {
            let b = [
                chunk[0],
                *chunk.get(1).unwrap_or(&0),
                *chunk.get(2).unwrap_or(&0),
            ];
            let n = ((b[0] as u32) << 16) | ((b[1] as u32) << 8) | b[2] as u32;
            out.push(A[((n >> 18) & 63) as usize] as char);
            out.push(A[((n >> 12) & 63) as usize] as char);
            out.push(if chunk.len() > 1 {
                A[((n >> 6) & 63) as usize] as char
            } else {
                '='
            });
            out.push(if chunk.len() > 2 {
                A[(n & 63) as usize] as char
            } else {
                '='
            });
        }
        out
    }

    #[test]
    fn b64_decode_roundtrips_and_rejects_garbage() {
        assert_eq!(b64_decode("QUJD").as_deref(), Some(&b"ABC"[..]));
        assert_eq!(
            b64_decode(&b64_encode(&[0, 255, 16, 32])).unwrap(),
            vec![0, 255, 16, 32]
        );
        assert_eq!(b64_decode("!!!"), None);
    }

    #[test]
    fn boot_vbmeta_props_maps_32byte_bootinfo_to_hex() {
        let json = serde_json::json!({
            "bootInfo": {
                "verifiedBootKey": b64_encode(&[0x11u8; 32]),
                "verifiedBootHash": b64_encode(&[0x22u8; 32]),
            }
        })
        .to_string();
        assert_eq!(
            boot_vbmeta_props(&json),
            vec![
                ("ro.boot.vbmeta.public_key_digest", "11".repeat(32)),
                ("ro.boot.vbmeta.digest", "22".repeat(32)),
            ]
        );
    }

    #[test]
    fn boot_vbmeta_props_skips_zero_short_and_missing() {
        let json = serde_json::json!({
            "bootInfo": {
                "verifiedBootKey": b64_encode(&[0u8; 32]),   // all zero
                "verifiedBootHash": b64_encode(&[0x11u8; 3]), // too short
            }
        })
        .to_string();
        assert!(boot_vbmeta_props(&json).is_empty());
        assert!(boot_vbmeta_props("{}").is_empty());
        assert!(boot_vbmeta_props("not json").is_empty());
    }

    #[test]
    fn next_action_covers_the_lifecycle() {
        // keystore2 gone → reset.
        assert_eq!(next_action(None, 5, 5, false), Action::Reset);
        // first keystore2 (nothing injected yet) → inject.
        assert_eq!(next_action(Some(5), -1, -1, false), Action::Inject);
        // keystore2 restarted (pid changed) → inject the new one.
        assert_eq!(next_action(Some(7), 5, 5, false), Action::Inject);
        // injected but the first push has not landed yet → push (not Nothing: the re-push bug).
        assert_eq!(next_action(Some(5), 5, -1, false), Action::Push);
        // config edited since the last push → push.
        assert_eq!(next_action(Some(5), 5, 5, true), Action::Push);
        // steady state → nothing.
        assert_eq!(next_action(Some(5), 5, 5, false), Action::Nothing);
    }

    #[test]
    fn backoff_grows_after_repeated_failures() {
        assert_eq!(backoff_secs(0), 2);
        assert_eq!(backoff_secs(3), 2);
        assert_eq!(backoff_secs(4), 30);
    }

    #[test]
    fn sepolicy_rules_let_keystore_read_the_interceptor() {
        // The one rule M2-5 proved strictly required on Android 16.
        assert!(super::ATTEST_SEPOLICY.iter().any(|r| {
            r.contains("allow keystore adb_data_file file")
                && r.contains("read")
                && r.contains("execute")
        }));
    }

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

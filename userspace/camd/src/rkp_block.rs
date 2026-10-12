//! Cut all device internet while Duck Detector is in the foreground.
//!
//! Duck Detector reads a local timing signal: on a device whose TEE cannot provision
//! RKP keys, keystore2 still reaches out to `rkpd` on every attested key, and that
//! network round-trip is what the detector times as "certificate post-processing". The
//! simplest way to make that signal disappear is to make sure nothing on the device can
//! reach the network *while the detector is looking*: a watcher polls the foreground app,
//! and for as long as Duck Detector is on top it inserts an `iptables`/`ip6tables` DROP on
//! the `OUTPUT` chain (everything except loopback). Wi-Fi stays associated, but no packet
//! leaves, so the detector sees exactly what it would with Wi-Fi off: "connected, no
//! internet" with the `!` badge. When Duck Detector leaves the foreground the rule is
//! pulled and the network comes straight back.
//!
//! This only hides a local signal. The real keybox check lives on the verifier's servers;
//! a genuine, un-revoked factory keybox still passes there.
//!
//! The watcher is a single detached `camd block-rkp watch` process (one at a time, guarded
//! by a flock). The enable flag `/data/adb/cam/.block_rkp` drives it: `enable` writes the
//! flag and spawns the watcher, `disable` removes the flag (the watcher sees that on its
//! next poll, drops the DROP rule and exits) and clears any rule immediately. The flag is
//! re-read on boot (`on_boot_completed`) to respawn the watcher, since both the firewall
//! rules and the running process are gone after a reboot.
#![cfg_attr(not(target_os = "android"), allow(dead_code))]

use serde_json::{Value, json};

/// Foreground packages that trigger the device-wide network cut.
pub const TARGET_PACKAGES: [&str; 1] = ["com.eltavine.duckdetector"];

/// Pull the foreground package out of `dumpsys activity activities`.
///
/// Newer Android reports the focused app as `topResumedActivity=ActivityRecord{<hash> u0
/// <pkg>/<cls> ...}`; older builds use `mResumedActivity=...`. We prefer the `top` line
/// (the truly focused display) and fall back to `mResumedActivity`. Returns the package
/// (the token before `/`), or `None` when nothing is resumed.
pub fn parse_foreground_package(dumpsys: &str) -> Option<String> {
    pick_resumed(dumpsys, "topResumedActivity")
        .or_else(|| pick_resumed(dumpsys, "mResumedActivity"))
}

fn pick_resumed(dumpsys: &str, key: &str) -> Option<String> {
    for line in dumpsys.lines() {
        let line = line.trim();
        if !line.starts_with(key) {
            continue;
        }
        let rhs = line.split_once('=')?.1.trim_start();
        if rhs.starts_with("null") {
            continue;
        }
        // Find the "<pkg>/<cls>" component among the whitespace-separated tokens.
        for tok in rhs.split_whitespace() {
            if let Some((pkg, _cls)) = tok.split_once('/')
                && pkg.contains('.')
                && pkg
                    .bytes()
                    .all(|b| b.is_ascii_alphanumeric() || b == b'.' || b == b'_')
            {
                return Some(pkg.to_owned());
            }
        }
    }
    None
}

/// Is one of the target packages the foreground app in this `dumpsys` output?
pub fn is_target_foreground(dumpsys: &str) -> bool {
    parse_foreground_package(dumpsys).is_some_and(|pkg| TARGET_PACKAGES.contains(&pkg.as_str()))
}

pub fn status_json(enabled: bool, blocking: bool, foreground: Option<&str>) -> Value {
    json!({
        "enabled": enabled,
        "blocking": blocking,
        "foreground": foreground,
        "targets": TARGET_PACKAGES,
    })
}

#[cfg(target_os = "android")]
mod android {
    use super::{is_target_foreground, parse_foreground_package, status_json};
    use crate::defs;
    use crate::utils;
    use anyhow::{Context, Result};
    use log::{info, warn};
    use std::fs::{File, OpenOptions};
    use std::os::unix::fs::OpenOptionsExt;
    use std::os::unix::io::AsRawFd;
    use std::os::unix::process::CommandExt;
    use std::path::Path;
    use std::process::{Command, Stdio};
    use std::thread;
    use std::time::Duration;

    const POLL_INTERVAL: Duration = Duration::from_secs(1);
    /// The rule body shared by `-C`/`-I`/`-D`: drop everything leaving, except loopback.
    const RULE: [&str; 6] = ["OUTPUT", "!", "-o", "lo", "-j", "DROP"];
    const IPT_BINS: [&str; 2] = ["iptables", "ip6tables"];

    pub fn is_enabled() -> bool {
        Path::new(defs::BLOCK_RKP_FLAG).exists()
    }

    fn run_ipt(bin: &str, args: &[&str]) -> bool {
        Command::new(bin)
            .args(args)
            .stdin(Stdio::null())
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            .status()
            .is_ok_and(|s| s.success())
    }

    fn rule_present(bin: &str) -> bool {
        let mut args = vec!["-C"];
        args.extend_from_slice(&RULE);
        run_ipt(bin, &args)
    }

    /// Is the DROP rule currently installed (for IPv4)?
    pub fn is_blocking() -> bool {
        rule_present("iptables")
    }

    fn apply_block() {
        for bin in IPT_BINS {
            if rule_present(bin) {
                continue;
            }
            // Insert at the top of OUTPUT so our DROP wins over any later ACCEPT.
            let mut args = vec!["-I", "OUTPUT", "1"];
            args.extend_from_slice(&RULE[1..]);
            if !run_ipt(bin, &args) {
                warn!("block rkp: {bin} insert DROP failed");
            }
        }
    }

    fn remove_block() {
        for bin in IPT_BINS {
            // Pull every copy we may have inserted (bounded, in case -D keeps succeeding).
            let mut args = vec!["-D"];
            args.extend_from_slice(&RULE);
            let mut guard = 0;
            while rule_present(bin) && guard < 16 {
                if !run_ipt(bin, &args) {
                    break;
                }
                guard += 1;
            }
        }
    }

    fn dumpsys_activities() -> String {
        Command::new("dumpsys")
            .args(["activity", "activities"])
            .stdin(Stdio::null())
            .stderr(Stdio::null())
            .output()
            .map(|o| String::from_utf8_lossy(&o.stdout).into_owned())
            .unwrap_or_default()
    }

    fn foreground_package() -> Option<String> {
        parse_foreground_package(&dumpsys_activities())
    }

    fn try_lock(file: &File) -> bool {
        unsafe { libc::flock(file.as_raw_fd(), libc::LOCK_EX | libc::LOCK_NB) == 0 }
    }

    /// Long-running watcher: while the feature is on, keep the DROP rule in sync with
    /// whether Duck Detector is the foreground app. Exits when the flag is cleared.
    pub fn watch() -> Result<()> {
        let lock = OpenOptions::new()
            .read(true)
            .write(true)
            .create(true)
            .truncate(false)
            .mode(0o600)
            .open(defs::BLOCK_RKP_LOCK)
            .with_context(|| format!("open {}", defs::BLOCK_RKP_LOCK))?;
        if !try_lock(&lock) {
            info!("block rkp: watcher already running, exiting");
            return Ok(());
        }

        info!("block rkp: watcher started");
        let mut blocking = false;
        loop {
            if !is_enabled() {
                if blocking {
                    remove_block();
                }
                info!("block rkp: flag cleared, watcher exiting");
                return Ok(());
            }

            let want_block = is_target_foreground(&dumpsys_activities());
            if want_block && !blocking {
                apply_block();
                blocking = true;
                info!("block rkp: Duck foreground, cut network");
            } else if !want_block && blocking {
                remove_block();
                blocking = false;
                info!("block rkp: Duck left, restored network");
            }

            thread::sleep(POLL_INTERVAL);
        }
    }

    /// Fork + exec a detached `camd block-rkp watch`. The flock in `watch` makes a second
    /// one exit immediately, so this is safe to call again (boot, re-enable).
    fn spawn_watcher() {
        match utils::create_daemon(true) {
            Ok(true) => {
                let mut command = Command::new("/proc/self/exe");
                command
                    .args(["block-rkp", "watch"])
                    .stdin(Stdio::null())
                    .stdout(Stdio::null())
                    .stderr(Stdio::null())
                    .current_dir("/");
                let err = command.exec();
                log::error!("block rkp: failed to exec watcher: {err:#}");
                unsafe { libc::_exit(1) };
            }
            Ok(false) => {}
            Err(e) => warn!("block rkp: spawn watcher failed: {e:#}"),
        }
    }

    /// Re-read the flag on boot and bring the watcher back if the feature is on.
    pub fn apply_if_enabled() {
        if is_enabled() {
            spawn_watcher();
        }
    }

    pub fn status() -> Result<()> {
        let fg = foreground_package();
        println!(
            "{}",
            serde_json::to_string_pretty(
                &status_json(is_enabled(), is_blocking(), fg.as_deref(),)
            )?
        );
        Ok(())
    }

    pub fn set_enabled(enabled: bool) -> Result<()> {
        let flag = Path::new(defs::BLOCK_RKP_FLAG);
        if enabled {
            std::fs::write(flag, b"")
                .with_context(|| format!("Failed to create {}", flag.display()))?;
            spawn_watcher();
        } else {
            if flag.exists() {
                std::fs::remove_file(flag)
                    .with_context(|| format!("Failed to remove {}", flag.display()))?;
            }
            // Drop any rule now instead of waiting for the watcher's next poll.
            remove_block();
        }
        Ok(())
    }
}

#[cfg(target_os = "android")]
pub use android::*;

#[cfg(test)]
mod tests {
    use super::*;

    const TOP: &str = "\
    ResumedActivity: ActivityRecord{abc u0 com.foo/.Bar}
      topResumedActivity=ActivityRecord{dead u0 com.eltavine.duckdetector/.MainActivity t42}";

    const OLD: &str =
        "    mResumedActivity=ActivityRecord{beef u0 com.eltavine.duckdetector/.MainActivity t7}";

    const OTHER: &str =
        "    topResumedActivity=ActivityRecord{1234 u0 com.android.launcher3/.Launcher t1}";

    const NONE: &str = "    topResumedActivity=null";

    #[test]
    fn parses_top_resumed_package() {
        assert_eq!(
            parse_foreground_package(TOP).as_deref(),
            Some("com.eltavine.duckdetector")
        );
    }

    #[test]
    fn falls_back_to_m_resumed() {
        assert_eq!(
            parse_foreground_package(OLD).as_deref(),
            Some("com.eltavine.duckdetector")
        );
    }

    #[test]
    fn null_resumed_is_none() {
        assert_eq!(parse_foreground_package(NONE), None);
    }

    #[test]
    fn target_foreground_detection() {
        assert!(is_target_foreground(TOP));
        assert!(is_target_foreground(OLD));
        assert!(!is_target_foreground(OTHER));
        assert!(!is_target_foreground(NONE));
    }

    #[test]
    fn status_shape() {
        let v = status_json(true, true, Some("com.eltavine.duckdetector"));
        assert_eq!(
            v,
            serde_json::json!({
                "enabled": true,
                "blocking": true,
                "foreground": "com.eltavine.duckdetector",
                "targets": ["com.eltavine.duckdetector"],
            })
        );
    }
}

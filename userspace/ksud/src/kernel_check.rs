//! Kernel check: the first boot after `flash-ak3` checks that the device runs
//! the kernel that was flashed, with KernelSU built in and SUSFS when the zip
//! is a project build.
//!
//! `flash-ak3` writes what it expects to `ak3_pending.json` together with the
//! boot id of the boot it ran in. At boot-completed of a later boot the
//! running kernel is compared with it, the outcome is kept in
//! `ak3_check.json` for the Manager and the pending file is dropped.
#![cfg_attr(not(target_os = "android"), allow(dead_code))]

use std::io::Write;
use std::path::Path;

use anyhow::{Context, Result};
use serde_json::{Value, json};

/// What the flashed zip should bring.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct Expected {
    /// `uname -r` of the new kernel; None when it could not be read from the zip
    pub release: Option<String>,
    /// a project build: KernelSU is built in, not loaded as an LKM
    pub built_in: bool,
    /// SUSFS version of a project build
    pub susfs: Option<String>,
    pub ksu_version: Option<i64>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Pending {
    /// `/proc/sys/kernel/random/boot_id` of the boot that flashed: that boot
    /// still runs the old kernel, so it is never checked
    pub boot_id: String,
    pub flashed_at: u64,
    pub zip: String,
    /// the boot backup made before flashing, to restore from if the check fails
    pub backup: Option<String>,
    pub expected: Expected,
}

#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct Running {
    pub release: String,
    pub built_in: bool,
    pub susfs: Option<String>,
    pub ksu_version: i64,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Problem {
    /// another kernel is running: the device fell back to the old one
    Release,
    /// KernelSU runs as an LKM although the build has it built in
    NotBuiltIn,
    /// the build has SUSFS but the running kernel does not
    NoSusfs,
}

impl Problem {
    pub const fn as_str(self) -> &'static str {
        match self {
            Self::Release => "release",
            Self::NotBuiltIn => "lkm",
            Self::NoSusfs => "susfs",
        }
    }
}

pub fn evaluate(expected: &Expected, running: &Running) -> Vec<Problem> {
    let mut problems = Vec::new();
    if expected
        .release
        .as_deref()
        .is_some_and(|release| release != running.release)
    {
        problems.push(Problem::Release);
    }
    if expected.built_in && !running.built_in {
        problems.push(Problem::NotBuiltIn);
    }
    if expected.susfs.is_some() && running.susfs.is_none() {
        problems.push(Problem::NoSusfs);
    }
    problems
}

/// The kernel release in an uncompressed arm64 `Image`, from its
/// `Linux version <release> (<builder>) ...` banner.
pub fn banner_release(image: &[u8]) -> Option<String> {
    const MARKER: &[u8] = b"Linux version ";
    let mut from = 0;
    while let Some(pos) = image[from..]
        .windows(MARKER.len())
        .position(|w| w == MARKER)
    {
        let start = from + pos + MARKER.len();
        let rest = &image[start..image.len().min(start + 128)];
        if let Some(end) = rest.iter().position(|&b| b == b' ') {
            let release = &rest[..end];
            let valid = !release.is_empty()
                && rest[end..].starts_with(b" (")
                && release.iter().all(|b| b.is_ascii_graphic() && *b != b'%');
            if valid {
                return std::str::from_utf8(release).ok().map(str::to_owned);
            }
        }
        from = start;
    }
    None
}

/// `sfs.json`, which the project's builds carry next to the Image.
pub fn expected_from_sfs_json(text: &str) -> Option<Expected> {
    let value: Value = serde_json::from_str(text).ok()?;
    Some(Expected {
        release: value["release"]
            .as_str()
            .filter(|s| !s.is_empty())
            .map(str::to_owned),
        built_in: true,
        susfs: value["susfsVersion"]
            .as_str()
            .filter(|s| !s.is_empty())
            .map(str::to_owned),
        ksu_version: value["ksuVersion"].as_i64(),
    })
}

fn expected_json(expected: &Expected) -> Value {
    json!({
        "release": expected.release,
        "builtIn": expected.built_in,
        "susfs": expected.susfs,
        "ksuVersion": expected.ksu_version,
    })
}

fn expected_of(value: &Value) -> Expected {
    Expected {
        release: value["release"].as_str().map(str::to_owned),
        built_in: value["builtIn"].as_bool().unwrap_or(false),
        susfs: value["susfs"].as_str().map(str::to_owned),
        ksu_version: value["ksuVersion"].as_i64(),
    }
}

pub fn pending_json(pending: &Pending) -> Value {
    json!({
        "bootId": pending.boot_id,
        "flashedAt": pending.flashed_at,
        "zip": pending.zip,
        "backup": pending.backup,
        "expected": expected_json(&pending.expected),
    })
}

pub fn parse_pending(text: &str) -> Option<Pending> {
    let value: Value = serde_json::from_str(text).ok()?;
    Some(Pending {
        boot_id: value["bootId"].as_str()?.to_owned(),
        flashed_at: value["flashedAt"].as_u64().unwrap_or(0),
        zip: value["zip"].as_str().unwrap_or_default().to_owned(),
        backup: value["backup"].as_str().map(str::to_owned),
        expected: expected_of(&value["expected"]),
    })
}

/// The check outcome kept for the Manager.
pub fn result_json(pending: &Pending, running: &Running, problems: &[Problem], now: u64) -> Value {
    json!({
        "ok": problems.is_empty(),
        "checkedAt": now,
        "flashedAt": pending.flashed_at,
        "zip": pending.zip,
        "backup": pending.backup,
        "expected": expected_json(&pending.expected),
        "running": {
            "release": running.release,
            "builtIn": running.built_in,
            "susfs": running.susfs,
            "ksuVersion": running.ksu_version,
        },
        "problems": problems.iter().map(|p| p.as_str()).collect::<Vec<_>>(),
    })
}

/// Notification title and body; ksud has no resources, so the two languages live here.
pub fn notice_text(release: &str, problems: &[Problem], vietnamese: bool) -> (String, String) {
    if problems.is_empty() {
        return if vietnamese {
            (
                "SU Kernel: kernel mới chạy tốt".to_owned(),
                format!("Đang chạy {release} như đã flash."),
            )
        } else {
            (
                "SU Kernel: new kernel is running".to_owned(),
                format!("Running {release} as flashed."),
            )
        };
    }
    let reasons: Vec<&str> = problems
        .iter()
        .map(|p| match (p, vietnamese) {
            (Problem::Release, true) => "máy không chạy kernel vừa flash",
            (Problem::Release, false) => "the flashed kernel is not running",
            (Problem::NotBuiltIn, true) => "KernelSU không tích hợp trong kernel",
            (Problem::NotBuiltIn, false) => "KernelSU is not built into the kernel",
            (Problem::NoSusfs, true) => "thiếu SUSFS",
            (Problem::NoSusfs, false) => "SUSFS is missing",
        })
        .collect();
    if vietnamese {
        (
            "SU Kernel: kernel mới có vấn đề".to_owned(),
            format!(
                "{}. Đang chạy {release}. Mở trang Cài GKI để khôi phục kernel cũ.",
                reasons.join(", ")
            ),
        )
    } else {
        (
            "SU Kernel: problem with the new kernel".to_owned(),
            format!(
                "{}. Running {release}. Open the GKI install page to restore the previous kernel.",
                reasons.join(", ")
            ),
        )
    }
}

/// Write through a synced temp file and rename, like the boot guard state.
pub fn write_json(path: &Path, value: &Value) -> Result<()> {
    let tmp = path.with_extension("json.tmp");
    let mut file = std::fs::File::create(&tmp)
        .with_context(|| format!("Failed to create {}", tmp.display()))?;
    file.write_all(value.to_string().as_bytes())?;
    file.sync_all()?;
    drop(file);
    std::fs::rename(&tmp, path)
        .with_context(|| format!("Failed to rename {} to {}", tmp.display(), path.display()))?;
    Ok(())
}

#[cfg(target_os = "android")]
mod device {
    use std::path::Path;
    use std::time::{SystemTime, UNIX_EPOCH};

    use anyhow::Result;
    use log::{info, warn};
    use serde_json::Value;

    use super::{Expected, Pending, Problem, Running};
    use crate::defs;

    pub fn now() -> u64 {
        SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .map_or(0, |d| d.as_secs())
    }

    pub fn boot_id() -> String {
        std::fs::read_to_string("/proc/sys/kernel/random/boot_id")
            .map(|s| s.trim().to_owned())
            .unwrap_or_default()
    }

    /// Called by flash-ak3 once the zip is flashed.
    pub fn record_flash(zip: &str, backup: Option<&Path>, expected: Expected) -> Result<()> {
        let pending = Pending {
            boot_id: boot_id(),
            flashed_at: now(),
            zip: zip.to_owned(),
            backup: backup.map(|p| p.to_string_lossy().into_owned()),
            expected,
        };
        // an older outcome is about another kernel now
        let _ = std::fs::remove_file(defs::KERNEL_CHECK_PATH);
        super::write_json(
            Path::new(defs::KERNEL_CHECK_PENDING_PATH),
            &super::pending_json(&pending),
        )
    }

    /// A restored backup is not the kernel the pending check is waiting for.
    pub fn clear_pending() {
        let _ = std::fs::remove_file(defs::KERNEL_CHECK_PENDING_PATH);
    }

    pub fn running() -> Running {
        Running {
            release: rustix::system::uname()
                .release()
                .to_string_lossy()
                .into_owned(),
            built_in: !crate::ksucalls::is_lkm(),
            susfs: crate::susfs::get_info().ok().map(|info| info.version),
            ksu_version: i64::from(crate::ksucalls::get_version()),
        }
    }

    /// Runs the pending check, if any; returns what to tell the user.
    pub fn on_boot_completed() -> Option<(String, Vec<Problem>)> {
        let text = std::fs::read_to_string(defs::KERNEL_CHECK_PENDING_PATH).ok()?;
        let Some(pending) = super::parse_pending(&text) else {
            warn!("kernel check: unreadable pending file, dropping it");
            clear_pending();
            return None;
        };
        if pending.boot_id == boot_id() {
            // a userspace restart (soft reboot) in the boot that flashed
            return None;
        }
        let running = running();
        let problems = super::evaluate(&pending.expected, &running);
        info!(
            "kernel check: running {} (expected {:?}), problems {:?}",
            running.release, pending.expected.release, problems
        );
        let result = super::result_json(&pending, &running, &problems, now());
        if let Err(e) = super::write_json(Path::new(defs::KERNEL_CHECK_PATH), &result) {
            warn!("kernel check: save result failed: {e}");
        }
        clear_pending();
        Some((running.release, problems))
    }

    /// `{"pending": bool, "result": <outcome or null>}` for the Manager.
    pub fn print_status() {
        let pending = Path::new(defs::KERNEL_CHECK_PENDING_PATH).exists();
        let result = std::fs::read_to_string(defs::KERNEL_CHECK_PATH)
            .ok()
            .and_then(|text| serde_json::from_str::<Value>(&text).ok())
            .unwrap_or(Value::Null);
        println!(
            "{}",
            serde_json::json!({ "pending": pending, "result": result })
        );
    }

    pub fn clear() -> Result<()> {
        match std::fs::remove_file(defs::KERNEL_CHECK_PATH) {
            Err(e) if e.kind() != std::io::ErrorKind::NotFound => Err(e.into()),
            _ => Ok(()),
        }
    }
}

#[cfg(target_os = "android")]
pub use device::*;

#[cfg(test)]
mod tests {
    use super::*;

    fn sfs_expected() -> Expected {
        Expected {
            release: Some("6.12.30-android16-5-g1".to_owned()),
            built_in: true,
            susfs: Some("v2.0.0".to_owned()),
            ksu_version: Some(32800),
        }
    }

    fn running(release: &str, built_in: bool, susfs: Option<&str>) -> Running {
        Running {
            release: release.to_owned(),
            built_in,
            susfs: susfs.map(str::to_owned),
            ksu_version: 32800,
        }
    }

    #[test]
    fn matching_kernel_has_no_problems() {
        let r = running("6.12.30-android16-5-g1", true, Some("v2.0.0"));
        assert!(evaluate(&sfs_expected(), &r).is_empty());
    }

    #[test]
    fn old_kernel_after_fallback() {
        let r = running("6.12.23-android16-5-gstock", false, None);
        assert_eq!(
            evaluate(&sfs_expected(), &r),
            vec![Problem::Release, Problem::NotBuiltIn, Problem::NoSusfs]
        );
    }

    #[test]
    fn unknown_zip_only_checks_what_it_knows() {
        let r = running("6.1.99-whatever", false, None);
        assert!(evaluate(&Expected::default(), &r).is_empty());
        let with_release = Expected {
            release: Some("6.1.100".to_owned()),
            ..Expected::default()
        };
        assert_eq!(evaluate(&with_release, &r), vec![Problem::Release]);
    }

    #[test]
    fn reads_release_from_banner() {
        let mut image = b"\0\0Linux version %s (%s)\0junk".to_vec();
        image.extend_from_slice(
            b"Linux version 6.12.30-android16-5-g1750f757fabe-ab13938768-4k (kleaf@build-host) (Android clang)\0",
        );
        assert_eq!(
            banner_release(&image).as_deref(),
            Some("6.12.30-android16-5-g1750f757fabe-ab13938768-4k")
        );
        assert_eq!(banner_release(b"no banner here"), None);
        assert_eq!(banner_release(b"Linux version "), None);
    }

    #[test]
    fn reads_sfs_json() {
        let e = expected_from_sfs_json(
            r#"{"kmi":"android16-6.12","release":"6.12.30-android16-5-g1","ksuVersion":32800,"susfsVersion":"v2.0.0"}"#,
        )
        .unwrap();
        assert_eq!(e, sfs_expected());
        assert!(expected_from_sfs_json("not json").is_none());
    }

    #[test]
    fn pending_round_trips() {
        let pending = Pending {
            boot_id: "abc".to_owned(),
            flashed_at: 7,
            zip: "KernelSU-SFS.zip".to_owned(),
            backup: Some("/data/adb/ksu/ak3_backup/boot_a.img".to_owned()),
            expected: sfs_expected(),
        };
        let text = pending_json(&pending).to_string();
        assert_eq!(parse_pending(&text), Some(pending));
        assert_eq!(parse_pending("{}"), None);
    }

    #[test]
    fn result_lists_problem_codes() {
        let pending = parse_pending(r#"{"bootId":"x","expected":{"builtIn":true}}"#).unwrap();
        let r = running("6.1.1", false, None);
        let problems = evaluate(&pending.expected, &r);
        let v = result_json(&pending, &r, &problems, 9);
        assert_eq!(v["ok"], false);
        assert_eq!(v["problems"], json!(["lkm"]));
        assert_eq!(v["running"]["release"], "6.1.1");
    }

    #[test]
    fn notice_mentions_each_problem() {
        let (_, body) = notice_text("6.1.1", &[Problem::Release, Problem::NoSusfs], false);
        assert!(body.contains("not running") && body.contains("SUSFS") && body.contains("6.1.1"));
        let (title, _) = notice_text("6.1.1", &[], true);
        assert!(title.contains("tốt"));
    }
}

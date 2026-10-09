//! Root hiding audit: what a non-root app could still see, and what ksud can turn on to hide it.
//!
//! [`audit`] works on a [`Snapshot`] of the device (props, mounts, what app processes
//! have mapped, enabled modules, KernelSU features) so it can be tested off device.
//! Each [`Finding`] may carry a [`Fix`] ksud applies itself: KernelSU's kernel umount
//! and SELinux hide features, or ksud's hide bootloader. Everything else is reported only.
#![cfg_attr(not(target_os = "android"), allow(dead_code))]

use serde_json::{Value, json};

use crate::hide_bootloader;

/// Mapped files under these prefixes come from root tooling, never from the stock system.
const ROOT_PREFIXES: [&str; 2] = ["/data/adb/", "/debug_ramdisk/"];

/// su and busybox outside KernelSU's own `/system/bin/su`, which sucompat answers for
/// allowed apps only (it would always look present to ksud).
pub const SU_PATHS: [&str; 8] = [
    "/system/xbin/su",
    "/system/sbin/su",
    "/sbin/su",
    "/vendor/bin/su",
    "/su/bin/su",
    "/system/xbin/busybox",
    "/system/bin/busybox",
    "/data/local/tmp/busybox",
];

/// Folders recovery apps leave on shared storage.
pub const RECOVERY_PATHS: [&str; 3] = ["/sdcard/TWRP", "/sdcard/Fox", "/sdcard/OrangeFox"];

#[derive(Debug, Clone, Default)]
pub struct Snapshot {
    pub props: Vec<(String, String)>,
    pub hide_bootloader: bool,
    /// `androidboot.*` from /proc/bootconfig and /proc/cmdline
    pub boot_args: Vec<(String, String)>,
    /// /proc/1/mountinfo
    pub mountinfo: String,
    /// KernelSU umounts module mounts for non-root apps; None when unknown
    pub kernel_umount: Option<bool>,
    /// KernelSU shows apps the stock sepolicy; None when the kernel lacks it
    pub selinux_hide: Option<bool>,
    /// distinct paths app processes have mapped
    pub app_maps: Vec<String>,
    pub modules: Vec<String>,
    /// those of [`SU_PATHS`] and [`RECOVERY_PATHS`] that exist
    pub existing: Vec<String>,
    pub selinux_enforcing: Option<bool>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Fix {
    KernelUmount,
    /// takes effect after a reboot: the stock policy is backed up while booting
    SelinuxHide,
    HideBootloader,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Level {
    /// an app can see it right now
    Leak,
    /// worth a look, but not a leak by itself
    Warn,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Finding {
    pub id: &'static str,
    pub level: Level,
    pub items: Vec<String>,
    pub fix: Option<Fix>,
}

struct Mount<'a> {
    root: &'a str,
    point: &'a str,
    source: &'a str,
    options: &'a str,
}

fn mounts(mountinfo: &str) -> impl Iterator<Item = Mount<'_>> {
    mountinfo.lines().filter_map(|line| {
        let (head, tail) = line.split_once(" - ")?;
        let fields: Vec<&str> = head.split_whitespace().collect();
        let mut tail = tail.split_whitespace();
        let _fstype = tail.next()?;
        Some(Mount {
            root: fields.get(3)?,
            point: fields.get(4)?,
            source: tail.next().unwrap_or_default(),
            options: tail.next().unwrap_or_default(),
        })
    })
}

/// Mounts a module made itself (a bind from /data/adb, an overlay over module dirs):
/// KernelSU only umounts what it mounted, so apps keep seeing these.
fn module_mounts(mountinfo: &str) -> Vec<String> {
    let mut out: Vec<String> = Vec::new();
    for m in mounts(mountinfo) {
        if m.source == "KSU" {
            continue;
        }
        let from_adb = m.root.starts_with("/adb/") || m.options.contains("/data/adb/");
        if from_adb && !out.iter().any(|p| p == m.point) {
            out.push(m.point.to_owned());
        }
    }
    out
}

fn ksu_mount_count(mountinfo: &str) -> usize {
    mounts(mountinfo).filter(|m| m.source == "KSU").count()
}

/// `/data/adb/modules/x/lib.so (deleted)` -> `/data/adb/modules/x/lib.so`
pub fn mapped_path(line: &str) -> Option<&str> {
    // address perms offset dev inode path
    let path = line.split_whitespace().nth(5)?;
    let start = line.find(path)?;
    let path = line[start..].trim_end();
    let path = path.strip_suffix(" (deleted)").unwrap_or(path);
    ROOT_PREFIXES
        .iter()
        .any(|prefix| path.starts_with(prefix))
        .then_some(path)
}

fn is_lsposed(id: &str) -> bool {
    id.to_ascii_lowercase().contains("lsposed")
}

fn is_revanced(id: &str) -> bool {
    let id = id.to_ascii_lowercase();
    id.contains("revanced") || id.starts_with("rvx") || id.contains("_rvx")
}

fn prop<'a>(props: &'a [(String, String)], name: &str) -> Option<&'a str> {
    props
        .iter()
        .find(|(n, _)| n == name)
        .map(|(_, v)| v.as_str())
}

pub fn audit(s: &Snapshot) -> Vec<Finding> {
    let mut out = Vec::new();
    let mut push = |id, level, items: Vec<String>, fix: Option<Fix>| {
        out.push(Finding {
            id,
            level,
            items,
            fix,
        });
    };

    let own_mounts = module_mounts(&s.mountinfo);
    if !own_mounts.is_empty() {
        push("moduleMounts", Level::Leak, own_mounts, None);
    }

    let ksu_mounts = ksu_mount_count(&s.mountinfo);
    if ksu_mounts > 0 && s.kernel_umount == Some(false) {
        push(
            "ksuMounts",
            Level::Leak,
            vec![ksu_mounts.to_string()],
            Some(Fix::KernelUmount),
        );
    }

    if s.selinux_hide == Some(false) {
        push(
            "selinuxRules",
            Level::Leak,
            Vec::new(),
            Some(Fix::SelinuxHide),
        );
    }

    if !s.app_maps.is_empty() {
        push("maps", Level::Leak, s.app_maps.clone(), None);
    }

    if !s.hide_bootloader {
        let props: Vec<String> = hide_bootloader::plan(&s.props)
            .into_iter()
            .map(|(name, _)| format!("{name}={}", prop(&s.props, &name).unwrap_or_default()))
            .collect();
        if !props.is_empty() {
            push("props", Level::Leak, props, Some(Fix::HideBootloader));
        }
    }

    let boot_args: Vec<String> = s
        .boot_args
        .iter()
        .filter(|(name, value)| hide_bootloader::safe_value(name).is_some_and(|safe| safe != value))
        .map(|(name, value)| format!("{name}={value}"))
        .collect();
    if !boot_args.is_empty() {
        push("bootArgs", Level::Leak, boot_args, None);
    }

    let lsposed: Vec<String> = s
        .modules
        .iter()
        .filter(|id| is_lsposed(id))
        .cloned()
        .collect();
    if !lsposed.is_empty() {
        push("lsposed", Level::Warn, lsposed, None);
    }

    let revanced: Vec<String> = s
        .modules
        .iter()
        .filter(|id| is_revanced(id))
        .cloned()
        .collect();
    if !revanced.is_empty() {
        push("revanced", Level::Warn, revanced, None);
    }

    let custom_rom: Vec<String> = ["ro.lineage.version", "ro.crdroid.version", "ro.modversion"]
        .iter()
        .filter_map(|name| prop(&s.props, name).map(|v| format!("{name}={v}")))
        .collect();
    if !custom_rom.is_empty() {
        push("customRom", Level::Warn, custom_rom, None);
    }

    if !s.existing.is_empty() {
        push("files", Level::Leak, s.existing.clone(), None);
    }

    if s.selinux_enforcing == Some(false) {
        push("selinux", Level::Leak, Vec::new(), None);
    }

    let adb = ["sys.usb.config", "persist.sys.usb.config"]
        .iter()
        .any(|name| prop(&s.props, name).is_some_and(|v| v.split(',').any(|f| f == "adb")));
    if adb {
        push("adb", Level::Warn, Vec::new(), None);
    }

    out
}

const fn level_name(level: Level) -> &'static str {
    match level {
        Level::Leak => "leak",
        Level::Warn => "warn",
    }
}

const fn fix_name(fix: Fix) -> &'static str {
    match fix {
        Fix::KernelUmount => "kernelUmount",
        Fix::SelinuxHide => "selinuxHide",
        Fix::HideBootloader => "hideBootloader",
    }
}

pub fn report_json(findings: &[Finding]) -> Value {
    let list: Vec<Value> = findings
        .iter()
        .map(|f| {
            json!({
                "id": f.id,
                "level": level_name(f.level),
                "items": f.items,
                "fix": f.fix.map(fix_name),
            })
        })
        .collect();
    json!({
        "fixable": findings.iter().filter(|f| f.fix.is_some()).count(),
        "findings": list,
    })
}

/// The distinct fixes of `findings`, in the order they were found.
pub fn fixes(findings: &[Finding]) -> Vec<Fix> {
    let mut out = Vec::new();
    for fix in findings.iter().filter_map(|f| f.fix) {
        if !out.contains(&fix) {
            out.push(fix);
        }
    }
    out
}

#[cfg(target_os = "android")]
mod device {
    use std::collections::BTreeSet;
    use std::path::Path;

    use anyhow::Result;
    use serde_json::json;

    use super::{Fix, RECOVERY_PATHS, SU_PATHS, Snapshot};
    use crate::feature::FeatureId;
    use crate::{hide_bootloader, ksucalls, resetprop};

    /// Android app uids (per user), isolated processes included.
    fn is_app_uid(uid: u32) -> bool {
        let app_id = uid % 100_000;
        (10_000..20_000).contains(&app_id) || (90_000..100_000).contains(&app_id)
    }

    fn uid_of(pid: &Path) -> Option<u32> {
        let status = std::fs::read_to_string(pid.join("status")).ok()?;
        status
            .lines()
            .find_map(|line| line.strip_prefix("Uid:"))?
            .split_whitespace()
            .next()?
            .parse()
            .ok()
    }

    /// What app processes have mapped from root tooling, across every running app.
    fn app_maps() -> Vec<String> {
        let mut found = BTreeSet::new();
        let Ok(procs) = std::fs::read_dir("/proc") else {
            return Vec::new();
        };
        for entry in procs.flatten() {
            let pid = entry.path();
            let is_pid = pid
                .file_name()
                .and_then(|n| n.to_str())
                .is_some_and(|n| n.bytes().all(|b| b.is_ascii_digit()));
            if !is_pid || !uid_of(&pid).is_some_and(is_app_uid) {
                continue;
            }
            let Ok(maps) = std::fs::read_to_string(pid.join("maps")) else {
                continue;
            };
            for line in maps.lines() {
                if let Some(path) = super::mapped_path(line) {
                    found.insert(path.to_owned());
                }
            }
        }
        found.into_iter().collect()
    }

    /// `/sdcard/x` is checked at `/data/media/0/x`: ksud's mount namespace may not have
    /// the emulated storage mounted.
    fn exists(path: &str) -> bool {
        let real = path
            .strip_prefix("/sdcard/")
            .map_or_else(|| path.to_owned(), |rest| format!("/data/media/0/{rest}"));
        Path::new(&real).exists()
    }

    fn feature(id: FeatureId) -> Option<bool> {
        ksucalls::get_feature(id as u32)
            .ok()
            .filter(|(_, supported)| *supported)
            .map(|(value, _)| value != 0)
    }

    pub fn snapshot() -> Snapshot {
        let bootconfig = std::fs::read_to_string("/proc/bootconfig").unwrap_or_default();
        let cmdline = std::fs::read_to_string("/proc/cmdline").unwrap_or_default();
        Snapshot {
            props: resetprop::list_props().unwrap_or_default(),
            hide_bootloader: hide_bootloader::is_enabled(),
            boot_args: hide_bootloader::parse_boot_args(&bootconfig, &cmdline),
            mountinfo: std::fs::read_to_string("/proc/1/mountinfo").unwrap_or_default(),
            kernel_umount: feature(FeatureId::KernelUmount),
            selinux_hide: feature(FeatureId::SelinuxHide),
            app_maps: app_maps(),
            modules: crate::module::enabled_module_ids(),
            existing: SU_PATHS
                .iter()
                .chain(RECOVERY_PATHS.iter())
                .filter(|p| exists(p))
                .map(|p| (*p).to_owned())
                .collect(),
            selinux_enforcing: std::fs::read_to_string("/sys/fs/selinux/enforce")
                .ok()
                .map(|v| v.trim() != "0"),
        }
    }

    /// Turns a KernelSU feature on now and in the saved config, like the Manager's switch.
    fn enable_feature(id: FeatureId) -> Result<()> {
        ksucalls::set_feature(id as u32, 1)?;
        let mut saved = crate::feature::load_binary_config().unwrap_or_default();
        saved.insert(id as u32, 1);
        crate::feature::save_binary_config(&saved)
    }

    /// `ksud hiding-audit [--apply]`: the findings as JSON; with `apply`, every fix is
    /// turned on first and what was done is printed instead.
    pub fn run(apply: bool) -> Result<()> {
        let findings = super::audit(&snapshot());
        if !apply {
            println!("{}", super::report_json(&findings));
            return Ok(());
        }
        let mut applied = Vec::new();
        for fix in super::fixes(&findings) {
            match fix {
                Fix::KernelUmount => enable_feature(FeatureId::KernelUmount)?,
                Fix::SelinuxHide => enable_feature(FeatureId::SelinuxHide)?,
                Fix::HideBootloader => hide_bootloader::set_enabled(true)?,
            }
            applied.push(super::fix_name(fix));
        }
        println!(
            "{}",
            json!({
                "applied": applied,
                "rebootNeeded": applied.contains(&"selinuxHide"),
            })
        );
        Ok(())
    }
}

#[cfg(target_os = "android")]
pub use device::run;

#[cfg(test)]
mod tests {
    use super::*;

    fn props(list: &[(&str, &str)]) -> Vec<(String, String)> {
        list.iter()
            .map(|(n, v)| ((*n).to_owned(), (*v).to_owned()))
            .collect()
    }

    fn snapshot() -> Snapshot {
        Snapshot {
            kernel_umount: Some(true),
            selinux_hide: Some(true),
            selinux_enforcing: Some(true),
            ..Snapshot::default()
        }
    }

    const MOUNTINFO: &str = "\
20 1 254:6 / / ro,relatime shared:1 - erofs /dev/block/dm-6 ro
40 20 254:40 /adb/modules/hosts/system/etc/hosts /system/etc/hosts ro shared:1 - f2fs /dev/block/dm-40 rw
41 20 0:50 / /system/fonts ro shared:1 - overlay overlay ro,lowerdir=/data/adb/modules/font/system/fonts:/system/fonts
42 20 0:51 / /system/app ro shared:1 - overlay KSU ro,lowerdir=/data/adb/modules/x/system/app:/system/app
";

    #[test]
    fn clean_device_has_no_findings() {
        assert!(audit(&snapshot()).is_empty());
    }

    #[test]
    fn finds_mounts_modules_made_themselves() {
        let s = Snapshot {
            mountinfo: MOUNTINFO.to_owned(),
            ..snapshot()
        };
        let findings = audit(&s);
        assert_eq!(findings.len(), 1);
        assert_eq!(findings[0].id, "moduleMounts");
        assert_eq!(
            findings[0].items,
            vec!["/system/etc/hosts", "/system/fonts"]
        );
        assert_eq!(findings[0].fix, None);
    }

    #[test]
    fn ksu_mounts_leak_without_kernel_umount() {
        let s = Snapshot {
            mountinfo: MOUNTINFO.to_owned(),
            kernel_umount: Some(false),
            ..snapshot()
        };
        let findings = audit(&s);
        let ksu = findings.iter().find(|f| f.id == "ksuMounts").unwrap();
        assert_eq!(ksu.items, vec!["1"]);
        assert_eq!(ksu.fix, Some(Fix::KernelUmount));
    }

    #[test]
    fn selinux_hide_off_is_fixable_and_unsupported_is_skipped() {
        let off = Snapshot {
            selinux_hide: Some(false),
            ..snapshot()
        };
        let findings = audit(&off);
        assert_eq!(findings[0].id, "selinuxRules");
        assert_eq!(findings[0].fix, Some(Fix::SelinuxHide));
        let unsupported = Snapshot {
            selinux_hide: None,
            ..snapshot()
        };
        assert!(audit(&unsupported).is_empty());
    }

    #[test]
    fn reads_mapped_root_files() {
        assert_eq!(
            mapped_path("7f00-7f10 r-xp 00000000 fe:2a 123   /data/adb/modules/z/lib.so (deleted)"),
            Some("/data/adb/modules/z/lib.so")
        );
        assert_eq!(
            mapped_path("7f00-7f10 r-xp 00000000 fe:2a 123  /system/lib64/libc.so"),
            None
        );
        assert_eq!(mapped_path("7f00-7f10 rw-p 00000000 00:00 0"), None);
    }

    #[test]
    fn bootloader_props_suggest_hide_bootloader() {
        let s = Snapshot {
            props: props(&[
                ("ro.boot.verifiedbootstate", "orange"),
                ("ro.boot.flash.locked", "1"),
            ]),
            ..snapshot()
        };
        let findings = audit(&s);
        assert_eq!(findings[0].id, "props");
        assert_eq!(findings[0].items, vec!["ro.boot.verifiedbootstate=orange"]);
        assert_eq!(findings[0].fix, Some(Fix::HideBootloader));
        let hidden = Snapshot {
            hide_bootloader: true,
            ..s
        };
        assert!(audit(&hidden).is_empty());
    }

    #[test]
    fn module_warnings_follow_installed_modules() {
        let s = Snapshot {
            modules: vec![
                "zygisk_lsposed".to_owned(),
                "rvx-youtube".to_owned(),
                "other".to_owned(),
            ],
            ..snapshot()
        };
        let ids: Vec<&str> = audit(&s).iter().map(|f| f.id).collect();
        assert_eq!(ids, vec!["lsposed", "revanced"]);
    }

    #[test]
    fn fixes_are_listed_once() {
        let s = Snapshot {
            mountinfo: MOUNTINFO.to_owned(),
            kernel_umount: Some(false),
            selinux_hide: Some(false),
            props: props(&[("ro.debuggable", "1")]),
            ..snapshot()
        };
        assert_eq!(
            fixes(&audit(&s)),
            vec![Fix::KernelUmount, Fix::SelinuxHide, Fix::HideBootloader]
        );
    }

    #[test]
    fn selinux_and_adb_are_reported_without_fix() {
        let s = Snapshot {
            selinux_enforcing: Some(false),
            props: props(&[("sys.usb.config", "mtp,adb")]),
            ..snapshot()
        };
        let findings = audit(&s);
        assert_eq!(findings.len(), 2);
        assert!(findings.iter().all(|f| f.fix.is_none()));
    }

    #[test]
    fn report_counts_fixable_findings() {
        let s = Snapshot {
            selinux_enforcing: Some(false),
            selinux_hide: Some(false),
            ..snapshot()
        };
        let v = report_json(&audit(&s));
        assert_eq!(v["fixable"], 1);
        assert_eq!(v["findings"][0]["fix"], "selinuxHide");
    }
}

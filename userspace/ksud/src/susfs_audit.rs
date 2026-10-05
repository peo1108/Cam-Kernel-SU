//! Root hiding audit: what a non-root app could still see, and the settings that hide it.
//!
//! [`audit`] works on a [`Snapshot`] of the device (props, mounts, what app processes
//! have mapped, enabled modules, the SUSFS settings) so it can be tested off device.
//! Each [`Finding`] may carry [`Fix`]es: SUSFS settings derived from what is installed
//! (the mount points a module bind mounts itself, the libraries it maps into apps, the
//! LSPosed / ReVanced options when those modules are on) or ksud's hide bootloader.
//! [`apply_fixes`] merges them into the SUSFS settings.
#![cfg_attr(not(target_os = "android"), allow(dead_code))]

use serde_json::{Value, json};

use crate::hide_bootloader;
use crate::susfs_config::{BootconfigMode, HideMnts, SusPathEntry, SusfsConfig};

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

/// Mount ids SUSFS hands out to its own mounts (`DEFAULT_KSU_MNT_ID` and up).
const SUS_MOUNT_ID: u64 = 2_000_000_000;

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
    pub config: SusfsConfig,
    /// SUSFS is in the kernel and ksud applies its settings (no susfs4ksu module)
    pub susfs: bool,
    /// distinct paths app processes have mapped
    pub app_maps: Vec<String>,
    pub modules: Vec<String>,
    /// those of [`SU_PATHS`] and [`RECOVERY_PATHS`] that exist
    pub existing: Vec<String>,
    pub selinux_enforcing: Option<bool>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Fix {
    TryUmount(Vec<String>),
    AutoTryUmount,
    HideSusMounts,
    SusMap(Vec<String>),
    SusPath(Vec<String>),
    FakeBootconfig,
    ForceHideLsposed,
    HideRevanced,
    HideCusrom,
    /// ksud's hide bootloader, which works without SUSFS
    HideBootloader,
}

impl Fix {
    const fn needs_susfs(&self) -> bool {
        !matches!(self, Self::HideBootloader)
    }
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
    id: u64,
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
            id: fields.first()?.parse().ok()?,
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
        if m.source == "KSU" || m.id >= SUS_MOUNT_ID {
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
    let c = &s.config;
    let mut out = Vec::new();
    let mut push = |id, level, items: Vec<String>, fix: Option<Fix>| {
        let fix = fix.filter(|f| s.susfs || !f.needs_susfs());
        out.push(Finding {
            id,
            level,
            items,
            fix,
        });
    };

    let own_mounts: Vec<String> = module_mounts(&s.mountinfo)
        .into_iter()
        .filter(|p| !c.try_umounts.contains(p))
        .collect();
    if !own_mounts.is_empty() {
        push(
            "moduleMounts",
            Level::Leak,
            own_mounts.clone(),
            Some(Fix::TryUmount(own_mounts)),
        );
    }

    let ksu_mounts = ksu_mount_count(&s.mountinfo);
    if ksu_mounts > 0 && s.kernel_umount == Some(false) && !(s.susfs && c.auto_try_umount) {
        push(
            "ksuMounts",
            Level::Leak,
            vec![ksu_mounts.to_string()],
            Some(Fix::AutoTryUmount),
        );
    }

    if s.susfs && c.hide_sus_mnts == HideMnts::Off {
        push(
            "susMounts",
            Level::Warn,
            Vec::new(),
            Some(Fix::HideSusMounts),
        );
    }

    let maps: Vec<String> = s
        .app_maps
        .iter()
        .filter(|p| !c.sus_maps.contains(p))
        .cloned()
        .collect();
    if !maps.is_empty() {
        push("maps", Level::Leak, maps.clone(), Some(Fix::SusMap(maps)));
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
    let faked = s.susfs && c.bootconfig_mode != BootconfigMode::Off;
    if !boot_args.is_empty() && !faked {
        push(
            "bootArgs",
            Level::Leak,
            boot_args,
            Some(Fix::FakeBootconfig),
        );
    }

    let lsposed: Vec<String> = s
        .modules
        .iter()
        .filter(|id| is_lsposed(id))
        .cloned()
        .collect();
    if !lsposed.is_empty() && !c.force_hide_lsposed {
        push("lsposed", Level::Warn, lsposed, Some(Fix::ForceHideLsposed));
    }

    let revanced: Vec<String> = s
        .modules
        .iter()
        .filter(|id| is_revanced(id))
        .cloned()
        .collect();
    if !revanced.is_empty() && !c.hide_revanced {
        push("revanced", Level::Warn, revanced, Some(Fix::HideRevanced));
    }

    let custom_rom: Vec<String> = ["ro.lineage.version", "ro.crdroid.version", "ro.modversion"]
        .iter()
        .filter_map(|name| prop(&s.props, name).map(|v| format!("{name}={v}")))
        .collect();
    if !custom_rom.is_empty() && c.hide_cusrom == 0 {
        push("customRom", Level::Warn, custom_rom, Some(Fix::HideCusrom));
    }

    let hidden = |path: &String| c.sus_paths.iter().any(|e| &e.path == path);
    let files: Vec<String> = s.existing.iter().filter(|p| !hidden(p)).cloned().collect();
    if !files.is_empty() {
        push(
            "files",
            Level::Leak,
            files.clone(),
            Some(Fix::SusPath(files)),
        );
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

fn add_unique(list: &mut Vec<String>, items: &[String]) {
    for item in items {
        if !list.contains(item) {
            list.push(item.clone());
        }
    }
}

/// The settings with every SUSFS fix of `findings` merged in; also whether ksud's hide
/// bootloader should be turned on.
pub fn apply_fixes(config: &SusfsConfig, findings: &[Finding]) -> (SusfsConfig, bool) {
    let mut c = config.clone();
    let mut hide_bootloader = false;
    for fix in findings.iter().filter_map(|f| f.fix.as_ref()) {
        match fix {
            Fix::TryUmount(points) => add_unique(&mut c.try_umounts, points),
            Fix::AutoTryUmount => c.auto_try_umount = true,
            Fix::HideSusMounts => c.hide_sus_mnts = HideMnts::Always,
            Fix::SusMap(paths) => add_unique(&mut c.sus_maps, paths),
            Fix::SusPath(paths) => {
                for path in paths {
                    if !c.sus_paths.iter().any(|e| &e.path == path) {
                        c.sus_paths.push(SusPathEntry {
                            path: path.clone(),
                            wait: 0,
                        });
                    }
                }
            }
            Fix::FakeBootconfig => {
                if c.bootconfig_mode == BootconfigMode::Off {
                    c.bootconfig_mode = BootconfigMode::Auto;
                }
            }
            Fix::ForceHideLsposed => c.force_hide_lsposed = true,
            Fix::HideRevanced => c.hide_revanced = true,
            // the lowest level: keeps the ROM's apps, libs and init scripts working
            Fix::HideCusrom => c.hide_cusrom = c.hide_cusrom.max(1),
            Fix::HideBootloader => hide_bootloader = true,
        }
    }
    (c, hide_bootloader)
}

const fn level_name(level: Level) -> &'static str {
    match level {
        Level::Leak => "leak",
        Level::Warn => "warn",
    }
}

const fn fix_name(fix: &Fix) -> &'static str {
    match fix {
        Fix::TryUmount(_) => "tryUmount",
        Fix::AutoTryUmount => "autoTryUmount",
        Fix::HideSusMounts => "hideSusMounts",
        Fix::SusMap(_) => "susMap",
        Fix::SusPath(_) => "susPath",
        Fix::FakeBootconfig => "fakeBootconfig",
        Fix::ForceHideLsposed => "forceHideLsposed",
        Fix::HideRevanced => "hideRevanced",
        Fix::HideCusrom => "hideCusrom",
        Fix::HideBootloader => "hideBootloader",
    }
}

pub fn report_json(s: &Snapshot, findings: &[Finding]) -> Value {
    let list: Vec<Value> = findings
        .iter()
        .map(|f| {
            json!({
                "id": f.id,
                "level": level_name(f.level),
                "items": f.items,
                "fix": f.fix.as_ref().map(fix_name),
            })
        })
        .collect();
    json!({
        "susfs": s.susfs,
        "fixable": findings.iter().filter(|f| f.fix.is_some()).count(),
        "findings": list,
    })
}

#[cfg(target_os = "android")]
mod device {
    use std::collections::BTreeSet;
    use std::path::Path;

    use anyhow::Result;
    use serde_json::json;

    use super::{RECOVERY_PATHS, SU_PATHS, Snapshot};
    use crate::{hide_bootloader, ksucalls, resetprop, susfs};

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

    pub fn snapshot() -> Snapshot {
        let bootconfig = std::fs::read_to_string("/proc/bootconfig").unwrap_or_default();
        let cmdline = std::fs::read_to_string("/proc/cmdline").unwrap_or_default();
        Snapshot {
            props: resetprop::list_props().unwrap_or_default(),
            hide_bootloader: hide_bootloader::is_enabled(),
            boot_args: hide_bootloader::parse_boot_args(&bootconfig, &cmdline),
            mountinfo: std::fs::read_to_string("/proc/1/mountinfo").unwrap_or_default(),
            kernel_umount: ksucalls::get_feature(crate::feature::FeatureId::KernelUmount as u32)
                .ok()
                .filter(|(_, supported)| *supported)
                .map(|(value, _)| value != 0),
            config: susfs::load_config(),
            susfs: susfs::is_built_in() && !susfs::module_active(),
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

    /// `ksud susfs audit [--apply]`: the findings as JSON; with `apply`, every fix is
    /// merged into the SUSFS settings (applied like a save from the Manager) first.
    pub fn run(apply: bool) -> Result<()> {
        let snapshot = snapshot();
        let findings = super::audit(&snapshot);
        if !apply {
            println!("{}", super::report_json(&snapshot, &findings));
            return Ok(());
        }
        let (config, hide_bootloader) = super::apply_fixes(&snapshot.config, &findings);
        let susfs = if config == snapshot.config {
            serde_json::Value::Null
        } else {
            susfs::save_and_apply(&config)?
        };
        if hide_bootloader {
            hide_bootloader::set_enabled(true)?;
        }
        println!(
            "{}",
            json!({ "susfs": susfs, "hideBootloader": hide_bootloader })
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
            susfs: true,
            kernel_umount: Some(true),
            selinux_enforcing: Some(true),
            ..Snapshot::default()
        }
    }

    const MOUNTINFO: &str = "\
20 1 254:6 / / ro,relatime shared:1 - erofs /dev/block/dm-6 ro
40 20 254:40 /adb/modules/hosts/system/etc/hosts /system/etc/hosts ro shared:1 - f2fs /dev/block/dm-40 rw
41 20 0:50 / /system/fonts ro shared:1 - overlay overlay ro,lowerdir=/data/adb/modules/font/system/fonts:/system/fonts
42 20 0:51 / /system/app ro shared:1 - overlay KSU ro,lowerdir=/data/adb/modules/x/system/app:/system/app
2000000001 20 0:52 / /debug_ramdisk ro - tmpfs tmpfs rw
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
    }

    #[test]
    fn already_umounted_mounts_are_left_out() {
        let mut s = Snapshot {
            mountinfo: MOUNTINFO.to_owned(),
            ..snapshot()
        };
        s.config.try_umounts = vec!["/system/etc/hosts".to_owned(), "/system/fonts".to_owned()];
        assert!(audit(&s).is_empty());
    }

    #[test]
    fn ksu_mounts_leak_without_any_umount() {
        let s = Snapshot {
            mountinfo: MOUNTINFO.to_owned(),
            kernel_umount: Some(false),
            ..snapshot()
        };
        let findings = audit(&s);
        let ksu = findings.iter().find(|f| f.id == "ksuMounts").unwrap();
        assert_eq!(ksu.fix, Some(Fix::AutoTryUmount));
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
    fn without_susfs_only_hide_bootloader_is_offered() {
        let s = Snapshot {
            susfs: false,
            app_maps: vec!["/data/adb/modules/z/lib.so".to_owned()],
            props: props(&[("ro.debuggable", "1")]),
            ..snapshot()
        };
        let findings = audit(&s);
        let maps = findings.iter().find(|f| f.id == "maps").unwrap();
        assert_eq!(maps.fix, None);
        let props = findings.iter().find(|f| f.id == "props").unwrap();
        assert_eq!(props.fix, Some(Fix::HideBootloader));
    }

    #[test]
    fn module_options_follow_installed_modules() {
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
    fn fixes_merge_into_settings() {
        let s = Snapshot {
            mountinfo: MOUNTINFO.to_owned(),
            app_maps: vec!["/data/adb/modules/z/lib.so".to_owned()],
            existing: vec!["/system/xbin/su".to_owned()],
            boot_args: props(&[("androidboot.verifiedbootstate", "orange")]),
            modules: vec!["zygisk_lsposed".to_owned()],
            props: props(&[("ro.lineage.version", "22"), ("ro.debuggable", "1")]),
            ..snapshot()
        };
        let findings = audit(&s);
        let (c, hide_bootloader) = apply_fixes(&s.config, &findings);
        assert!(hide_bootloader);
        assert_eq!(c.try_umounts, vec!["/system/etc/hosts", "/system/fonts"]);
        assert_eq!(c.sus_maps, vec!["/data/adb/modules/z/lib.so"]);
        assert_eq!(c.sus_paths[0].path, "/system/xbin/su");
        assert_eq!(c.bootconfig_mode, BootconfigMode::Auto);
        assert!(c.force_hide_lsposed);
        assert_eq!(c.hide_cusrom, 1);
        // applied settings leave nothing for SUSFS to fix
        let after = Snapshot {
            config: c,
            hide_bootloader: true,
            ..s
        };
        assert!(audit(&after).iter().all(|f| f.fix.is_none()));
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
            existing: vec!["/sdcard/TWRP".to_owned()],
            ..snapshot()
        };
        let v = report_json(&s, &audit(&s));
        assert_eq!(v["fixable"], 1);
        assert_eq!(v["findings"][0]["fix"], "susPath");
    }
}

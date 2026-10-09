//! Root hiding audit: what a non-root app could still see, and what camd can turn on to hide it.
//!
//! [`audit`] works on a [`Snapshot`] of the device (props, mounts, what app processes
//! have mapped, enabled modules, KernelSU features) so it can be tested off device.
//! Each [`Finding`] may carry a [`Fix`] camd applies itself: KernelSU's kernel umount
//! and SELinux hide features, or camd's hide bootloader. Everything else is reported only.
//!
//! With `--uid`, the audit looks through one running app's own processes instead: the
//! mounts and libraries that app really sees. [`Rules`] can come from the Manager's
//! signed hiding rules (`--rules`), so what is looked for can change without a camd update.
#![cfg_attr(not(target_os = "android"), allow(dead_code))]

use serde_json::{Value, json};

use crate::hide_bootloader;

/// Mapped files under these prefixes come from root tooling, never from the stock system.
const ROOT_PREFIXES: [&str; 2] = ["/data/adb/", "/debug_ramdisk/"];

/// su and busybox outside KernelSU's own `/system/bin/su`, which sucompat answers for
/// allowed apps only (it would always look present to camd).
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

/// KernelSU's own su, answered by sucompat for allowed apps only: always there for camd.
const SUCOMPAT_SU: &str = "/system/bin/su";

/// Mount sources root tooling uses; a stock device never shows them to apps.
const MOUNT_SOURCES: [&str; 4] = ["KSU", "APatch", "magisk", "worker"];

/// Partitions apps expect stock: an overlay or a tmpfs over them comes from a module.
const PARTITIONS: [&str; 5] = ["/system", "/vendor", "/product", "/system_ext", "/odm"];

/// What the audit looks for: built in, or the Manager's signed hiding rules (`--rules`).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Rules {
    pub su_paths: Vec<String>,
    /// lower case; a mapped path holding one comes from root or hook tooling
    pub map_markers: Vec<String>,
    pub mount_sources: Vec<String>,
}

impl Default for Rules {
    fn default() -> Self {
        let owned = |list: &[&str]| list.iter().map(|s| (*s).to_owned()).collect();
        Self {
            su_paths: owned(&SU_PATHS),
            map_markers: owned(&ROOT_PREFIXES),
            mount_sources: owned(&MOUNT_SOURCES),
        }
    }
}

impl Rules {
    /// The Manager's hiding-rules.json; a list it lacks keeps the built-in one.
    pub fn parse(json: &str) -> anyhow::Result<Self> {
        let v: Value = serde_json::from_str(json)?;
        let list = |key: &str| -> Option<Vec<String>> {
            let items = v.get(key)?.as_array()?;
            Some(
                items
                    .iter()
                    .filter_map(|s| s.as_str().map(str::to_owned))
                    .collect(),
            )
        };
        let default = Self::default();
        Ok(Self {
            su_paths: list("suPaths").unwrap_or(default.su_paths),
            map_markers: list("mapMarkers").map_or(default.map_markers, |markers| {
                markers.iter().map(|m| m.to_ascii_lowercase()).collect()
            }),
            mount_sources: list("mountSources").unwrap_or(default.mount_sources),
        })
    }

    /// The su paths camd can judge: KernelSU's own su always answers it.
    pub fn su_paths_for_camd(&self) -> impl Iterator<Item = &str> {
        self.su_paths
            .iter()
            .map(String::as_str)
            .filter(|p| *p != SUCOMPAT_SU)
    }
}

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
    /// modules the items come from, when their paths name one
    pub modules: Vec<String>,
    /// seen from inside an app (`--uid`), not across the device
    pub app_view: bool,
}

struct Mount<'a> {
    root: &'a str,
    point: &'a str,
    fstype: &'a str,
    source: &'a str,
    options: &'a str,
}

fn mounts(mountinfo: &str) -> impl Iterator<Item = Mount<'_>> {
    mountinfo.lines().filter_map(|line| {
        let (head, tail) = line.split_once(" - ")?;
        let fields: Vec<&str> = head.split_whitespace().collect();
        let mut tail = tail.split_whitespace();
        let fstype = tail.next()?;
        Some(Mount {
            root: fields.get(3)?,
            point: fields.get(4)?,
            fstype,
            source: tail.next().unwrap_or_default(),
            options: tail.next().unwrap_or_default(),
        })
    })
}

/// Module ids the texts name, as bind roots (`/adb/modules/<id>/...`) and overlay
/// lowerdirs (`/data/adb/modules/<id>/...`) show them; each once, in order.
pub fn module_ids<'a>(texts: impl IntoIterator<Item = &'a str>) -> Vec<String> {
    const DIR: &str = "adb/modules/";
    let mut out: Vec<String> = Vec::new();
    for text in texts {
        let mut rest = text;
        while let Some(at) = rest.find(DIR) {
            rest = &rest[at + DIR.len()..];
            let id = rest.split(['/', ':', ',', ' ']).next().unwrap_or_default();
            if !id.is_empty() && !out.iter().any(|o| o == id) {
                out.push(id.to_owned());
            }
        }
    }
    out
}

fn from_adb(m: &Mount) -> bool {
    m.root.starts_with("/adb/") || m.options.contains("/data/adb/")
}

/// Mounts a module made itself (a bind from /data/adb, an overlay over module dirs):
/// KernelSU only umounts what it mounted, so apps keep seeing these.
fn module_mounts(mountinfo: &str) -> Vec<String> {
    let mut out: Vec<String> = Vec::new();
    for m in mounts(mountinfo) {
        if m.source != "KSU" && from_adb(&m) && !out.iter().any(|p| p == m.point) {
            out.push(m.point.to_owned());
        }
    }
    out
}

/// The modules behind [`module_mounts`], or behind KernelSU's own mounts with `ksu`.
fn mount_modules(mountinfo: &str, ksu: bool) -> Vec<String> {
    module_ids(
        mounts(mountinfo)
            .filter(|m| (m.source == "KSU") == ksu && (ksu || from_adb(m)))
            .flat_map(|m| [m.root, m.options]),
    )
}

fn ksu_mount_count(mountinfo: &str) -> usize {
    mounts(mountinfo).filter(|m| m.source == "KSU").count()
}

/// Mounts an app sees that come from root tooling: its mount sources, its folders, or
/// modules over a partition. Items read `point (fstype, source)`; the modules come along.
pub fn app_view_mounts(mountinfo: &str, rules: &Rules) -> (Vec<String>, Vec<String>) {
    let mut items: Vec<String> = Vec::new();
    let mut texts = Vec::new();
    for m in mounts(mountinfo) {
        let on_partition = PARTITIONS.iter().any(|p| {
            m.point == *p
                || m.point
                    .strip_prefix(p)
                    .is_some_and(|rest| rest.starts_with('/'))
        });
        let suspicious = rules.mount_sources.iter().any(|s| s == m.source)
            || from_adb(&m)
            || [m.root, m.point, m.options]
                .iter()
                .any(|t| t.contains("/data/adb") || t.contains("/debug_ramdisk"))
            || (on_partition && m.fstype == "overlay")
            || (on_partition && m.fstype == "tmpfs" && m.point != "/system");
        if !suspicious {
            continue;
        }
        let item = format!("{} ({}, {})", m.point, m.fstype, m.source);
        if !items.contains(&item) {
            items.push(item);
        }
        texts.extend([m.root, m.options]);
    }
    (items, module_ids(texts))
}

/// `/data/adb/modules/x/lib.so (deleted)` -> `/data/adb/modules/x/lib.so`, for a path
/// under root tooling's folders or holding one of `markers`.
pub fn mapped_path<'a>(line: &'a str, markers: &[String]) -> Option<&'a str> {
    // address perms offset dev inode path
    let path = line.split_whitespace().nth(5)?;
    let start = line.find(path)?;
    let path = line[start..].trim_end();
    let path = path.strip_suffix(" (deleted)").unwrap_or(path);
    let lower = path.to_ascii_lowercase();
    (ROOT_PREFIXES.iter().any(|prefix| path.starts_with(prefix))
        || markers.iter().any(|m| lower.contains(m.as_str())))
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
    let mut out: Vec<Finding> = Vec::new();
    let push = |out: &mut Vec<Finding>, id, level, items: Vec<String>, fix: Option<Fix>| {
        let modules = module_ids(items.iter().map(String::as_str));
        out.push(Finding {
            id,
            level,
            items,
            fix,
            modules,
            app_view: false,
        });
    };

    let own_mounts = module_mounts(&s.mountinfo);
    if !own_mounts.is_empty() {
        push(&mut out, "moduleMounts", Level::Leak, own_mounts, None);
        if let Some(f) = out.last_mut() {
            f.modules = mount_modules(&s.mountinfo, false);
        }
    }

    let ksu_mounts = ksu_mount_count(&s.mountinfo);
    if ksu_mounts > 0 && s.kernel_umount == Some(false) {
        push(
            &mut out,
            "camMounts",
            Level::Leak,
            vec![ksu_mounts.to_string()],
            Some(Fix::KernelUmount),
        );
        if let Some(f) = out.last_mut() {
            f.modules = mount_modules(&s.mountinfo, true);
        }
    }

    if s.selinux_hide == Some(false) {
        push(
            &mut out,
            "selinuxRules",
            Level::Leak,
            Vec::new(),
            Some(Fix::SelinuxHide),
        );
    }

    if !s.app_maps.is_empty() {
        push(&mut out, "maps", Level::Leak, s.app_maps.clone(), None);
    }

    if !s.hide_bootloader {
        let props: Vec<String> = hide_bootloader::plan(&s.props)
            .into_iter()
            .map(|(name, _)| format!("{name}={}", prop(&s.props, &name).unwrap_or_default()))
            .collect();
        if !props.is_empty() {
            push(
                &mut out,
                "props",
                Level::Leak,
                props,
                Some(Fix::HideBootloader),
            );
        }
    }

    let boot_args: Vec<String> = s
        .boot_args
        .iter()
        .filter(|(name, value)| hide_bootloader::safe_value(name).is_some_and(|safe| safe != value))
        .map(|(name, value)| format!("{name}={value}"))
        .collect();
    if !boot_args.is_empty() {
        push(&mut out, "bootArgs", Level::Leak, boot_args, None);
    }

    let lsposed: Vec<String> = s
        .modules
        .iter()
        .filter(|id| is_lsposed(id))
        .cloned()
        .collect();
    if !lsposed.is_empty() {
        push(&mut out, "lsposed", Level::Warn, lsposed, None);
    }

    let revanced: Vec<String> = s
        .modules
        .iter()
        .filter(|id| is_revanced(id))
        .cloned()
        .collect();
    if !revanced.is_empty() {
        push(&mut out, "revanced", Level::Warn, revanced, None);
    }

    let custom_rom: Vec<String> = ["ro.lineage.version", "ro.crdroid.version", "ro.modversion"]
        .iter()
        .filter_map(|name| prop(&s.props, name).map(|v| format!("{name}={v}")))
        .collect();
    if !custom_rom.is_empty() {
        push(&mut out, "customRom", Level::Warn, custom_rom, None);
    }

    if !s.existing.is_empty() {
        push(&mut out, "files", Level::Leak, s.existing.clone(), None);
    }

    if s.selinux_enforcing == Some(false) {
        push(&mut out, "selinux", Level::Leak, Vec::new(), None);
    }

    let adb = ["sys.usb.config", "persist.sys.usb.config"]
        .iter()
        .any(|name| prop(&s.props, name).is_some_and(|v| v.split(',').any(|f| f == "adb")));
    if adb {
        push(&mut out, "adb", Level::Warn, Vec::new(), None);
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

/// Counts the page shows next to the findings (what is hidden well): numbers, by name.
pub fn stats(s: &Snapshot) -> Value {
    json!({
        "rootModuleMounts": ksu_mount_count(&s.mountinfo) + module_mounts(&s.mountinfo).len(),
        "modules": s.modules.len(),
    })
}

pub fn report_json(findings: &[Finding], stats: &Value) -> Value {
    let list: Vec<Value> = findings
        .iter()
        .map(|f| {
            json!({
                "id": f.id,
                "level": level_name(f.level),
                "items": f.items,
                "fix": f.fix.map(fix_name),
                "modules": f.modules,
                "view": if f.app_view { "app" } else { "root" },
            })
        })
        .collect();
    json!({
        "fixable": findings.iter().filter(|f| f.fix.is_some()).count(),
        "findings": list,
        "stats": stats,
    })
}

/// What one app sees, from its processes' mountinfo and maps (`--uid`).
pub fn app_findings(mountinfo: &str, mapped: Vec<String>, rules: &Rules) -> Vec<Finding> {
    let mut out = Vec::new();
    let (mounts, modules) = app_view_mounts(mountinfo, rules);
    if !mounts.is_empty() {
        out.push(Finding {
            id: "appMounts",
            level: Level::Leak,
            items: mounts,
            fix: Some(Fix::KernelUmount),
            modules,
            app_view: true,
        });
    }
    if !mapped.is_empty() {
        out.push(Finding {
            id: "appMaps",
            level: Level::Leak,
            modules: module_ids(mapped.iter().map(String::as_str)),
            items: mapped,
            fix: None,
            app_view: true,
        });
    }
    out
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
    use std::path::{Path, PathBuf};

    use anyhow::Result;
    use serde_json::json;

    use super::{Fix, RECOVERY_PATHS, Rules, Snapshot};
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

    /// Running processes whose uid passes `wanted`.
    fn pids(wanted: impl Fn(u32) -> bool) -> Vec<PathBuf> {
        let Ok(procs) = std::fs::read_dir("/proc") else {
            return Vec::new();
        };
        let mut out: Vec<PathBuf> = procs
            .flatten()
            .map(|entry| entry.path())
            .filter(|pid| {
                pid.file_name()
                    .and_then(|n| n.to_str())
                    .is_some_and(|n| n.bytes().all(|b| b.is_ascii_digit()))
                    && uid_of(pid).is_some_and(&wanted)
            })
            .collect();
        out.sort();
        out
    }

    /// What `pids` have mapped from root or hook tooling.
    fn mapped(pids: &[PathBuf], markers: &[String]) -> Vec<String> {
        let mut found = BTreeSet::new();
        for pid in pids {
            let Ok(maps) = std::fs::read_to_string(pid.join("maps")) else {
                continue;
            };
            for line in maps.lines() {
                if let Some(path) = super::mapped_path(line, markers) {
                    found.insert(path.to_owned());
                }
            }
        }
        found.into_iter().collect()
    }

    /// `/sdcard/x` is checked at `/data/media/0/x`: camd's mount namespace may not have
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

    pub fn snapshot(rules: &Rules) -> Snapshot {
        let bootconfig = std::fs::read_to_string("/proc/bootconfig").unwrap_or_default();
        let cmdline = std::fs::read_to_string("/proc/cmdline").unwrap_or_default();
        Snapshot {
            props: resetprop::list_props().unwrap_or_default(),
            hide_bootloader: hide_bootloader::is_enabled(),
            boot_args: hide_bootloader::parse_boot_args(&bootconfig, &cmdline),
            mountinfo: std::fs::read_to_string("/proc/1/mountinfo").unwrap_or_default(),
            kernel_umount: feature(FeatureId::KernelUmount),
            selinux_hide: feature(FeatureId::SelinuxHide),
            app_maps: mapped(&pids(is_app_uid), &rules.map_markers),
            modules: crate::module::enabled_module_ids(),
            existing: rules
                .su_paths_for_camd()
                .chain(RECOVERY_PATHS.iter().copied())
                .filter(|p| exists(p))
                .map(str::to_owned)
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

    /// `camd hiding-audit --uid <uid>`: what that app's running processes see; `running`
    /// is false when none runs (the Manager then offers to start the app).
    fn run_uid(uid: u32, rules: &Rules) {
        let pids = pids(|u| u == uid);
        let mountinfo = pids
            .first()
            .and_then(|pid| std::fs::read_to_string(pid.join("mountinfo")).ok())
            .unwrap_or_default();
        let findings = super::app_findings(&mountinfo, mapped(&pids, &rules.map_markers), rules);
        let mut report = super::report_json(&findings, &json!({ "processes": pids.len() }));
        report["running"] = json!(!pids.is_empty());
        println!("{report}");
    }

    /// `camd hiding-audit [--apply] [--rules <json>] [--uid <uid>]`: the findings as JSON;
    /// with `apply`, every fix is turned on first and what was done is printed instead.
    pub fn run(apply: bool, rules: Option<PathBuf>, uid: Option<u32>) -> Result<()> {
        let rules = match rules {
            Some(path) => Rules::parse(&std::fs::read_to_string(path)?)?,
            None => Rules::default(),
        };
        if let Some(uid) = uid {
            run_uid(uid, &rules);
            return Ok(());
        }
        let snapshot = snapshot(&rules);
        let findings = super::audit(&snapshot);
        if !apply {
            println!(
                "{}",
                super::report_json(&findings, &super::stats(&snapshot))
            );
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
        let ksu = findings.iter().find(|f| f.id == "camMounts").unwrap();
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
        let markers = Rules::default().map_markers;
        assert_eq!(
            mapped_path(
                "7f00-7f10 r-xp 00000000 fe:2a 123   /data/adb/modules/z/lib.so (deleted)",
                &markers
            ),
            Some("/data/adb/modules/z/lib.so")
        );
        assert_eq!(
            mapped_path(
                "7f00-7f10 r-xp 00000000 fe:2a 123  /system/lib64/libc.so",
                &markers
            ),
            None
        );
        assert_eq!(
            mapped_path("7f00-7f10 rw-p 00000000 00:00 0", &markers),
            None
        );
    }

    #[test]
    fn map_markers_come_from_the_rules() {
        let rules = Rules::parse(r#"{"mapMarkers": ["LSPD"]}"#).unwrap();
        assert_eq!(rules.map_markers, vec!["lspd"]);
        assert_eq!(rules.su_paths, Rules::default().su_paths);
        assert_eq!(
            mapped_path(
                "7f00-7f10 r-xp 00000000 fe:2a 1 /memfd:liblspd.so (deleted)",
                &rules.map_markers
            ),
            Some("/memfd:liblspd.so")
        );
    }

    #[test]
    fn camd_skips_sucompat_su() {
        let rules = Rules::parse(r#"{"suPaths": ["/system/bin/su", "/sbin/su"]}"#).unwrap();
        assert_eq!(
            rules.su_paths_for_camd().collect::<Vec<_>>(),
            vec!["/sbin/su"]
        );
    }

    #[test]
    fn names_the_modules_behind_mounts() {
        assert_eq!(
            module_ids([
                "/adb/modules/hosts/system/etc/hosts",
                "ro,lowerdir=/data/adb/modules/a/system:/data/adb/modules/b/system:/system",
                "/data/adb/modules/a/lib.so",
            ]),
            vec!["hosts", "a", "b"]
        );
        let s = Snapshot {
            mountinfo: MOUNTINFO.to_owned(),
            kernel_umount: Some(false),
            ..snapshot()
        };
        let findings = audit(&s);
        assert_eq!(findings[0].modules, vec!["hosts", "font"]);
        assert_eq!(findings[1].modules, vec!["x"]);
    }

    #[test]
    fn app_view_sees_what_kernel_umount_left() {
        let rules = Rules::default();
        let (items, modules) = app_view_mounts(MOUNTINFO, &rules);
        assert_eq!(
            items,
            vec![
                "/system/etc/hosts (f2fs, /dev/block/dm-40)",
                "/system/fonts (overlay, overlay)",
                "/system/app (overlay, KSU)",
            ]
        );
        assert_eq!(modules, vec!["hosts", "font", "x"]);
        let clean = "20 1 254:6 / / ro,relatime shared:1 - erofs /dev/block/dm-6 ro\n";
        assert!(app_findings(clean, Vec::new(), &rules).is_empty());
        let findings = app_findings(
            MOUNTINFO,
            vec!["/data/adb/modules/z/lib.so".to_owned()],
            &rules,
        );
        assert_eq!(findings.len(), 2);
        assert!(findings.iter().all(|f| f.app_view));
        assert_eq!(findings[1].modules, vec!["z"]);
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
        let v = report_json(&audit(&s), &stats(&s));
        assert_eq!(v["fixable"], 1);
        assert_eq!(v["findings"][0]["fix"], "selinuxHide");
        assert_eq!(v["findings"][0]["view"], "root");
        assert_eq!(v["stats"]["rootModuleMounts"], 0);
    }
}

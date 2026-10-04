//! SUSFS settings kept by ksud, so the susfs4ksu module is not needed.
//!
//! The kernel forgets everything SUSFS was told on reboot, so the Manager's settings
//! live in `susfs.json` and ksud applies them again on every boot, at the same stages
//! the susfs4ksu module (sidex15) uses: [`boot_actions`] lists what each stage runs and
//! `susfs::run_actions` carries it out.
//!
//! Most of SUSFS can only be added to, never taken back: a hidden path stays hidden
//! and a fake bootconfig stays set until reboot. [`plan_live`] works out what a change
//! made in the Manager can apply right away and whether the rest needs a reboot.
#![cfg_attr(not(target_os = "android"), allow(dead_code))]

use std::fmt::Write as _;

use serde_json::{Value, json};

/// `uid_scheme` values the kernel accepts for open_redirect, `UID_NON_APP_PROC..=UID_UMOUNTED_PROC`.
pub const MAX_UID_SCHEME: i32 = 4;
/// `SUSFS_MAX_LEN_PATHNAME` minus the terminating NUL
pub const MAX_PATH_LEN: usize = 255;
/// `SUSFS_FAKE_CMDLINE_OR_BOOTCONFIG_SIZE` minus the terminating NUL
pub const MAX_BOOTCONFIG_LEN: usize = 8191;
/// `__NEW_UTS_LEN`
pub const MAX_UNAME_LEN: usize = 64;
/// seconds boot-completed waits for /sdcard/Android/data before the sus paths, like the module
pub const STORAGE_WAIT_SECS: u32 = 100;
/// the most a single sus path waits for its path to show up
pub const MAX_PATH_WAIT_SECS: u32 = 300;
pub const MAX_CUSROM_LEVEL: u8 = 5;

/// Mounts of the stock system that auto try_umount leaves alone when asked to (module's `legit_mounts.txt`).
pub const DEFAULT_LEGIT_MOUNTS: [&str; 46] = [
    "/system",
    "/system_ext",
    "/vendor",
    "/odm",
    "/product",
    "/system_dlkm",
    "/vendor_dlkm",
    "/odm_dlkm",
    "/apex",
    "/system/app",
    "/system/priv-app",
    "/system/lib",
    "/system/lib64",
    "/vendor/app",
    "/vendor/priv-app",
    "/vendor/lib",
    "/vendor/lib64",
    "/product/app",
    "/product/priv-app",
    "/product/lib",
    "/product/lib64",
    "/system_ext/app",
    "/system_ext/priv-app",
    "/system_ext/lib",
    "/system_ext/lib64",
    "/data",
    "/cache",
    "/metadata",
    "/persist",
    "/mnt",
    "/storage",
    "/debug_ramdisk",
    "/dev",
    "/proc",
    "/sys",
    "/sys/fs/cgroup",
    "/my_product",
    "/my_engineering",
    "/my_company",
    "/my_carrier",
    "/my_region",
    "/my_heytap",
    "/my_stock",
    "/my_preload",
    "/my_bigball",
    "/my_manifest",
];

/// What LSPosed leaves mounted for dex2oat; umounted for apps by "force hide LSPosed".
pub const LSPOSED_DEX2OAT_PATHS: [&str; 6] = [
    "/system/apex/com.android.art/bin/dex2oat",
    "/system/apex/com.android.art/bin/dex2oat32",
    "/system/apex/com.android.art/bin/dex2oat64",
    "/apex/com.android.art/bin/dex2oat",
    "/apex/com.android.art/bin/dex2oat32",
    "/apex/com.android.art/bin/dex2oat64",
];

/// Apps whose ReVanced mounts "hide ReVanced" umounts for other apps.
pub const REVANCED_PACKAGES: [&str; 2] = [
    "com.google.android.youtube",
    "com.google.android.apps.youtube.music",
];

/// Files a custom ROM names itself in; "hide" bind mounts a copy without those lines.
pub const VENDOR_SEPOLICY_FILES: [&str; 3] = [
    "/vendor/etc/selinux/vendor_sepolicy.cil",
    "/vendor/etc/selinux/vendor_file_contexts",
    "/system_ext/etc/selinux/system_ext_sepolicy.cil",
];
pub const COMPAT_MATRIX_FILE: &str = "/system/etc/vintf/compatibility_matrix.device.xml";

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum HideMnts {
    Off,
    Always,
    /// on from post-fs-data so zygote caches nothing, off again at boot-completed
    UntilBootCompleted,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum UnameStage {
    PostFsData,
    BootCompleted,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum BootconfigMode {
    Off,
    /// generated from the real /proc/bootconfig (or /proc/cmdline) on every boot
    Auto,
    Custom,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum VoldAppData {
    Off,
    SusPath,
    SusPathLoop,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum RedirectStage {
    Service,
    BootCompleted,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SusPathEntry {
    pub path: String,
    /// seconds to wait for the path to exist first, 0: try once
    pub wait: u32,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct OpenRedirect {
    pub target: String,
    pub redirect: String,
    pub uid_scheme: i32,
    pub stage: RedirectStage,
}

/// `add_sus_kstat_statically`: every field is a number or `default` to keep the real value.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct KstatEntry {
    pub path: String,
    /// ino, dev, nlink, size, atime, atime_nsec, mtime, mtime_nsec, ctime, ctime_nsec, blocks, blksize
    pub fields: [String; 12],
}

pub const KSTAT_FIELD_NAMES: [&str; 12] = [
    "ino",
    "dev",
    "nlink",
    "size",
    "atime",
    "atimeNsec",
    "mtime",
    "mtimeNsec",
    "ctime",
    "ctimeNsec",
    "blocks",
    "blksize",
];

impl KstatEntry {
    /// The parsed fields, `None` for `default`; an error names the first field that is neither.
    pub fn values(&self) -> Result<[Option<i64>; 12], String> {
        let mut out = [None; 12];
        for (i, raw) in self.fields.iter().enumerate() {
            let raw = raw.trim();
            if raw.is_empty() || raw == "default" {
                continue;
            }
            out[i] = Some(raw.parse::<i64>().map_err(|_| {
                format!(
                    "{} of '{}' is not a number: {raw}",
                    KSTAT_FIELD_NAMES[i], self.path
                )
            })?);
        }
        Ok(out)
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
#[allow(clippy::struct_excessive_bools)]
pub struct SusfsConfig {
    /// SUSFS logging to the kernel log; the kernel starts with it on
    pub enable_log: bool,
    /// show `su` in avc denials as `priv_app`
    pub avc_log_spoofing: bool,
    pub hide_sus_mnts: HideMnts,

    /// empty: the real value
    pub uname_release: String,
    /// empty: the real value
    pub uname_version: String,
    pub uname_stage: UnameStage,

    pub bootconfig_mode: BootconfigMode,
    /// the fake /proc/bootconfig (or /proc/cmdline on non-GKI) for [`BootconfigMode::Custom`]
    pub fake_bootconfig: String,

    pub hide_loops: bool,
    pub hide_vendor_sepolicy: bool,
    pub hide_compat_matrix: bool,
    /// 0 off, 1..=5 hides more and more lineage/crdroid files
    pub hide_cusrom: u8,
    pub hide_gapps: bool,
    pub hide_revanced: bool,
    pub force_hide_lsposed: bool,
    pub emulate_vold_app_data: VoldAppData,
    pub auto_try_umount: bool,
    pub skip_legit_mounts: bool,
    pub legit_mounts: Vec<String>,

    /// the verified boot props the module sets in service.sh
    pub spoof_props: bool,
    pub vbmeta_size: u32,
    /// `ro.boot.vbmeta.digest` for devices that lack it (the module's VerifiedBootHash)
    pub vbmeta_digest: String,

    pub sus_paths: Vec<SusPathEntry>,
    pub sus_path_loops: Vec<SusPathEntry>,
    pub sus_maps: Vec<String>,
    pub try_umounts: Vec<String>,
    pub open_redirects: Vec<OpenRedirect>,
    pub sus_kstats: Vec<KstatEntry>,
}

impl Default for SusfsConfig {
    fn default() -> Self {
        Self {
            enable_log: true,
            avc_log_spoofing: false,
            // simonpunk: "it is a MUST now to have it enabled all the time"
            hide_sus_mnts: HideMnts::Always,
            uname_release: String::new(),
            uname_version: String::new(),
            uname_stage: UnameStage::BootCompleted,
            bootconfig_mode: BootconfigMode::Off,
            fake_bootconfig: String::new(),
            hide_loops: false,
            hide_vendor_sepolicy: false,
            hide_compat_matrix: false,
            hide_cusrom: 0,
            hide_gapps: false,
            hide_revanced: false,
            force_hide_lsposed: false,
            emulate_vold_app_data: VoldAppData::Off,
            auto_try_umount: false,
            skip_legit_mounts: false,
            legit_mounts: DEFAULT_LEGIT_MOUNTS
                .iter()
                .map(|s| (*s).to_owned())
                .collect(),
            spoof_props: false,
            vbmeta_size: 8192,
            vbmeta_digest: String::new(),
            sus_paths: Vec::new(),
            sus_path_loops: Vec::new(),
            sus_maps: Vec::new(),
            try_umounts: Vec::new(),
            open_redirects: Vec::new(),
            sus_kstats: Vec::new(),
        }
    }
}

const fn hide_mnts_name(v: HideMnts) -> &'static str {
    match v {
        HideMnts::Off => "off",
        HideMnts::Always => "always",
        HideMnts::UntilBootCompleted => "untilBootCompleted",
    }
}

const fn uname_stage_name(v: UnameStage) -> &'static str {
    match v {
        UnameStage::PostFsData => "postFsData",
        UnameStage::BootCompleted => "bootCompleted",
    }
}

const fn bootconfig_mode_name(v: BootconfigMode) -> &'static str {
    match v {
        BootconfigMode::Off => "off",
        BootconfigMode::Auto => "auto",
        BootconfigMode::Custom => "custom",
    }
}

const fn vold_name(v: VoldAppData) -> &'static str {
    match v {
        VoldAppData::Off => "off",
        VoldAppData::SusPath => "susPath",
        VoldAppData::SusPathLoop => "susPathLoop",
    }
}

const fn redirect_stage_name(v: RedirectStage) -> &'static str {
    match v {
        RedirectStage::Service => "service",
        RedirectStage::BootCompleted => "bootCompleted",
    }
}

/// Drop surrounding blanks and trailing slashes, but keep `/` itself.
pub fn normalize_path(path: &str) -> String {
    let path = path.trim();
    let trimmed = path.trim_end_matches('/');
    if trimmed.is_empty() && path.starts_with('/') {
        "/".to_owned()
    } else {
        trimmed.to_owned()
    }
}

/// Unique, normalized, non-empty paths from a JSON array of strings.
fn path_list(value: Option<&Value>) -> Vec<String> {
    let mut list: Vec<String> = Vec::new();
    for item in value.and_then(Value::as_array).into_iter().flatten() {
        if let Some(path) = item.as_str().map(normalize_path).filter(|p| !p.is_empty())
            && !list.contains(&path)
        {
            list.push(path);
        }
    }
    list
}

/// Sus path entries, either `{ "path", "wait" }` objects or plain strings.
fn sus_path_list(value: Option<&Value>) -> Vec<SusPathEntry> {
    let mut list: Vec<SusPathEntry> = Vec::new();
    for item in value.and_then(Value::as_array).into_iter().flatten() {
        let (path, wait) = match item {
            Value::String(s) => (normalize_path(s), 0),
            Value::Object(_) => (
                item.get("path")
                    .and_then(Value::as_str)
                    .map(normalize_path)
                    .unwrap_or_default(),
                item.get("wait")
                    .and_then(Value::as_u64)
                    .map_or(0, |w| w.min(u64::from(MAX_PATH_WAIT_SECS)) as u32),
            ),
            _ => continue,
        };
        if !path.is_empty() && !list.iter().any(|e| e.path == path) {
            list.push(SusPathEntry { path, wait });
        }
    }
    list
}

impl SusfsConfig {
    /// The `(release, version)` to hand to the kernel; `default` keeps the real value.
    pub fn uname_args(&self) -> (&str, &str) {
        const fn pick(s: &str) -> &str {
            if s.is_empty() { "default" } else { s }
        }
        (pick(&self.uname_release), pick(&self.uname_version))
    }

    pub fn uname_spoofed(&self) -> bool {
        self.uname_args() != ("default", "default")
    }

    pub fn to_json(&self) -> Value {
        let entries = |list: &[SusPathEntry]| -> Vec<Value> {
            list.iter()
                .map(|e| json!({ "path": e.path, "wait": e.wait }))
                .collect()
        };
        let redirects: Vec<Value> = self
            .open_redirects
            .iter()
            .map(|r| {
                json!({
                    "target": r.target,
                    "redirect": r.redirect,
                    "uidScheme": r.uid_scheme,
                    "stage": redirect_stage_name(r.stage),
                })
            })
            .collect();
        let kstats: Vec<Value> = self
            .sus_kstats
            .iter()
            .map(|k| {
                let mut obj = serde_json::Map::new();
                obj.insert("path".into(), json!(k.path));
                for (name, value) in KSTAT_FIELD_NAMES.iter().zip(&k.fields) {
                    obj.insert((*name).into(), json!(value));
                }
                Value::Object(obj)
            })
            .collect();
        json!({
            "enableLog": self.enable_log,
            "avcLogSpoofing": self.avc_log_spoofing,
            "hideSusMnts": hide_mnts_name(self.hide_sus_mnts),
            "unameRelease": self.uname_release,
            "unameVersion": self.uname_version,
            "unameStage": uname_stage_name(self.uname_stage),
            "bootconfigMode": bootconfig_mode_name(self.bootconfig_mode),
            "fakeBootconfig": self.fake_bootconfig,
            "hideLoops": self.hide_loops,
            "hideVendorSepolicy": self.hide_vendor_sepolicy,
            "hideCompatMatrix": self.hide_compat_matrix,
            "hideCusrom": self.hide_cusrom,
            "hideGapps": self.hide_gapps,
            "hideRevanced": self.hide_revanced,
            "forceHideLsposed": self.force_hide_lsposed,
            "emulateVoldAppData": vold_name(self.emulate_vold_app_data),
            "autoTryUmount": self.auto_try_umount,
            "skipLegitMounts": self.skip_legit_mounts,
            "legitMounts": self.legit_mounts,
            "spoofProps": self.spoof_props,
            "vbmetaSize": self.vbmeta_size,
            "vbmetaDigest": self.vbmeta_digest,
            "susPaths": entries(&self.sus_paths),
            "susPathLoops": entries(&self.sus_path_loops),
            "susMaps": self.sus_maps,
            "tryUmounts": self.try_umounts,
            "openRedirects": redirects,
            "susKstats": kstats,
        })
    }

    /// Missing or unknown fields keep their defaults; lists come back unique and normalized.
    pub fn from_json(value: &Value) -> Self {
        let d = Self::default();
        let bool_of =
            |key: &str, default: bool| value.get(key).and_then(Value::as_bool).unwrap_or(default);
        let str_of = |key: &str| {
            value
                .get(key)
                .and_then(Value::as_str)
                .map(|s| s.trim().to_owned())
                .unwrap_or_default()
        };
        let name_of = |key: &str| value.get(key).and_then(Value::as_str).unwrap_or_default();

        let mut open_redirects: Vec<OpenRedirect> = Vec::new();
        for item in value
            .get("openRedirects")
            .and_then(Value::as_array)
            .into_iter()
            .flatten()
        {
            let field = |key: &str| {
                item.get(key)
                    .and_then(Value::as_str)
                    .map(normalize_path)
                    .unwrap_or_default()
            };
            let redirect = OpenRedirect {
                target: field("target"),
                redirect: field("redirect"),
                // the module's default when the line has none
                uid_scheme: item
                    .get("uidScheme")
                    .and_then(Value::as_i64)
                    .and_then(|v| i32::try_from(v).ok())
                    .unwrap_or(2),
                stage: match item.get("stage").and_then(Value::as_str) {
                    Some("service") => RedirectStage::Service,
                    _ => RedirectStage::BootCompleted,
                },
            };
            if !redirect.target.is_empty()
                && !open_redirects.iter().any(|r| r.target == redirect.target)
            {
                open_redirects.push(redirect);
            }
        }

        let mut sus_kstats: Vec<KstatEntry> = Vec::new();
        for item in value
            .get("susKstats")
            .and_then(Value::as_array)
            .into_iter()
            .flatten()
        {
            let path = item
                .get("path")
                .and_then(Value::as_str)
                .map(normalize_path)
                .unwrap_or_default();
            if path.is_empty() || sus_kstats.iter().any(|k| k.path == path) {
                continue;
            }
            let fields = KSTAT_FIELD_NAMES.map(|name| match item.get(name) {
                Some(Value::String(s)) if !s.trim().is_empty() => s.trim().to_owned(),
                Some(Value::Number(n)) => n.to_string(),
                _ => "default".to_owned(),
            });
            sus_kstats.push(KstatEntry { path, fields });
        }

        let legit_mounts = if value.get("legitMounts").is_some() {
            path_list(value.get("legitMounts"))
        } else {
            d.legit_mounts.clone()
        };

        Self {
            enable_log: bool_of("enableLog", d.enable_log),
            avc_log_spoofing: bool_of("avcLogSpoofing", d.avc_log_spoofing),
            hide_sus_mnts: match name_of("hideSusMnts") {
                "off" => HideMnts::Off,
                "untilBootCompleted" => HideMnts::UntilBootCompleted,
                "always" => HideMnts::Always,
                _ => d.hide_sus_mnts,
            },
            uname_release: str_of("unameRelease"),
            uname_version: str_of("unameVersion"),
            uname_stage: match name_of("unameStage") {
                "postFsData" => UnameStage::PostFsData,
                _ => UnameStage::BootCompleted,
            },
            bootconfig_mode: match name_of("bootconfigMode") {
                "auto" => BootconfigMode::Auto,
                "custom" => BootconfigMode::Custom,
                _ => BootconfigMode::Off,
            },
            // keep the text as written, only drop trailing blank lines
            fake_bootconfig: value
                .get("fakeBootconfig")
                .and_then(Value::as_str)
                .map(str::trim_end)
                .filter(|s| !s.is_empty())
                .map(|s| format!("{s}\n"))
                .unwrap_or_default(),
            hide_loops: bool_of("hideLoops", d.hide_loops),
            hide_vendor_sepolicy: bool_of("hideVendorSepolicy", d.hide_vendor_sepolicy),
            hide_compat_matrix: bool_of("hideCompatMatrix", d.hide_compat_matrix),
            hide_cusrom: value
                .get("hideCusrom")
                .and_then(Value::as_u64)
                .map_or(d.hide_cusrom, |v| v.min(u64::from(MAX_CUSROM_LEVEL)) as u8),
            hide_gapps: bool_of("hideGapps", d.hide_gapps),
            hide_revanced: bool_of("hideRevanced", d.hide_revanced),
            force_hide_lsposed: bool_of("forceHideLsposed", d.force_hide_lsposed),
            emulate_vold_app_data: match name_of("emulateVoldAppData") {
                "susPath" => VoldAppData::SusPath,
                "susPathLoop" => VoldAppData::SusPathLoop,
                _ => VoldAppData::Off,
            },
            auto_try_umount: bool_of("autoTryUmount", d.auto_try_umount),
            skip_legit_mounts: bool_of("skipLegitMounts", d.skip_legit_mounts),
            legit_mounts,
            spoof_props: bool_of("spoofProps", d.spoof_props),
            vbmeta_size: value
                .get("vbmetaSize")
                .and_then(Value::as_u64)
                .and_then(|v| u32::try_from(v).ok())
                .filter(|v| *v > 0)
                .unwrap_or(d.vbmeta_size),
            vbmeta_digest: str_of("vbmetaDigest").to_ascii_lowercase(),
            sus_paths: sus_path_list(value.get("susPaths")),
            sus_path_loops: sus_path_list(value.get("susPathLoops")),
            sus_maps: path_list(value.get("susMaps")),
            try_umounts: path_list(value.get("tryUmounts")),
            open_redirects,
            sus_kstats,
        }
    }

    /// Everything the kernel would refuse or silently cut short.
    pub fn validate(&self) -> Vec<String> {
        let mut errors = Vec::new();
        for (name, value) in [
            ("uname release", &self.uname_release),
            ("uname version", &self.uname_version),
        ] {
            if value.len() > MAX_UNAME_LEN {
                errors.push(format!("{name} is longer than {MAX_UNAME_LEN} bytes"));
            }
        }
        if self.bootconfig_mode == BootconfigMode::Custom && self.fake_bootconfig.is_empty() {
            errors.push("custom bootconfig is empty".to_owned());
        }
        if self.fake_bootconfig.len() > MAX_BOOTCONFIG_LEN {
            errors.push(format!(
                "fake bootconfig is longer than {MAX_BOOTCONFIG_LEN} bytes"
            ));
        }
        if !self.vbmeta_digest.is_empty()
            && !self.vbmeta_digest.chars().all(|c| c.is_ascii_hexdigit())
        {
            errors.push("vbmeta digest is not hex".to_owned());
        }
        let paths = self
            .sus_paths
            .iter()
            .chain(&self.sus_path_loops)
            .map(|e| &e.path)
            .chain(&self.sus_maps)
            .chain(&self.try_umounts)
            .chain(
                self.open_redirects
                    .iter()
                    .flat_map(|r| [&r.target, &r.redirect]),
            )
            .chain(self.sus_kstats.iter().map(|k| &k.path));
        for path in paths {
            if !path.starts_with('/') {
                errors.push(format!("'{path}' is not an absolute path"));
            } else if path.len() > MAX_PATH_LEN {
                errors.push(format!("'{path}' is longer than {MAX_PATH_LEN} bytes"));
            }
        }
        for r in &self.open_redirects {
            if r.redirect.is_empty() {
                errors.push(format!(
                    "open redirect of '{}' has no redirected path",
                    r.target
                ));
            }
            if !(0..=MAX_UID_SCHEME).contains(&r.uid_scheme) {
                errors.push(format!(
                    "uid scheme {} of '{}' is not 0..={MAX_UID_SCHEME}",
                    r.uid_scheme, r.target
                ));
            }
        }
        for k in &self.sus_kstats {
            if let Err(e) = k.values() {
                errors.push(e);
            }
        }
        errors
    }
}

/// Where in the boot ksud is.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Stage {
    PostFsData,
    Service,
    BootCompleted,
}

/// One thing to tell the kernel or do on the device. Paths come as written; the runner resolves them.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Action {
    EnableLog(bool),
    AvcLogSpoofing(bool),
    HideSusMnts(bool),
    SetUname(String, String),
    /// `None`: generate from the running bootconfig
    SetBootconfig(Option<String>),
    SusPath(SusPathEntry),
    SusPathLoop(SusPathEntry),
    SusMap(String),
    TryUmount(String),
    TryUmountDel(String),
    OpenRedirect(OpenRedirect),
    SusKstat(Box<KstatEntry>),
    SpoofProps {
        vbmeta_size: u32,
        vbmeta_digest: String,
    },
    HideLoops,
    /// bind mount a copy of the file without its lineage lines and hide the mount
    HideRomFile(String),
    HideCusrom(u8),
    HideGapps,
    HideRevanced,
    AutoTryUmount {
        skip: Vec<String>,
        rehide_mnts: bool,
    },
    /// wait until /sdcard/Android/data exists, at most this many seconds
    WaitForStorage(u32),
    EmulateVoldAppData {
        loop_: bool,
    },
}

/// What a stage of the boot runs, in the order of the susfs4ksu module's scripts.
pub fn boot_actions(c: &SusfsConfig, stage: Stage) -> Vec<Action> {
    let mut actions = Vec::new();
    match stage {
        Stage::PostFsData => {
            actions.push(Action::EnableLog(c.enable_log));
            actions.push(Action::HideSusMnts(c.hide_sus_mnts != HideMnts::Off));
            if c.avc_log_spoofing {
                actions.push(Action::AvcLogSpoofing(true));
            }
            if c.uname_stage == UnameStage::PostFsData && c.uname_spoofed() {
                let (release, version) = c.uname_args();
                actions.push(Action::SetUname(release.to_owned(), version.to_owned()));
            }
        }
        Stage::Service => {
            if c.spoof_props {
                actions.push(Action::SpoofProps {
                    vbmeta_size: c.vbmeta_size,
                    vbmeta_digest: c.vbmeta_digest.clone(),
                });
            }
            actions.extend(
                c.open_redirects
                    .iter()
                    .filter(|r| r.stage == RedirectStage::Service)
                    .cloned()
                    .map(Action::OpenRedirect),
            );
            if c.hide_loops {
                actions.push(Action::HideLoops);
            }
            if c.hide_vendor_sepolicy {
                actions.extend(
                    VENDOR_SEPOLICY_FILES
                        .iter()
                        .map(|f| Action::HideRomFile((*f).to_owned())),
                );
            }
            if c.hide_compat_matrix {
                actions.push(Action::HideRomFile(COMPAT_MATRIX_FILE.to_owned()));
            }
        }
        Stage::BootCompleted => {
            if c.auto_try_umount {
                actions.push(Action::AutoTryUmount {
                    skip: if c.skip_legit_mounts {
                        c.legit_mounts.clone()
                    } else {
                        Vec::new()
                    },
                    rehide_mnts: c.hide_sus_mnts != HideMnts::Off,
                });
            }
            actions.extend(
                c.open_redirects
                    .iter()
                    .filter(|r| r.stage == RedirectStage::BootCompleted)
                    .cloned()
                    .map(Action::OpenRedirect),
            );
            actions.extend(c.sus_maps.iter().cloned().map(Action::SusMap));
            actions.extend(
                c.sus_kstats
                    .iter()
                    .cloned()
                    .map(|k| Action::SusKstat(Box::new(k))),
            );
            actions.extend(c.try_umounts.iter().cloned().map(Action::TryUmount));
            if c.uname_stage == UnameStage::BootCompleted && c.uname_spoofed() {
                let (release, version) = c.uname_args();
                actions.push(Action::SetUname(release.to_owned(), version.to_owned()));
            }
            if c.hide_cusrom > 0 {
                actions.push(Action::HideCusrom(c.hide_cusrom));
            }
            if c.hide_gapps {
                actions.push(Action::HideGapps);
            }
            match c.bootconfig_mode {
                BootconfigMode::Off => {}
                BootconfigMode::Auto => actions.push(Action::SetBootconfig(None)),
                BootconfigMode::Custom => {
                    actions.push(Action::SetBootconfig(Some(c.fake_bootconfig.clone())));
                }
            }
            if c.hide_revanced {
                actions.push(Action::HideRevanced);
            }
            if c.force_hide_lsposed {
                actions.extend(
                    LSPOSED_DEX2OAT_PATHS
                        .iter()
                        .map(|p| Action::TryUmount((*p).to_owned())),
                );
            }
            if c.hide_sus_mnts == HideMnts::UntilBootCompleted {
                actions.push(Action::HideSusMnts(false));
            }
            if c.emulate_vold_app_data != VoldAppData::Off
                || !c.sus_paths.is_empty()
                || !c.sus_path_loops.is_empty()
            {
                actions.push(Action::WaitForStorage(STORAGE_WAIT_SECS));
            }
            if c.emulate_vold_app_data != VoldAppData::Off {
                actions.push(Action::EmulateVoldAppData {
                    loop_: c.emulate_vold_app_data == VoldAppData::SusPathLoop,
                });
            }
            actions.extend(c.sus_paths.iter().cloned().map(Action::SusPath));
            actions.extend(c.sus_path_loops.iter().cloned().map(Action::SusPathLoop));
        }
    }
    actions
}

/// What applying `new` on top of the running `old` takes, after boot completed.
#[derive(Debug, Default, PartialEq, Eq)]
pub struct LivePlan {
    pub actions: Vec<Action>,
    /// something can only be taken back by a reboot
    pub reboot_needed: bool,
}

pub fn plan_live(old: &SusfsConfig, new: &SusfsConfig) -> LivePlan {
    let mut actions = Vec::new();
    let mut reboot_needed = false;

    if old.enable_log != new.enable_log {
        actions.push(Action::EnableLog(new.enable_log));
    }
    if old.avc_log_spoofing != new.avc_log_spoofing {
        actions.push(Action::AvcLogSpoofing(new.avc_log_spoofing));
    }
    // after boot, "until boot completed" means off
    let hidden_now = |c: &SusfsConfig| c.hide_sus_mnts == HideMnts::Always;
    if hidden_now(old) != hidden_now(new) {
        actions.push(Action::HideSusMnts(hidden_now(new)));
    }
    if old.uname_args() != new.uname_args() {
        let (release, version) = new.uname_args();
        actions.push(Action::SetUname(release.to_owned(), version.to_owned()));
    }

    let bootconfig_of = |c: &SusfsConfig| match c.bootconfig_mode {
        BootconfigMode::Off => None,
        BootconfigMode::Auto => Some(None),
        BootconfigMode::Custom => Some(Some(c.fake_bootconfig.clone())),
    };
    match (bootconfig_of(old), bootconfig_of(new)) {
        (Some(_), None) => reboot_needed = true,
        (a, Some(b)) if a.as_ref() != Some(&b) => actions.push(Action::SetBootconfig(b)),
        _ => {}
    }

    // switches: turning one on runs it now, turning it off needs a reboot
    let switches = [
        (old.hide_loops, new.hide_loops, vec![Action::HideLoops]),
        (
            old.hide_vendor_sepolicy,
            new.hide_vendor_sepolicy,
            VENDOR_SEPOLICY_FILES
                .iter()
                .map(|f| Action::HideRomFile((*f).to_owned()))
                .collect(),
        ),
        (
            old.hide_compat_matrix,
            new.hide_compat_matrix,
            vec![Action::HideRomFile(COMPAT_MATRIX_FILE.to_owned())],
        ),
        (old.hide_gapps, new.hide_gapps, vec![Action::HideGapps]),
        (
            old.hide_revanced,
            new.hide_revanced,
            vec![Action::HideRevanced],
        ),
        (
            old.spoof_props,
            new.spoof_props,
            vec![Action::SpoofProps {
                vbmeta_size: new.vbmeta_size,
                vbmeta_digest: new.vbmeta_digest.clone(),
            }],
        ),
    ];
    for (was, is, run) in switches {
        match (was, is) {
            (false, true) => actions.extend(run),
            (true, false) => reboot_needed = true,
            _ => {}
        }
    }
    if old.spoof_props
        && new.spoof_props
        && (old.vbmeta_size != new.vbmeta_size || old.vbmeta_digest != new.vbmeta_digest)
    {
        actions.push(Action::SpoofProps {
            vbmeta_size: new.vbmeta_size,
            vbmeta_digest: new.vbmeta_digest.clone(),
        });
    }
    if new.hide_cusrom > old.hide_cusrom {
        actions.push(Action::HideCusrom(new.hide_cusrom));
    } else if new.hide_cusrom < old.hide_cusrom {
        reboot_needed = true;
    }
    match (old.emulate_vold_app_data, new.emulate_vold_app_data) {
        (a, b) if a == b => {}
        (_, VoldAppData::Off) => reboot_needed = true,
        (a, b) => {
            // sus_path to sus_path_loop adds to what is there, the other way round cannot drop the loop
            reboot_needed |= a == VoldAppData::SusPathLoop;
            actions.push(Action::EmulateVoldAppData {
                loop_: b == VoldAppData::SusPathLoop,
            });
        }
    }

    // the kernel umount list is the only one that can drop entries
    let lsposed = |c: &SusfsConfig| -> Vec<String> {
        let mut list = c.try_umounts.clone();
        if c.force_hide_lsposed {
            list.extend(LSPOSED_DEX2OAT_PATHS.iter().map(|p| (*p).to_owned()));
        }
        list
    };
    let (old_umounts, new_umounts) = (lsposed(old), lsposed(new));
    actions.extend(
        old_umounts
            .iter()
            .filter(|p| !new_umounts.contains(p))
            .cloned()
            .map(Action::TryUmountDel),
    );
    actions.extend(
        new_umounts
            .iter()
            .filter(|p| !old_umounts.contains(p))
            .cloned()
            .map(Action::TryUmount),
    );
    if new.auto_try_umount
        && (!old.auto_try_umount
            || old.skip_legit_mounts != new.skip_legit_mounts
            || old.legit_mounts != new.legit_mounts)
    {
        actions.push(Action::AutoTryUmount {
            skip: if new.skip_legit_mounts {
                new.legit_mounts.clone()
            } else {
                Vec::new()
            },
            rehide_mnts: new.hide_sus_mnts == HideMnts::Always,
        });
    }
    // auto try_umount added mounts we no longer know of
    reboot_needed |= old.auto_try_umount && !new.auto_try_umount;

    // added entries apply now; anything removed or changed stays until reboot
    macro_rules! diff_list {
        ($field:ident, $key:expr, $same:expr, $action:expr) => {{
            for item in &new.$field {
                match old.$field.iter().find(|o| $key(*o) == $key(item)) {
                    None => actions.push($action(item.clone())),
                    Some(o) if !$same(o, item) => reboot_needed = true,
                    Some(_) => {}
                }
            }
            reboot_needed |= old
                .$field
                .iter()
                .any(|o| !new.$field.iter().any(|n| $key(n) == $key(o)));
        }};
    }
    // a sus path's wait only matters at boot, and the path is there by now
    let path_of = |e: &SusPathEntry| e.path.clone();
    let same_path = |_: &SusPathEntry, _: &SusPathEntry| true;
    let now = |e: SusPathEntry| SusPathEntry { wait: 0, ..e };
    diff_list!(sus_paths, path_of, same_path, |e| Action::SusPath(now(e)));
    diff_list!(sus_path_loops, path_of, same_path, |e| Action::SusPathLoop(
        now(e)
    ));
    diff_list!(
        sus_maps,
        |p: &String| p.clone(),
        |a: &String, b: &String| a == b,
        Action::SusMap
    );
    diff_list!(
        open_redirects,
        |r: &OpenRedirect| r.target.clone(),
        |a: &OpenRedirect, b: &OpenRedirect| a.redirect == b.redirect
            && a.uid_scheme == b.uid_scheme,
        Action::OpenRedirect
    );
    diff_list!(
        sus_kstats,
        |k: &KstatEntry| k.path.clone(),
        |a: &KstatEntry, b: &KstatEntry| a == b,
        |k| { Action::SusKstat(Box::new(k)) }
    );

    LivePlan {
        actions,
        reboot_needed,
    }
}

/// `/proc/bootconfig` (`key = "value"`) or `/proc/cmdline` (`key=value ...`) as a locked,
/// verified device reports it: every `androidboot.*` value hide_bootloader has a rule for is
/// set to its safe value, the verify-error entries are dropped, and `hwname` and
/// `product.hardware.sku` are set to `product_name` like the module does.
pub fn locked_bootconfig(current: &str, product_name: &str) -> String {
    const DROP: [&str; 2] = [
        "androidboot.verifiedbooterror",
        "androidboot.verifyerrorpart",
    ];
    const PRODUCT_KEYS: [&str; 2] = ["androidboot.hwname", "androidboot.product.hardware.sku"];
    let key_of = |entry: &str| entry.split_once('=').map(|(k, _)| k.trim().to_owned());
    let safe_of = |key: &str| -> Option<String> {
        if !key.starts_with("androidboot.") {
            return None;
        }
        if PRODUCT_KEYS.contains(&key) && !product_name.is_empty() {
            return Some(product_name.to_owned());
        }
        crate::hide_bootloader::safe_value(key).map(str::to_owned)
    };

    let is_bootconfig =
        current.lines().any(|l| l.contains(" = ")) || current.trim_end().contains('\n');
    if is_bootconfig {
        let mut out = String::new();
        for line in current.lines() {
            match key_of(line) {
                Some(key) if DROP.contains(&key.as_str()) => {}
                Some(key) => {
                    if let Some(safe) = safe_of(&key) {
                        let _ = writeln!(out, "{key} = \"{safe}\"");
                    } else {
                        out.push_str(line);
                        out.push('\n');
                    }
                }
                None => {
                    out.push_str(line);
                    out.push('\n');
                }
            }
        }
        out
    } else {
        let args: Vec<String> = current
            .split_whitespace()
            .filter_map(|arg| {
                let Some(key) = key_of(arg) else {
                    return Some(arg.to_owned());
                };
                if DROP.contains(&key.as_str()) {
                    return None;
                }
                Some(safe_of(&key).map_or_else(|| arg.to_owned(), |safe| format!("{key}={safe}")))
            })
            .collect();
        args.join(" ") + "\n"
    }
}

/// Whether a custom ROM file at `path` is hidden at `level` (module's hide_cusrom 1..=5):
/// the lower the level, the more file kinds are left alone so the ROM keeps working.
pub fn cusrom_hides(path: &str, level: u8) -> bool {
    let lower = path.to_ascii_lowercase();
    if !(lower.contains("lineage") || lower.contains("crdroid")) {
        return false;
    }
    // the module only takes names with a dot in them
    if !path.contains('.') {
        return false;
    }
    // the module's grep -vE '.(apk|jar|...)': any character, then the name, anywhere in the path
    let kept: &[&str] = match level {
        0 => return false,
        1 => &["apk", "jar", "odex", "vdex", "so", "rc"],
        2 => &["apk", "jar", "odex", "vdex", "so"],
        3 => &["apk", "jar", "odex", "vdex"],
        4 => &["apk", "jar"],
        _ => &[],
    };
    if level < 5 && path.contains("/vendor/bin/hw/") {
        return false;
    }
    let rest = path.get(1..).unwrap_or_default();
    !kept.iter().any(|name| rest.contains(name))
}

/// The mount points auto try_umount takes from `/proc/1/mountinfo`: mounts made by
/// KernelSU (their source is `KSU`) and those SUSFS gave its own mount id range.
pub fn ksu_mounts(mountinfo: &str, skip: &[String]) -> Vec<String> {
    let mut out: Vec<String> = Vec::new();
    for line in mountinfo.lines() {
        let fields: Vec<&str> = line.split_whitespace().collect();
        if fields.len() < 5 {
            continue;
        }
        let Ok(id) = fields[0].parse::<u64>() else {
            continue;
        };
        let source = line
            .split(" - ")
            .nth(1)
            .and_then(|rest| rest.split_whitespace().nth(1));
        let ksu_source = source == Some("KSU");
        // DEFAULT_KSU_MNT_ID and up
        let sus_id = id >= 2_000_000_000;
        if !(ksu_source || sus_id) {
            continue;
        }
        let mnt = fields[4].to_owned();
        if !skip.contains(&mnt) && !out.contains(&mnt) {
            out.push(mnt);
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn json_roundtrip_and_cleanup() {
        let raw = json!({
            "enableLog": false,
            "hideSusMnts": "untilBootCompleted",
            "susPaths": ["/data/adb/x/", { "path": "  /data/adb/x ", "wait": 9 }, "", { "path": "/system/addon.d", "wait": 99999 }],
            "openRedirects": [
                { "target": "/system/etc/hosts", "redirect": "/data/local/tmp/hosts", "uidScheme": 3 },
                { "target": "/system/etc/hosts", "redirect": "/other" },
            ],
            "susKstats": [{ "path": "/system/framework/services.jar", "mtime": 1230768000, "ino": "" }],
            "fakeBootconfig": "a = \"b\"\n\n\n",
            "hideCusrom": 9,
        });
        let config = SusfsConfig::from_json(&raw);
        assert!(!config.enable_log);
        assert_eq!(config.hide_sus_mnts, HideMnts::UntilBootCompleted);
        assert_eq!(
            config.sus_paths,
            [
                SusPathEntry {
                    path: "/data/adb/x".into(),
                    wait: 0
                },
                SusPathEntry {
                    path: "/system/addon.d".into(),
                    wait: MAX_PATH_WAIT_SECS
                },
            ]
        );
        assert_eq!(config.open_redirects.len(), 1);
        assert_eq!(config.open_redirects[0].stage, RedirectStage::BootCompleted);
        assert_eq!(config.sus_kstats[0].fields[0], "default");
        assert_eq!(config.sus_kstats[0].fields[6], "1230768000");
        assert_eq!(config.fake_bootconfig, "a = \"b\"\n");
        assert_eq!(config.hide_cusrom, MAX_CUSROM_LEVEL);
        assert_eq!(config.legit_mounts.len(), DEFAULT_LEGIT_MOUNTS.len());
        assert_eq!(SusfsConfig::from_json(&config.to_json()), config);
        assert!(config.validate().is_empty());
        assert_eq!(SusfsConfig::from_json(&json!({})), SusfsConfig::default());
    }

    #[test]
    fn validate_rejects_bad_values() {
        let mut fields = KSTAT_FIELD_NAMES.map(|_| "default".to_owned());
        fields[3] = "big".into();
        let config = SusfsConfig {
            sus_maps: vec!["relative".into(), format!("/{}", "a".repeat(300))],
            uname_release: "x".repeat(65),
            open_redirects: vec![OpenRedirect {
                target: "/a".into(),
                redirect: "/b".into(),
                uid_scheme: 9,
                stage: RedirectStage::Service,
            }],
            sus_kstats: vec![KstatEntry {
                path: "/k".into(),
                fields,
            }],
            bootconfig_mode: BootconfigMode::Custom,
            ..Default::default()
        };
        assert_eq!(config.validate().len(), 6);
    }

    #[test]
    fn boot_stages() {
        let config = SusfsConfig {
            uname_release: "5.10.1".into(),
            hide_sus_mnts: HideMnts::UntilBootCompleted,
            sus_paths: vec![SusPathEntry {
                path: "/sdcard/TWRP".into(),
                wait: 0,
            }],
            force_hide_lsposed: true,
            ..Default::default()
        };
        let post = boot_actions(&config, Stage::PostFsData);
        assert_eq!(post, [Action::EnableLog(true), Action::HideSusMnts(true)]);
        let done = boot_actions(&config, Stage::BootCompleted);
        assert!(done.contains(&Action::SetUname("5.10.1".into(), "default".into())));
        assert!(done.contains(&Action::TryUmount(LSPOSED_DEX2OAT_PATHS[0].into())));
        let off = done
            .iter()
            .position(|a| *a == Action::HideSusMnts(false))
            .unwrap();
        let wait = done
            .iter()
            .position(|a| matches!(a, Action::WaitForStorage(_)))
            .unwrap();
        assert!(off < wait);
        assert!(matches!(done.last(), Some(Action::SusPath(_))));
        assert!(boot_actions(&SusfsConfig::default(), Stage::Service).is_empty());
    }

    #[test]
    fn live_plan() {
        let old = SusfsConfig {
            sus_paths: vec![
                SusPathEntry {
                    path: "/a".into(),
                    wait: 0,
                },
                SusPathEntry {
                    path: "/b".into(),
                    wait: 0,
                },
            ],
            bootconfig_mode: BootconfigMode::Auto,
            try_umounts: vec!["/x".into()],
            ..Default::default()
        };
        let new = SusfsConfig {
            sus_paths: vec![
                SusPathEntry {
                    path: "/a".into(),
                    wait: 0,
                },
                SusPathEntry {
                    path: "/c".into(),
                    wait: 5,
                },
            ],
            uname_release: "5.10".into(),
            avc_log_spoofing: true,
            hide_gapps: true,
            try_umounts: vec!["/y".into()],
            ..Default::default()
        };
        let plan = plan_live(&old, &new);
        assert!(plan.actions.contains(&Action::SusPath(SusPathEntry {
            path: "/c".into(),
            wait: 0
        })));
        assert!(plan.actions.contains(&Action::AvcLogSpoofing(true)));
        assert!(
            plan.actions
                .contains(&Action::SetUname("5.10".into(), "default".into()))
        );
        assert!(plan.actions.contains(&Action::HideGapps));
        assert!(plan.actions.contains(&Action::TryUmountDel("/x".into())));
        assert!(plan.actions.contains(&Action::TryUmount("/y".into())));
        assert!(
            !plan
                .actions
                .iter()
                .any(|a| matches!(a, Action::SetBootconfig(_)))
        );
        assert!(plan.reboot_needed);

        let undo = plan_live(
            &new,
            &SusfsConfig {
                uname_release: String::new(),
                ..new.clone()
            },
        );
        assert_eq!(
            undo.actions,
            [Action::SetUname("default".into(), "default".into())]
        );
        assert!(!undo.reboot_needed);
        assert_eq!(plan_live(&new, &new), LivePlan::default());
    }

    #[test]
    fn locks_bootconfig() {
        let bootconfig = "androidboot.verifiedbootstate = \"orange\"\nandroidboot.hardware = \"qcom\"\nandroidboot.verifiedbooterror = \"x\"\nandroidboot.vbmeta.device_state = \"unlocked\"\nandroidboot.hwname = \"alioth_cn\"\n";
        assert_eq!(
            locked_bootconfig(bootconfig, "alioth"),
            "androidboot.verifiedbootstate = \"green\"\nandroidboot.hardware = \"qcom\"\nandroidboot.vbmeta.device_state = \"locked\"\nandroidboot.hwname = \"alioth\"\n"
        );
        let cmdline = "console=null androidboot.verifiedbootstate=orange androidboot.verifyerrorpart=boot quiet";
        assert_eq!(
            locked_bootconfig(cmdline, ""),
            "console=null androidboot.verifiedbootstate=green quiet\n"
        );
    }

    #[test]
    fn cusrom_levels() {
        assert!(!cusrom_hides("/system/etc/init/lineage.rc", 1));
        assert!(cusrom_hides("/system/etc/init/lineage.rc", 2));
        assert!(!cusrom_hides(
            "/system/app/LineageParts/LineageParts.apk",
            4
        ));
        assert!(cusrom_hides("/system/app/LineageParts/LineageParts.apk", 5));
        assert!(!cusrom_hides("/vendor/bin/hw/vendor.lineage.power", 4));
        assert!(cusrom_hides("/vendor/bin/hw/vendor.lineage.power", 5));
        assert!(!cusrom_hides("/system/etc/hosts", 5));
    }

    #[test]
    fn finds_ksu_mounts() {
        let mountinfo = "\
22 1 253:5 / / ro,relatime shared:1 - ext4 /dev/block/dm-5 ro
2000000001 22 0:80 / /system/etc/hosts rw shared:2 - tmpfs KSU rw
2000000002 22 0:81 / /system rw shared:3 - overlay KSU rw
400 22 0:82 / /vendor/lib rw - overlay KSU rw
";
        assert_eq!(
            ksu_mounts(mountinfo, &[]),
            ["/system/etc/hosts", "/system", "/vendor/lib"]
        );
        assert_eq!(
            ksu_mounts(mountinfo, &["/system".into()]),
            ["/system/etc/hosts", "/vendor/lib"]
        );
    }
}

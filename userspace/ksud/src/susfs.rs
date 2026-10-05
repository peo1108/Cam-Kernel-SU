//! SUSFS: the kernel's version and features, and the settings the Manager keeps in
//! `susfs.json` (see `susfs_config`), applied at every boot so the susfs4ksu module
//! and its `ksu_susfs` tool are not needed. SUSFS is reached through the reboot
//! syscall with `SUSFS_MAGIC`, which the kernel only accepts from uid 0.

use std::ffi::CStr;
use std::fmt::Write as _;
use std::io::Write as _;
use std::os::unix::fs::MetadataExt;
use std::path::{Path, PathBuf};
use std::time::{Duration, Instant};

use anyhow::{Context, Result, bail};
use log::{info, warn};
use serde_json::json;

use crate::susfs_config::{
    Action, KstatEntry, OpenRedirect, REVANCED_PACKAGES, Stage, SusPathEntry, SusfsConfig,
    boot_actions, cusrom_hides, ksu_mounts, locked_bootconfig, plan_live,
};
use crate::{defs, ksu_uapi, ksucalls, utils};

const KSU_INSTALL_MAGIC1: u32 = 0xDEAD_BEEF;
const SUSFS_MAGIC: u32 = 0xFAFA_FAFA;

const CMD_SUSFS_ADD_SUS_PATH: u32 = 0x55550;
const CMD_SUSFS_ADD_SUS_PATH_LOOP: u32 = 0x55553;
const CMD_SUSFS_HIDE_SUS_MNTS_FOR_NON_SU_PROCS: u32 = 0x55561;
const CMD_SUSFS_ADD_SUS_KSTAT: u32 = 0x55570;
const CMD_SUSFS_UPDATE_SUS_KSTAT: u32 = 0x55571;
const CMD_SUSFS_ADD_SUS_KSTAT_STATICALLY: u32 = 0x55572;
const CMD_SUSFS_SET_UNAME: u32 = 0x55590;
const CMD_SUSFS_ENABLE_LOG: u32 = 0x555a0;
const CMD_SUSFS_SET_CMDLINE_OR_BOOTCONFIG: u32 = 0x555b0;
const CMD_SUSFS_ADD_OPEN_REDIRECT: u32 = 0x555c0;
const CMD_SUSFS_SHOW_VERSION: u32 = 0x555e1;
const CMD_SUSFS_SHOW_ENABLED_FEATURES: u32 = 0x555e2;
const CMD_SUSFS_SHOW_VARIANT: u32 = 0x555e3;
const CMD_SUSFS_ENABLE_AVC_LOG_SPOOFING: u32 = 0x60010;
const CMD_SUSFS_ADD_SUS_MAP: u32 = 0x60020;

const ERR_CMD_NOT_SUPPORTED: i32 = 126;

const SUSFS_MAX_LEN_PATHNAME: usize = 256;
const SUSFS_FAKE_CMDLINE_OR_BOOTCONFIG_SIZE: usize = 8192;
const SUSFS_ENABLED_FEATURES_SIZE: usize = 8192;
const SUSFS_MAX_VERSION_BUFSIZE: usize = 16;
const SUSFS_MAX_VARIANT_BUFSIZE: usize = 16;
/// `__NEW_UTS_LEN + 1`
const UTS_BUFSIZE: usize = 65;

const KSTAT_SPOOF_INO: i32 = 1 << 0;
const KSTAT_SPOOF_DEV: i32 = 1 << 1;
const KSTAT_SPOOF_NLINK: i32 = 1 << 2;
const KSTAT_SPOOF_SIZE: i32 = 1 << 3;
const KSTAT_SPOOF_ATIME_TV_SEC: i32 = 1 << 4;
const KSTAT_SPOOF_ATIME_TV_NSEC: i32 = 1 << 5;
const KSTAT_SPOOF_MTIME_TV_SEC: i32 = 1 << 6;
const KSTAT_SPOOF_MTIME_TV_NSEC: i32 = 1 << 7;
const KSTAT_SPOOF_CTIME_TV_SEC: i32 = 1 << 8;
const KSTAT_SPOOF_CTIME_TV_NSEC: i32 = 1 << 9;
const KSTAT_SPOOF_BLOCKS: i32 = 1 << 10;
const KSTAT_SPOOF_BLKSIZE: i32 = 1 << 11;
/// in the order of `KstatEntry::fields`
const KSTAT_FLAGS: [i32; 12] = [
    KSTAT_SPOOF_INO,
    KSTAT_SPOOF_DEV,
    KSTAT_SPOOF_NLINK,
    KSTAT_SPOOF_SIZE,
    KSTAT_SPOOF_ATIME_TV_SEC,
    KSTAT_SPOOF_ATIME_TV_NSEC,
    KSTAT_SPOOF_MTIME_TV_SEC,
    KSTAT_SPOOF_MTIME_TV_NSEC,
    KSTAT_SPOOF_CTIME_TV_SEC,
    KSTAT_SPOOF_CTIME_TV_NSEC,
    KSTAT_SPOOF_BLOCKS,
    KSTAT_SPOOF_BLKSIZE,
];
/// what `add_sus_kstat` / `update_sus_kstat` spoof: everything but nlink and size
const KSTAT_AUTO_SPOOF: i32 = KSTAT_SPOOF_INO
    | KSTAT_SPOOF_DEV
    | KSTAT_SPOOF_ATIME_TV_SEC
    | KSTAT_SPOOF_ATIME_TV_NSEC
    | KSTAT_SPOOF_MTIME_TV_SEC
    | KSTAT_SPOOF_MTIME_TV_NSEC
    | KSTAT_SPOOF_CTIME_TV_SEC
    | KSTAT_SPOOF_CTIME_TV_NSEC
    | KSTAT_SPOOF_BLKSIZE
    | KSTAT_SPOOF_BLOCKS;

/// `umount2` flag for the kernel umount list
const MNT_DETACH: u32 = 2;

/// the susfs4ksu module applies the same settings; with it active ksud leaves SUSFS to it
const SUSFS_MODULE_DIR: &str = "/data/adb/modules/susfs4ksu";
const SUSFS_LOG_PATH: &str = "/data/adb/ksu/log/susfs.log";

#[repr(C)]
struct SusfsEnabledFeatures {
    enabled_features: [u8; SUSFS_ENABLED_FEATURES_SIZE],
    err: i32,
}

#[repr(C)]
struct SusfsVariant {
    susfs_variant: [u8; SUSFS_MAX_VARIANT_BUFSIZE],
    err: i32,
}

#[repr(C)]
struct SusfsVersion {
    susfs_version: [u8; SUSFS_MAX_VERSION_BUFSIZE],
    err: i32,
}

/// `st_susfs_sus_path` and `st_susfs_sus_map`
#[repr(C)]
struct SusfsPath {
    target_pathname: [u8; SUSFS_MAX_LEN_PATHNAME],
    err: i32,
}

/// `st_susfs_log`, `st_susfs_avc_log_spoofing` and `st_susfs_hide_sus_mnts_for_non_su_procs`
#[repr(C)]
struct SusfsSwitch {
    enabled: bool,
    err: i32,
}

#[repr(C)]
struct SusfsUname {
    release: [u8; UTS_BUFSIZE],
    version: [u8; UTS_BUFSIZE],
    err: i32,
}

#[repr(C)]
struct SusfsBootconfig {
    fake_cmdline_or_bootconfig: [u8; SUSFS_FAKE_CMDLINE_OR_BOOTCONFIG_SIZE],
    err: i32,
}

#[repr(C)]
struct SusfsOpenRedirect {
    target_pathname: [u8; SUSFS_MAX_LEN_PATHNAME],
    redirected_pathname: [u8; SUSFS_MAX_LEN_PATHNAME],
    uid_scheme: i32,
    err: i32,
}

#[repr(C)]
struct SusfsKstat {
    is_statically: bool,
    target_ino: u64,
    target_pathname: [u8; SUSFS_MAX_LEN_PATHNAME],
    spoofed_ino: u64,
    spoofed_dev: u64,
    spoofed_nlink: u32,
    spoofed_size: i64,
    spoofed_atime_tv_sec: i64,
    spoofed_atime_tv_nsec: u64,
    spoofed_mtime_tv_sec: i64,
    spoofed_mtime_tv_nsec: u64,
    spoofed_ctime_tv_sec: i64,
    spoofed_ctime_tv_nsec: u64,
    spoofed_blocks: i64,
    spoofed_blksize: i64,
    flags: i32,
    err: i32,
}

pub struct SusfsInfo {
    pub version: String,
    pub variant: String,
    pub features: Vec<String>,
}

/// True when the running kernel was built with `CONFIG_KSU_SUSFS`.
pub fn is_built_in() -> bool {
    ksucalls::get_info().flags & ksu_uapi::KSU_GET_INFO_FLAG_SUSFS != 0
}

fn susfs_call<T>(cmd: u32, info: &mut T) {
    unsafe {
        libc::syscall(
            libc::SYS_reboot,
            KSU_INSTALL_MAGIC1,
            SUSFS_MAGIC,
            cmd,
            std::ptr::from_mut(info),
        );
    }
}

fn check_err(cmd: u32, err: i32) -> Result<()> {
    match err {
        0 => Ok(()),
        ERR_CMD_NOT_SUPPORTED => bail!("SUSFS command {cmd:#x} is not supported by this kernel"),
        _ => bail!(
            "SUSFS command {cmd:#x} failed: {}",
            std::io::Error::from_raw_os_error(err.abs())
        ),
    }
}

fn c_buf_to_string(buf: &[u8]) -> String {
    CStr::from_bytes_until_nul(buf)
        .map_or_else(|_| String::from_utf8_lossy(buf), CStr::to_string_lossy)
        .trim()
        .to_string()
}

/// `s` NUL-terminated in a buffer of `N`, refused when it does not fit.
fn c_buf<const N: usize>(s: &str) -> Result<[u8; N]> {
    let bytes = s.as_bytes();
    if bytes.len() >= N {
        bail!("'{s}' is longer than {} bytes", N - 1);
    }
    if bytes.contains(&0) {
        bail!("'{s}' contains a NUL byte");
    }
    let mut buf = [0u8; N];
    buf[..bytes.len()].copy_from_slice(bytes);
    Ok(buf)
}

pub fn get_info() -> Result<SusfsInfo> {
    if !is_built_in() {
        bail!("SUSFS is not built into this kernel");
    }

    let mut version = SusfsVersion {
        susfs_version: [0; SUSFS_MAX_VERSION_BUFSIZE],
        err: ERR_CMD_NOT_SUPPORTED,
    };
    susfs_call(CMD_SUSFS_SHOW_VERSION, &mut version);
    check_err(CMD_SUSFS_SHOW_VERSION, version.err)?;

    let mut variant = SusfsVariant {
        susfs_variant: [0; SUSFS_MAX_VARIANT_BUFSIZE],
        err: ERR_CMD_NOT_SUPPORTED,
    };
    susfs_call(CMD_SUSFS_SHOW_VARIANT, &mut variant);
    check_err(CMD_SUSFS_SHOW_VARIANT, variant.err)?;

    let mut features = Box::new(SusfsEnabledFeatures {
        enabled_features: [0; SUSFS_ENABLED_FEATURES_SIZE],
        err: ERR_CMD_NOT_SUPPORTED,
    });
    susfs_call(CMD_SUSFS_SHOW_ENABLED_FEATURES, features.as_mut());
    check_err(CMD_SUSFS_SHOW_ENABLED_FEATURES, features.err)?;

    let features = c_buf_to_string(&features.enabled_features)
        .lines()
        .map(|line| {
            line.trim()
                .trim_start_matches("CONFIG_KSU_SUSFS_")
                .to_string()
        })
        .filter(|line| !line.is_empty())
        .collect();

    Ok(SusfsInfo {
        version: c_buf_to_string(&version.susfs_version),
        variant: c_buf_to_string(&variant.susfs_variant),
        features,
    })
}

pub fn print_info(json: bool) -> Result<()> {
    if json {
        let value = match get_info() {
            Ok(info) => json!({
                "enabled": true,
                "version": info.version,
                "variant": info.variant,
                "features": info.features,
            }),
            Err(e) => json!({
                "enabled": false,
                "error": e.to_string(),
            }),
        };
        println!("{value}");
        return Ok(());
    }

    let info = get_info()?;
    println!("version: {}", info.version);
    println!("variant: {}", info.variant);
    println!("features:");
    for feature in &info.features {
        println!("  {feature}");
    }
    Ok(())
}

// ---------------------------------------------------------------------------
// kernel calls

fn set_switch(cmd: u32, enabled: bool) -> Result<()> {
    let mut info = SusfsSwitch {
        enabled,
        err: ERR_CMD_NOT_SUPPORTED,
    };
    susfs_call(cmd, &mut info);
    check_err(cmd, info.err)
}

fn add_path(cmd: u32, path: &str) -> Result<()> {
    let mut info = SusfsPath {
        target_pathname: c_buf(path)?,
        err: ERR_CMD_NOT_SUPPORTED,
    };
    susfs_call(cmd, &mut info);
    check_err(cmd, info.err)
}

fn set_uname(release: &str, version: &str) -> Result<()> {
    let mut info = SusfsUname {
        release: c_buf(release)?,
        version: c_buf(version)?,
        err: ERR_CMD_NOT_SUPPORTED,
    };
    susfs_call(CMD_SUSFS_SET_UNAME, &mut info);
    check_err(CMD_SUSFS_SET_UNAME, info.err)
}

fn set_bootconfig(content: &str) -> Result<()> {
    let mut info = Box::new(SusfsBootconfig {
        fake_cmdline_or_bootconfig: c_buf(content)?,
        err: ERR_CMD_NOT_SUPPORTED,
    });
    susfs_call(CMD_SUSFS_SET_CMDLINE_OR_BOOTCONFIG, info.as_mut());
    check_err(CMD_SUSFS_SET_CMDLINE_OR_BOOTCONFIG, info.err)
}

fn add_open_redirect(target: &str, redirect: &str, uid_scheme: i32) -> Result<()> {
    let mut info = SusfsOpenRedirect {
        target_pathname: c_buf(target)?,
        redirected_pathname: c_buf(redirect)?,
        uid_scheme,
        err: ERR_CMD_NOT_SUPPORTED,
    };
    susfs_call(CMD_SUSFS_ADD_OPEN_REDIRECT, &mut info);
    check_err(CMD_SUSFS_ADD_OPEN_REDIRECT, info.err)
}

/// The kstat of `path` as the kernel wants it, spoofing nothing yet.
fn kstat_of(path: &str) -> Result<SusfsKstat> {
    let meta = std::fs::metadata(path).with_context(|| format!("stat {path}"))?;
    Ok(SusfsKstat {
        is_statically: false,
        target_ino: meta.ino(),
        target_pathname: c_buf(path)?,
        spoofed_ino: meta.ino(),
        spoofed_dev: meta.dev(),
        spoofed_nlink: meta.nlink() as u32,
        spoofed_size: meta.size() as i64,
        spoofed_atime_tv_sec: meta.atime(),
        spoofed_atime_tv_nsec: meta.atime_nsec() as u64,
        spoofed_mtime_tv_sec: meta.mtime(),
        spoofed_mtime_tv_nsec: meta.mtime_nsec() as u64,
        spoofed_ctime_tv_sec: meta.ctime(),
        spoofed_ctime_tv_nsec: meta.ctime_nsec() as u64,
        spoofed_blocks: meta.blocks() as i64,
        spoofed_blksize: meta.blksize() as i64,
        flags: 0,
        err: ERR_CMD_NOT_SUPPORTED,
    })
}

/// `add_sus_kstat` (before a bind mount) or `update_sus_kstat` (after it).
fn sus_kstat_dynamic(cmd: u32, path: &str) -> Result<()> {
    let path = real_path(path)?;
    let mut info = kstat_of(&path)?;
    info.flags = KSTAT_AUTO_SPOOF;
    susfs_call(cmd, &mut info);
    check_err(cmd, info.err)
}

/// `add_sus_kstat_statically`: `values` in the order of `KstatEntry::fields`, `None` keeps the real one.
fn sus_kstat_static(path: &str, values: &[Option<i64>; 12]) -> Result<()> {
    let path = real_path(path)?;
    let mut info = kstat_of(&path)?;
    info.is_statically = true;
    for (value, flag) in values.iter().zip(KSTAT_FLAGS) {
        let Some(v) = *value else { continue };
        info.flags |= flag;
        match flag {
            KSTAT_SPOOF_INO => info.spoofed_ino = v as u64,
            KSTAT_SPOOF_DEV => info.spoofed_dev = v as u64,
            KSTAT_SPOOF_NLINK => info.spoofed_nlink = v as u32,
            KSTAT_SPOOF_SIZE => info.spoofed_size = v,
            KSTAT_SPOOF_ATIME_TV_SEC => info.spoofed_atime_tv_sec = v,
            KSTAT_SPOOF_ATIME_TV_NSEC => info.spoofed_atime_tv_nsec = v as u64,
            KSTAT_SPOOF_MTIME_TV_SEC => info.spoofed_mtime_tv_sec = v,
            KSTAT_SPOOF_MTIME_TV_NSEC => info.spoofed_mtime_tv_nsec = v as u64,
            KSTAT_SPOOF_CTIME_TV_SEC => info.spoofed_ctime_tv_sec = v,
            KSTAT_SPOOF_CTIME_TV_NSEC => info.spoofed_ctime_tv_nsec = v as u64,
            KSTAT_SPOOF_BLOCKS => info.spoofed_blocks = v,
            _ => info.spoofed_blksize = v,
        }
    }
    susfs_call(CMD_SUSFS_ADD_SUS_KSTAT_STATICALLY, &mut info);
    check_err(CMD_SUSFS_ADD_SUS_KSTAT_STATICALLY, info.err)
}

fn real_path(path: &str) -> Result<String> {
    let real = std::fs::canonicalize(path).with_context(|| format!("realpath {path}"))?;
    Ok(real.to_string_lossy().into_owned())
}

// ---------------------------------------------------------------------------
// settings file and log

fn config_path() -> &'static Path {
    Path::new(defs::SUSFS_CONFIG_PATH)
}

pub fn load_config() -> SusfsConfig {
    std::fs::read_to_string(config_path())
        .ok()
        .and_then(|s| serde_json::from_str(&s).ok())
        .map(|v| SusfsConfig::from_json(&v))
        .unwrap_or_default()
}

fn save_config(config: &SusfsConfig) -> Result<()> {
    let path = config_path();
    utils::ensure_dir_exists(defs::WORKING_DIR)?;
    let tmp = path.with_extension("json.tmp");
    let mut file = std::fs::File::create(&tmp)
        .with_context(|| format!("Failed to create {}", tmp.display()))?;
    file.write_all(serde_json::to_string_pretty(&config.to_json())?.as_bytes())?;
    file.sync_all()?;
    drop(file);
    std::fs::rename(&tmp, path)
        .with_context(|| format!("Failed to rename {} to {}", tmp.display(), path.display()))?;
    Ok(())
}

/// The susfs4ksu module is installed and enabled: it drives SUSFS, so ksud stays out of its way.
pub fn module_active() -> bool {
    let dir = Path::new(SUSFS_MODULE_DIR);
    dir.is_dir()
        && !dir.join(defs::DISABLE_FILE_NAME).exists()
        && !dir.join(defs::REMOVE_FILE_NAME).exists()
}

fn log_line(line: &str) {
    info!("susfs: {line}");
    let _ = utils::ensure_dir_exists(defs::LOG_DIR);
    if let Ok(mut file) = std::fs::OpenOptions::new()
        .create(true)
        .append(true)
        .open(SUSFS_LOG_PATH)
    {
        let time = chrono::Local::now().format("%H:%M:%S");
        let _ = writeln!(file, "{time} {line}");
    }
}

// ---------------------------------------------------------------------------
// actions

/// How an action went; bulk actions report counts.
struct Outcome {
    ok: usize,
    failed: Vec<String>,
}

impl Outcome {
    const fn new() -> Self {
        Self {
            ok: 0,
            failed: Vec::new(),
        }
    }

    fn record(&mut self, what: &str, result: Result<()>) {
        match result {
            Ok(()) => self.ok += 1,
            Err(e) => self.failed.push(format!("{what}: {e:#}")),
        }
    }
}

fn wait_for(path: &Path, secs: u32) -> bool {
    let deadline = Instant::now() + Duration::from_secs(u64::from(secs));
    while !path.exists() {
        if Instant::now() >= deadline {
            return false;
        }
        std::thread::sleep(Duration::from_secs(1));
    }
    true
}

fn sus_path(entry: &SusPathEntry, looped: bool) -> Result<()> {
    if entry.wait > 0 {
        wait_for(Path::new(&entry.path), entry.wait);
    }
    if looped {
        // add_sus_path_loop takes the path as given and does not need it to exist
        add_path(CMD_SUSFS_ADD_SUS_PATH_LOOP, &entry.path)
    } else {
        add_path(CMD_SUSFS_ADD_SUS_PATH, &real_path(&entry.path)?)
    }
}

fn open_redirect(r: &OpenRedirect) -> Result<()> {
    let target = real_path(&r.target)?;
    let redirect = real_path(&r.redirect)?;
    // the redirected file reports the original's identity and times, like the module does
    let meta = std::fs::metadata(&target)?;
    add_open_redirect(&target, &redirect, r.uid_scheme)?;
    let values = [
        Some(meta.ino() as i64),
        Some(meta.dev() as i64),
        None,
        None,
        Some(meta.atime()),
        Some(0),
        Some(meta.mtime()),
        Some(0),
        Some(meta.ctime()),
        Some(0),
        Some(meta.blocks() as i64),
        Some(meta.blksize() as i64),
    ];
    sus_kstat_static(&redirect, &values)
}

fn kstat_entry(k: &KstatEntry) -> Result<()> {
    let values = k.values().map_err(anyhow::Error::msg)?;
    sus_kstat_static(&k.path, &values)
}

/// Where the edited copies of ROM files live: a tmpfs, so nothing is left after reboot.
fn scratch_dir() -> PathBuf {
    let base = if Path::new("/mnt/vendor").is_dir() {
        "/mnt/vendor"
    } else {
        "/mnt"
    };
    Path::new(base).join("ksu_susfs")
}

/// Copy owner, mode and SELinux context of `from` onto `to`.
fn clone_perm(to: &Path, from: &Path) -> Result<()> {
    let meta = std::fs::metadata(from)?;
    std::fs::set_permissions(to, meta.permissions())?;
    std::os::unix::fs::chown(to, Some(meta.uid()), Some(meta.gid()))?;
    if let Ok(con) = crate::restorecon::lgetfilecon(from) {
        crate::restorecon::lsetfilecon(to, &con)?;
    }
    Ok(())
}

/// Bind mount a copy of `file` without its lineage lines over it, keeping its stat.
fn hide_rom_file(file: &str) -> Result<bool> {
    let Ok(content) = std::fs::read_to_string(file) else {
        return Ok(false);
    };
    if !content.contains("lineage") {
        return Ok(false);
    }
    let dir = scratch_dir();
    std::fs::create_dir_all(&dir)?;
    let name = Path::new(file).file_name().context("no file name")?;
    let copy = dir.join(name);
    let filtered: String =
        content
            .lines()
            .filter(|l| !l.contains("lineage"))
            .fold(String::new(), |mut out, l| {
                let _ = writeln!(out, "{l}");
                out
            });
    std::fs::write(&copy, filtered)?;
    clone_perm(&copy, Path::new(file))?;
    sus_kstat_dynamic(CMD_SUSFS_ADD_SUS_KSTAT, file)?;
    rustix::mount::mount_bind(&copy, file).with_context(|| format!("bind mount over {file}"))?;
    sus_kstat_dynamic(CMD_SUSFS_UPDATE_SUS_KSTAT, file)?;
    Ok(true)
}

/// Every file and directory under `roots`, without following symlinks; `skip` prunes a subtree.
fn walk(roots: &[&str], mut visit: impl FnMut(&Path, bool) -> bool) {
    let mut stack: Vec<PathBuf> = roots.iter().map(PathBuf::from).collect();
    while let Some(dir) = stack.pop() {
        let Ok(entries) = std::fs::read_dir(&dir) else {
            continue;
        };
        for entry in entries.flatten() {
            let Ok(kind) = entry.file_type() else {
                continue;
            };
            let path = entry.path();
            let is_dir = kind.is_dir();
            if !(is_dir || kind.is_file()) {
                continue;
            }
            // visit returns false when the path is taken care of with all it holds
            if visit(&path, is_dir) && is_dir {
                stack.push(path);
            }
        }
    }
}

const ROM_ROOTS: [&str; 4] = ["/system", "/vendor", "/system_ext", "/product"];

fn hide_found(outcome: &mut Outcome, path: &Path) -> bool {
    let p = path.to_string_lossy();
    outcome.record(&p, add_path(CMD_SUSFS_ADD_SUS_PATH, &p));
    false
}

fn hide_cusrom(level: u8) -> Outcome {
    let mut outcome = Outcome::new();
    walk(&ROM_ROOTS, |path, _| {
        if cusrom_hides(&path.to_string_lossy(), level) {
            hide_found(&mut outcome, path)
        } else {
            true
        }
    });
    outcome
}

fn hide_gapps() -> Outcome {
    let mut outcome = Outcome::new();
    walk(&ROM_ROOTS, |path, is_dir| {
        let name = path
            .file_name()
            .map(|n| n.to_string_lossy().to_ascii_lowercase())
            .unwrap_or_default();
        let hit = name.contains("gapps") && (is_dir || name.ends_with("xml"));
        if hit {
            hide_found(&mut outcome, path)
        } else {
            true
        }
    });
    outcome
}

fn hide_loops() -> Outcome {
    let mut outcome = Outcome::new();
    let Ok(entries) = std::fs::read_dir("/proc/fs/jbd2") else {
        return outcome;
    };
    for entry in entries.flatten() {
        let name = entry.file_name().to_string_lossy().into_owned();
        let Some(device) = name.strip_suffix("-8").filter(|d| d.starts_with("loop")) else {
            continue;
        };
        for path in [
            format!("/proc/fs/jbd2/{name}"),
            format!("/proc/fs/ext4/{device}"),
        ] {
            outcome.record(&path, add_path(CMD_SUSFS_ADD_SUS_PATH, &path));
        }
    }
    outcome
}

fn command_lines(program: &str, args: &[&str]) -> Vec<String> {
    std::process::Command::new(program)
        .args(args)
        .output()
        .map(|o| {
            String::from_utf8_lossy(&o.stdout)
                .lines()
                .map(str::to_owned)
                .collect()
        })
        .unwrap_or_default()
}

fn hide_revanced(boot: bool) -> Outcome {
    let mut outcome = Outcome::new();
    if boot {
        // ReVanced's mounts show up a little after boot completed
        for _ in 0..15 {
            if std::fs::read_to_string("/proc/self/mounts").is_ok_and(|m| m.contains("youtube")) {
                break;
            }
            std::thread::sleep(Duration::from_secs(1));
        }
    }
    for package in REVANCED_PACKAGES {
        for line in command_lines("pm", &["path", package]) {
            if let Some(path) = line.strip_prefix("package:") {
                outcome.record(path, ksucalls::umount_list_add(path.trim(), MNT_DETACH));
            }
        }
    }
    outcome
}

fn auto_try_umount(skip: &[String], rehide_mnts: bool) -> Outcome {
    let mut outcome = Outcome::new();
    // mounts hidden from us would be missed
    if rehide_mnts {
        let _ = set_switch(CMD_SUSFS_HIDE_SUS_MNTS_FOR_NON_SU_PROCS, false);
    }
    let mountinfo = std::fs::read_to_string("/proc/1/mountinfo").unwrap_or_default();
    for mnt in ksu_mounts(&mountinfo, skip) {
        outcome.record(&mnt, ksucalls::umount_list_add(&mnt, MNT_DETACH));
    }
    if rehide_mnts {
        outcome.record(
            "hide sus mounts",
            set_switch(CMD_SUSFS_HIDE_SUS_MNTS_FOR_NON_SU_PROCS, true),
        );
    }
    outcome
}

fn emulate_vold_app_data(looped: bool) -> Outcome {
    let mut outcome = Outcome::new();
    for line in command_lines("pm", &["list", "packages", "-3"]) {
        let Some(package) = line.strip_prefix("package:") else {
            continue;
        };
        let path = format!("/sdcard/Android/data/{}", package.trim());
        // an app without a data dir is nothing to hide
        if !looped && !Path::new(&path).exists() {
            continue;
        }
        let entry = SusPathEntry {
            path: path.clone(),
            wait: 0,
        };
        outcome.record(&path, sus_path(&entry, looped));
    }
    outcome
}

/// The verified boot props the susfs4ksu module sets in service.sh.
fn spoof_props(vbmeta_size: u32, vbmeta_digest: &str) -> Outcome {
    let mut outcome = Outcome::new();
    let get = |name: &str| utils::getprop(name).unwrap_or_default();
    let mut set = |name: &str, value: &str| {
        outcome.record(name, crate::resetprop::set_prop(name, value));
    };
    // set only when missing
    let size = vbmeta_size.to_string();
    for (name, value) in [
        ("ro.boot.vbmeta.invalidate_on_error", "yes"),
        ("ro.boot.vbmeta.avb_version", "1.2"),
        ("ro.boot.vbmeta.hash_alg", "sha256"),
        ("ro.boot.vbmeta.size", size.as_str()),
        ("ro.boot.vbmeta.digest", vbmeta_digest),
    ] {
        if !value.is_empty() && get(name).is_empty() {
            set(name, value);
        }
    }
    // set when missing or different
    for (name, value) in [
        ("ro.boot.vbmeta.device_state", "locked"),
        ("ro.boot.verifiedbootstate", "green"),
        ("ro.boot.flash.locked", "1"),
        ("ro.boot.veritymode", "enforcing"),
        ("ro.boot.warranty_bit", "0"),
    ] {
        if get(name) != value {
            set(name, value);
        }
    }
    // set only when present and different
    for (name, value) in [
        ("vendor.boot.vbmeta.device_state", "locked"),
        ("vendor.boot.verifiedbootstate", "green"),
        ("ro.warranty_bit", "0"),
        ("ro.debuggable", "0"),
        ("ro.force.debuggable", "0"),
        ("ro.secure", "1"),
        ("ro.adb.secure", "1"),
        ("ro.build.type", "user"),
        ("ro.build.tags", "release-keys"),
        ("ro.vendor.boot.warranty_bit", "0"),
        ("ro.vendor.warranty_bit", "0"),
        ("sys.oem_unlock_allowed", "0"),
        ("ro.secureboot.lockstate", "locked"),
        ("ro.boot.realmebootstate", "green"),
        ("ro.boot.realme.lockstate", "1"),
        ("ro.crypto.state", "encrypted"),
    ] {
        let current = get(name);
        if !current.is_empty() && current != value {
            set(name, value);
        }
    }
    // booted from recovery, or a cloud phone
    for name in ["ro.bootmode", "ro.boot.bootmode", "vendor.boot.bootmode"] {
        if get(name).contains("recovery") {
            set(name, "unknown");
        }
    }
    if !get("ro.kernel.qemu").is_empty() {
        set("ro.kernel.qemu", "");
    }
    outcome
}

fn auto_bootconfig() -> Result<String> {
    let bootconfig = std::fs::read_to_string("/proc/bootconfig").unwrap_or_default();
    let current = if bootconfig.trim().is_empty() {
        std::fs::read_to_string("/proc/cmdline").context("read /proc/cmdline")?
    } else {
        bootconfig
    };
    let product = utils::getprop("ro.product.name").unwrap_or_default();
    Ok(locked_bootconfig(&current, &product))
}

fn describe(action: &Action) -> String {
    match action {
        Action::EnableLog(on) => format!("enable_log {}", u8::from(*on)),
        Action::AvcLogSpoofing(on) => format!("enable_avc_log_spoofing {}", u8::from(*on)),
        Action::HideSusMnts(on) => format!("hide_sus_mnts_for_non_su_procs {}", u8::from(*on)),
        Action::SetUname(r, v) => format!("set_uname '{r}' '{v}'"),
        Action::SetBootconfig(None) => "set_cmdline_or_bootconfig (auto)".to_owned(),
        Action::SetBootconfig(Some(_)) => "set_cmdline_or_bootconfig (custom)".to_owned(),
        Action::SusPath(e) => format!("add_sus_path {}", e.path),
        Action::SusPathLoop(e) => format!("add_sus_path_loop {}", e.path),
        Action::SusMap(p) => format!("add_sus_map {p}"),
        Action::TryUmount(p) => format!("kernel umount add {p}"),
        Action::TryUmountDel(p) => format!("kernel umount del {p}"),
        Action::OpenRedirect(r) => format!(
            "add_open_redirect {} -> {} ({})",
            r.target, r.redirect, r.uid_scheme
        ),
        Action::SusKstat(k) => format!("add_sus_kstat_statically {}", k.path),
        Action::SpoofProps { .. } => "spoof boot props".to_owned(),
        Action::HideLoops => "hide loops".to_owned(),
        Action::HideRomFile(f) => format!("hide lineage in {f}"),
        Action::HideCusrom(level) => format!("hide custom ROM (level {level})"),
        Action::HideGapps => "hide gapps".to_owned(),
        Action::HideRevanced => "hide ReVanced".to_owned(),
        Action::AutoTryUmount { .. } => "auto try_umount".to_owned(),
        Action::WaitForStorage(secs) => format!("wait for /sdcard/Android/data ({secs}s)"),
        Action::EmulateVoldAppData { loop_ } => {
            format!(
                "emulate vold app data ({})",
                if *loop_ { "sus_path_loop" } else { "sus_path" }
            )
        }
    }
}

/// Run `actions` in order, logging each; `boot` allows the waits only boot needs.
/// Returns the failures.
pub fn run_actions(actions: &[Action], boot: bool) -> Vec<String> {
    let mut failures = Vec::new();
    for action in actions {
        let what = describe(action);
        let outcome = match action {
            Action::EnableLog(on) => single(&what, set_switch(CMD_SUSFS_ENABLE_LOG, *on)),
            Action::AvcLogSpoofing(on) => {
                single(&what, set_switch(CMD_SUSFS_ENABLE_AVC_LOG_SPOOFING, *on))
            }
            Action::HideSusMnts(on) => single(
                &what,
                set_switch(CMD_SUSFS_HIDE_SUS_MNTS_FOR_NON_SU_PROCS, *on),
            ),
            Action::SetUname(r, v) => single(&what, set_uname(r, v)),
            Action::SetBootconfig(content) => single(
                &what,
                content
                    .as_ref()
                    .map_or_else(auto_bootconfig, |c| Ok(c.clone()))
                    .and_then(|c| set_bootconfig(&c)),
            ),
            Action::SusPath(e) => single(&what, sus_path(e, false)),
            Action::SusPathLoop(e) => single(&what, sus_path(e, true)),
            Action::SusMap(p) => single(&what, add_path(CMD_SUSFS_ADD_SUS_MAP, p)),
            Action::TryUmount(p) => single(&what, ksucalls::umount_list_add(p, MNT_DETACH)),
            Action::TryUmountDel(p) => single(&what, ksucalls::umount_list_del(p)),
            Action::OpenRedirect(r) => single(&what, open_redirect(r)),
            Action::SusKstat(k) => single(&what, kstat_entry(k)),
            Action::SpoofProps {
                vbmeta_size,
                vbmeta_digest,
            } => spoof_props(*vbmeta_size, vbmeta_digest),
            Action::HideLoops => hide_loops(),
            Action::HideRomFile(f) => match hide_rom_file(f) {
                Ok(false) => continue,
                result => single(&what, result.map(|_| ())),
            },
            Action::HideCusrom(level) => hide_cusrom(*level),
            Action::HideGapps => hide_gapps(),
            Action::HideRevanced => hide_revanced(boot),
            Action::AutoTryUmount { skip, rehide_mnts } => auto_try_umount(skip, *rehide_mnts),
            Action::WaitForStorage(secs) => {
                let ready = !boot || wait_for(Path::new("/sdcard/Android/data"), *secs);
                single(
                    &what,
                    if ready {
                        Ok(())
                    } else {
                        Err(anyhow::anyhow!("timed out"))
                    },
                )
            }
            Action::EmulateVoldAppData { loop_ } => emulate_vold_app_data(*loop_),
        };
        match (outcome.ok, outcome.failed.len()) {
            (_, 0) => log_line(&format!("ok   {what} ({})", outcome.ok)),
            (ok, n) => {
                log_line(&format!("FAIL {what}: {ok} ok, {n} failed"));
                for f in &outcome.failed {
                    log_line(&format!("       {f}"));
                }
            }
        }
        failures.extend(outcome.failed);
    }
    failures
}

fn single(what: &str, result: Result<()>) -> Outcome {
    let mut outcome = Outcome::new();
    outcome.record(what, result);
    outcome
}

// ---------------------------------------------------------------------------
// boot and Manager entry points

/// Apply the settings for one boot stage. Never fails the boot: problems go to the log.
pub fn apply_stage(stage: Stage) {
    if !is_built_in() {
        return;
    }
    if stage == Stage::PostFsData {
        let _ = std::fs::remove_file(SUSFS_LOG_PATH);
    }
    if module_active() {
        log_line(&format!(
            "{stage:?}: susfs4ksu module is active, leaving SUSFS to it"
        ));
        return;
    }
    if utils::is_safe_mode() {
        log_line(&format!("{stage:?}: safe mode, skipped"));
        return;
    }
    let config = load_config();
    let actions = boot_actions(&config, stage);
    if actions.is_empty() {
        return;
    }
    log_line(&format!("== {stage:?}"));
    if stage == Stage::BootCompleted {
        // waits for storage and walks the system partitions: off the boot path
        match utils::create_daemon(false) {
            Ok(true) => {
                run_actions(&actions, true);
                log_line("== done");
                std::process::exit(0);
            }
            Ok(false) => {}
            Err(e) => warn!("susfs: fork failed: {e:#}"),
        }
    } else {
        run_actions(&actions, true);
    }
}

/// `ksud susfs config`: the settings and whether ksud applies them.
pub fn print_config() {
    let value = json!({
        "config": load_config().to_json(),
        "moduleActive": module_active(),
        "saved": config_path().exists(),
    });
    println!("{value}");
}

/// `ksud susfs set-config <file>`: save the settings and apply what can be applied now.
pub fn set_config(file: &str) -> Result<()> {
    let raw = if file == "-" {
        std::io::read_to_string(std::io::stdin())?
    } else {
        std::fs::read_to_string(file).with_context(|| format!("read {file}"))?
    };
    let value: serde_json::Value = serde_json::from_str(&raw).context("settings are not JSON")?;
    println!("{}", save_and_apply(&SusfsConfig::from_json(&value))?);
    Ok(())
}

/// Saves `new` and applies what SUSFS can take now; the JSON says what happened.
pub fn save_and_apply(new: &SusfsConfig) -> Result<serde_json::Value> {
    let errors = new.validate();
    if !errors.is_empty() {
        return Ok(
            json!({ "saved": false, "errors": errors, "failed": [], "rebootNeeded": false }),
        );
    }

    let old = load_config();
    save_config(new)?;
    let module = module_active();
    let (failed, reboot_needed) = if module || !is_built_in() {
        (Vec::new(), false)
    } else {
        let plan = plan_live(&old, new);
        if !plan.actions.is_empty() {
            log_line("== settings changed");
        }
        (run_actions(&plan.actions, false), plan.reboot_needed)
    };
    Ok(json!({
        "saved": true,
        "errors": [],
        "failed": failed,
        "rebootNeeded": reboot_needed,
        "moduleActive": module,
    }))
}

/// `ksud susfs bootconfig`: what auto mode would hand the kernel on this device.
pub fn print_auto_bootconfig() -> Result<()> {
    print!("{}", auto_bootconfig()?);
    Ok(())
}

/// `ksud susfs log`: what the last boot and the last changes did.
pub fn print_log() {
    print!(
        "{}",
        std::fs::read_to_string(SUSFS_LOG_PATH).unwrap_or_default()
    );
}

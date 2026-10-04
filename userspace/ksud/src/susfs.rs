//! Read-only view of SUSFS, mirroring the `show` commands of simonpunk's
//! `ksu_susfs` tool. SUSFS is reached through the reboot syscall with
//! `SUSFS_MAGIC`, which the kernel only accepts from uid 0.

use std::ffi::CStr;

use anyhow::{Result, bail};

use crate::{ksu_uapi, ksucalls};

const KSU_INSTALL_MAGIC1: u32 = 0xDEAD_BEEF;
const SUSFS_MAGIC: u32 = 0xFAFA_FAFA;

const CMD_SUSFS_SHOW_VERSION: u32 = 0x555e1;
const CMD_SUSFS_SHOW_ENABLED_FEATURES: u32 = 0x555e2;
const CMD_SUSFS_SHOW_VARIANT: u32 = 0x555e3;

const ERR_CMD_NOT_SUPPORTED: i32 = 126;

const SUSFS_ENABLED_FEATURES_SIZE: usize = 8192;
const SUSFS_MAX_VERSION_BUFSIZE: usize = 16;
const SUSFS_MAX_VARIANT_BUFSIZE: usize = 16;

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
        _ => bail!("SUSFS command {cmd:#x} failed: {err}"),
    }
}

fn c_buf_to_string(buf: &[u8]) -> String {
    CStr::from_bytes_until_nul(buf)
        .map_or_else(|_| String::from_utf8_lossy(buf), CStr::to_string_lossy)
        .trim()
        .to_string()
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
            Ok(info) => serde_json::json!({
                "enabled": true,
                "version": info.version,
                "variant": info.variant,
                "features": info.features,
            }),
            Err(e) => serde_json::json!({
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

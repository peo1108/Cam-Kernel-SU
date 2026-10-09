use crate::defs;
use anyhow::Result;
use jwalk::{Parallelism::Serial, WalkDir};
use std::path::Path;

use anyhow::{Context, Ok};
use extattr::{Flags as XattrFlags, lsetxattr};

pub const SYSTEM_CON: &str = "u:object_r:system_file:s0";
pub const CAM_CON: &str = "u:object_r:cam_file:s0";
/// what a kernel from before the Cam rename knows
pub const KSU_CON: &str = "u:object_r:ksu_file:s0";
pub const UNLABEL_CON: &str = "u:object_r:unlabeled:s0";

const SELINUX_XATTR: &str = "security.selinux";

pub fn lsetfilecon<P: AsRef<Path>>(path: P, con: &str) -> Result<()> {
    lsetxattr(&path, SELINUX_XATTR, con, XattrFlags::empty()).with_context(|| {
        format!(
            "Failed to change SELinux context for {}",
            path.as_ref().display()
        )
    })?;
    Ok(())
}

/// Labels one of our files; uses the KernelSU label on a kernel without cam_file.
/// Asks the policy first: with mac_admin, setting an unknown label succeeds and
/// leaves a file init cannot run after a reboot.
pub fn set_su_file_con<P: AsRef<Path>>(path: P) -> Result<()> {
    let con = if is_context_valid(CAM_CON) {
        CAM_CON
    } else {
        KSU_CON
    };
    lsetfilecon(path, con)
}

/// Whether the running kernel is one with the cam domain, not a KernelSU one.
pub fn kernel_has_cam_domain() -> bool {
    is_context_valid(CAM_CON)
}

/// Same check as libselinux security_check_context: the write fails for a context the policy lacks.
fn is_context_valid(con: &str) -> bool {
    let mut buf = con.as_bytes().to_vec();
    buf.push(0);
    std::fs::OpenOptions::new()
        .write(true)
        .open("/sys/fs/selinux/context")
        .and_then(|mut file| std::io::Write::write_all(&mut file, &buf))
        .is_ok()
}

pub fn lgetfilecon<P: AsRef<Path>>(path: P) -> Result<String> {
    let con = extattr::lgetxattr(&path, SELINUX_XATTR).with_context(|| {
        format!(
            "Failed to get SELinux context for {}",
            path.as_ref().display()
        )
    })?;
    let con = String::from_utf8_lossy(&con);
    Ok(con.to_string())
}

pub fn setsyscon<P: AsRef<Path>>(path: P) -> Result<()> {
    lsetfilecon(path, SYSTEM_CON)
}

pub fn restore_syscon<P: AsRef<Path>>(dir: P) -> Result<()> {
    for dir_entry in WalkDir::new(dir).parallelism(Serial) {
        if let Some(path) = dir_entry.ok().map(|dir_entry| dir_entry.path()) {
            setsyscon(&path)?;
        }
    }
    Ok(())
}

fn restore_syscon_if_unlabeled<P: AsRef<Path>>(dir: P) -> Result<()> {
    for dir_entry in WalkDir::new(dir).parallelism(Serial) {
        if let Some(path) = dir_entry.ok().map(|dir_entry| dir_entry.path())
            && let anyhow::Result::Ok(con) = lgetfilecon(&path)
            && (con == UNLABEL_CON || con.is_empty())
        {
            lsetfilecon(&path, SYSTEM_CON)?;
        }
    }
    Ok(())
}

pub fn restorecon() -> Result<()> {
    set_su_file_con(defs::DAEMON_PATH)?;
    restore_syscon_if_unlabeled(defs::MODULE_DIR)?;
    Ok(())
}

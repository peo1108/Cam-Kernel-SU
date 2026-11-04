use anyhow::Result;
use log::{info, warn};
use std::fs;
use std::process::Command;

use crate::utils;

/// Find PIDs of processes running in our su domain (u:r:cam:s0, or u:r:ksu:s0 before the rename).
/// Returns a list of PIDs excluding our own.
fn find_su_domain_pids() -> Vec<i32> {
    let my_pid = std::process::id() as i32;
    let mut pids = Vec::new();
    let legacy_domain = hidden!("u:r:ksu:s0");

    let Ok(entries) = fs::read_dir("/proc") else {
        return pids;
    };

    for entry in entries.flatten() {
        let name = entry.file_name();
        let Some(pid) = name.to_str().and_then(|s| s.parse::<i32>().ok()) else {
            continue;
        };
        if pid == my_pid {
            continue;
        }

        let attr_path = format!("/proc/{pid}/attr/current");
        if let Ok(context) = fs::read_to_string(&attr_path) {
            let context = context.trim().trim_end_matches('\0');
            if context == "u:r:cam:s0" || context == legacy_domain {
                pids.push(pid);
            }
        }
    }

    pids
}

/// The driver and fd wrapper anon inodes (the kernel's `[ksu_driver*]`, `[ksu_fdwrapper]`).
fn is_driver_fd(target: &str, driver: &str, wrapper: &str) -> bool {
    target.contains(driver) || target.contains(wrapper)
}

fn driver_fd_names() -> (String, String) {
    (hidden!("[ksu_driver"), hidden!("[ksu_fdwrapper]"))
}

/// Find PIDs of processes holding driver or fd wrapper file descriptors.
/// Returns a list of PIDs excluding our own.
fn find_driver_fd_holders() -> Vec<i32> {
    let my_pid = std::process::id() as i32;
    let mut pids = Vec::new();
    let (driver, wrapper) = driver_fd_names();

    let Ok(entries) = fs::read_dir("/proc") else {
        return pids;
    };

    for entry in entries.flatten() {
        let name = entry.file_name();
        let Some(pid) = name.to_str().and_then(|s| s.parse::<i32>().ok()) else {
            continue;
        };
        if pid == my_pid {
            continue;
        }

        let fd_dir = format!("/proc/{pid}/fd");
        let Ok(fds) = fs::read_dir(&fd_dir) else {
            continue;
        };

        for fd_entry in fds.flatten() {
            let link_path = fd_entry.path();
            if let Ok(target) = fs::read_link(&link_path) {
                let target_str = target.to_string_lossy();
                if is_driver_fd(&target_str, &driver, &wrapper) {
                    pids.push(pid);
                    break;
                }
            }
        }
    }

    pids
}

fn kill_pids(pids: &[i32], signal: i32) {
    for &pid in pids {
        unsafe {
            libc::kill(pid, signal);
        }
    }
}

/// Close all driver and fd wrapper fds held by the current process.
fn close_driver_fds() {
    let Ok(entries) = fs::read_dir("/proc/self/fd") else {
        return;
    };
    let (driver, wrapper) = driver_fd_names();

    for entry in entries.flatten() {
        let Ok(fd) = entry.file_name().to_string_lossy().parse::<i32>() else {
            continue;
        };
        if let Ok(target) = fs::read_link(entry.path()) {
            let target_str = target.to_string_lossy();
            if is_driver_fd(&target_str, &driver, &wrapper) {
                info!("unload: closing fd {fd} -> {target_str}");
                unsafe {
                    libc::close(fd);
                }
            }
        }
    }
}

pub fn unload() -> Result<()> {
    info!("unload: starting the unload sequence");

    // 0. Switch cgroups so we don't get killed along with our parent shell
    utils::switch_cgroups();

    // 1. stop (Android init stop command - stops all services)
    info!("unload: stopping Android services...");
    let _ = Command::new("stop").status();

    // 2. Kill all su domain processes and processes holding driver fds (except ourselves)
    info!("unload: killing su domain processes...");
    let su_pids = find_su_domain_pids();
    if !su_pids.is_empty() {
        info!(
            "unload: found {} su domain processes, sending SIGKILL",
            su_pids.len()
        );
        kill_pids(&su_pids, libc::SIGKILL);
    }

    info!("unload: killing processes holding driver fds...");
    let fd_pids = find_driver_fd_holders();
    if !fd_pids.is_empty() {
        info!(
            "unload: found {} processes holding driver fds, sending SIGKILL",
            fd_pids.len()
        );
        kill_pids(&fd_pids, libc::SIGKILL);
    }

    // 3. Close all our own driver and fd wrapper fds
    info!("unload: closing all driver fds...");
    close_driver_fds();

    // 4. delete_module, under the old name too for an LKM loaded before the rename
    info!("unload: removing camsu module...");
    if let Err(e) = rustix::system::delete_module(c"camsu", 0) {
        warn!("unload: delete_module camsu failed: {e}");
        let legacy = std::ffi::CString::new(hidden!("kernelsu")).expect("no NUL");
        if let Err(e) = rustix::system::delete_module(&legacy, 0) {
            warn!("unload: delete_module of the old name failed: {e}");
        }
    }

    // 5. start (Android init start command - restarts all services)
    info!("unload: restarting Android services...");
    let _ = Command::new("start").status();

    // 6. Exit
    info!("unload: done, exiting camd");
    std::process::exit(0);
}

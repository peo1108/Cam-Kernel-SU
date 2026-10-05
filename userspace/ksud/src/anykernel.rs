//! Flash an AnyKernel3 zip from a running system, the way kernel flasher
//! apps do: back up the target boot partition, then run the zip's
//! `update-binary` in boot mode with `OUTFD` pointing at stdout.
//!
//! With `inactive`, AnyKernel3 flashes the other slot (`SLOT_SELECT=inactive`)
//! and the device is switched to it, for installing right after an OTA.
//!
//! Before anything is written, the stock modules (`system_dlkm`, `vendor_dlkm`,
//! `vendor`, `odm`) are checked against the `vmlinux.symvers` the project's
//! builds carry: a module whose symbol CRCs the new kernel does not export
//! is refused at load time. After flashing, the next boot checks the kernel
//! that came up (see `kernel_check`).
//!
//! Backups live in `/data/adb/ksu/ak3_backup/`: `boot<slot>.img` is the kernel
//! that was there before the last flash, `boot<slot>-original.img` the one from
//! before the very first flash, which is never overwritten.

use std::collections::{BTreeMap, HashSet};
use std::fs::{self, File};
use std::io::{self, BufRead, BufReader, Read, Seek, Write};
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};

use anyhow::{Context, Result, bail, ensure};

use crate::kernel_check::{self, Expected};
use crate::modversions::{self, ModuleCrcs};
use crate::{assets, boot_patch, defs, utils};

const UPDATE_BINARY: &str = "META-INF/com/google/android/update-binary";
const AK3_SCRIPT: &str = "anykernel.sh";
const AK3_BUSYBOX: &str = "tools/busybox";
const ORIGINAL_SUFFIX: &str = "-original";
/// CRCs of the symbols the zip's kernel exports (`0x<crc>\t<symbol>` lines)
const SYMVERS: &str = "vmlinux.symvers";
/// what a project build is: kernel release, KernelSU and SUSFS versions
const SFS_JSON: &str = "sfs.json";
/// an uncompressed kernel, whose banner gives the release of other zips
const IMAGE: &str = "Image";
/// where the device keeps the modules it loads after the first stage
const MODULE_DIRS: [&str; 4] = [
    "/system_dlkm/lib/modules",
    "/vendor_dlkm/lib/modules",
    "/vendor/lib/modules",
    "/odm/lib/modules",
];
/// mismatched symbols printed per module
const MISMATCHES_SHOWN: usize = 3;

fn backup_dir() -> PathBuf {
    Path::new(defs::WORKING_DIR).join("ak3_backup")
}

fn boot_partition(slot_suffix: &str) -> Result<PathBuf> {
    let partition = PathBuf::from(format!("/dev/block/by-name/boot{slot_suffix}"));
    ensure!(
        partition.exists(),
        "boot partition {} not found",
        partition.display()
    );
    Ok(partition)
}

fn copy_synced(src: &Path, dst: &Path) -> Result<()> {
    let mut from = File::open(src).with_context(|| format!("open {}", src.display()))?;
    let mut to = File::create(dst).with_context(|| format!("create {}", dst.display()))?;
    io::copy(&mut from, &mut to)
        .with_context(|| format!("copy {} to {} failed", src.display(), dst.display()))?;
    to.sync_all()?;
    Ok(())
}

fn check_zip(zip_path: &Path) -> Result<()> {
    let file = File::open(zip_path).with_context(|| format!("open {}", zip_path.display()))?;
    let archive = zip::ZipArchive::new(file).context("not a valid zip file")?;
    let names: Vec<&str> = archive.file_names().collect();
    ensure!(
        names.contains(&UPDATE_BINARY),
        "{UPDATE_BINARY} is missing, this is not a flashable zip"
    );
    ensure!(
        names.contains(&AK3_SCRIPT),
        "{AK3_SCRIPT} is missing, this is not an AnyKernel3 zip"
    );
    drop(names);
    check_tools_arch(archive)
}

/// Upstream AnyKernel3 ships 32-bit ARM tools, which 64-bit-only devices
/// cannot run: AK3 would only fail later with "Busybox setup failed".
fn check_tools_arch(mut archive: zip::ZipArchive<File>) -> Result<()> {
    let has_32bit_abi =
        utils::getprop("ro.product.cpu.abilist32").is_some_and(|abis| !abis.trim().is_empty());
    if has_32bit_abi {
        return Ok(());
    }
    let Ok(mut busybox) = archive.by_name(AK3_BUSYBOX) else {
        return Ok(());
    };
    let mut header = [0u8; 5];
    if busybox.read_exact(&mut header).is_err() || &header[..4] != b"\x7fELF" {
        return Ok(());
    }
    // EI_CLASS: 1 = 32-bit, 2 = 64-bit
    ensure!(
        header[4] != 1,
        "this AnyKernel3 zip only has 32-bit tools, but this device runs 64-bit apps only; \
         use a zip built with arm64 tools (e.g. the project's own builds)"
    );
    Ok(())
}

fn extract_update_binary(zip_path: &Path, dest: &Path) -> Result<()> {
    let mut archive = zip::ZipArchive::new(File::open(zip_path)?)?;
    let mut entry = archive.by_name(UPDATE_BINARY)?;
    let mut out = File::create(dest).with_context(|| format!("create {}", dest.display()))?;
    io::copy(&mut entry, &mut out)?;
    Ok(())
}

fn read_entry(zip_path: &Path, name: &str) -> Result<Option<Vec<u8>>> {
    let mut archive = zip::ZipArchive::new(File::open(zip_path)?)?;
    let Ok(mut entry) = archive.by_name(name) else {
        return Ok(None);
    };
    let mut data = Vec::with_capacity(usize::try_from(entry.size()).unwrap_or(0));
    entry.read_to_end(&mut data)?;
    Ok(Some(data))
}

/// What the next boot should find: a project build says so in `sfs.json`;
/// for another zip the release comes from its Image banner, when readable.
fn expected_kernel(zip_path: &Path) -> Expected {
    if let Some(expected) = read_entry(zip_path, SFS_JSON)
        .ok()
        .flatten()
        .and_then(|data| kernel_check::expected_from_sfs_json(&String::from_utf8_lossy(&data)))
    {
        return expected;
    }
    Expected {
        release: read_entry(zip_path, IMAGE)
            .ok()
            .flatten()
            .and_then(|image| kernel_check::banner_release(&image)),
        ..Expected::default()
    }
}

fn collect_modules(
    dir: &Path,
    depth: u32,
    seen: &mut HashSet<String>,
    out: &mut Vec<(String, ModuleCrcs)>,
) {
    let Ok(entries) = fs::read_dir(dir) else {
        return;
    };
    for entry in entries.flatten() {
        let path = entry.path();
        if path.is_dir() {
            if depth > 0 {
                collect_modules(&path, depth - 1, seen, out);
            }
            continue;
        }
        let Some(file_name) = path.file_name().and_then(|n| n.to_str()) else {
            continue;
        };
        if path.extension().is_none_or(|ext| ext != "ko") {
            continue;
        }
        // vendor/lib/modules often holds the same modules as vendor_dlkm: count each once
        let name = modversions::module_name(file_name);
        if seen.contains(&name) {
            continue;
        }
        if let Some(crcs) = fs::read(&path)
            .ok()
            .and_then(|elf| modversions::module_crcs(&elf))
        {
            seen.insert(name.clone());
            out.push((name, crcs));
        }
    }
}

fn loaded_modules() -> HashSet<String> {
    fs::read_to_string("/proc/modules")
        .unwrap_or_default()
        .lines()
        .filter_map(|line| line.split_whitespace().next())
        .map(str::to_owned)
        .collect()
}

fn print_mismatches(
    out: &mut impl Write,
    report: &BTreeMap<String, Vec<modversions::Mismatch>>,
) -> io::Result<()> {
    for (module, bad) in report {
        let shown: Vec<String> = bad
            .iter()
            .take(MISMATCHES_SHOWN)
            .map(|m| {
                format!(
                    "{} ({:#010x} != {:#010x})",
                    m.symbol, m.module_crc, m.kernel_crc
                )
            })
            .collect();
        let more = bad.len().saturating_sub(MISMATCHES_SHOWN);
        if more > 0 {
            writeln!(out, "  {module}: {} and {more} more", shown.join(", "))?;
        } else {
            writeln!(out, "  {module}: {}", shown.join(", "))?;
        }
    }
    Ok(())
}

/// Refuses the zip when a module the device has loaded right now would be
/// refused by the new kernel. Modules that are not loaded only get a warning:
/// images often carry modules for hardware the device does not have.
fn check_modules(zip_path: &Path, skip: bool) -> Result<()> {
    let mut stdout = io::stdout();
    if skip {
        writeln!(stdout, "- Skipping the module check")?;
        return Ok(());
    }
    let Some(symvers) = read_entry(zip_path, SYMVERS)? else {
        writeln!(
            stdout,
            "- No {SYMVERS} in this zip, cannot check the stock modules"
        )?;
        return Ok(());
    };
    let kernel = modversions::parse_symvers(&String::from_utf8_lossy(&symvers));
    if kernel.is_empty() {
        writeln!(
            stdout,
            "- {SYMVERS} lists no symbols, cannot check the stock modules"
        )?;
        return Ok(());
    }

    let mut seen = HashSet::new();
    let mut found = Vec::new();
    for dir in MODULE_DIRS {
        collect_modules(Path::new(dir), 4, &mut seen, &mut found);
    }
    if found.is_empty() {
        writeln!(stdout, "- No stock modules found to check")?;
        return Ok(());
    }
    writeln!(
        stdout,
        "- Checking {} stock modules against the new kernel",
        found.len()
    )?;

    let report = modversions::report(&found, &kernel);
    let loaded = loaded_modules();
    let (in_use, unused): (BTreeMap<_, _>, BTreeMap<_, _>) = report
        .into_iter()
        .partition(|(name, _)| loaded.contains(name));

    if !unused.is_empty() {
        writeln!(
            stdout,
            "- Warning: {} module(s) not loaded now would not load with this kernel:",
            unused.len()
        )?;
        print_mismatches(&mut stdout, &unused)?;
    }
    if !in_use.is_empty() {
        writeln!(
            stdout,
            "- {} loaded module(s) would be refused by this kernel:",
            in_use.len()
        )?;
        print_mismatches(&mut stdout, &in_use)?;
        bail!(
            "this kernel does not match the device's modules: it could boot without them or not \
             at all. Use the build of the device's own GKI release (the recommended one), or \
             pass --skip-module-check to flash anyway"
        );
    }
    writeln!(stdout, "- The modules in use match the new kernel")?;
    Ok(())
}

/// Copies the boot partition about to be flashed so a bad kernel can be
/// undone with `ksud ak3-backup restore` or `fastboot flash boot<slot> <backup>`.
fn backup_boot(inactive: bool) -> Result<PathBuf> {
    let slot_suffix = boot_patch::get_slot_suffix(inactive);
    let partition = boot_partition(&slot_suffix)?;

    let dir = backup_dir();
    fs::create_dir_all(&dir)?;
    let backup = dir.join(format!("boot{slot_suffix}.img"));
    copy_synced(&partition, &backup)?;

    // keep the first kernel ever replaced, so the device can always go back to it
    let original = dir.join(format!("boot{slot_suffix}{ORIGINAL_SUFFIX}.img"));
    if !original.exists() {
        copy_synced(&backup, &original)?;
    }
    Ok(backup)
}

/// Prints the boot backups as JSON for the Manager.
pub fn list_backups() -> Result<()> {
    let mut backups = Vec::new();
    if let Ok(entries) = fs::read_dir(backup_dir()) {
        for entry in entries.flatten() {
            let path = entry.path();
            let Some(name) = path.file_name().and_then(|n| n.to_str()) else {
                continue;
            };
            let Some(stem) = name
                .strip_prefix("boot")
                .and_then(|n| n.strip_suffix(".img"))
            else {
                continue;
            };
            let (slot, original) = stem
                .strip_suffix(ORIGINAL_SUFFIX)
                .map_or((stem, false), |slot| (slot, true));
            let meta = entry.metadata()?;
            let modified = meta
                .modified()
                .ok()
                .and_then(|t| t.duration_since(std::time::UNIX_EPOCH).ok())
                .map_or(0, |d| d.as_secs());
            backups.push(serde_json::json!({
                "path": path.to_string_lossy(),
                "slot": slot,
                "original": original,
                "size": meta.len(),
                "modified": modified,
            }));
        }
    }
    println!("{}", serde_json::Value::Array(backups));
    Ok(())
}

/// Writes a backup made by `flash` back to the boot partition of its slot.
pub fn restore_backup(file: &str) -> Result<()> {
    let path = fs::canonicalize(file).with_context(|| format!("realpath: {file} failed"))?;
    let dir = fs::canonicalize(backup_dir())?;
    ensure!(
        path.parent() == Some(dir.as_path()),
        "{} is not an AnyKernel3 boot backup",
        path.display()
    );
    let name = path
        .file_name()
        .and_then(|n| n.to_str())
        .context("bad backup name")?;
    let stem = name
        .strip_prefix("boot")
        .and_then(|n| n.strip_suffix(".img"))
        .context("bad backup name")?;
    let slot_suffix = stem.strip_suffix(ORIGINAL_SUFFIX).unwrap_or(stem);
    let partition = boot_partition(slot_suffix)?;

    let backup_size = fs::metadata(&path)?.len();
    let partition_size = File::open(&partition)?.seek(io::SeekFrom::End(0))?;
    ensure!(
        backup_size <= partition_size,
        "backup ({backup_size} bytes) is larger than {} ({partition_size} bytes)",
        partition.display()
    );

    let mut stdout = io::stdout();
    writeln!(
        stdout,
        "- Restoring {} to {}",
        path.display(),
        partition.display()
    )?;
    let mut from = File::open(&path)?;
    let mut to = fs::OpenOptions::new().write(true).open(&partition)?;
    io::copy(&mut from, &mut to)?;
    to.sync_all()?;
    kernel_check::clear_pending();
    writeln!(stdout, "- Done, reboot to use the restored kernel")?;
    Ok(())
}

pub fn flash(zip: &str, no_backup: bool, inactive: bool, skip_module_check: bool) -> Result<()> {
    let zip_path = fs::canonicalize(zip).with_context(|| format!("realpath: {zip} failed"))?;
    check_zip(&zip_path)?;

    let mut stdout = io::stdout();
    if inactive {
        ensure!(
            !boot_patch::get_slot_suffix(false).is_empty(),
            "this device has no A/B slots"
        );
        writeln!(
            stdout,
            "- Target: inactive slot {}",
            boot_patch::get_slot_suffix(true)
        )?;
        // the mounted modules are this slot's; the OTA put new ones in the other
        writeln!(
            stdout,
            "- The inactive slot's modules are not mounted, skipping the module check"
        )?;
    } else {
        check_modules(&zip_path, skip_module_check)?;
    }
    let backup = if no_backup {
        writeln!(stdout, "- Skipping boot backup")?;
        None
    } else {
        writeln!(stdout, "- Backing up boot partition")?;
        let backup = backup_boot(inactive)?;
        writeln!(stdout, "- Boot backup: {}", backup.display())?;
        Some(backup)
    };
    let expected = expected_kernel(&zip_path);

    let work_dir = Path::new(defs::WORKING_DIR).join("ak3");
    if work_dir.exists() {
        fs::remove_dir_all(&work_dir)?;
    }
    fs::create_dir_all(&work_dir)?;
    let update_binary = work_dir.join("update-binary");
    extract_update_binary(&zip_path, &update_binary)?;

    writeln!(stdout, "- Running AnyKernel3")?;
    stdout.flush()?;

    // update-binary <api> <outfd> <zip>; OUTFD=1 makes ui_print go to stdout
    let mut child = Command::new(assets::BUSYBOX_PATH)
        .arg("sh")
        .arg(&update_binary)
        .arg("3")
        .arg("1")
        .arg(&zip_path)
        .current_dir(&work_dir)
        .env("ASH_STANDALONE", "1")
        .env("OUTFD", "1")
        .env("BOOTMODE", "true")
        .env("KSU", "true")
        .env("SLOT_SELECT", if inactive { "inactive" } else { "active" })
        .stdout(Stdio::piped())
        .spawn()
        .context("failed to start update-binary")?;

    if let Some(out) = child.stdout.take() {
        for line in BufReader::new(out).lines() {
            let line = line?;
            let trimmed = line.trim_start();
            // AK3 ends every ui_print with a bare "ui_print" line (a recovery newline)
            if trimmed == "ui_print" {
                continue;
            }
            let line = trimmed.strip_prefix("ui_print ").unwrap_or(line.as_str());
            // recovery protocol commands and unzip's file listing are noise here
            if line.starts_with("progress")
                || line.starts_with("set_progress")
                || line.starts_with("Archive:")
                || trimmed.starts_with("inflating:")
                || trimmed.starts_with("creating:")
                || trimmed.starts_with("extracting:")
            {
                continue;
            }
            writeln!(stdout, "{line}")?;
        }
    }

    let status = child.wait()?;
    let _ = fs::remove_dir_all(&work_dir);
    if !status.success() {
        bail!("AnyKernel3 failed with {status}");
    }

    if inactive {
        writeln!(stdout, "- Switching to the inactive slot")?;
        boot_patch::post_ota()?;
    }

    let zip_name = zip_path
        .file_name()
        .map_or_else(String::new, |n| n.to_string_lossy().into_owned());
    match &expected.release {
        Some(release) => writeln!(stdout, "- The next boot checks that {release} runs")?,
        None => writeln!(stdout, "- The next boot checks the new kernel")?,
    }
    if let Err(e) = kernel_check::record_flash(&zip_name, backup.as_deref(), expected) {
        log::warn!("kernel check: cannot record the flash: {e}");
    }

    writeln!(stdout, "- Done, reboot to use the new kernel")?;
    Ok(())
}

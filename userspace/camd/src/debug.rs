use anyhow::{Context, Ok, Result, bail};
use std::{ffi::CString, fs, path::Path};

use crate::ksucalls;

pub fn insmod(module: &Path, params: &[String]) -> Result<()> {
    // KSU_IOCTL_LOOKUP_SYMBOLS was introduced in Cam UAPI v7 (upstream v6; Cam's v6 is
    // EVENT_SERVICES). Prefer resolving the module's undefined (non-exported) symbols
    // through it; older kernels, or a failed lookup, fall back to /proc/kallsyms.
    const LOOKUP_SYMBOL_UAPI_VERSION: u32 = 7;

    let module = module
        .canonicalize()
        .with_context(|| format!("resolve module path failed: {}", module.display()))?;
    let cparams = CString::new(params.join(" "))?;
    let module_data =
        fs::read(&module).with_context(|| format!("read module failed: {}", module.display()))?;

    if ksucalls::get_info().uapi_version >= LOOKUP_SYMBOL_UAPI_VERSION {
        caminit::load_module_with_resolver(&module_data, &cparams, |names| {
            ksucalls::lookup_symbols(names).or_else(|e| {
                log::warn!("kernel symbol lookup failed: {e:#}, using /proc/kallsyms");
                caminit::resolve_kallsyms(names)
            })
        })
    } else {
        log::info!("kernel symbol lookup unsupported, using /proc/kallsyms");
        caminit::load_module(&module_data, &cparams)
    }
    .with_context(|| format!("load module failed: {}", module.display()))?;

    println!("Loaded kernel module: {}", module.display());
    Ok(())
}

/// Get mark status for a process
pub fn mark_get(pid: i32) -> Result<()> {
    let result = ksucalls::mark_get(pid)?;
    if pid == 0 {
        bail!("Please specify a pid to get its mark status");
    }
    println!(
        "Process {pid} mark status: {}",
        if result != 0 { "marked" } else { "unmarked" }
    );
    Ok(())
}

/// Mark a process
pub fn mark_set(pid: i32) -> Result<()> {
    ksucalls::mark_set(pid)?;
    if pid == 0 {
        println!("All processes marked successfully");
    } else {
        println!("Process {pid} marked successfully");
    }
    Ok(())
}

/// Unmark a process
pub fn mark_unset(pid: i32) -> Result<()> {
    ksucalls::mark_unset(pid)?;
    if pid == 0 {
        println!("All processes unmarked successfully");
    } else {
        println!("Process {pid} unmarked successfully");
    }
    Ok(())
}

/// Refresh mark for all running processes
pub fn mark_refresh() -> Result<()> {
    ksucalls::mark_refresh()?;
    println!("Refreshed mark for all running processes");
    Ok(())
}

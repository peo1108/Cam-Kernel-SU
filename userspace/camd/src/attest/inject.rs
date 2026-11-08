//! Driving the vendored `inject` binary to load the interceptor into keystore2.
//!
//! `inject <pid> <lib.so> entry` attaches to keystore2 with ptrace and loads
//! `libteesim_keymint.so`. The pid-matching helper is pure and host-tested; finding the live pid and
//! spawning the binary are Android-only. The restart-watching loop is added in M2-4.

/// The arg0 basename from a `/proc/<pid>/cmdline` blob (NUL-separated args), or `None` if empty.
pub fn cmdline_basename(cmdline: &[u8]) -> Option<String> {
    let end = cmdline
        .iter()
        .position(|&b| b == 0)
        .unwrap_or(cmdline.len());
    let arg0 = &cmdline[..end];
    let base = arg0
        .iter()
        .rposition(|&b| b == b'/')
        .map_or(arg0, |i| &arg0[i + 1..]);
    if base.is_empty() {
        return None;
    }
    Some(String::from_utf8_lossy(base).into_owned())
}

#[cfg(target_os = "android")]
pub use android::{keystore2_pid, run_inject};

#[cfg(target_os = "android")]
mod android {
    use super::cmdline_basename;
    use crate::defs;
    use anyhow::{Context, Result, bail};

    /// The pid of the running `keystore2`, or `None` if it is not up.
    pub fn keystore2_pid() -> Option<i32> {
        for entry in std::fs::read_dir("/proc").ok()?.flatten() {
            let name = entry.file_name();
            let Some(name) = name.to_str() else { continue };
            if name.is_empty() || !name.bytes().all(|b| b.is_ascii_digit()) {
                continue;
            }
            let cmdline = std::fs::read(entry.path().join("cmdline")).unwrap_or_default();
            if cmdline_basename(&cmdline).as_deref() == Some("keystore2") {
                return name.parse().ok();
            }
        }
        None
    }

    /// Run `inject <pid> <TEESIM_LIB> entry`, loading the interceptor into keystore2. Errors on a
    /// non-zero exit, with the binary's stderr in the message.
    pub fn run_inject(pid: i32) -> Result<()> {
        let out = std::process::Command::new(defs::TEESIM_INJECT)
            .arg(pid.to_string())
            .arg(defs::TEESIM_LIB)
            .arg("entry")
            .output()
            .with_context(|| format!("spawn {}", defs::TEESIM_INJECT))?;
        if !out.status.success() {
            bail!(
                "inject exit {:?}: {}",
                out.status.code(),
                String::from_utf8_lossy(&out.stderr).trim()
            );
        }
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::cmdline_basename;

    #[test]
    fn basename_strips_path_and_stops_at_nul() {
        assert_eq!(
            cmdline_basename(b"/system/bin/keystore2\0--flag\0").as_deref(),
            Some("keystore2")
        );
    }

    #[test]
    fn basename_bare_name() {
        assert_eq!(
            cmdline_basename(b"keystore2\0").as_deref(),
            Some("keystore2")
        );
    }

    #[test]
    fn basename_no_nul() {
        assert_eq!(cmdline_basename(b"keystore2").as_deref(), Some("keystore2"));
    }

    #[test]
    fn basename_empty_is_none() {
        assert_eq!(cmdline_basename(b""), None);
    }
}

//! Patch-time root seed: apps granted root by the kernel on first boot.
//! Written into the ramdisk `ksu_config` as the `seed` module parameter:
//! `seed=<nonce>,<pkg>:<appid>[,<pkg>:<appid>...]` (parsed by kernel/policy/pkg_tracker.c).

use std::fmt::Write;
use std::io::Read;

use anyhow::{Context, Result, bail, ensure};

const MAX_ENTRIES: usize = 32;
const MAX_PACKAGE_LEN: usize = 255;
const NONCE_LEN: usize = 16;
const MIN_APPID: u32 = 10000;
const MAX_APPID: u32 = 19999;

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct SeedEntry {
    pub package: String,
    pub appid: u32,
}

/// Parses `<pkg>:<appid>`, as given to `boot-patch --seed`.
pub fn parse_seed_entry(s: &str) -> Result<SeedEntry> {
    let Some((package, appid)) = s.split_once(':') else {
        bail!("invalid seed '{s}': expected <package>:<appid>");
    };
    ensure!(
        !package.is_empty()
            && package.len() <= MAX_PACKAGE_LEN
            && package
                .bytes()
                .all(|c| c.is_ascii_alphanumeric() || c == b'.' || c == b'_'),
        "invalid seed package name '{package}'"
    );
    ensure!(
        !appid.is_empty() && appid.bytes().all(|c| c.is_ascii_digit()),
        "invalid seed appid '{appid}'"
    );
    let appid: u32 = appid
        .parse()
        .with_context(|| format!("invalid seed appid '{appid}'"))?;
    ensure!(
        (MIN_APPID..=MAX_APPID).contains(&appid),
        "seed appid {appid} out of range {MIN_APPID}..={MAX_APPID}"
    );
    Ok(SeedEntry {
        package: package.to_owned(),
        appid,
    })
}

/// Builds the `seed=...` module parameter for `ksu_config`.
pub fn build_seed_param(nonce: &str, entries: &[SeedEntry]) -> Result<String> {
    ensure!(
        nonce.len() == NONCE_LEN
            && nonce
                .bytes()
                .all(|c| c.is_ascii_digit() || (b'a'..=b'f').contains(&c)),
        "invalid seed nonce '{nonce}'"
    );
    ensure!(
        !entries.is_empty() && entries.len() <= MAX_ENTRIES,
        "seed must have 1..={MAX_ENTRIES} entries, got {}",
        entries.len()
    );
    let mut param = format!("seed={nonce}");
    for entry in entries {
        let _ = write!(param, ",{}:{}", entry.package, entry.appid);
    }
    Ok(param)
}

pub fn random_nonce() -> Result<String> {
    let mut bytes = [0u8; NONCE_LEN / 2];
    std::fs::File::open("/dev/urandom")
        .and_then(|mut f| f.read_exact(&mut bytes))
        .context("failed to read /dev/urandom")?;
    Ok(bytes.iter().fold(String::new(), |mut s, b| {
        let _ = write!(s, "{b:02x}");
        s
    }))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn entry(package: &str, appid: u32) -> SeedEntry {
        SeedEntry {
            package: package.into(),
            appid,
        }
    }

    #[test]
    fn parse_ok() {
        assert_eq!(
            parse_seed_entry("com.termux:10234").unwrap(),
            entry("com.termux", 10234)
        );
        assert_eq!(
            parse_seed_entry("a_B.9:19999").unwrap(),
            entry("a_B.9", 19999)
        );
    }

    #[test]
    fn parse_rejects() {
        for bad in [
            "com.termux",
            "com termux:10234",
            "a:9999",
            "a:20000",
            ":10001",
            "a:",
            "a:1x",
            "a-b:10001",
            "a:+10001",
            "a:10001:1",
            "a,b:10001",
        ] {
            assert!(parse_seed_entry(bad).is_err(), "{bad}");
        }
        assert!(parse_seed_entry(&format!("{}:10001", "a".repeat(256))).is_err());
        assert!(parse_seed_entry(&format!("{}:10001", "a".repeat(255))).is_ok());
    }

    #[test]
    fn build_seed_param_ok() {
        assert_eq!(
            build_seed_param(
                "0123456789abcdef",
                &[entry("a", 10001), entry("b.c", 10002)]
            )
            .unwrap(),
            "seed=0123456789abcdef,a:10001,b.c:10002"
        );
    }

    #[test]
    fn build_seed_param_rejects() {
        let e = entry("a", 10001);
        assert!(build_seed_param("0123456789abcdef", &[]).is_err());
        assert!(build_seed_param("0123456789abcdef", &vec![e.clone(); 33]).is_err());
        assert!(build_seed_param("0123456789abcdef", &vec![e.clone(); 32]).is_ok());
        assert!(build_seed_param("0123456789ABCDEF", std::slice::from_ref(&e)).is_err());
        assert!(build_seed_param("short", &[e]).is_err());
    }

    #[test]
    fn nonce_shape() {
        let (a, b) = (random_nonce().unwrap(), random_nonce().unwrap());
        assert_eq!(a.len(), 16);
        assert!(
            a.chars()
                .all(|c| c.is_ascii_hexdigit() && !c.is_ascii_uppercase())
        );
        assert_ne!(a, b);
    }
}

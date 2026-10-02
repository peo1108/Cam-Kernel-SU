//! `ksud allow`: grant or revoke root for an installed app from a root shell
//! (adb `su`, Termux), without re-flashing a patch-time seed.

use anyhow::{Result, bail, ensure};

use crate::seed::{MAX_APPID, MIN_APPID, is_valid_package_name};

#[cfg(target_os = "android")]
const PACKAGES_LIST: &str = "/data/system/packages.list";
#[cfg(target_os = "android")]
const KSU_DEFAULT_SELINUX_DOMAIN: &[u8] = b"u:r:ksu:s0";

/// `(package, appid)` pairs from packages.list; malformed lines are skipped.
pub fn parse_packages_list(content: &str) -> Vec<(String, u32)> {
    content
        .lines()
        .filter_map(|line| {
            let mut fields = line.split_whitespace();
            let package = fields.next()?;
            let uid = fields.next()?.parse().ok()?;
            Some((package.to_owned(), uid))
        })
        .collect()
}

/// Appid of an installed regular app.
pub fn find_app_appid(packages: &[(String, u32)], package: &str) -> Result<u32> {
    ensure!(
        is_valid_package_name(package),
        "invalid package name '{package}'"
    );
    let Some((_, appid)) = packages.iter().find(|(name, _)| name == package) else {
        bail!("package '{package}' is not installed");
    };
    ensure!(
        (MIN_APPID..=MAX_APPID).contains(appid),
        "'{package}' (uid {appid}) is not a regular app"
    );
    Ok(*appid)
}

#[cfg(target_os = "android")]
fn installed_packages() -> Result<Vec<(String, u32)>> {
    use anyhow::Context;
    let content = std::fs::read_to_string(PACKAGES_LIST)
        .with_context(|| format!("failed to read {PACKAGES_LIST}"))?;
    Ok(parse_packages_list(&content))
}

#[cfg(target_os = "android")]
fn set_root(package: &str, appid: u32, allow: bool) -> Result<()> {
    use crate::{ksu_uapi, ksucalls};

    // SAFETY: plain C struct, all-zero is a valid starting point.
    let mut cmd: ksu_uapi::ksu_set_app_profile_cmd = unsafe { std::mem::zeroed() };
    let profile = &mut cmd.profile;
    profile.version = ksu_uapi::KSU_APP_PROFILE_VER;
    for (dst, src) in profile.key.iter_mut().zip(package.bytes()) {
        *dst = src as std::os::raw::c_char;
    }
    profile.curr_uid = appid as i32;
    profile.allow_su = allow;
    // SAFETY: writing union fields of a zeroed C struct.
    unsafe {
        if allow {
            let rp = &mut profile.__bindgen_anon_1.rp_config;
            rp.use_default = true;
            for (dst, src) in rp
                .profile
                .selinux_domain
                .iter_mut()
                .zip(KSU_DEFAULT_SELINUX_DOMAIN)
            {
                *dst = *src as std::os::raw::c_char;
            }
        } else {
            profile.__bindgen_anon_1.nrp_config.use_default = true;
        }
    }
    ksucalls::set_app_profile(&mut cmd)
}

#[cfg(target_os = "android")]
pub fn add(package: &str) -> Result<()> {
    let appid = find_app_appid(&installed_packages()?, package)?;
    set_root(package, appid, true)?;
    println!("granted root: {package} ({appid})");
    Ok(())
}

#[cfg(target_os = "android")]
pub fn remove(package: &str) -> Result<()> {
    let appid = find_app_appid(&installed_packages()?, package)?;
    set_root(package, appid, false)?;
    println!("revoked root: {package} ({appid})");
    Ok(())
}

#[cfg(target_os = "android")]
pub fn list() -> Result<()> {
    for (package, appid) in installed_packages()? {
        if (MIN_APPID..=MAX_APPID).contains(&appid) && crate::ksucalls::uid_granted_root(appid)? {
            println!("{package} {appid}");
        }
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    const PKGS: &str = "com.termux 10234 0 /data/user/0/com.termux default:targetSdkVersion=28 3003 0 1\n\
                        me.weishu.kernelsu 10324 1 /data/user/0/me.weishu.kernelsu default 3003 0 1\n\
                        broken-line\n\
                        com.bad notanumber 0 /data x\n\
                        com.android.shell 2000 0 /data/user_de/0/com.android.shell platform 3003 0 1\n";

    #[test]
    fn parses_packages_list() {
        assert_eq!(
            parse_packages_list(PKGS),
            vec![
                ("com.termux".to_owned(), 10234),
                ("me.weishu.kernelsu".to_owned(), 10324),
                ("com.android.shell".to_owned(), 2000),
            ]
        );
    }

    #[test]
    fn finds_app_appid() {
        let pkgs = parse_packages_list(PKGS);
        assert_eq!(find_app_appid(&pkgs, "com.termux").unwrap(), 10234);
        assert!(find_app_appid(&pkgs, "com.missing").is_err());
        assert!(find_app_appid(&pkgs, "bad name").is_err());
        // system uids are not regular apps
        assert!(find_app_appid(&pkgs, "com.android.shell").is_err());
    }
}

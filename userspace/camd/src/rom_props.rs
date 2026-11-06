//! Clear ROM-shipped properties that root detectors read as module residue.
//!
//! The stock ZUI ROM of the Lenovo Legion Y700 Gen5 (`TB323*`) sets
//! `persist.logd.size.{main,system,radio,events}=2M` in `/system/build.prop`. Chunqiu
//! Native Check reports any non-empty `persist.logd.size*` as "Found property", taking
//! it for something a module wrote. logd on that ROM keeps 256 KiB buffers either way.
//!
//! The values go through the property service (plain `setprop`, not a write into the
//! property area), so init keeps the empty value in `/data/property/persistent_properties`
//! and it wins over build.prop on later boots. Only properties the device already has
//! are touched, and only on that model.
#![cfg_attr(not(target_os = "android"), allow(dead_code))]

const LOGD_SIZE: &str = "persist.logd.size";

/// The Y700 Gen5 ROM is the only one known to ship these values.
pub fn applies_to(manufacturer: &str, model: &str) -> bool {
    manufacturer.eq_ignore_ascii_case("lenovo") && model.starts_with("TB323")
}

fn is_logd_size(name: &str) -> bool {
    name.strip_prefix(LOGD_SIZE)
        .is_some_and(|rest| rest.is_empty() || rest.starts_with('.'))
}

/// The properties to clear: `persist.logd.size` and `persist.logd.size.*` that hold a value.
pub fn plan(props: &[(String, String)]) -> Vec<String> {
    props
        .iter()
        .filter(|(name, value)| is_logd_size(name) && !value.is_empty())
        .map(|(name, _)| name.clone())
        .collect()
}

#[cfg(target_os = "android")]
pub fn apply() {
    use log::{info, warn};

    let get = |name: &str| crate::utils::getprop(name).unwrap_or_default();
    if !applies_to(&get("ro.product.manufacturer"), &get("ro.product.model")) {
        return;
    }
    let props = match crate::resetprop::list_props() {
        Ok(props) => props,
        Err(e) => {
            warn!("rom props: list props failed: {e}");
            return;
        }
    };
    for name in plan(&props) {
        match std::process::Command::new("setprop")
            .args([name.as_str(), ""])
            .status()
        {
            Ok(status) if status.success() => info!("rom props: cleared {name}"),
            Ok(status) => warn!("rom props: setprop {name} exited with {status}"),
            Err(e) => warn!("rom props: setprop {name} failed: {e}"),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn props(v: &[(&str, &str)]) -> Vec<(String, String)> {
        v.iter()
            .map(|(k, v)| ((*k).to_owned(), (*v).to_owned()))
            .collect()
    }

    #[test]
    fn only_y700_gen5() {
        assert!(applies_to("LENOVO", "TB323FU"));
        assert!(applies_to("Lenovo", "TB323FC"));
        assert!(!applies_to("LENOVO", "TB321FU"));
        assert!(!applies_to("Xiaomi", "TB323FU"));
        assert!(!applies_to("", ""));
    }

    #[test]
    fn clears_what_the_tb323fu_rom_ships() {
        let p = props(&[
            ("persist.logd.size", ""),
            ("persist.logd.size.events", "2M"),
            ("persist.logd.size.main", "2M"),
            ("persist.logd.size.radio", "2M"),
            ("persist.logd.size.system", "2M"),
            ("ro.logd.size.stats", "64K"),
            ("ro.product.model", "TB323FU"),
        ]);
        assert_eq!(
            plan(&p),
            vec![
                "persist.logd.size.events",
                "persist.logd.size.main",
                "persist.logd.size.radio",
                "persist.logd.size.system",
            ]
        );
    }

    #[test]
    fn leaves_empty_and_lookalike_props() {
        let p = props(&[
            ("persist.logd.size", "4M"),
            ("persist.logd.size.crash", ""),
            ("persist.logd.sizex", "1M"),
            ("persist.logd.security", "1"),
            ("ro.logd.size", "1M"),
        ]);
        assert_eq!(plan(&p), vec!["persist.logd.size"]);
        assert!(plan(&[]).is_empty());
    }
}

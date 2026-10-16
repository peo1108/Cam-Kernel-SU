//! Keeps `block_modules=` from bootlooping the device in first stage init.
//!
//! The LKM puts the names into the kernel's module blacklist as soon as caminit loads it, which
//! is before first stage init. A blacklisted module fails to load with EPERM, and first stage
//! init treats a module of `modules.load` that fails to load as fatal (LOG(FATAL), a bootloop)
//! unless libmodprobe finds it, or one of its dependencies, in `modules.blocklist`.
//!
//! So caminit, while the ramdisk is still writable:
//! 1. adds each name to `modules.blocklist` of every module dir in the ramdisk, unless a module
//!    that would still load depends on it (blocking it would make modprobe drop that driver);
//! 2. takes every name first stage init would still load (in a `modules.dep`, not blocklisted
//!    there) out of the params given to the LKM, so the kernel never refuses those.
//!
//! Step 1 follows SukiSU Ultra's ksuinit `blocklist.rs`; SukiSU delays the kernel blacklist to
//! second stage instead of step 2, which would need a new LKM.

use std::collections::{BTreeSet, HashMap};
use std::fs::{self, OpenOptions};
use std::io::{ErrorKind, Write};
use std::path::Path;

use anyhow::{Context, Result};

const MODULE_BASE_DIR: &str = "/lib/modules";
const PARAM: &str = "block_modules=";

/// libmodprobe's MakeCanonical: file name without `.ko`, `-` replaced by `_`.
fn canonical_name(path: &str) -> String {
    let name = path.rsplit('/').next().unwrap_or(path);
    let name = name.strip_suffix(".ko").unwrap_or(name);
    name.replace('-', "_")
}

/// Same rule as camd's `--block-module`, anything else is ignored.
fn is_valid_name(name: &str) -> bool {
    !name.is_empty()
        && name.len() <= 64
        && name.bytes().all(|c| c.is_ascii_alphanumeric() || c == b'_')
}

/// The module names of the `block_modules=` entries in the ksu_config params.
pub fn block_modules_from_params(params: &str) -> BTreeSet<String> {
    params
        .split_ascii_whitespace()
        .filter_map(|p| p.strip_prefix(PARAM))
        .flat_map(|v| v.split(','))
        .filter(|n| is_valid_name(n))
        .map(str::to_owned)
        .collect()
}

/// `params` without the names in `loadable`; a `block_modules=` left empty is dropped.
/// camd writes the params joined by single spaces, so they are joined back the same way.
pub fn params_without(params: &str, loadable: &BTreeSet<String>) -> String {
    params
        .split_ascii_whitespace()
        .filter_map(|p| match p.strip_prefix(PARAM) {
            None => Some(p.to_owned()),
            Some(list) => {
                let kept: Vec<&str> = list
                    .split(',')
                    .filter(|n| is_valid_name(n) && !loadable.contains(*n))
                    .collect();
                (!kept.is_empty()).then(|| format!("{PARAM}{}", kept.join(",")))
            }
        })
        .collect::<Vec<_>>()
        .join(" ")
}

/// `modules.dep`: module -> all modules it depends on (depmod lists them transitively).
fn parse_modules_dep(content: &str) -> HashMap<String, BTreeSet<String>> {
    content
        .lines()
        .filter_map(|line| line.split_once(':'))
        .map(|(module, deps)| {
            let deps = deps.split_ascii_whitespace().map(canonical_name).collect();
            (canonical_name(module.trim()), deps)
        })
        .collect()
}

fn parse_blocklist(content: &str) -> BTreeSet<String> {
    content
        .lines()
        .filter_map(|line| {
            let mut words = line.split_ascii_whitespace();
            match (words.next(), words.next()) {
                (Some("blocklist"), Some(name)) => Some(canonical_name(name)),
                _ => None,
            }
        })
        .collect()
}

#[derive(Debug, Default, PartialEq, Eq)]
pub struct Plan {
    /// Names to append to modules.blocklist.
    pub block: Vec<String>,
    /// Names left alone because other modules in this dir depend on them:
    /// modprobe would silently skip those too, possibly a driver the device needs.
    pub skipped: Vec<(String, Vec<String>)>,
}

/// Decide which of `names` to blocklist for one module dir. A name is only
/// blocked if it does not make modprobe skip any module that would load
/// otherwise, i.e. every module depending on it is blocked as well or already
/// skipped because of the existing blocklist.
pub fn plan(
    deps: &HashMap<String, BTreeSet<String>>,
    blocklist: &BTreeSet<String>,
    names: &BTreeSet<String>,
) -> Plan {
    // libmodprobe IsBlocklisted(): the module or any of its dependencies is blocklisted.
    let already_skipped = |module: &str, module_deps: &BTreeSet<String>| {
        blocklist.contains(module) || !module_deps.is_disjoint(blocklist)
    };
    let mut blocked: BTreeSet<&str> = names
        .iter()
        .filter(|n| deps.contains_key(n.as_str()))
        .map(String::as_str)
        .collect();
    let mut skipped = Vec::new();

    // Dropping a name can leave another one with an unblocked dependent, so repeat.
    loop {
        let unsafe_name = blocked.iter().find_map(|&name| {
            let dependents: Vec<String> = deps
                .iter()
                .filter(|(module, module_deps)| {
                    module.as_str() != name
                        && module_deps.contains(name)
                        && !blocked.contains(module.as_str())
                        && !already_skipped(module, module_deps)
                })
                .map(|(module, _)| module.clone())
                .collect();
            (!dependents.is_empty()).then_some((name, dependents))
        });
        let Some((name, mut dependents)) = unsafe_name else {
            break;
        };
        dependents.sort();
        blocked.remove(name);
        skipped.push((name.to_owned(), dependents));
    }

    Plan {
        block: blocked
            .into_iter()
            .filter(|n| !blocklist.contains(*n))
            .map(str::to_owned)
            .collect(),
        skipped,
    }
}

fn read_optional(path: &Path) -> Result<String> {
    match fs::read_to_string(path) {
        Ok(content) => Ok(content),
        Err(e) if e.kind() == ErrorKind::NotFound => Ok(String::new()),
        Err(e) => Err(e).with_context(|| format!("Cannot read {}", path.display())),
    }
}

/// Blocklists what is safe to in one dir; returns the names first stage init would still load
/// from it (present in its modules.dep, not blocklisted there).
fn apply_to_dir(dir: &Path, names: &BTreeSet<String>) -> BTreeSet<String> {
    let deps = match read_optional(&dir.join("modules.dep")) {
        Ok(content) => parse_modules_dep(&content),
        Err(e) => {
            // which modules this dir loads is unknown: the kernel must not refuse any of them
            log::error!("{e:?}");
            return names.clone();
        }
    };
    let present: BTreeSet<String> = names
        .iter()
        .filter(|n| deps.contains_key(n.as_str()))
        .cloned()
        .collect();
    if present.is_empty() {
        return present;
    }

    let blocklist_path = dir.join("modules.blocklist");
    let existing = match read_optional(&blocklist_path) {
        Ok(content) => content,
        Err(e) => {
            log::error!("{e:?}");
            return present;
        }
    };
    let blocklist = parse_blocklist(&existing);
    let plan = plan(&deps, &blocklist, names);

    for (name, dependents) in &plan.skipped {
        log::warn!(
            "Not blocking module {name} in {}: needed by {}",
            dir.display(),
            dependents.join(", ")
        );
    }
    let mut written = plan.block.clone();
    if !plan.block.is_empty() {
        let separator = if existing.is_empty() || existing.ends_with('\n') {
            ""
        } else {
            "\n"
        };
        let lines: String = std::iter::once(separator.to_owned())
            .chain(plan.block.iter().map(|name| format!("blocklist {name}\n")))
            .collect();
        let result = OpenOptions::new()
            .create(true)
            .append(true)
            .open(&blocklist_path)
            .and_then(|mut f| f.write_all(lines.as_bytes()));
        match result {
            Ok(()) => log::info!(
                "Blocklisted modules in {}: {}",
                dir.display(),
                plan.block.join(", ")
            ),
            Err(e) => {
                log::error!("Cannot write {}: {e}", blocklist_path.display());
                written.clear();
            }
        }
    }
    present
        .into_iter()
        .filter(|n| !blocklist.contains(n) && !written.contains(n))
        .collect()
}

/// Blocklists `names` in /lib/modules and each of its subdirectories (first stage init picks
/// one of them depending on the kernel release). Returns the names first stage init would still
/// load: the kernel must not get those.
pub fn apply(names: &BTreeSet<String>) -> BTreeSet<String> {
    apply_in(Path::new(MODULE_BASE_DIR), names)
}

fn apply_in(base: &Path, names: &BTreeSet<String>) -> BTreeSet<String> {
    if names.is_empty() {
        return BTreeSet::new();
    }
    let mut dirs = vec![base.to_path_buf()];
    if let Ok(entries) = fs::read_dir(base) {
        dirs.extend(
            entries
                .flatten()
                .filter(|e| e.file_type().is_ok_and(|t| t.is_dir()))
                .map(|e| e.path()),
        );
    }
    dirs.iter()
        .flat_map(|dir| apply_to_dir(dir, names))
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn names(list: &[&str]) -> BTreeSet<String> {
        list.iter().map(|s| (*s).to_owned()).collect()
    }

    const DEP: &str = "\
kernel/a.ko:
kernel/vr.ko:
kernel/mkp.ko:
kernel/display-drv.ko: kernel/mkp.ko kernel/a.ko
kernel/guard.ko: kernel/vr.ko
";

    fn temp_dir(tag: &str) -> std::path::PathBuf {
        let dir = std::env::temp_dir().join(format!("caminit-{tag}-{}", std::process::id()));
        let _ = fs::remove_dir_all(&dir);
        fs::create_dir_all(&dir).unwrap();
        dir
    }

    #[test]
    fn params() {
        assert_eq!(
            block_modules_from_params("allow_shell=1 block_modules=vr,bad-name,,mkp norc=1"),
            names(&["mkp", "vr"])
        );
        assert!(block_modules_from_params("allow_shell=1").is_empty());
    }

    #[test]
    fn params_drop_loadable_names() {
        let params = "bundled=1 block_modules=vr,mkp,guard seed=x";
        assert_eq!(
            params_without(params, &names(&["mkp"])),
            "bundled=1 block_modules=vr,guard seed=x"
        );
        assert_eq!(
            params_without(params, &names(&["vr", "mkp", "guard"])),
            "bundled=1 seed=x"
        );
        assert_eq!(params_without(params, &BTreeSet::new()), params);
        // a key that only starts with block_modules is not the param
        assert_eq!(
            params_without("block_modules_foo=1", &names(&["foo"])),
            "block_modules_foo=1"
        );
    }

    #[test]
    fn modules_dep_is_canonical() {
        let deps = parse_modules_dep(DEP);
        assert_eq!(deps["display_drv"], names(&["mkp", "a"]));
        assert!(deps["vr"].is_empty());
    }

    #[test]
    fn blocks_leaf_and_skips_needed() {
        let deps = parse_modules_dep(DEP);
        let plan = plan(
            &deps,
            &BTreeSet::new(),
            &names(&["mkp", "vr", "guard", "absent"]),
        );
        // guard depends on vr, but guard is blocked too; display_drv needs mkp.
        assert_eq!(plan.block, vec!["guard".to_owned(), "vr".to_owned()]);
        assert_eq!(
            plan.skipped,
            vec![("mkp".to_owned(), vec!["display_drv".to_owned()])]
        );
    }

    #[test]
    fn skipping_cascades() {
        let deps = parse_modules_dep(DEP);
        // vr is only safe while guard is blocked; guard is fine on its own.
        let plan = plan(&deps, &BTreeSet::new(), &names(&["vr"]));
        assert!(plan.block.is_empty());
        assert_eq!(
            plan.skipped,
            vec![("vr".to_owned(), vec!["guard".to_owned()])]
        );
    }

    #[test]
    fn existing_blocklist() {
        let deps = parse_modules_dep(DEP);
        let existing = parse_blocklist("blocklist guard\n# comment\nblocklist vr\n");
        assert_eq!(existing, names(&["guard", "vr"]));
        // Already blocklisted: nothing to add, and guard no longer counts as a dependent.
        let plan = plan(&deps, &existing, &names(&["vr"]));
        assert_eq!(plan, Plan::default());
    }

    #[test]
    fn writes_blocklist() {
        let dir = temp_dir("write");
        fs::write(dir.join("modules.dep"), DEP).unwrap();
        fs::write(dir.join("modules.blocklist"), "blocklist a").unwrap();
        let loadable = apply_to_dir(&dir, &names(&["vr", "guard", "mkp"]));
        let content = fs::read_to_string(dir.join("modules.blocklist")).unwrap();
        fs::remove_dir_all(&dir).unwrap();
        // display_drv also needs the blocklisted a, so it is skipped anyway and mkp can go.
        assert_eq!(
            content,
            "blocklist a\nblocklist guard\nblocklist mkp\nblocklist vr\n"
        );
        assert!(loadable.is_empty());
    }

    #[test]
    fn skipped_names_stay_loadable_for_the_kernel() {
        let dir = temp_dir("skip");
        fs::write(dir.join("modules.dep"), DEP).unwrap();
        // display_drv needs mkp and nothing blocklists it: first stage init loads mkp
        let loadable = apply_to_dir(&dir, &names(&["mkp", "vr", "absent"]));
        let content = fs::read_to_string(dir.join("modules.blocklist")).unwrap_or_default();
        fs::remove_dir_all(&dir).unwrap();
        assert_eq!(loadable, names(&["mkp", "vr"]));
        assert_eq!(content, "");
    }

    #[test]
    fn subdirs_are_covered_and_missing_dirs_load_nothing() {
        let base = temp_dir("base");
        let sub = base.join("6.12.30-android16-5");
        fs::create_dir_all(&sub).unwrap();
        fs::write(sub.join("modules.dep"), DEP).unwrap();
        let loadable = apply_in(&base, &names(&["mkp", "guard"]));
        let content = fs::read_to_string(sub.join("modules.blocklist")).unwrap();
        fs::remove_dir_all(&base).unwrap();
        assert_eq!(loadable, names(&["mkp"]));
        assert_eq!(content, "blocklist guard\n");

        let none = std::env::temp_dir().join("caminit-no-such-dir");
        assert!(apply_in(&none, &names(&["mkp"])).is_empty());
    }
}

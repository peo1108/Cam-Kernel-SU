//! Installed modules in one tar, to bring them back after a reset or on another phone.
//!
//! `module export` copies each module dir (state flags left out, like `copy_module_tree`) next to
//! a `cam-modules.json` manifest and tars them with busybox, which keeps modes, symlinks and
//! whiteout nodes. `module import` checks every entry name before extracting, then stages each
//! chosen module the way `module restore` stages a kept version: `modules_update/<id>` plus the
//! `update` flag, applied on the next boot (so the boot guard sees it as changed).
//! The Manager passes files in its own cache dir: camd copies the input before reading it, and
//! writes the output into the file the Manager created, which keeps the app as its owner.
#![cfg_attr(not(target_os = "android"), allow(dead_code))]

use std::collections::BTreeSet;

use anyhow::{Result, bail, ensure};
use serde_json::{Value, json};

pub const MANIFEST: &str = "cam-modules.json";
const FORMAT: &str = "cam-modules";
const VERSION: u64 = 1;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Entry {
    pub id: String,
    pub name: String,
    pub author: String,
    pub version: String,
    pub version_code: i64,
    pub enabled: bool,
}

impl Entry {
    fn to_json(&self) -> Value {
        json!({
            "id": self.id,
            "name": self.name,
            "author": self.author,
            "version": self.version,
            "versionCode": self.version_code,
            "enabled": self.enabled,
        })
    }
}

pub fn manifest_json(created: i64, entries: &[Entry]) -> Value {
    json!({
        "format": FORMAT,
        "version": VERSION,
        "created": created,
        "modules": entries.iter().map(Entry::to_json).collect::<Vec<_>>(),
    })
}

/// The manifest's modules; `valid_id` is `module::validate_module_id` (kept out for host tests).
pub fn parse_manifest(text: &str, valid_id: impl Fn(&str) -> bool) -> Result<Vec<Entry>> {
    let v: Value = serde_json::from_str(text)?;
    ensure!(
        v["format"] == FORMAT,
        "not a module backup ({MANIFEST} format)"
    );
    let version = v["version"].as_u64().unwrap_or(0);
    ensure!(
        version == VERSION,
        "module backup version {version} is not supported"
    );
    let Some(modules) = v["modules"].as_array() else {
        bail!("{MANIFEST} lists no modules");
    };
    let mut seen = BTreeSet::new();
    let mut entries = Vec::new();
    for m in modules {
        let id = m["id"].as_str().unwrap_or_default().to_owned();
        ensure!(valid_id(&id), "invalid module id '{id}' in {MANIFEST}");
        ensure!(seen.insert(id.clone()), "module '{id}' listed twice");
        let text = |key: &str| m[key].as_str().unwrap_or_default().to_owned();
        entries.push(Entry {
            name: text("name"),
            author: text("author"),
            version: text("version"),
            version_code: m["versionCode"].as_i64().unwrap_or(0),
            enabled: m["enabled"].as_bool().unwrap_or(true),
            id,
        });
    }
    Ok(entries)
}

/// `name` as tar lists it, without a leading `./` and trailing `/`.
fn clean_name(name: &str) -> &str {
    let mut n = name;
    while let Some(rest) = n.strip_prefix("./") {
        n = rest;
    }
    n.trim_end_matches('/')
}

/// Every entry must be the manifest or sit under a module dir the manifest names, with no
/// absolute path or `..`, and nothing may sit under a symlink in the archive (busybox tar would
/// follow it while extracting, out of the target dir).
pub fn check_entries(names: &[String], symlinks: &[String], ids: &BTreeSet<&str>) -> Result<()> {
    let links: BTreeSet<&str> = symlinks.iter().map(|s| clean_name(s)).collect();
    for raw in names {
        let name = clean_name(raw);
        if name.is_empty() || name == "." {
            continue;
        }
        ensure!(!name.starts_with('/'), "absolute path in backup: {raw}");
        let parts: Vec<&str> = name.split('/').collect();
        ensure!(!parts.contains(&".."), "path leaving the backup: {raw}");
        if name == MANIFEST {
            continue;
        }
        ensure!(
            ids.contains(parts[0]),
            "file outside the listed modules: {raw}"
        );
        for end in 1..parts.len() {
            let prefix = parts[..end].join("/");
            ensure!(
                !links.contains(prefix.as_str()),
                "file under a symlink in backup: {raw}"
            );
        }
    }
    Ok(())
}

/// Names of the symlinks in `busybox tar -tvf` output
/// (`lrwxrwxrwx 0/0 0 2026-10-16 10:00:00 id/link -> target`).
pub fn symlinks_in_listing(listing: &str) -> Vec<String> {
    listing
        .lines()
        .filter(|line| line.starts_with('l'))
        .filter_map(|line| {
            // skip mode, owner, size, date and time
            let mut rest = line;
            for _ in 0..5 {
                rest = rest.trim_start();
                let end = rest.find(char::is_whitespace)?;
                rest = &rest[end..];
            }
            let name = rest.trim_start();
            let name = name.split(" -> ").next()?;
            (!name.is_empty()).then(|| name.to_owned())
        })
        .collect()
}

#[cfg(target_os = "android")]
mod android {
    use std::collections::BTreeSet;
    use std::path::{Path, PathBuf};
    use std::process::Command;

    use anyhow::{Context, Result, bail, ensure};
    use log::{info, warn};
    use serde_json::json;

    use super::{
        Entry, MANIFEST, check_entries, manifest_json, parse_manifest, symlinks_in_listing,
    };
    use crate::assets;
    use crate::defs::{self, MODULE_DIR, MODULE_UPDATE_DIR, UPDATE_FILE_NAME};
    use crate::module;
    use crate::utils::ensure_clean_dir;

    const WORK: &str = const_format::concatcp!(defs::WORKING_DIR, "module_archive/");

    fn busybox_tar(args: &[&str]) -> Result<Vec<u8>> {
        let out = Command::new(assets::BUSYBOX_PATH)
            .arg("tar")
            .args(args)
            .output()
            .context("failed to run busybox tar")?;
        ensure!(
            out.status.success(),
            "tar {} failed: {}",
            args.join(" "),
            String::from_utf8_lossy(&out.stderr).trim()
        );
        Ok(out.stdout)
    }

    fn entry_of(dir: &Path, id: &str, enabled: bool) -> Result<Entry> {
        let prop = module::read_module_prop(dir)?;
        let get = |key: &str| prop.get(key).cloned().unwrap_or_default();
        Ok(Entry {
            id: id.to_owned(),
            name: get("name"),
            author: get("author"),
            version: get("version"),
            version_code: get("versionCode").trim().parse().unwrap_or(0),
            enabled,
        })
    }

    /// Writes the chosen modules (all when `ids` is empty) to `out`.
    pub fn export(out: &str, ids: &[String]) -> Result<()> {
        let mut chosen: Vec<String> = Vec::new();
        for dir in std::fs::read_dir(MODULE_DIR)?.flatten() {
            let id = dir.file_name().to_string_lossy().into_owned();
            if module::validate_module_id(&id).is_err() || (!ids.is_empty() && !ids.contains(&id)) {
                continue;
            }
            let live = dir.path();
            if live.join(defs::REMOVE_FILE_NAME).exists() || !live.join("module.prop").exists() {
                continue;
            }
            chosen.push(id);
        }
        chosen.sort();
        if let Some(missing) = ids.iter().find(|id| !chosen.contains(id)) {
            bail!("module {missing} is not installed");
        }
        ensure!(!chosen.is_empty(), "no module to back up");

        let work = Path::new(WORK);
        let tree = work.join("export");
        ensure_clean_dir(&tree)?;
        let mut entries = Vec::new();
        for id in &chosen {
            let live = Path::new(MODULE_DIR).join(id);
            // a pending update is what the module will be after the next boot
            let pending = Path::new(MODULE_UPDATE_DIR).join(id);
            let src =
                if live.join(UPDATE_FILE_NAME).exists() && pending.join("module.prop").exists() {
                    pending
                } else {
                    live.clone()
                };
            let enabled = !live.join(defs::DISABLE_FILE_NAME).exists();
            entries.push(entry_of(&src, id, enabled)?);
            module::copy_module_tree(&src, &tree.join(id))?;
        }
        let created = chrono::Utc::now().timestamp_millis();
        std::fs::write(
            tree.join(MANIFEST),
            serde_json::to_string_pretty(&manifest_json(created, &entries))?,
        )?;

        let tar = work.join("export.tar");
        let tree_str = tree.to_string_lossy();
        let tar_str = tar.to_string_lossy();
        let mut args = vec!["-cf", &tar_str, "-C", &tree_str, MANIFEST];
        args.extend(chosen.iter().map(String::as_str));
        let result = busybox_tar(&args).and_then(|_| {
            std::fs::copy(&tar, out).with_context(|| format!("failed to write {out}"))?;
            Ok(())
        });
        std::fs::remove_dir_all(work).ok();
        result?;
        info!("exported {} modules to {out}", entries.len());
        println!(
            "{}",
            serde_json::to_string(&manifest_json(created, &entries))?
        );
        Ok(())
    }

    /// The backup copied to camd's own dir (`input` may be a pipe), and its manifest.
    fn open_backup(input: &str) -> Result<(PathBuf, Vec<Entry>)> {
        let work = Path::new(WORK);
        ensure_clean_dir(work)?;
        let tar = work.join("import.tar");
        std::fs::copy(input, &tar).with_context(|| format!("failed to read {input}"))?;
        let tar_str = tar.to_string_lossy().into_owned();
        let manifest = busybox_tar(&["-xOf", &tar_str, MANIFEST])
            .with_context(|| format!("{MANIFEST} not found, not a module backup"))?;
        let entries = parse_manifest(&String::from_utf8_lossy(&manifest), |id| {
            module::validate_module_id(id).is_ok()
        })?;
        Ok((tar, entries))
    }

    /// Prints the backup's modules and what is installed now, without changing anything.
    pub fn list(input: &str) -> Result<()> {
        let result = open_backup(input);
        std::fs::remove_dir_all(WORK).ok();
        let (_, entries) = result?;
        let modules: Vec<_> = entries
            .iter()
            .map(|e| {
                let live = Path::new(MODULE_DIR).join(&e.id);
                let installed = module::read_module_prop(&live)
                    .ok()
                    .map(|p| p.get("version").cloned().unwrap_or_default());
                let mut v = e.to_json();
                v["installed"] = json!(installed);
                v
            })
            .collect();
        println!("{}", serde_json::to_string(&json!({ "modules": modules }))?);
        Ok(())
    }

    /// Stages the chosen modules (all when `only` is empty); they replace the installed ones on
    /// the next boot.
    pub fn import(input: &str, only: &[String]) -> Result<()> {
        let result = import_inner(input, only);
        std::fs::remove_dir_all(WORK).ok();
        result
    }

    fn import_inner(input: &str, only: &[String]) -> Result<()> {
        let (tar, entries) = open_backup(input)?;
        let tar_str = tar.to_string_lossy().into_owned();
        let ids: BTreeSet<&str> = entries.iter().map(|e| e.id.as_str()).collect();
        if let Some(unknown) = only.iter().find(|id| !ids.contains(id.as_str())) {
            bail!("module {unknown} is not in the backup");
        }

        let names: Vec<String> = String::from_utf8_lossy(&busybox_tar(&["-tf", &tar_str])?)
            .lines()
            .map(str::to_owned)
            .collect();
        let symlinks =
            symlinks_in_listing(&String::from_utf8_lossy(&busybox_tar(&["-tvf", &tar_str])?));
        check_entries(&names, &symlinks, &ids)?;

        let tree = Path::new(WORK).join("tree");
        ensure_clean_dir(&tree)?;
        busybox_tar(&["-xpf", &tar_str, "-C", &tree.to_string_lossy()])?;

        let mut imported = Vec::new();
        let mut failed = Vec::new();
        for entry in entries
            .iter()
            .filter(|e| only.is_empty() || only.contains(&e.id))
        {
            let dir = tree.join(&entry.id);
            let staged = module::read_module_prop(&dir).and_then(|prop| {
                let id = prop.get("id").map(|s| s.trim()).unwrap_or_default();
                ensure!(
                    id == entry.id,
                    "module.prop says id '{id}', not '{}'",
                    entry.id
                );
                module::stage_module_tree(&dir, &entry.id, entry.enabled)
            });
            match staged {
                Ok(()) => imported.push(entry.id.clone()),
                Err(e) => {
                    warn!("import {}: {e:#}", entry.id);
                    failed.push(json!({ "id": entry.id, "error": format!("{e:#}") }));
                }
            }
        }
        if let Err(e) = module::regenerate_preinit_rc() {
            warn!("regenerate preinit rc failed: {e}");
        }
        println!(
            "{}",
            serde_json::to_string(&json!({ "imported": imported, "failed": failed }))?
        );
        ensure!(
            failed.is_empty() || !imported.is_empty(),
            "no module was imported"
        );
        Ok(())
    }
}

#[cfg(target_os = "android")]
pub use android::{export, import, list};

#[cfg(test)]
mod tests {
    use super::*;

    fn ids<'a>(list: &[&'a str]) -> BTreeSet<&'a str> {
        list.iter().copied().collect()
    }

    fn names(list: &[&str]) -> Vec<String> {
        list.iter().map(|s| (*s).to_owned()).collect()
    }

    fn valid(id: &str) -> bool {
        !id.is_empty() && !id.contains('/') && id != ".."
    }

    #[test]
    fn manifest_round_trips() {
        let entries = vec![Entry {
            id: "zygisksu".into(),
            name: "Zygisk Next".into(),
            author: "Dr-TSNG".into(),
            version: "1.2".into(),
            version_code: 512,
            enabled: false,
        }];
        let text = manifest_json(1, &entries).to_string();
        assert_eq!(parse_manifest(&text, valid).unwrap(), entries);
    }

    #[test]
    fn manifest_rejects_other_files_and_bad_ids() {
        assert!(
            parse_manifest(r#"{"format":"cam-config","version":1,"modules":[]}"#, valid).is_err()
        );
        assert!(
            parse_manifest(
                r#"{"format":"cam-modules","version":2,"modules":[]}"#,
                valid
            )
            .is_err()
        );
        let bad = r#"{"format":"cam-modules","version":1,"modules":[{"id":"../x"}]}"#;
        assert!(parse_manifest(bad, valid).is_err());
        let twice = r#"{"format":"cam-modules","version":1,"modules":[{"id":"a"},{"id":"a"}]}"#;
        assert!(parse_manifest(twice, valid).is_err());
    }

    #[test]
    fn entries_must_stay_inside_listed_modules() {
        let ok = names(&[
            "./",
            "./cam-modules.json",
            "./a/",
            "./a/module.prop",
            "b/system/bin/x",
        ]);
        assert!(check_entries(&ok, &[], &ids(&["a", "b"])).is_ok());

        for bad in [
            "/etc/passwd",
            "a/../../x",
            "c/module.prop",
            "cam-modules.json/x",
            "..",
        ] {
            assert!(
                check_entries(&names(&[bad]), &[], &ids(&["a"])).is_err(),
                "{bad} should be refused"
            );
        }
    }

    #[test]
    fn nothing_may_sit_under_a_symlink() {
        let list = names(&["a/link", "a/link/evil"]);
        let links = names(&["./a/link"]);
        assert!(check_entries(&list, &links, &ids(&["a"])).is_err());
        // a symlink itself is fine (modules ship them)
        assert!(check_entries(&names(&["a/link", "a/other"]), &links, &ids(&["a"])).is_ok());
    }

    #[test]
    fn symlinks_are_read_from_a_verbose_listing() {
        let listing = "drwxr-xr-x 0/0         0 2026-10-16 10:00:00 a/\n\
                       lrwxrwxrwx 0/0         0 2026-10-16 10:00:00 a/lib -> /system/lib64\n\
                       -rw-r--r-- 0/0        12 2026-10-16 10:00:00 a/module.prop\n\
                       lrwxrwxrwx root/root   0 2026-10-16 10:00:01 a/my link -> x\n";
        assert_eq!(symlinks_in_listing(listing), names(&["a/lib", "a/my link"]));
    }
}

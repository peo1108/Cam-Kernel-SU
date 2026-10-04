//! Finds files and props that more than one enabled module overrides.
#![cfg_attr(not(target_os = "android"), allow(dead_code))]

use std::collections::{BTreeMap, BTreeSet};
use std::path::Path;

use serde_json::{Value, json};

/// Partitions the installer may move from `system/<p>` to `<module>/<p>`.
const PARTITIONS: [&str; 4] = ["vendor", "system_ext", "product", "odm"];
const REPLACE_MARKER: &str = ".replace";

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord)]
pub enum EntryKind {
    File,
    Whiteout,
    ReplaceDir,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord)]
pub enum ConflictKind {
    File,
    Replace,
    Prop,
}

impl ConflictKind {
    const fn as_str(self) -> &'static str {
        match self {
            Self::File => "file",
            Self::Replace => "replace",
            Self::Prop => "prop",
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Conflict {
    pub kind: ConflictKind,
    /// a target path such as `/system/etc/hosts`, or a prop key
    pub path: String,
    /// sorted
    pub modules: Vec<String>,
}

pub struct ModuleInput {
    pub id: String,
    pub entries: Vec<(String, EntryKind)>,
    pub props: Vec<(String, String)>,
}

#[cfg(unix)]
fn is_whiteout(meta: &std::fs::Metadata) -> bool {
    use std::os::unix::fs::{FileTypeExt, MetadataExt};
    meta.file_type().is_char_device() && meta.rdev() == 0
}

#[cfg(not(unix))]
const fn is_whiteout(_meta: &std::fs::Metadata) -> bool {
    false
}

/// Never follows symlinks: a link is an entry like a file.
fn walk(dir: &Path, target: &str, top_level_system: bool, out: &mut Vec<(String, EntryKind)>) {
    let Ok(entries) = std::fs::read_dir(dir) else {
        return;
    };
    if std::fs::symlink_metadata(dir.join(REPLACE_MARKER)).is_ok() {
        out.push((target.to_owned(), EntryKind::ReplaceDir));
    }
    for entry in entries.flatten() {
        let name = entry.file_name();
        let Some(name) = name.to_str() else {
            continue;
        };
        if name == REPLACE_MARKER {
            continue;
        }
        let path = entry.path();
        let Ok(meta) = std::fs::symlink_metadata(&path) else {
            continue;
        };
        let partition = top_level_system && PARTITIONS.contains(&name);
        if partition && meta.file_type().is_symlink() {
            // left behind by the installer; the real tree is <module>/<partition>
            continue;
        }
        let child = if partition {
            format!("/{name}")
        } else {
            format!("{target}/{name}")
        };
        if meta.is_dir() {
            walk(&path, &child, false, out);
        } else if is_whiteout(&meta) {
            out.push((child, EntryKind::Whiteout));
        } else {
            out.push((child, EntryKind::File));
        }
    }
}

/// Entries of a whole module: `system/` plus partitions moved out of it.
/// `system/vendor/...` and `<module>/vendor/...` both become `/vendor/...`.
pub fn scan_module_tree(module_dir: &Path) -> Vec<(String, EntryKind)> {
    let mut out = Vec::new();
    walk(&module_dir.join("system"), "/system", true, &mut out);
    for partition in PARTITIONS {
        let dir = module_dir.join(partition);
        if std::fs::symlink_metadata(&dir).is_ok_and(|meta| meta.is_dir()) {
            walk(&dir, &format!("/{partition}"), false, &mut out);
        }
    }
    out.sort();
    out
}

pub fn parse_system_prop(text: &str) -> Vec<(String, String)> {
    text.lines()
        .filter_map(|line| {
            let line = line.trim();
            if line.is_empty() || line.starts_with('#') {
                return None;
            }
            let (key, value) = line.split_once('=')?;
            let key = key.trim();
            (!key.is_empty()).then(|| (key.to_owned(), value.trim().to_owned()))
        })
        .collect()
}

fn conflict(kind: ConflictKind, path: &str, modules: &BTreeSet<&str>) -> Conflict {
    Conflict {
        kind,
        path: path.to_owned(),
        modules: modules.iter().map(|id| (*id).to_owned()).collect(),
    }
}

pub fn find_conflicts(modules: &[ModuleInput]) -> Vec<Conflict> {
    let mut conflicts = Vec::new();

    let mut files: BTreeMap<&str, BTreeSet<&str>> = BTreeMap::new();
    for module in modules {
        for (path, kind) in &module.entries {
            if *kind != EntryKind::ReplaceDir {
                files.entry(path).or_default().insert(&module.id);
            }
        }
    }
    for (path, ids) in &files {
        if ids.len() > 1 {
            conflicts.push(conflict(ConflictKind::File, path, ids));
        }
    }

    let mut replaced: BTreeMap<&str, BTreeSet<&str>> = BTreeMap::new();
    for module in modules {
        for (dir, _) in module
            .entries
            .iter()
            .filter(|(_, kind)| *kind == EntryKind::ReplaceDir)
        {
            let prefix = format!("{dir}/");
            for other in modules.iter().filter(|other| other.id != module.id) {
                if other
                    .entries
                    .iter()
                    .any(|(path, _)| path == dir || path.starts_with(&prefix))
                {
                    let ids = replaced.entry(dir).or_default();
                    ids.insert(&module.id);
                    ids.insert(&other.id);
                }
            }
        }
    }
    for (dir, ids) in &replaced {
        conflicts.push(conflict(ConflictKind::Replace, dir, ids));
    }

    // key -> module -> value; a module setting a key twice keeps its last value
    let mut props: BTreeMap<&str, BTreeMap<&str, &str>> = BTreeMap::new();
    for module in modules {
        for (key, value) in &module.props {
            props.entry(key).or_default().insert(&module.id, value);
        }
    }
    for (key, by_module) in &props {
        let values: BTreeSet<&str> = by_module.values().copied().collect();
        if values.len() > 1 {
            let ids: BTreeSet<&str> = by_module.keys().copied().collect();
            conflicts.push(conflict(ConflictKind::Prop, key, &ids));
        }
    }

    conflicts.sort_by(|a, b| (a.kind, &a.path).cmp(&(b.kind, &b.path)));
    conflicts
}

pub fn conflicts_json(conflicts: &[Conflict]) -> Value {
    Value::Array(
        conflicts
            .iter()
            .map(|c| json!({ "kind": c.kind.as_str(), "path": c.path, "modules": c.modules }))
            .collect(),
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    fn m(id: &str, entries: &[(&str, EntryKind)], props: &[(&str, &str)]) -> ModuleInput {
        ModuleInput {
            id: id.into(),
            entries: entries.iter().map(|(p, k)| ((*p).into(), *k)).collect(),
            props: props
                .iter()
                .map(|(k, v)| ((*k).into(), (*v).into()))
                .collect(),
        }
    }

    #[test]
    fn same_file_in_two_modules() {
        let c = find_conflicts(&[
            m("a", &[("/system/etc/hosts", EntryKind::File)], &[]),
            m("b", &[("/system/etc/hosts", EntryKind::File)], &[]),
            m("c", &[("/system/etc/other", EntryKind::File)], &[]),
        ]);
        assert_eq!(
            c,
            vec![Conflict {
                kind: ConflictKind::File,
                path: "/system/etc/hosts".into(),
                modules: vec!["a".into(), "b".into()],
            }]
        );
    }

    #[test]
    fn whiteout_vs_file_conflicts() {
        let c = find_conflicts(&[
            m("a", &[("/system/app/Foo", EntryKind::Whiteout)], &[]),
            m("b", &[("/system/app/Foo", EntryKind::File)], &[]),
        ]);
        assert_eq!(c.len(), 1);
        assert_eq!(c[0].kind, ConflictKind::File);
    }

    #[test]
    fn replace_dir_vs_child() {
        let c = find_conflicts(&[
            m("a", &[("/system/media/audio", EntryKind::ReplaceDir)], &[]),
            m(
                "b",
                &[("/system/media/audio/ui/x.ogg", EntryKind::File)],
                &[],
            ),
            m("c", &[("/system/media/audiox", EntryKind::File)], &[]),
        ]);
        assert_eq!(
            c,
            vec![Conflict {
                kind: ConflictKind::Replace,
                path: "/system/media/audio".into(),
                modules: vec!["a".into(), "b".into()],
            }]
        );
    }

    #[test]
    fn prop_conflict_only_when_values_differ() {
        let c = find_conflicts(&[
            m("a", &[], &[("ro.x", "1"), ("ro.same", "z")]),
            m("b", &[], &[("ro.x", "2"), ("ro.same", "z")]),
        ]);
        assert_eq!(
            c,
            vec![Conflict {
                kind: ConflictKind::Prop,
                path: "ro.x".into(),
                modules: vec!["a".into(), "b".into()],
            }]
        );
    }

    #[test]
    fn parses_system_prop() {
        assert_eq!(
            parse_system_prop("# c\n\n ro.a = 1 \nro.b=x=y\nbad\n"),
            vec![
                ("ro.a".to_owned(), "1".to_owned()),
                ("ro.b".to_owned(), "x=y".to_owned())
            ]
        );
    }

    #[test]
    fn scans_tree_with_replace() {
        let d = tempfile::tempdir().unwrap();
        let sys = d.path().join("system");
        std::fs::create_dir_all(sys.join("etc")).unwrap();
        std::fs::write(sys.join("etc/hosts"), "").unwrap();
        std::fs::create_dir_all(sys.join("media/audio")).unwrap();
        std::fs::write(sys.join("media/audio/.replace"), "").unwrap();
        let e = scan_module_tree(d.path());
        assert_eq!(
            e,
            vec![
                ("/system/etc/hosts".to_owned(), EntryKind::File),
                ("/system/media/audio".to_owned(), EntryKind::ReplaceDir),
            ]
        );
    }

    #[cfg(unix)]
    #[test]
    fn walker_does_not_follow_symlinks() {
        let d = tempfile::tempdir().unwrap();
        let sys = d.path().join("system");
        std::fs::create_dir_all(sys.join("lib")).unwrap();
        std::os::unix::fs::symlink(&sys, sys.join("lib/loop")).unwrap();
        assert_eq!(
            scan_module_tree(d.path()),
            vec![("/system/lib/loop".to_owned(), EntryKind::File)]
        );
    }

    #[cfg(unix)]
    #[test]
    fn partition_paths_match_moved_and_inline_layouts() {
        // the installer moves system/vendor to <module>/vendor and leaves a symlink behind
        let moved = tempfile::tempdir().unwrap();
        std::fs::create_dir_all(moved.path().join("vendor/etc")).unwrap();
        std::fs::write(moved.path().join("vendor/etc/x.conf"), "").unwrap();
        std::fs::create_dir_all(moved.path().join("system")).unwrap();
        std::os::unix::fs::symlink("../vendor", moved.path().join("system/vendor")).unwrap();

        let inline = tempfile::tempdir().unwrap();
        std::fs::create_dir_all(inline.path().join("system/vendor/etc")).unwrap();
        std::fs::write(inline.path().join("system/vendor/etc/x.conf"), "").unwrap();

        let expected = vec![("/vendor/etc/x.conf".to_owned(), EntryKind::File)];
        assert_eq!(scan_module_tree(moved.path()), expected);
        assert_eq!(scan_module_tree(inline.path()), expected);
    }

    #[test]
    fn json_shape() {
        let v = conflicts_json(&[Conflict {
            kind: ConflictKind::Prop,
            path: "ro.x".into(),
            modules: vec!["a".into()],
        }]);
        assert_eq!(
            v,
            serde_json::json!([{ "kind": "prop", "path": "ro.x", "modules": ["a"] }])
        );
    }
}

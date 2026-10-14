//! Points at the modules behind a failed boot: lines of that boot's logs that report a crash
//! or an error and name a module's directory, or a file a module puts in place.
#![cfg_attr(not(target_os = "android"), allow(dead_code))]

use std::collections::{BTreeMap, HashMap};

use crate::module_conflicts::EntryKind;

/// Where modules live; a path under one names the module right after it.
const MODULE_ROOTS: [&str; 2] = ["/data/adb/modules/", "/data/adb/modules_update/"];

/// Lines that report a crash, a failed load or an error. Only these count: a module's directory
/// and the files it overlays show up in plenty of harmless lines (camd logs every script it runs).
const PROBLEM_MARKERS: [&str; 24] = [
    "F DEBUG",
    "FATAL",
    "Fatal",
    "fatal",
    "Abort message",
    "Aborted",
    "backtrace",
    " pc ",
    "SIGSEGV",
    "SIGABRT",
    "SIGBUS",
    "Segmentation fault",
    "CANNOT LINK",
    "dlopen failed",
    "crash",
    "died",
    "Exception",
    "error",
    "Error",
    "ERROR",
    "failed",
    "Failed",
    "not found",
    "cannot",
];

/// Longest evidence line kept, in chars.
const MAX_LINE: usize = 300;
/// Most of a log read; boot logs are a few MB.
const MAX_LOG_BYTES: u64 = 32 << 20;

/// What each enabled module puts in place.
pub struct Owners {
    ids: Vec<String>,
    /// overlaid file -> module
    files: HashMap<String, String>,
    /// replaced directory -> module
    dirs: HashMap<String, String>,
}

impl Owners {
    /// `modules`: id and entries (`module_conflicts::scan_module_tree`).
    pub fn new(modules: &[(String, Vec<(String, EntryKind)>)]) -> Self {
        let mut files = HashMap::new();
        let mut dirs = HashMap::new();
        for (id, entries) in modules {
            for (path, kind) in entries {
                let map = if *kind == EntryKind::ReplaceDir {
                    &mut dirs
                } else {
                    &mut files
                };
                map.entry(path.clone()).or_insert_with(|| id.clone());
            }
        }
        Self {
            ids: modules.iter().map(|(id, _)| id.clone()).collect(),
            files,
            dirs,
        }
    }

    fn by_module_dir(&self, path: &str) -> Option<&String> {
        let rest = MODULE_ROOTS
            .iter()
            .find_map(|root| path.strip_prefix(root))?;
        let id = rest.split('/').next()?;
        self.ids.iter().find(|known| *known == id)
    }

    fn by_overlay(&self, path: &str) -> Option<&String> {
        if let Some(id) = self.files.get(path) {
            return Some(id);
        }
        let mut dir = path;
        while let Some(pos) = dir.rfind('/') {
            dir = &dir[..pos];
            if dir.is_empty() {
                break;
            }
            if let Some(id) = self.dirs.get(dir) {
                return Some(id);
            }
        }
        None
    }
}

fn is_problem_line(line: &str) -> bool {
    PROBLEM_MARKERS.iter().any(|marker| line.contains(marker))
}

/// Path-like words of a log line.
fn paths(line: &str) -> impl Iterator<Item = &str> {
    line.split(|c: char| c.is_whitespace() || "()[]{}<>\"'`,;=".contains(c))
        .filter_map(|word| {
            let start = word.find('/')?;
            let word = word[start..].trim_end_matches(['.', ':', '!', '?']);
            (word.len() > 1).then_some(word)
        })
}

fn shorten(line: &str) -> String {
    let line = line.trim();
    match line.char_indices().nth(MAX_LINE) {
        Some((end, _)) => format!("{}…", &line[..end]),
        None => line.to_owned(),
    }
}

/// The first problem line naming each module, by its directory or by a file it overlays.
pub fn scan<'a>(lines: impl Iterator<Item = &'a str>, owners: &Owners) -> BTreeMap<String, String> {
    let mut found = BTreeMap::new();
    for line in lines.filter(|line| is_problem_line(line)) {
        for path in paths(line) {
            let owner = owners
                .by_module_dir(path)
                .or_else(|| owners.by_overlay(path));
            if let Some(id) = owner
                && !found.contains_key(id)
            {
                found.insert(id.clone(), shorten(line));
            }
        }
    }
    found
}

/// Scans the logs of the boot before this one: camd's own logcat and dmesg of it (rotated to
/// `*.old.log` at this post-fs-data) and the kernel's pstore console, when there is one.
/// `include_camd_logs` is false when the failed boots never reached post-fs-data: the old
/// logs are then from an older boot.
#[cfg(target_os = "android")]
pub fn from_last_boot(owners: &Owners, include_camd_logs: bool) -> BTreeMap<String, String> {
    use std::io::Read;
    use std::path::Path;

    let mut logs: Vec<std::path::PathBuf> = Vec::new();
    if include_camd_logs {
        let dir = Path::new(crate::defs::LOG_DIR);
        logs.push(dir.join("logcat.old.log"));
        logs.push(dir.join("dmesg.old.log"));
    }
    for name in ["console-ramoops-0", "console-ramoops"] {
        logs.push(Path::new("/sys/fs/pstore").join(name));
    }
    let mut found = BTreeMap::new();
    for log in logs {
        let Ok(file) = std::fs::File::open(&log) else {
            continue;
        };
        let mut bytes = Vec::new();
        if file.take(MAX_LOG_BYTES).read_to_end(&mut bytes).is_err() {
            continue;
        }
        let text = String::from_utf8_lossy(&bytes);
        for (id, line) in scan(text.lines(), owners) {
            found.entry(id).or_insert(line);
        }
    }
    found
}

#[cfg(test)]
mod tests {
    use super::*;

    fn owners() -> Owners {
        Owners::new(&[
            (
                "fonts".to_owned(),
                vec![("/system/fonts/Roboto.ttf".to_owned(), EntryKind::File)],
            ),
            (
                "audio".to_owned(),
                vec![("/system/media/audio".to_owned(), EntryKind::ReplaceDir)],
            ),
            (
                "libs".to_owned(),
                vec![("/system/lib64/libfoo.so".to_owned(), EntryKind::File)],
            ),
            ("script".to_owned(), vec![]),
        ])
    }

    #[test]
    fn module_dir_counts_in_problem_lines_only() {
        let lines = [
            // camd logs every script it runs: not evidence
            "I CamD: exec /data/adb/modules/script/post-fs-data.sh",
            "E sh: /data/adb/modules/script/post-fs-data.sh[3]: busybox: not found",
        ];
        let found = scan(lines.into_iter(), &owners());
        assert_eq!(found.get("script").map(String::as_str), Some(lines[1]));
        // an id that is not a known module is not evidence
        assert!(scan(["FATAL /data/adb/modules/other/x"].into_iter(), &owners()).is_empty());
    }

    #[test]
    fn overlaid_file_counts_only_in_crash_lines() {
        let lines = [
            "I Typeface: loading /system/fonts/Roboto.ttf",
            "F DEBUG   : #05 pc 0000000000012345  /system/lib64/libfoo.so (bar+12)",
        ];
        let found = scan(lines.into_iter(), &owners());
        assert!(!found.contains_key("fonts"));
        assert_eq!(found.get("libs").map(String::as_str), Some(lines[1]));
    }

    #[test]
    fn file_under_a_replaced_dir_points_at_its_module() {
        let found = scan(
            ["E AudioFlinger: crash reading '/system/media/audio/ui/x.ogg'"].into_iter(),
            &owners(),
        );
        assert!(found.contains_key("audio"));
    }

    #[test]
    fn first_line_wins_and_long_lines_are_cut() {
        let long = format!("FATAL /data/adb/modules/script/x {}", "y".repeat(400));
        let found = scan(
            [long.as_str(), "FATAL /data/adb/modules/script/z"].into_iter(),
            &owners(),
        );
        let line = &found["script"];
        assert!(line.starts_with("FATAL /data/adb/modules/script/x"));
        assert!(line.ends_with('…'));
        assert_eq!(line.chars().count(), MAX_LINE + 1);
    }

    #[test]
    fn paths_split_on_punctuation() {
        let words: Vec<&str> = paths(
            "cannot execv('/data/adb/modules/a/bin/x'): No such file, at /system/lib64/y.so.",
        )
        .collect();
        assert_eq!(
            words,
            vec!["/data/adb/modules/a/bin/x", "/system/lib64/y.so"]
        );
    }
}

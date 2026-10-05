//! Module symbol CRCs (CONFIG_MODVERSIONS): what each stock `.ko` was built
//! against, and what a new kernel exports.
//!
//! A module whose recorded CRC of a symbol differs from the CRC the kernel
//! exports is refused at load time ("disagrees about version of symbol").
//! When that hits a module the device needs (Wi-Fi, audio, display), the new
//! kernel boots without it or not at all, so flash-ak3 compares them first.
#![cfg_attr(not(target_os = "android"), allow(dead_code))]

use std::collections::{BTreeMap, HashMap};

/// The symbols a module needs and the CRC each one had when it was built.
pub type ModuleCrcs = Vec<(String, u32)>;

/// `struct modversion_info` on 64-bit: `unsigned long crc` + `char name[56]`.
const MODVERSION_INFO_SIZE: usize = 64;
const MODVERSION_CRC_SIZE: usize = 8;

const SHT_NOBITS: u32 = 8;

struct Section<'a> {
    name: &'a [u8],
    data: &'a [u8],
}

fn u16_at(data: &[u8], off: usize) -> Option<u16> {
    Some(u16::from_le_bytes(data.get(off..off + 2)?.try_into().ok()?))
}

fn u32_at(data: &[u8], off: usize) -> Option<u32> {
    Some(u32::from_le_bytes(data.get(off..off + 4)?.try_into().ok()?))
}

fn u64_at(data: &[u8], off: usize) -> Option<u64> {
    Some(u64::from_le_bytes(data.get(off..off + 8)?.try_into().ok()?))
}

fn c_str(data: &[u8]) -> &[u8] {
    data.iter()
        .position(|&b| b == 0)
        .map_or(data, |end| &data[..end])
}

/// Sections of a little-endian ELF64 file; None for anything else.
fn sections(elf: &[u8]) -> Option<Vec<Section<'_>>> {
    if elf.get(..4)? != b"\x7fELF" || *elf.get(4)? != 2 || *elf.get(5)? != 1 {
        return None;
    }
    let shoff = usize::try_from(u64_at(elf, 0x28)?).ok()?;
    let shentsize = usize::from(u16_at(elf, 0x3a)?);
    let shnum = usize::from(u16_at(elf, 0x3c)?);
    let shstrndx = usize::from(u16_at(elf, 0x3e)?);
    if shentsize < 64 || shstrndx >= shnum {
        return None;
    }

    let header = |i: usize| -> Option<(u32, u32, &[u8])> {
        let base = shoff.checked_add(i.checked_mul(shentsize)?)?;
        let name = u32_at(elf, base)?;
        let kind = u32_at(elf, base + 4)?;
        let offset = usize::try_from(u64_at(elf, base + 24)?).ok()?;
        let size = usize::try_from(u64_at(elf, base + 32)?).ok()?;
        let data = if kind == SHT_NOBITS {
            &[][..]
        } else {
            elf.get(offset..offset.checked_add(size)?)?
        };
        Some((name, kind, data))
    };

    let (_, _, names) = header(shstrndx)?;
    let mut out = Vec::with_capacity(shnum);
    for i in 0..shnum {
        let (name, _, data) = header(i)?;
        let name = names.get(name as usize..).map_or(&[][..], c_str);
        out.push(Section { name, data });
    }
    Some(out)
}

/// The symbol CRCs a `.ko` records. Kernels with extended modversions keep
/// the complete list in `__version_ext_*` (`__versions` then only has the
/// short names, if anything), older ones in `__versions`. Empty for a module
/// built without modversions, None when the file is not an ELF64 module.
pub fn module_crcs(elf: &[u8]) -> Option<ModuleCrcs> {
    let sections = sections(elf)?;
    let find = |name: &[u8]| sections.iter().find(|s| s.name == name).map(|s| s.data);

    if let (Some(crcs), Some(names)) = (find(b"__version_ext_crcs"), find(b"__version_ext_names")) {
        let names = names
            .split(|&b| b == 0)
            .filter(|n| !n.is_empty())
            .map(|n| String::from_utf8_lossy(n).into_owned());
        let crcs = crcs
            .chunks_exact(4)
            .map(|c| u32::from_le_bytes(c.try_into().unwrap_or_default()));
        return Some(names.zip(crcs).collect());
    }

    let Some(versions) = find(b"__versions") else {
        return Some(Vec::new());
    };
    Some(
        versions
            .chunks_exact(MODVERSION_INFO_SIZE)
            .filter_map(|entry| {
                let crc = u64_at(entry, 0)?;
                let name = c_str(&entry[MODVERSION_CRC_SIZE..]);
                (!name.is_empty()).then(|| (String::from_utf8_lossy(name).into_owned(), crc as u32))
            })
            .collect(),
    )
}

/// `vmlinux.symvers` / `Module.symvers`: `0x<crc>\t<symbol>[\t<module>\t...]`.
/// Only the first two columns are read, so a trimmed copy works as well.
pub fn parse_symvers(text: &str) -> HashMap<String, u32> {
    text.lines()
        .filter_map(|line| {
            let mut cols = line.split('\t');
            let crc = cols.next()?.trim();
            let name = cols.next()?.trim();
            let crc = u32::from_str_radix(crc.strip_prefix("0x").unwrap_or(crc), 16).ok()?;
            (!name.is_empty()).then(|| (name.to_owned(), crc))
        })
        .collect()
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Mismatch {
    pub symbol: String,
    /// the CRC the module was built against
    pub module_crc: u32,
    /// the CRC the new kernel exports
    pub kernel_crc: u32,
}

/// Symbols `module` needs that the new kernel exports with another CRC.
/// A symbol missing from `kernel` is exported by another module (or is not
/// in the KMI at all) and cannot be judged here, so it is left out.
pub fn mismatches(module: &[(String, u32)], kernel: &HashMap<String, u32>) -> Vec<Mismatch> {
    module
        .iter()
        .filter_map(|(symbol, module_crc)| {
            let kernel_crc = *kernel.get(symbol)?;
            (kernel_crc != *module_crc).then(|| Mismatch {
                symbol: symbol.clone(),
                module_crc: *module_crc,
                kernel_crc,
            })
        })
        .collect()
}

/// `foo-bar.ko` is loaded as `foo_bar`, the name `/proc/modules` shows.
pub fn module_name(file_name: &str) -> String {
    file_name
        .strip_suffix(".ko")
        .unwrap_or(file_name)
        .replace('-', "_")
}

/// Every module of `found` with at least one mismatch, by module name.
pub fn report(
    found: &[(String, ModuleCrcs)],
    kernel: &HashMap<String, u32>,
) -> BTreeMap<String, Vec<Mismatch>> {
    found
        .iter()
        .filter_map(|(name, crcs)| {
            let bad = mismatches(crcs, kernel);
            (!bad.is_empty()).then(|| (name.clone(), bad))
        })
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    /// A minimal ELF64 LE file with the given sections (plus null + shstrtab).
    fn elf(sections: &[(&str, Vec<u8>)]) -> Vec<u8> {
        let mut shstrtab = vec![0u8];
        let mut name_offsets = Vec::new();
        for (name, _) in sections {
            name_offsets.push(shstrtab.len() as u32);
            shstrtab.extend_from_slice(name.as_bytes());
            shstrtab.push(0);
        }
        let shstrtab_name = shstrtab.len() as u32;
        shstrtab.extend_from_slice(b".shstrtab\0");

        let mut out = vec![0u8; 64];
        out[..4].copy_from_slice(b"\x7fELF");
        out[4] = 2;
        out[5] = 1;
        let mut placed = Vec::new();
        for (_, data) in sections {
            placed.push((out.len() as u64, data.len() as u64));
            out.extend_from_slice(data);
        }
        let strtab_at = out.len() as u64;
        out.extend_from_slice(&shstrtab);
        let shoff = out.len() as u64;
        let shnum = sections.len() + 2;

        let mut header = |name: u32, kind: u32, offset: u64, size: u64| {
            let mut sh = vec![0u8; 64];
            sh[0..4].copy_from_slice(&name.to_le_bytes());
            sh[4..8].copy_from_slice(&kind.to_le_bytes());
            sh[24..32].copy_from_slice(&offset.to_le_bytes());
            sh[32..40].copy_from_slice(&size.to_le_bytes());
            out.extend_from_slice(&sh);
        };
        header(0, 0, 0, 0);
        for (i, (offset, size)) in placed.iter().enumerate() {
            header(name_offsets[i], 1, *offset, *size);
        }
        header(shstrtab_name, 3, strtab_at, shstrtab.len() as u64);

        out[0x28..0x30].copy_from_slice(&shoff.to_le_bytes());
        out[0x3a..0x3c].copy_from_slice(&64u16.to_le_bytes());
        out[0x3c..0x3e].copy_from_slice(&(shnum as u16).to_le_bytes());
        out[0x3e..0x40].copy_from_slice(&((shnum - 1) as u16).to_le_bytes());
        out
    }

    fn versions(entries: &[(&str, u32)]) -> Vec<u8> {
        let mut out = Vec::new();
        for (name, crc) in entries {
            let mut entry = vec![0u8; MODVERSION_INFO_SIZE];
            entry[..8].copy_from_slice(&u64::from(*crc).to_le_bytes());
            entry[8..8 + name.len()].copy_from_slice(name.as_bytes());
            out.extend_from_slice(&entry);
        }
        out
    }

    #[test]
    fn reads_basic_modversions() {
        let ko = elf(&[(
            "__versions",
            versions(&[("printk", 0x1234_5678), ("kfree", 0xdead_beef)]),
        )]);
        assert_eq!(
            module_crcs(&ko).unwrap(),
            vec![
                ("printk".to_owned(), 0x1234_5678),
                ("kfree".to_owned(), 0xdead_beef)
            ]
        );
    }

    #[test]
    fn prefers_extended_modversions() {
        let long = "a_symbol_name_that_is_far_too_long_for_the_basic_modversion_entry";
        let mut crcs = Vec::new();
        crcs.extend_from_slice(&1u32.to_le_bytes());
        crcs.extend_from_slice(&2u32.to_le_bytes());
        let names = format!("kfree\0{long}\0").into_bytes();
        let ko = elf(&[
            ("__versions", versions(&[("kfree", 1)])),
            ("__version_ext_crcs", crcs),
            ("__version_ext_names", names),
        ]);
        assert_eq!(
            module_crcs(&ko).unwrap(),
            vec![("kfree".to_owned(), 1), (long.to_owned(), 2)]
        );
    }

    #[test]
    fn module_without_modversions_is_empty() {
        let ko = elf(&[(".text", vec![0; 16])]);
        assert_eq!(module_crcs(&ko).unwrap(), Vec::new());
    }

    #[test]
    fn rejects_non_elf() {
        assert!(module_crcs(b"not an elf file at all, just text").is_none());
        assert!(module_crcs(&[]).is_none());
    }

    #[test]
    fn rejects_truncated_elf() {
        let mut ko = elf(&[("__versions", versions(&[("kfree", 1)]))]);
        ko.truncate(80);
        assert!(module_crcs(&ko).is_none());
    }

    #[test]
    fn parses_symvers() {
        let map = parse_symvers(
            "0x12345678\tprintk\tvmlinux\tEXPORT_SYMBOL\t\n\
             0xdeadbeef\tkfree\tvmlinux\tEXPORT_SYMBOL_GPL\tNS\n\
             0x00000001\ttrimmed\n\
             garbage line\n",
        );
        assert_eq!(map.get("printk"), Some(&0x1234_5678));
        assert_eq!(map.get("kfree"), Some(&0xdead_beef));
        assert_eq!(map.get("trimmed"), Some(&1));
        assert_eq!(map.len(), 3);
    }

    #[test]
    fn finds_only_differing_known_symbols() {
        let kernel = parse_symvers("0x1\tsame\n0x2\tchanged\n");
        let module = vec![
            ("same".to_owned(), 1),
            ("changed".to_owned(), 3),
            ("from_another_module".to_owned(), 9),
        ];
        assert_eq!(
            mismatches(&module, &kernel),
            vec![Mismatch {
                symbol: "changed".to_owned(),
                module_crc: 3,
                kernel_crc: 2
            }]
        );
    }

    #[test]
    fn report_lists_bad_modules_only() {
        let kernel = parse_symvers("0x1\ta\n0x2\tb\n");
        let found = vec![
            ("good".to_owned(), vec![("a".to_owned(), 1)]),
            ("bad".to_owned(), vec![("b".to_owned(), 5)]),
        ];
        let report = report(&found, &kernel);
        assert_eq!(report.keys().collect::<Vec<_>>(), vec!["bad"]);
    }

    #[test]
    fn module_names_match_proc_modules() {
        assert_eq!(module_name("snd-soc-core.ko"), "snd_soc_core");
        assert_eq!(module_name("wlan.ko"), "wlan");
    }
}

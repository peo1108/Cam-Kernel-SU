//! Compute the AVB vbmeta digest from the device's own partitions.
//!
//! Reproduces `avb_slot_verify_data_calculate_vbmeta_digest` (what the bootloader
//! exposes as `ro.boot.vbmeta.digest`): hash the concatenation of every vbmeta
//! struct reached from the top-level `vbmeta` partition — the main struct first,
//! then each chained partition's struct in descriptor order, depth-first.
//!
//! Used to fill `ro.boot.vbmeta.digest` only when the bootloader left it empty or
//! all-zero: the value is the device's own, computed here, never fabricated. A
//! read or parse failure yields `None`, so the caller writes either the real
//! digest or nothing (a wrong digest would be a tell of its own).

const AVB_MAGIC: &[u8; 4] = b"AVB0";
const FOOTER_MAGIC: &[u8; 4] = b"AVBf";
const HEADER_SIZE: usize = 256;
const FOOTER_SIZE: usize = 64;
const CHAIN_PARTITION_TAG: u64 = 4;
/// A vbmeta chain deeper/wider than this is treated as corrupt, not hashed.
const MAX_PARTITIONS: usize = 16;

fn be_u32(buf: &[u8], off: usize) -> Option<u32> {
    buf.get(off..off + 4)
        .map(|b| u32::from_be_bytes([b[0], b[1], b[2], b[3]]))
}

fn be_u64(buf: &[u8], off: usize) -> Option<u64> {
    buf.get(off..off + 8)
        .map(|b| u64::from_be_bytes([b[0], b[1], b[2], b[3], b[4], b[5], b[6], b[7]]))
}

/// The fields of AvbVBMetaImageHeader we need (all big-endian). The algorithm_type
/// (offset 28) is not kept: `ro.boot.vbmeta.digest` is SHA-256 on Android devices.
struct Header {
    auth_size: u64,
    aux_size: u64,
    descriptors_offset: u64,
    descriptors_size: u64,
}

/// Parse the 256-byte header at the start of a vbmeta struct. `None` if the magic
/// is wrong or the buffer is too short.
fn parse_header(blob: &[u8]) -> Option<Header> {
    if blob.len() < HEADER_SIZE || &blob[0..4] != AVB_MAGIC {
        return None;
    }
    Some(Header {
        auth_size: be_u64(blob, 12)?,
        aux_size: be_u64(blob, 20)?,
        descriptors_offset: be_u64(blob, 96)?,
        descriptors_size: be_u64(blob, 104)?,
    })
}

/// Total size of the vbmeta struct described by `h` (header + auth + aux).
fn struct_len(h: &Header) -> Option<usize> {
    usize::try_from(h.auth_size)
        .ok()?
        .checked_add(usize::try_from(h.aux_size).ok()?)?
        .checked_add(HEADER_SIZE)
}

/// Names of the chain partitions referenced by this struct, in descriptor order.
/// Descriptor body of a chain partition: rollback_index_location u32, partition_name_len
/// u32, public_key_len u32, reserved[64], partition_name[name_len], public_key[key_len].
fn chain_partitions(blob: &[u8], h: &Header) -> Vec<String> {
    let mut out = Vec::new();
    let aux_start = HEADER_SIZE.saturating_add(h.auth_size as usize);
    let Ok(desc_off) = usize::try_from(h.descriptors_offset) else {
        return out;
    };
    let Ok(desc_size) = usize::try_from(h.descriptors_size) else {
        return out;
    };
    let Some(start) = aux_start.checked_add(desc_off) else {
        return out;
    };
    let Some(end) = start.checked_add(desc_size) else {
        return out;
    };
    let Some(descs) = blob.get(start..end) else {
        return out;
    };

    let mut p = 0usize;
    while p + 16 <= descs.len() {
        let Some(tag) = be_u64(descs, p) else { break };
        let Some(nbf) = be_u64(descs, p + 8) else {
            break;
        };
        let Ok(nbf) = usize::try_from(nbf) else { break };
        let Some(body_end) = p.checked_add(16).and_then(|v| v.checked_add(nbf)) else {
            break;
        };
        let Some(body) = descs.get(p + 16..body_end) else {
            break;
        };
        if tag == CHAIN_PARTITION_TAG
            && let Some(name) = chain_partition_name(body)
        {
            out.push(name);
        }
        p = body_end;
    }
    out
}

/// The partition name in a chain-partition descriptor body, if present and UTF-8.
fn chain_partition_name(body: &[u8]) -> Option<String> {
    let name_len = be_u32(body, 4)? as usize;
    let name = body.get(76..76 + name_len)?;
    std::str::from_utf8(name).ok().map(str::to_owned)
}

/// `(vbmeta_offset, vbmeta_size)` from an AvbFooter (the last 64 bytes of a
/// footered partition such as boot). `None` if the magic is wrong.
fn parse_footer(footer: &[u8]) -> Option<(u64, u64)> {
    if footer.len() < FOOTER_SIZE || &footer[0..4] != FOOTER_MAGIC {
        return None;
    }
    Some((be_u64(footer, 20)?, be_u64(footer, 28)?))
}

#[cfg(target_os = "android")]
pub use android::*;

#[cfg(target_os = "android")]
mod android {
    use super::chain_partitions;
    use super::{
        AVB_MAGIC, FOOTER_SIZE, HEADER_SIZE, Header, MAX_PARTITIONS, parse_footer, parse_header,
        struct_len,
    };
    use std::collections::HashSet;
    use std::fs::File;
    use std::io::{Read, Seek, SeekFrom};

    fn slot_suffix() -> String {
        crate::utils::getprop("ro.boot.slot_suffix").unwrap_or_default()
    }

    fn read_at(file: &mut File, offset: u64, len: usize) -> Option<Vec<u8>> {
        file.seek(SeekFrom::Start(offset)).ok()?;
        let mut buf = vec![0u8; len];
        file.read_exact(&mut buf).ok()?;
        Some(buf)
    }

    /// The vbmeta struct bytes of a partition: located at offset 0 for a dedicated
    /// vbmeta partition (`vbmeta`, `vbmeta_system`…), or via the AvbFooter in the
    /// last 64 bytes for a partition with an appended image (`boot`, `recovery`…).
    fn partition_struct(name: &str) -> Option<Vec<u8>> {
        let path = format!("/dev/block/by-name/{name}{}", slot_suffix());
        let mut file = File::open(&path).ok()?;

        let mut head = [0u8; HEADER_SIZE];
        file.seek(SeekFrom::Start(0)).ok()?;
        file.read_exact(&mut head).ok()?;

        if &head[0..4] == AVB_MAGIC {
            let header = parse_header(&head)?;
            let len = struct_len(&header)?;
            return read_at(&mut file, 0, len);
        }

        // Footered image: struct sits at vbmeta_offset, pointed to by the footer.
        let size = file.seek(SeekFrom::End(0)).ok()?;
        let footer = read_at(
            &mut file,
            size.checked_sub(FOOTER_SIZE as u64)?,
            FOOTER_SIZE,
        )?;
        let (vbmeta_offset, vbmeta_size) = parse_footer(&footer)?;
        let buf = read_at(&mut file, vbmeta_offset, usize::try_from(vbmeta_size).ok()?)?;
        if buf.len() >= 4 && &buf[0..4] == AVB_MAGIC {
            Some(buf)
        } else {
            None
        }
    }

    /// Append `name`'s struct to `hashed`, then recurse into its chain partitions.
    /// `None` on any read/parse failure or a runaway chain, so the whole digest is
    /// either correct or absent.
    fn collect(
        name: &str,
        hashed: &mut Vec<u8>,
        seen: &mut HashSet<String>,
        count: &mut usize,
    ) -> Option<()> {
        if *count >= MAX_PARTITIONS {
            return None;
        }
        let st = partition_struct(name)?;
        let header: Header = parse_header(&st)?;
        hashed.extend_from_slice(&st);
        *count += 1;
        for cp in chain_partitions(&st, &header) {
            if seen.insert(cp.clone()) {
                collect(&cp, hashed, seen, count)?;
            }
        }
        Some(())
    }

    /// The device's real `ro.boot.vbmeta.digest` as lowercase hex, or `None` if the
    /// partitions cannot be read or parsed. `ro.boot.vbmeta.digest` is a SHA-256
    /// digest on Android devices in practice (and on every device seen so far).
    pub fn computed_digest() -> Option<String> {
        let mut hashed = Vec::new();
        let mut seen = HashSet::new();
        seen.insert("vbmeta".to_owned());
        let mut count = 0usize;
        collect("vbmeta", &mut hashed, &mut seen, &mut count)?;
        Some(sha256::digest(hashed.as_slice()))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn be32(v: u32) -> [u8; 4] {
        v.to_be_bytes()
    }
    fn be64(v: u64) -> [u8; 8] {
        v.to_be_bytes()
    }

    /// A minimal vbmeta struct: 256-byte header + aux holding `descriptors`.
    /// No auth block. `algorithm` goes in the header for completeness.
    fn vbmeta(algorithm: u32, descriptors: &[u8]) -> Vec<u8> {
        let mut header = vec![0u8; HEADER_SIZE];
        header[0..4].copy_from_slice(AVB_MAGIC);
        header[12..20].copy_from_slice(&be64(0)); // auth_size
        header[20..28].copy_from_slice(&be64(descriptors.len() as u64)); // aux_size
        header[28..32].copy_from_slice(&be32(algorithm));
        header[96..104].copy_from_slice(&be64(0)); // descriptors_offset
        header[104..112].copy_from_slice(&be64(descriptors.len() as u64)); // descriptors_size
        let mut out = header;
        out.extend_from_slice(descriptors);
        out
    }

    /// A chain-partition descriptor (tag 4) naming `name`, no public key.
    fn chain_descriptor(name: &str) -> Vec<u8> {
        let mut body = Vec::new();
        body.extend_from_slice(&be32(0)); // rollback_index_location
        body.extend_from_slice(&be32(name.len() as u32)); // partition_name_len
        body.extend_from_slice(&be32(0)); // public_key_len
        body.extend_from_slice(&[0u8; 64]); // reserved
        body.extend_from_slice(name.as_bytes());
        while body.len() % 8 != 0 {
            body.push(0);
        }
        let mut out = Vec::new();
        out.extend_from_slice(&be64(CHAIN_PARTITION_TAG));
        out.extend_from_slice(&be64(body.len() as u64));
        out.extend_from_slice(&body);
        out
    }

    #[test]
    fn parses_header_fields() {
        let st = vbmeta(2, &[]);
        let h = parse_header(&st).unwrap();
        assert_eq!(h.aux_size, 0);
        assert_eq!(struct_len(&h), Some(HEADER_SIZE));
    }

    #[test]
    fn rejects_bad_magic() {
        assert!(parse_header(&[0u8; HEADER_SIZE]).is_none());
        assert!(parse_header(b"AVB0").is_none()); // too short
    }

    #[test]
    fn reads_chain_partitions_in_order() {
        let descs = [chain_descriptor("boot"), chain_descriptor("vbmeta_system")].concat();
        let st = vbmeta(2, &descs);
        let h = parse_header(&st).unwrap();
        assert_eq!(chain_partitions(&st, &h), vec!["boot", "vbmeta_system"]);
    }

    #[test]
    fn ignores_non_chain_descriptors() {
        // tag 2 (hash) descriptor, 8-byte body: no chain names.
        let mut hash_desc = Vec::new();
        hash_desc.extend_from_slice(&be64(2));
        hash_desc.extend_from_slice(&be64(8));
        hash_desc.extend_from_slice(&[0u8; 8]);
        let st = vbmeta(2, &hash_desc);
        let h = parse_header(&st).unwrap();
        assert!(chain_partitions(&st, &h).is_empty());
    }

    #[test]
    fn parses_footer() {
        let mut footer = vec![0u8; FOOTER_SIZE];
        footer[0..4].copy_from_slice(FOOTER_MAGIC);
        footer[20..28].copy_from_slice(&be64(39_976_960));
        footer[28..36].copy_from_slice(&be64(2432));
        assert_eq!(parse_footer(&footer), Some((39_976_960, 2432)));
        assert!(parse_footer(&[0u8; FOOTER_SIZE]).is_none());
    }
}

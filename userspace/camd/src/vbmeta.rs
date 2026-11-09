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
//!
//! Also computes `ro.boot.vbmeta.public_key_digest` (SHA-256 of the top-level struct's
//! public key blob), filled under the same rule.
#![cfg_attr(not(target_os = "android"), allow(dead_code))]

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
/// `public_key_{offset,size}` locate the blob inside the auxiliary block that
/// `ro.boot.vbmeta.public_key_digest` is the hash of.
struct Header {
    auth_size: u64,
    aux_size: u64,
    public_key_offset: u64,
    public_key_size: u64,
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
        // public_key_offset at 64, public_key_size at 72 — both relative to the
        // auxiliary block, which starts right after header + authentication block.
        // (80/88 are public_key_metadata_{offset,size}: never read those here.)
        public_key_offset: be_u64(blob, 64)?,
        public_key_size: be_u64(blob, 72)?,
        descriptors_offset: be_u64(blob, 96)?,
        descriptors_size: be_u64(blob, 104)?,
    })
}

/// `ro.boot.vbmeta.public_key_digest` for a top-level vbmeta struct: the SHA-256 of the
/// public key blob in the auxiliary block, hex-encoded (lowercase, no padding — the same
/// shape the bootloader writes, and the shape `verifiedBootKey` carries in a certificate).
///
/// The hashed input is the key blob that `avb_vbmeta_image_verify` hands back as
/// `out_public_key_data` (`aux + public_key_offset`, `public_key_size` bytes, in AVB's
/// own `AvbRSAPublicKeyHeader` encoding, not DER) — never the descriptors and never the
/// whole struct (that is `ro.boot.vbmeta.digest`). Not every bootloader exposes this:
/// the TB323FU has no such property and attests SHA-256 of an empty key instead.
///
/// `None` when the struct is truncated, the key size is zero (a struct with no key of
/// its own), or the blob falls outside the aux block.
fn public_key_digest(blob: &[u8], h: &Header) -> Option<String> {
    let key_size = usize::try_from(h.public_key_size).ok()?;
    if key_size == 0 {
        return None;
    }
    let aux_size = usize::try_from(h.aux_size).ok()?;
    let key_offset = usize::try_from(h.public_key_offset).ok()?;
    // The key must fit entirely inside the auxiliary block, or the struct is corrupt.
    key_offset
        .checked_add(key_size)
        .filter(|end| *end <= aux_size)?;
    let aux_start = HEADER_SIZE.checked_add(usize::try_from(h.auth_size).ok()?)?;
    let start = aux_start.checked_add(key_offset)?;
    let key = blob.get(start..start.checked_add(key_size)?)?;
    Some(sha256::digest(key))
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
        public_key_digest, struct_len,
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

    /// The device's own `ro.boot.vbmeta.public_key_digest`, computed from the top-level
    /// `vbmeta` partition only — on bootloaders that set this property, that is the struct
    /// whose key they hash. A chained vbmeta's key lives in a chain descriptor, not in the
    /// aux block, so descending the chain would hash the wrong bytes. `None` when the
    /// partition cannot be read, or when its struct carries no key of its own.
    pub fn computed_public_key_digest() -> Option<String> {
        let st = partition_struct("vbmeta")?;
        let header = parse_header(&st)?;
        public_key_digest(&st, &header)
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

    /// A vbmeta struct carrying `key` as its public key: header + optional auth gap +
    /// aux block, where the key sits at `key_offset` inside the aux.
    fn vbmeta_with_key(auth_size: usize, key_offset: usize, key: &[u8]) -> Vec<u8> {
        let aux_size = key_offset + key.len();
        let mut header = vec![0u8; HEADER_SIZE];
        header[0..4].copy_from_slice(AVB_MAGIC);
        header[12..20].copy_from_slice(&be64(auth_size as u64));
        header[20..28].copy_from_slice(&be64(aux_size as u64));
        header[28..32].copy_from_slice(&be32(2));
        header[64..72].copy_from_slice(&be64(key_offset as u64)); // public_key_offset
        header[72..80].copy_from_slice(&be64(key.len() as u64)); // public_key_size
        header[96..104].copy_from_slice(&be64(0));
        header[104..112].copy_from_slice(&be64(0));

        let mut out = header;
        out.extend(std::iter::repeat_n(0u8, auth_size)); // authentication block
        out.extend(std::iter::repeat_n(0u8, key_offset)); // other aux content
        out.extend_from_slice(key);
        out
    }

    #[test]
    fn hashes_only_the_public_key_blob() {
        // Arbitrary bytes, not a real AvbRSAPublicKeyHeader key: the function must hash
        // the blob verbatim, not parse or canonicalise it.
        let key: Vec<u8> = (0u8..=255).cycle().take(300).collect();
        let st = vbmeta_with_key(0, 0, &key);
        let h = parse_header(&st).unwrap();
        assert_eq!(h.public_key_size, key.len() as u64);
        assert_eq!(
            public_key_digest(&st, &h),
            Some(sha256::digest(key.as_slice()))
        );
    }

    #[test]
    fn public_key_offset_is_relative_to_aux_not_header() {
        // 64 bytes of auth block and 40 bytes of descriptor before the key: the offset
        // must be measured from the start of aux, so the auth gap is skipped.
        let key: Vec<u8> = (0u8..200).collect();
        let st = vbmeta_with_key(64, 40, &key);
        let h = parse_header(&st).unwrap();
        assert_eq!(
            public_key_digest(&st, &h),
            Some(sha256::digest(key.as_slice()))
        );
    }

    #[test]
    fn no_key_yields_none() {
        // No key of its own (chained vbmeta): the bootloader leaves the property empty.
        let st = vbmeta(2, &[]);
        let h = parse_header(&st).unwrap();
        assert_eq!(h.public_key_size, 0);
        assert!(public_key_digest(&st, &h).is_none());
    }

    #[test]
    fn key_outside_aux_yields_none() {
        let key = [0xABu8; 32];
        let mut st = vbmeta_with_key(0, 0, &key);
        // Claim the key runs past the aux block: corrupt, so no digest rather than a
        // hash of whatever follows.
        st[72..80].copy_from_slice(&be64(key.len() as u64 + 8));
        let h = parse_header(&st).unwrap();
        assert!(public_key_digest(&st, &h).is_none());
    }

    #[test]
    fn reads_key_fields_not_metadata_fields() {
        // The aux layout avbtool's make_vbmeta_image writes: descriptors at aux offset 0,
        // the key right after, then public_key_metadata_offset pointing past the key with
        // size 0 (no metadata), the aux padded to 64. Every offset field is set as avbtool
        // sets it, so reading the metadata fields (80/88) instead of the key fields
        // (64/72) would hash descriptors + key here and fail.
        let descriptors = [0x11u8; 40];
        let key: Vec<u8> = (0u8..=255).cycle().take(520).collect();
        let key_offset = descriptors.len();
        let used = key_offset + key.len();
        let aux_size = used.div_ceil(64) * 64;
        let auth_size = 576;

        let mut header = vec![0u8; HEADER_SIZE];
        header[0..4].copy_from_slice(AVB_MAGIC);
        header[12..20].copy_from_slice(&be64(auth_size as u64));
        header[20..28].copy_from_slice(&be64(aux_size as u64));
        header[28..32].copy_from_slice(&be32(2));
        header[32..40].copy_from_slice(&be64(0)); // hash_offset
        header[40..48].copy_from_slice(&be64(32)); // hash_size
        header[48..56].copy_from_slice(&be64(32)); // signature_offset
        header[56..64].copy_from_slice(&be64(256)); // signature_size
        header[64..72].copy_from_slice(&be64(key_offset as u64)); // public_key_offset
        header[72..80].copy_from_slice(&be64(key.len() as u64)); // public_key_size
        header[80..88].copy_from_slice(&be64(used as u64)); // public_key_metadata_offset
        header[88..96].copy_from_slice(&be64(0)); // public_key_metadata_size
        header[96..104].copy_from_slice(&be64(0)); // descriptors_offset
        header[104..112].copy_from_slice(&be64(descriptors.len() as u64)); // descriptors_size

        let mut st = header;
        st.extend(std::iter::repeat_n(0xA5u8, auth_size)); // authentication block
        st.extend_from_slice(&descriptors);
        st.extend_from_slice(&key);
        st.resize(HEADER_SIZE + auth_size + aux_size, 0);

        let h = parse_header(&st).unwrap();
        assert_eq!(h.public_key_offset, key_offset as u64);
        assert_eq!(h.public_key_size, key.len() as u64);
        assert_eq!(struct_len(&h), Some(st.len()));
        assert_eq!(
            public_key_digest(&st, &h),
            Some(sha256::digest(key.as_slice()))
        );
    }

    #[test]
    fn truncated_struct_yields_none() {
        let key = [0x7Fu8; 32];
        let mut st = vbmeta_with_key(0, 0, &key);
        st.truncate(HEADER_SIZE + 16); // aux cut short
        assert!(parse_header(&st).is_some());
        let h = parse_header(&st).unwrap();
        assert!(public_key_digest(&st, &h).is_none());
    }
}

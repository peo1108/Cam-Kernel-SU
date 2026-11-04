//! Names the kernel, modules and older installs still know under KernelSU's names (`KSU_*`
//! module variables, `u:r:ksu:s0`, `[ksu_driver]`, `/data/adb/ksu`...), kept out of the binary as
//! text. camd ships inside the Manager APK as `libcamd.so`, and any app that can see the Manager
//! can read that file: a root checker scanning it must find no KernelSU name
//! (docs/CAM_CHANGES.md, row 85).
//!
//! `hidden!("KSU")` encodes the literal at compile time; only the encoded bytes reach the binary,
//! and they are decoded when used. Run `scripts/check_apk_strings.py` on a release APK to check.

/// Mask for byte `i`; varies per position so a fixed XOR scan finds nothing either.
const fn mask(key: u8, i: usize) -> u8 {
    key.wrapping_add((i as u8).wrapping_mul(29)) ^ 0x5c
}

const KEY: u8 = 0xa7;

pub const fn encode<const N: usize>(text: &str) -> [u8; N] {
    let bytes = text.as_bytes();
    let mut out = [0u8; N];
    let mut i = 0;
    while i < N {
        out[i] = bytes[i] ^ mask(KEY, i);
        i += 1;
    }
    out
}

pub fn decode(encoded: &[u8]) -> String {
    // black_box keeps the optimizer from folding the decode back into the plain text
    let key = std::hint::black_box(KEY);
    let bytes = encoded
        .iter()
        .enumerate()
        .map(|(i, b)| b ^ mask(key, i))
        .collect();
    String::from_utf8(bytes).expect("hidden! takes UTF-8 literals")
}

/// The literal as a `String`, stored encoded in the binary.
#[macro_export]
macro_rules! hidden {
    ($text:literal) => {{
        const N: usize = $text.len();
        const ENCODED: [u8; N] = $crate::hidden::encode::<N>($text);
        $crate::hidden::decode(&ENCODED)
    }};
}

#[cfg(test)]
mod tests {
    #[test]
    fn round_trips() {
        assert_eq!(hidden!("KSU_MODULE"), "KSU_MODULE");
        assert_eq!(hidden!("u:r:ksu:s0"), "u:r:ksu:s0");
        assert_eq!(hidden!(""), "");
    }

    #[test]
    fn encoded_bytes_hold_no_plain_text() {
        const ENCODED: [u8; 10] = super::encode::<10>("KSU_MODULE");
        assert!(!ENCODED.windows(3).any(|w| w == b"KSU"));
    }
}

package cam.caesar.vn.attest

// KeyMint integer encodings for OS version and patch levels (spec §3.2). Used when a profile
// overrides the harvested values from a date the user enters.

/** osVersion: major*10000 + minor*100 + sub (e.g. 16.0.0 -> 160000). */
fun osVersionInt(major: Int, minor: Int, sub: Int): Int = major * 10000 + minor * 100 + sub

/** osPatchLevel: YYYYMM (e.g. 2026-11 -> 202611). */
fun osPatchLevelInt(year: Int, month: Int): Int = year * 100 + month

/** vendor/boot patch level: YYYYMMDD (e.g. 2026-11-05 -> 20261105). */
fun datePatchLevelInt(year: Int, month: Int, day: Int): Int = year * 10000 + month * 100 + day

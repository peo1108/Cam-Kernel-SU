#!/usr/bin/env python3
"""Lists upstream (KernelSU) names left in a Manager APK, or in any files given.

Any app that can see the Manager can read its APK; a root checker scanning it must not find
"KernelSU", "ksu" or "weishu" (docs/CAM_CHANGES.md row 85). Names that still have to exist at
runtime are built by camd's `hidden!` and the Manager's `Hidden.s`, so they never appear as text.

    python3 scripts/check_apk_strings.py Cam_<version>_<code>-release.apk [more files...]

Exit status 1 when anything is found. Images and fonts are skipped (random bytes), and so are
names where "ksu" sits inside a word ("checksum", "LockSupport", "zygisksu"), and matches inside
compressed data: camd carries caminit and the .ko compressed, and three random bytes there spell
"ksU" often enough to fail a build now and then.
"""
import math
import re
import sys
import zipfile
from collections import Counter

# "ksu" not preceded by a letter, so words that merely contain it stay out
PATTERN = re.compile(rb"(?i)(?<![a-z])ksu|kernel_?su|weishu")
SKIP_SUFFIXES = (".png", ".webp", ".jpg", ".jpeg", ".gif", ".ttf", ".otf", ".filamat", ".glb", ".ktx", ".ogg", ".mp3")
CONTEXT = 24


MIN_TEXT_RUN = 8
# Bits per byte over 512 bytes around a match. Measured on a release APK: real strings in camd,
# libcamjni and classes.dex 4.4 to 5.0, camd's compressed payload 7.56 to 7.59, /dev/urandom 7.59.
ENTROPY_WINDOW = 256
MAX_TEXT_ENTROPY = 7.3


def entropy(data):
    counts = Counter(data)
    return -sum(n / len(data) * math.log2(n / len(data)) for n in counts.values())


def printable_run(data, start, end):
    """Length of the run of printable ASCII around data[start:end]."""
    while start > 0 and 0x20 <= data[start - 1] < 0x7F:
        start -= 1
    while end < len(data) and 0x20 <= data[end] < 0x7F:
        end += 1
    return end - start


def scan(name, data, hits):
    for m in PATTERN.finditer(data):
        # three letters inside compressed or random bytes are noise, not a name
        if printable_run(data, m.start(), m.end()) < MIN_TEXT_RUN:
            continue
        if entropy(data[max(0, m.start() - ENTROPY_WINDOW) : m.start() + ENTROPY_WINDOW]) > MAX_TEXT_ENTROPY:
            continue
        start, end = max(0, m.start() - CONTEXT), min(len(data), m.end() + CONTEXT)
        text = data[start:end].decode("latin-1")
        text = "".join(c if 32 <= ord(c) < 127 else "." for c in text)
        hits.append((name, m.start(), text))


def main(paths):
    hits = []
    for path in paths:
        if zipfile.is_zipfile(path):
            with zipfile.ZipFile(path) as z:
                for info in z.infolist():
                    if info.is_dir() or info.filename.lower().endswith(SKIP_SUFFIXES):
                        continue
                    scan(f"{path}!{info.filename}", z.read(info), hits)
        else:
            with open(path, "rb") as f:
                scan(path, f.read(), hits)
    for name, offset, text in hits:
        print(f"{name} @{offset:#x}: {text}")
    print(f"{len(hits)} upstream name(s) found", file=sys.stderr)
    return 1 if hits else 0


def encode(text):
    """The Manager's Hidden.s() form of text: every character shifted up by one."""
    return "".join(chr(ord(c) + 1) for c in text)


if __name__ == "__main__":
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    if sys.argv[1] == "--encode":
        for text in sys.argv[2:]:
            print(f"{text!r} -> {encode(text)!r}")
        sys.exit(0)
    sys.exit(main(sys.argv[1:]))

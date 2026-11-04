#!/usr/bin/env python3
"""Tests for check_apk_strings.py: python3 -m unittest scripts/test_check_apk_strings.py"""

import random
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from check_apk_strings import main, scan  # noqa: E402

# What the strings of a binary look like around a name.
STRINGS = b"\0".join([b"/proc/self/fd", b"failed to open", b"usage: camd [options]", b"/data/adb/modules"] * 8)
# What camd's compressed payload looks like (seeded: the same bytes every run).
NOISE = random.Random(1).randbytes(4096)


def hits(data):
    found = []
    scan("file", data, found)
    return [text for _, _, text in found]


class ScanTest(unittest.TestCase):
    def test_finds_names_among_strings(self):
        for name in (b"[ksu_driver]", b"KernelSU", b"kernelsu.ko", b"kernel_su_x", b"me.weishu.kernelsu", b"KSU_MODULE"):
            self.assertTrue(hits(STRINGS + b"\0" + name + b"\0" + STRINGS), name)

    def test_finds_a_name_in_a_small_file(self):
        self.assertTrue(hits(b"export KSU_MODULE=x\n"))

    def test_skips_words_that_only_contain_ksu(self):
        for word in (b"checksum", b"LockSupport", b"zygisksu"):
            self.assertEqual(hits(STRINGS + b"\0" + word + b"\0" + STRINGS), [], word)

    def test_skips_a_short_run(self):
        self.assertEqual(hits(STRINGS + b"\0\xe6ksu\xa3\0" + STRINGS), [])

    def test_skips_a_long_run_inside_compressed_data(self):
        # seen in the x86_64 camd of a CI build: "|b+E)ksU" failed the build
        self.assertEqual(hits(NOISE[:2048] + b"\xe6|b+E)ksU\xa3" + NOISE[2048:]), [])

    def test_a_string_next_to_compressed_data_is_still_found(self):
        self.assertTrue(hits(NOISE + b"\0" + STRINGS + b"\0[ksu_driver]\0" + STRINGS + b"\0" + NOISE))


class MainTest(unittest.TestCase):
    def apk(self, directory, entries):
        path = Path(directory) / "a.apk"
        with zipfile.ZipFile(path, "w") as z:
            for name, data in entries.items():
                z.writestr(name, data)
        return str(path)

    def test_status_follows_what_it_finds(self):
        with tempfile.TemporaryDirectory() as d:
            clean = self.apk(d, {"classes.dex": STRINGS, "lib/libcamd.so": NOISE})
            self.assertEqual(main([clean]), 0)
            dirty = self.apk(d, {"classes.dex": STRINGS + b"\0KernelSU\0" + STRINGS})
            self.assertEqual(main([dirty]), 1)

    def test_images_are_skipped(self):
        with tempfile.TemporaryDirectory() as d:
            self.assertEqual(main([self.apk(d, {"res/a.png": STRINGS + b"\0KernelSU\0"})]), 0)


if __name__ == "__main__":
    unittest.main()

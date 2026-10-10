#!/usr/bin/env python3
"""Tests for changelog_section.py: python3 -m unittest scripts/test_changelog_section.py"""

import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from changelog_section import section  # noqa: E402

SCRIPT = Path(__file__).resolve().parent / "changelog_section.py"

TEXT = """# Nhật ký cập nhật Cam Kernel SU

## 3.0.1 - 2026-10-20
### Sửa lỗi
- Sửa lỗi A

## 3.0.0 - 2026-10-12
### Tính năng mới
- Thêm OTA
- Có gì mới
"""


class SectionTest(unittest.TestCase):
    def test_returns_middle_entry(self):
        self.assertEqual(section(TEXT, "3.0.1"), "### Sửa lỗi\n- Sửa lỗi A")

    def test_last_entry_runs_to_eof(self):
        self.assertEqual(section(TEXT, "3.0.0"), "### Tính năng mới\n- Thêm OTA\n- Có gì mới")

    def test_missing_entry_is_none(self):
        self.assertIsNone(section(TEXT, "9.9.9"))

    def test_empty_entry_is_none(self):
        text = "## 3.0.2 - 2026-10-21\n\n## 3.0.1 - 2026-10-20\n- A\n"
        self.assertIsNone(section(text, "3.0.2"))

    def test_crlf_matches_lf(self):
        self.assertEqual(section(TEXT.replace("\n", "\r\n"), "3.0.1"), section(TEXT, "3.0.1"))

    def test_version_is_not_a_prefix_match(self):
        text = "## 3.0.10 - 2026-11-01\n- Mười\n"
        self.assertIsNone(section(text, "3.0.1"))

    def test_cli_exit_codes(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "CHANGELOG.md"
            path.write_text(TEXT, encoding="utf-8")
            missing = subprocess.run([sys.executable, str(SCRIPT), "9.9.9", str(path)], capture_output=True)
            self.assertEqual(missing.returncode, 1)
            found = subprocess.run([sys.executable, str(SCRIPT), "3.0.1", str(path)], capture_output=True)
            self.assertEqual(found.returncode, 0)
            self.assertEqual(found.stdout.decode("utf-8").strip(), "### Sửa lỗi\n- Sửa lỗi A")


if __name__ == "__main__":
    unittest.main()

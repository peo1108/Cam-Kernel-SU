#!/usr/bin/env python3
"""Print one version's entry from CHANGELOG.md (the Release workflow's notes).

Usage: changelog_section.py <version> [CHANGELOG.md]
Exits 1 when the entry is missing or empty, so a tag without notes never ships.
"""

import re
import sys
from pathlib import Path

HEADING = re.compile(r"^## (\d+\.\d+\.\d+)(\s|$)")


def section(text: str, version: str) -> str | None:
    """Body of the `## <version> - <date>` entry, without its heading, or None."""
    body = None
    for line in text.replace("\r\n", "\n").split("\n"):
        match = HEADING.match(line)
        if match:
            if body is not None:
                break
            if match.group(1) == version:
                body = []
            continue
        if body is not None:
            body.append(line)
    if body is None:
        return None
    result = "\n".join(body).strip("\n")
    return result if result.strip() else None


def main() -> int:
    if len(sys.argv) not in (2, 3):
        print(__doc__.strip(), file=sys.stderr)
        return 2
    version = sys.argv[1]
    path = Path(sys.argv[2]) if len(sys.argv) == 3 else Path(__file__).resolve().parent.parent / "CHANGELOG.md"
    body = section(path.read_text(encoding="utf-8"), version)
    if body is None:
        print(f"{path}: no '## {version} - <date>' entry, or it is empty", file=sys.stderr)
        return 1
    sys.stdout.buffer.write((body + "\n").encode("utf-8"))
    return 0


if __name__ == "__main__":
    sys.exit(main())

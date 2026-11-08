#!/usr/bin/env python3
"""Scans a device with the root detectors Cam checks before a release and compares with the last
clean scan (docs/CAM_CHANGES.md row 99).

    python -X utf8 scripts/device_detector_scan.py [--serial S] [--only duck,luna] [--save]

For each detector installed on the device: force-stop, launch (it scans on start), wait until its
screen stops changing, scroll through the result and read every card (a clickable block of text,
"Bootloader | 6 critical boot integrity signal(s) | Danger") with uiautomator. The cards are
compared with the baseline of that device model (scripts/detector_baselines/<model>.json): a card
that is new or gone is a change, and the exit status is 1. --save writes this scan as the new
baseline (after a scan judged clean by hand). Nothing is tapped but the launcher and the "later"
buttons of update dialogs; nothing on the device changes (the detectors turn auto-rotate on when
they start: the rotation settings are put back afterwards).

Needs adb (on PATH, or the Android SDK's platform-tools). The device must be unlocked with the
screen on. Run from Git Bash with python -X utf8 (Vietnamese and Chinese labels).
"""
from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from dataclasses import dataclass, field
from pathlib import Path

BASELINES = Path(__file__).resolve().parent / "detector_baselines"


@dataclass
class Detector:
    key: str
    name: str
    # exact package, or a pattern matched against the installed packages
    package: str = ""
    package_pattern: str = ""
    # seconds to leave the scan alone before reading (Luna reports "Farm Fingerprint" until ~40 s)
    min_wait: int = 15
    max_wait: int = 90
    # lines that change on every scan and say nothing (times, build stamps)
    noise: list[str] = field(default_factory=list)
    # a card matching one of these means the scan is still running, however still the screen is
    busy: list[str] = field(default_factory=list)
    # cards left out of the result: verdicts that say nothing is wrong
    quiet: list[str] = field(default_factory=list)


# Every line matching one of these is left out of a card, for every detector.
COMMON_NOISE = [
    r"\b\d{1,2}:\d{2}(:\d{2})?\b",  # times
    r"\b\d{4}[-./]\d{1,2}[-./]\d{1,2}\b",  # dates
    r"\b\d+(\.\d+)?\s*(ms|s|giây|秒)\b",  # durations
]

DETECTORS = [
    Detector(
        "duck",
        "Duck Detector",
        package="com.eltavine.duckdetector",
        noise=[
            r"^Tap to export full report$",
            r"^Chạm để xuất báo cáo",
            r"^\d{4}\.\d{2}\.\d{2}-[0-9a-f]+",
            # the app's frame, read or not depending on the layout the rotation gives
            r"^(Trang chủ|Cài đặt|Home|Settings|ALPHA|Telegram|Duck Detector)$",
        ],
        busy=[r"\b[1-9]\d* \| Pending\b"],  # the summary counts probes not finished yet
        # the list below the warnings is read only when the swipe gets that far; a probe that
        # turns Warning or Danger is a new card, and the counts card changes too
        quiet=[r"\| (Info|Clear)$"],
    ),
    Detector(
        "luna",
        "Luna",
        package_pattern=r"(?i)luna",
        min_wait=45,
        max_wait=120,
        noise=[r"^Luna [\d.]+$"],
        # while scanning it shows one category at a time ("2/8 BootLoader"), then the verdict
        # ("Trust Phone | 8/8")
        busy=[r"^\d+/\d+ \S"],
    ),
    Detector(
        "chunqiu",
        "Chunqiu Native Check",
        package_pattern=r"(?i)chunqiu|nativecheck|native\.check|eros",
        noise=[r"^\d+(\.\d+){1,3}$"],  # its version in the header
        # after the local checks it verifies online for a while, with the screen still
        busy=[r"(?i)verifying online", r"(?i)^local checks complete"],
        max_wait=150,
    ),
]

# Buttons that close an update or permission prompt without changing anything.
LATER = ["Để sau", "Later", "Not now", "Don’t allow", "Don't allow", "Không cho phép", "稍后", "以后再说", "取消"]


# ---------------------------------------------------------------- pure parts (unit tested)

BOUNDS = re.compile(r"\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]")


def _bounds(node: ET.Element) -> tuple[int, int, int, int] | None:
    m = BOUNDS.match(node.get("bounds") or "")
    return tuple(int(x) for x in m.groups()) if m else None  # type: ignore[return-value]


def _label(node: ET.Element) -> str:
    return re.sub(r"\s+", " ", (node.get("text") or node.get("content-desc") or "")).strip()


def _noisy(line: str, noise: list[str]) -> bool:
    return any(re.search(p, line) for p in [*COMMON_NOISE, *noise])


def cards(xml: str, noise: list[str] | None = None) -> list[str]:
    """The cards on one screen, top to bottom: the text of each clickable block (without the text
    of clickable blocks inside it) joined by " | ", and the texts no clickable block holds,
    joined per parent: Luna's verdict ("Trust Phone | 8/8") and Duck's counts ("0 | Danger | 2 |
    Warning | ...") are plain text side by side, and a count means nothing apart from its label.
    Blocks cut by the edge of the scrolling area are left out: they come back whole after a
    scroll."""
    noise = noise or []
    root = ET.fromstring(xml)
    scrollers = [b for n in root.iter("node") if n.get("scrollable") == "true" and (b := _bounds(n))]
    area = max(scrollers, key=lambda b: (b[2] - b[0]) * (b[3] - b[1])) if scrollers else None

    def cut(b: tuple[int, int, int, int]) -> bool:
        # empty, or cut by the scrolling area's edge (or outside it: toolbars, the navigation bar)
        if b[2] <= b[0] or b[3] <= b[1]:
            return True
        return bool(area and (b[1] < area[1] or b[3] > area[3] or b[1] == area[1] or b[3] == area[3]))

    found: list[tuple[int, int, str]] = []

    def loose(parent: ET.Element) -> None:
        # clickable blocks are cards of their own (below), with every text inside them
        children = [c for c in parent if c.get("clickable") != "true"]
        group = []
        for child in children:
            label, b = _label(child), _bounds(child)
            if label and b and not cut(b) and not _noisy(label, noise):
                group.append((b, label))
        if group:
            first = group[0][0]
            found.append((first[1], first[0], " | ".join(label for _, label in group)))
        for child in children:
            loose(child)

    loose(root)

    def texts(node: ET.Element) -> list[str]:
        out = []
        label = _label(node)
        if label:
            out.append(label)
        for child in node:
            if child.get("clickable") == "true":
                continue
            out.extend(texts(child))
        return out

    for node in root.iter("node"):
        if node.get("clickable") != "true":
            continue
        b = _bounds(node)
        if not b or cut(b):
            continue
        lines = [t for t in texts(node) if not _noisy(t, noise)]
        if lines:
            found.append((b[1], b[0], " | ".join(lines)))
    return [text for _, _, text in sorted(found)]


def busy(screen: list[str], patterns: list[str]) -> bool:
    """Whether a card says the scan is still running."""
    return any(re.search(p, card) for p in patterns for card in screen)


def merge(seen: list[str], screen: list[str]) -> list[str]:
    """[seen] with the cards of a new screen added in order (scrolling shows some twice)."""
    out = list(seen)
    for card in screen:
        if card not in out:
            out.append(card)
    return out


def compare(baseline: list[str], now: list[str]) -> tuple[list[str], list[str]]:
    """(new cards, gone cards). A card is its whole text: a changed verdict is one gone, one new."""
    return [c for c in now if c not in baseline], [c for c in baseline if c not in now]


# ---------------------------------------------------------------- device


class Device:
    def __init__(self, serial: str | None):
        adb = shutil.which("adb") or shutil.which("adb.exe")
        if not adb:
            sdk = Path(os.environ.get("LOCALAPPDATA", "")) / "Android" / "Sdk" / "platform-tools" / "adb.exe"
            adb = str(sdk) if sdk.exists() else None
        if not adb:
            sys.exit("adb not found")
        self.adb = adb
        if not serial:
            lines = self._run("devices").splitlines()[1:]
            online = [l.split("\t")[0] for l in lines if l.endswith("\tdevice")]
            if len(online) != 1:
                sys.exit(f"pick a device with --serial: {online or 'none online'}")
            serial = online[0]
        self.serial = serial

    def _run(self, *args: str) -> str:
        return subprocess.run([self.adb, *args], capture_output=True, text=True, encoding="utf-8", errors="replace").stdout

    def sh(self, cmd: str) -> str:
        return self._run("-s", self.serial, "shell", cmd)

    def screen(self) -> str:
        # exec-out: uiautomator writes to stdout through /dev/tty, so dump to a file and cat it
        self.sh("uiautomator dump /data/local/tmp/cam_detector_ui.xml >/dev/null")
        return self._run("-s", self.serial, "exec-out", "cat /data/local/tmp/cam_detector_ui.xml")

    def tap(self, x: int, y: int) -> None:
        self.sh(f"input tap {x} {y}")

    def size(self) -> tuple[int, int]:
        # the last line: "Override size" when there is one
        lines = self.sh("wm size").splitlines() or [""]
        m = re.search(r"(\d+)x(\d+)", lines[-1])
        return (int(m.group(1)), int(m.group(2))) if m else (1080, 2400)

    ROTATION = ("accelerometer_rotation", "user_rotation")

    def rotation(self) -> dict[str, str]:
        return {k: self.sh(f"settings get system {k}").strip() for k in self.ROTATION}

    def set_rotation(self, saved: dict[str, str]) -> None:
        # the detectors rewrite these when they start (auto-rotate on, portrait)
        for k, v in saved.items():
            if v and v != "null":
                self.sh(f"settings put system {k} {v}")

    def cleanup(self) -> None:
        # uiautomator leaves an empty dalvik-cache behind; rmdir keeps it if anything else is in it
        self.sh("rm -f /data/local/tmp/cam_detector_ui.xml")
        self.sh("rmdir /data/local/tmp/dalvik-cache/arm64 /data/local/tmp/dalvik-cache 2>/dev/null")


def resolve(d: Detector, packages: list[str]) -> str | None:
    if d.package:
        return d.package if d.package in packages else None
    hits = [p for p in packages if re.search(d.package_pattern, p)]
    return hits[0] if len(hits) == 1 else None


def dismiss_prompts(dev: Device, xml: str) -> bool:
    try:
        root = ET.fromstring(xml)
    except ET.ParseError:
        return False
    for node in root.iter("node"):
        if _label(node) in LATER and (b := _bounds(node)):
            dev.tap((b[0] + b[2]) // 2, (b[1] + b[3]) // 2)
            return True
    return False


def scan(dev: Device, d: Detector, package: str) -> list[str]:
    dev.sh(f"am force-stop {package}")
    dev.sh(f"monkey -p {package} -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1")
    start = time.time()
    time.sleep(d.min_wait)
    # settled: the same cards twice in a row, none of them saying the scan still runs
    last: list[str] | None = None
    while time.time() - start < d.max_wait:
        xml = dev.screen()
        if dismiss_prompts(dev, xml):
            time.sleep(2)
            continue
        try:
            now = cards(xml, d.noise)
        except ET.ParseError:
            now = []
        if now and now == last and not busy(now, d.busy):
            break
        last = now
        time.sleep(3)
    w, h = dev.size()
    seen = last or []
    quiet = 0
    for _ in range(20):
        dev.sh(f"input swipe {w // 2} {h * 3 // 4} {w // 2} {h // 3} 400")
        time.sleep(1.2)
        try:
            more = merge(seen, cards(dev.screen(), d.noise))
        except ET.ParseError:
            continue
        quiet = quiet + 1 if more == seen else 0
        seen = more
        if quiet == 2:
            break
    return [c for c in seen if not busy([c], d.quiet)]


def version_of(dev: Device, package: str) -> str:
    m = re.search(r"versionName=(\S+)", dev.sh(f"dumpsys package {package} | grep -m1 versionName"))
    return m.group(1) if m else "?"


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--serial")
    ap.add_argument("--only", help="comma separated: " + ",".join(d.key for d in DETECTORS))
    ap.add_argument("--save", action="store_true", help="write this scan as the device's baseline")
    ap.add_argument("--baseline", type=Path, help="baseline file (default: by device model)")
    args = ap.parse_args()

    dev = Device(args.serial)
    model = dev.sh("getprop ro.product.model").strip() or "unknown"
    path = args.baseline or BASELINES / f"{re.sub(r'[^A-Za-z0-9_.-]', '_', model)}.json"
    baseline = json.loads(path.read_text(encoding="utf-8")) if path.exists() else {}
    packages = [l.removeprefix("package:").strip() for l in dev.sh("pm list packages").splitlines() if l.startswith("package:")]
    want = set(args.only.split(",")) if args.only else None

    print(f"device {dev.serial} ({model}), baseline {path.name}{'' if baseline else ' (none yet)'}")
    rotation = dev.rotation()
    try:
        changed, result = scan_all(dev, packages, want, baseline)
    finally:
        dev.set_rotation(rotation)
        dev.cleanup()

    if args.save:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(f"\nbaseline saved: {path}")
    elif changed:
        print("\nchanged: read the + and - cards; when the new state is right, run again with --save")
    return 1 if changed and not args.save else 0


def scan_all(dev: Device, packages: list[str], want: set[str] | None, baseline: dict) -> tuple[bool, dict]:
    changed = False
    result = dict(baseline)
    for d in DETECTORS:
        if want and d.key not in want:
            continue
        package = resolve(d, packages)
        if not package:
            print(f"\n== {d.name}: not installed (or more than one package matches), skipped")
            continue
        version = version_of(dev, package)
        print(f"\n== {d.name} ({package} {version})")
        now = scan(dev, d, package)
        if not now:
            print("   no cards read: screen locked or off, or the app changed its layout")
            changed = True
            continue
        # the first card of several lines is the summary (single lines are buttons: "Telegram")
        print("   " + next((c for c in now if " | " in c), now[0]))
        old = baseline.get(d.key)
        if not old:
            print(f"   no baseline: {len(now)} cards read")
            for c in now[1:]:
                print("   . " + c)
        else:
            new, gone = compare(old["cards"], now)
            if old.get("version") != version:
                print(f"   app updated since the baseline: {old.get('version')} -> {version}")
            if new or gone:
                changed = True
                for c in new:
                    print("   + " + c)
                for c in gone:
                    print("   - " + c)
            else:
                print(f"   same as the baseline ({len(now)} cards)")
        result[d.key] = {"package": package, "version": version, "saved": time.strftime("%Y-%m-%d"), "cards": now}
    return changed, result


if __name__ == "__main__":
    sys.exit(main())

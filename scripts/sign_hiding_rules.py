#!/usr/bin/env python3
"""Sign the root hiding rules the Manager downloads.

The Manager ships manager/app/src/main/assets/hiding-rules.json and, when the hiding check
page opens, fetches the rules from caesarvn.xyz/feed with their .sig next to them. It only
takes a download whose ECDSA P-256 (SHA-256) signature checks out against the public key in
HidingRulesRepository.kt, and whose "version" is newer than the one it has.

The feed serves site/feed/hiding-rules.json, which this script writes from the APK's copy:
the same rules with the module mount source ("KSU") back at the head of mountSources. The
APK's copy leaves that upstream name out and the Manager and camd add it when they read the
rules (docs/CAM_CHANGES.md row 85), but Managers before 1.2.3-beta.4 (every cam.su.kernel
install among them) read mountSources as written and would stop checking it. Both files are
signed; site/scripts/build-feed.mjs refuses to publish when their versions differ.

    python scripts/sign_hiding_rules.py --gen-key ~/.cam/hiding-rules-key.pem
        new key pair; prints the public key to paste into HidingRulesRepository.kt
    python scripts/sign_hiding_rules.py --key ~/.cam/hiding-rules-key.pem
        writes hiding-rules.json.sig, site/feed/hiding-rules.json and its .sig; bump
        "version" first, or apps keep what they have

Never commit the private key.
"""

import argparse
import base64
import json
import os
import re
import sys
from pathlib import Path

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec

ROOT = Path(__file__).resolve().parent.parent
RULES = ROOT / "manager/app/src/main/assets/hiding-rules.json"
FEED_RULES = ROOT / "site/feed/hiding-rules.json"
# what module mounts carry as their source; kept out of the APK, kept in the feed for old Managers
MODULE_MOUNT_SOURCE = "KSU"


def public_key_b64(key: ec.EllipticCurvePrivateKey) -> str:
    der = key.public_key().public_bytes(
        serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo
    )
    return base64.b64encode(der).decode()


def gen_key(path: Path) -> None:
    if path.exists():
        sys.exit(f"{path} exists, not overwriting it")
    key = ec.generate_private_key(ec.SECP256R1())
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(
        key.private_bytes(
            serialization.Encoding.PEM,
            serialization.PrivateFormat.PKCS8,
            serialization.NoEncryption(),
        )
    )
    os.chmod(path, 0o600)
    print(f"private key: {path} (back it up, never commit it)")
    print(f"public key:  {public_key_b64(key)}")


def sign(path: Path) -> None:
    key = serialization.load_pem_private_key(path.read_bytes(), password=None)
    data = RULES.read_bytes()
    rules = json.loads(data)  # refuse to sign a file the app could not read
    for field in ("version", "mountSources", "mapMarkers", "suPaths", "safeProps"):
        if field not in rules:
            sys.exit(f"{RULES.name} lacks {field}")
    # the app drops an entry it cannot read (ModuleRisk.parse): refuse to sign one
    for i, risk in enumerate(rules.get("moduleRisks", [])):
        reason = risk.get("reason", {})
        if risk.get("level") not in ("block", "warn"):
            sys.exit(f"moduleRisks[{i}]: level must be block or warn")
        if not (risk.get("ids") or risk.get("patterns")):
            sys.exit(f"moduleRisks[{i}]: needs ids or patterns")
        if not (isinstance(reason.get("vi"), str) and isinstance(reason.get("en"), str)):
            sys.exit(f"moduleRisks[{i}]: reason needs vi and en")
    if MODULE_MOUNT_SOURCE in rules["mountSources"]:
        sys.exit(f"{RULES.name}: leave {MODULE_MOUNT_SOURCE} out of mountSources (row 85), the feed copy adds it")
    feed = feed_copy(data, rules)
    write_signed(key, RULES, data)
    FEED_RULES.parent.mkdir(parents=True, exist_ok=True)
    FEED_RULES.write_bytes(feed)
    write_signed(key, FEED_RULES, feed)
    feed_name = FEED_RULES.relative_to(ROOT).as_posix()
    print(f"signed {RULES.name} and {feed_name} version {rules['version']}")
    print(f"public key: {public_key_b64(key)}")


def feed_copy(data: bytes, rules: dict) -> bytes:
    """The APK's rules, byte for byte, with the module mount source first in mountSources."""
    head = f'"{MODULE_MOUNT_SOURCE}"' + (", " if rules["mountSources"] else "")
    text, count = re.subn(r'("mountSources"\s*:\s*\[\s*)', lambda m: m.group(1) + head, data.decode(), count=1)
    if count != 1:
        sys.exit(f"{RULES.name}: no mountSources list to extend")
    want = dict(rules, mountSources=[MODULE_MOUNT_SOURCE, *rules["mountSources"]])
    if json.loads(text) != want:
        sys.exit("the feed copy differs from the rules beyond mountSources")
    return text.encode()


def write_signed(key: ec.EllipticCurvePrivateKey, path: Path, data: bytes) -> None:
    signature = key.sign(data, ec.ECDSA(hashes.SHA256()))
    path.with_name(path.name + ".sig").write_bytes(base64.b64encode(signature) + b"\n")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument("--gen-key", type=Path, metavar="PEM")
    group.add_argument("--key", type=Path, metavar="PEM")
    args = parser.parse_args()
    if args.gen_key:
        gen_key(args.gen_key.expanduser())
    else:
        sign(args.key.expanduser())


if __name__ == "__main__":
    main()

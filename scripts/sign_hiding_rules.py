#!/usr/bin/env python3
"""Sign the root hiding rules the Manager downloads.

The Manager ships manager/app/src/main/assets/hiding-rules.json and, when the hiding check
page opens, fetches the same file from GitHub with its .sig next to it. It only takes a
download whose ECDSA P-256 (SHA-256) signature checks out against the public key in
HidingRulesRepository.kt, and whose "version" is newer than the one it has.

    python scripts/sign_hiding_rules.py --gen-key ~/.cam/hiding-rules-key.pem
        new key pair; prints the public key to paste into HidingRulesRepository.kt
    python scripts/sign_hiding_rules.py --key ~/.cam/hiding-rules-key.pem
        writes hiding-rules.json.sig; bump "version" first, or apps keep what they have

Never commit the private key.
"""

import argparse
import base64
import json
import os
import sys
from pathlib import Path

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec

RULES = Path(__file__).resolve().parent.parent / "manager/app/src/main/assets/hiding-rules.json"


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
    signature = key.sign(data, ec.ECDSA(hashes.SHA256()))
    RULES.with_name(RULES.name + ".sig").write_text(base64.b64encode(signature).decode() + "\n")
    print(f"signed {RULES.name} version {rules['version']} with {public_key_b64(key)}")


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

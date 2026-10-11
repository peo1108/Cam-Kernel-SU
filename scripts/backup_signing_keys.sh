#!/usr/bin/env bash
# Pack every Cam signing key into one AES-256 encrypted file, to keep somewhere other than this PC
# (docs/CAM_CHANGES.md section 8). Losing the release key means no more OTA updates, and a Manager
# signed with any other key has no root unless the kernel trusts it (KSU_MANAGER_CERT_BACKUP).
#
# Usage (Git Bash or WSL, interactive: openssl asks for the password twice):
#   scripts/backup_signing_keys.sh [output-file]
# Restore:
#   openssl enc -d -aes-256-cbc -pbkdf2 -iter 600000 -in <file> | tar -xzf - -C <dir>
#
# The password is never stored. Keep it apart from the file (password manager, paper).
set -euo pipefail

home="${USERPROFILE:-$HOME}"
home="$(cd "$home" && pwd)"
out="${1:-cam-signing-keys-$(date +%Y%m%d).tar.gz.enc}"

files=(
    ".android-keys/su-kernel-release.jks"      # release key: APK + kernel KSU_MANAGER_CERT
    ".android-keys/README-PASSWORD.txt"
    ".android-keys/su-kernel-backup.jks"       # offline backup key: kernel KSU_MANAGER_CERT_BACKUP
    ".android-keys/su-kernel-backup-PASSWORD.txt"
    ".cam/hiding-rules-key.pem"                # hiding-rules.json signature
    "Documents/Cam-Kernel-SU-keys"             # old "Cam Kernel SU" key
)

stage="$(mktemp -d)"
trap 'rm -rf "$stage"' EXIT
mkdir -p "$stage/cam-signing-keys"

missing=0
for f in "${files[@]}"; do
    if [ -e "$home/$f" ]; then
        mkdir -p "$stage/cam-signing-keys/$(dirname "$f")"
        cp -r "$home/$f" "$stage/cam-signing-keys/$f"
    else
        echo "missing: ~/$f" >&2
        missing=1
    fi
done
# only the signing lines of the Gradle properties, which hold the release key passwords
if [ -f "$home/.gradle/gradle.properties" ]; then
    grep -E '^(KEYSTORE|KEY_)' "$home/.gradle/gradle.properties" > "$stage/cam-signing-keys/gradle-signing.properties" || true
fi
[ "$missing" = 0 ] || echo "continuing without the missing files" >&2

tar -czf - -C "$stage" cam-signing-keys | openssl enc -e -aes-256-cbc -salt -pbkdf2 -iter 600000 -out "$out"

# prove the file opens before anyone relies on it
echo "checking: enter the password once more" >&2
openssl enc -d -aes-256-cbc -pbkdf2 -iter 600000 -in "$out" | tar -tzf - > /dev/null
echo "wrote $out ($(wc -c < "$out") bytes); copy it to two places off this PC" >&2

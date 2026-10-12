#!/usr/bin/env bash
# Deploys the caesarvn.xyz API (site/api) to the VPS. CI cannot do it: its deploy key only writes
# into /var/www/caesarvn. Run from the repo root (Git Bash or Linux) with the VPS admin key:
#   site/scripts/deploy-api.sh [ssh-key]
# Order: tests -> backup of the live database -> install -> restart -> health check.
set -euo pipefail
KEY="${1:-$HOME/.ssh/cc_vps_ed25519}"
HOST="root@36.50.135.173"
cd "$(dirname "$0")/../api"

echo "== tests"
node --test --test-timeout=60000 test/api.test.mjs test/business.test.mjs >/dev/null

echo "== backup and install"
tmp="$(mktemp -d)"
tar --force-local -cf "$tmp/api.tar" *.mjs routes/*.mjs 2>/dev/null || tar -cf "$tmp/api.tar" *.mjs routes/*.mjs
scp -q -i "$KEY" "$tmp/api.tar" "$HOST:/root/api.tar"
rm -rf "$tmp"
ssh -i "$KEY" -o BatchMode=yes "$HOST" 'set -e
systemctl start cam-site-backup.service
T=$(mktemp -d); tar -xf /root/api.tar -C $T; rm /root/api.tar
install -d -m 755 /opt/cam-site-api/routes
for f in $T/*.mjs; do install -m 644 $f /opt/cam-site-api/; done
for f in $T/routes/*.mjs; do install -m 644 $f /opt/cam-site-api/routes/; done
rm -rf $T
systemctl restart cam-site-api
sleep 2
systemctl is-active --quiet cam-site-api || { journalctl -u cam-site-api -n 30 --no-pager; exit 1; }
echo "api: active"'

echo "== health"
curl -fsS https://caesarvn.xyz/api/auth/features
echo

#!/usr/bin/env bash
# Prints "<sha> <tag>" of Duck Detector's newest nightly, the commit hiding-rules.json
# ("duckDetector") should follow. Used by .github/workflows/duck-watch.yml and by hand
# (docs/CAM_CHANGES.md section 12). Needs gh (logged in, or GH_TOKEN) and jq.
#
# Since 2026-10-18 each nightly is its own release, tagged nightly-YYYY-MM-DD-<sha>. The newest
# is picked by the date of the commit the tag points at, not by when the release was published:
# a nightly rebuilt for an older commit is published later but is not newer.
set -euo pipefail
DUCK=${DUCK:-eltavine/Duck-Detector-Refactoring}

tags=$(gh api "repos/$DUCK/releases?per_page=15" \
    --jq '.[] | select(.tag_name | startswith("nightly-")) | .tag_name')
[ -n "$tags" ] || { echo "no nightly-* release in $DUCK" >&2; exit 1; }

best=""
while read -r tag; do
    # ISO 8601 UTC dates sort as text
    line=$(gh api "repos/$DUCK/commits/$tag" --jq '.commit.committer.date + " " + .sha')
    [[ -z "$best" || "$line" > "$best" ]] && best="$line" && best_tag=$tag
done <<<"$tags"

echo "${best#* } $best_tag"

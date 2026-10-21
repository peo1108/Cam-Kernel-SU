#!/usr/bin/env bash
# Prints "<sha> <tag> <date>" of Duck Detector's newest nightly, the commit hiding-rules.json
# ("duckDetector") should follow. The only place that decides it: duck-watch.yml uses it, and
# deploy-site.yml publishes it as feed/duck-nightly.json for the Manager's hiding check page.
# Also run by hand (docs/CAM_CHANGES.md section 12). Needs gh (logged in, or GH_TOKEN).
#
# Since 2026-10-18 each nightly is its own release, tagged nightly-YYYY-MM-DD-<sha>. The newest
# is picked by the date of the commit the tag points at, not by when the release was published:
# a nightly rebuilt for an older commit is published later but is not newer. One GraphQL query
# reads the last 100 releases with their commits, so the order GitHub lists them in does not
# matter, and a release whose tag is gone (no commit) is skipped instead of failing the pick.
set -euo pipefail
DUCK=${DUCK:-eltavine/Duck-Detector-Refactoring}

best=$(gh api graphql -f owner="${DUCK%/*}" -f name="${DUCK#*/}" -f query='
query($owner: String!, $name: String!) {
  repository(owner: $owner, name: $name) {
    releases(first: 100, orderBy: {field: CREATED_AT, direction: DESC}) {
      nodes { tagName isDraft tagCommit { oid committedDate } }
    }
  }
}' --jq '
  # ISO 8601 UTC dates sort as text
  [.data.repository.releases.nodes[]
   | select((.isDraft | not) and (.tagName | startswith("nightly-")) and .tagCommit != null)]
  | max_by(.tagCommit.committedDate) // empty
  | "\(.tagCommit.oid) \(.tagName) \(.tagCommit.committedDate)"')
[ -n "$best" ] || { echo "no nightly-* release in $DUCK" >&2; exit 1; }
echo "$best"

#!/usr/bin/env bash
# Says what is new in a project Cam follows, for upstream-watch.yml (which opens, updates or
# closes one issue per project) and for running by hand.
#
#   scripts/upstream_watch.sh <kernelsu|teesimulator|chunqiu> <out-dir>
#
# Something new: writes <out-dir>/title and <out-dir>/body.md. Nothing new: writes neither.
#
# - kernelsu: commits of tiann/KernelSU main that main has not merged (git ancestry: upstream is
#   merged whole, docs/CAM_CHANGES.md section 4). Needs the full history of this repository.
# - teesimulator: commits after "seen" in .github/upstream-watch.json.
# - chunqiu: commits after "seen" that change Chunqiu's check list (its "path"), with the
#   headings they add: a new heading is usually a new check.
#
# Duck Detector has its own watch (duck-watch.yml). Needs git, jq and gh (logged in, or GH_TOKEN).
set -euo pipefail

source=${1:?usage: upstream_watch.sh <kernelsu|teesimulator|chunqiu> <out-dir>}
out=${2:?usage: upstream_watch.sh <kernelsu|teesimulator|chunqiu> <out-dir>}
root=$(git rev-parse --show-toplevel)
pins=${UPSTREAM_PINS:-$root/.github/upstream-watch.json}
mkdir -p "$out"
rm -f "$out/title" "$out/body.md"

pin() { jq -er --arg s "$source" --arg k "$1" '.[$s][$k]' "$pins"; }

# "a, b, c" from the arguments.
joined() {
  local all="" part
  for part in "$@"; do all+="${all:+, }$part"; done
  echo "$all"
}

# One markdown list item per non-empty line of $1; with $2 = code, each in backticks.
bullets() {
  local line
  while IFS= read -r line; do
    [ -n "$line" ] || continue
    if [ "${2:-}" = code ]; then
      printf -- "- \`%s\`\n" "$line"
    else
      printf -- '- %s\n' "$line"
    fi
  done <<<"$1"
}

# Where a KernelSU path lands in Cam (ksud is camd here, ksuinit is caminit).
area_of() {
  case "$1" in
    kernel/*) echo kernel ;;
    uapi/*) echo uapi ;;
    userspace/ksud/*) echo ksud ;;
    userspace/ksuinit/*) echo ksuinit ;;
    manager/app/src/main/res/values-*) echo translations ;;
    manager/*) echo manager ;;
    website/* | docs/*) echo docs ;;
    js/*) echo js ;;
    .github/*) echo ci ;;
    *) echo other ;;
  esac
}

kernelsu() {
  local url=${KSU_URL:-https://github.com/tiann/KernelSU.git}
  git -C "$root" fetch --quiet --no-tags "$url" main
  local head base
  head=$(git -C "$root" rev-parse FETCH_HEAD)
  base=$(git -C "$root" merge-base HEAD "$head")
  local -a commits
  mapfile -t commits < <(git -C "$root" rev-list --reverse "HEAD..$head")
  echo "kernelsu: ${#commits[@]} commit(s) not merged (merge base ${base:0:8}, upstream ${head:0:8})"
  [ "${#commits[@]}" -gt 0 ] || return 0

  local core="" manager="" rest="" n_core=0 n_manager=0 n_rest=0 sha subject areas line
  local -a areas_list
  for sha in "${commits[@]}"; do
    subject=$(git -C "$root" log -1 --format=%s "$sha")
    mapfile -t areas_list < <(git -C "$root" diff-tree --no-commit-id --name-only -r --root "$sha" | while read -r f; do area_of "$f"; done | sort -u)
    areas=$(joined "${areas_list[@]}")
    line="- [\`${sha:0:8}\`](https://github.com/tiann/KernelSU/commit/$sha) $subject (${areas:-no files})"
    case ", $areas," in
      *", kernel,"* | *", uapi,"* | *", ksud,"* | *", ksuinit,"*) core+="$line"$'\n'; n_core=$((n_core + 1)) ;;
      *", manager,"*) manager+="$line"$'\n'; n_manager=$((n_manager + 1)) ;;
      *) rest+="$line"$'\n'; n_rest=$((n_rest + 1)) ;;
    esac
  done

  local parts=()
  [ "$n_core" -eq 0 ] || parts+=("$n_core kernel/userspace")
  [ "$n_manager" -eq 0 ] || parts+=("$n_manager manager")
  [ "$n_rest" -eq 0 ] || parts+=("$n_rest other")
  local summary
  summary=$(joined "${parts[@]}")
  echo "KernelSU: ${#commits[@]} commit(s) to merge ($summary)" > "$out/title"
  {
    echo "KernelSU \`main\` has **${#commits[@]}** commit(s) that Cam's \`main\` has not merged (\`${base:0:8}\` → [\`${head:0:8}\`](https://github.com/tiann/KernelSU/compare/$base...$head))."
    if [ "$n_core" -gt 0 ]; then
      echo
      echo "### Kernel and userspace (ksud is camd, ksuinit is caminit)"
      printf '%s' "$core"
    fi
    if [ "$n_manager" -gt 0 ]; then
      echo
      echo "### Manager"
      printf '%s' "$manager"
    fi
    if [ "$n_rest" -gt 0 ]; then
      echo
      echo "### Translations, docs, CI and the rest"
      printf '%s' "$rest"
    fi
    echo
    echo "### What to do"
    echo "Merge \`upstream/main\` into \`main\` as section 4 of \`docs/CAM_CHANGES.md\` says (\"kéo update\" also checks Duck Detector and TEESimulator). This issue is updated by each run and closes itself once nothing is left to merge."
  } > "$out/body.md"
}

# The commits after "seen" on the watched branch, as GitHub's compare API returns them.
compare_seen() {
  local repo=$1 branch=$2 seen=$3
  gh api "repos/$repo/compare/$seen...$branch"
}

teesimulator() {
  local repo branch seen cmp ahead head
  repo=$(pin repo) branch=$(pin branch) seen=$(pin seen)
  cmp=$(compare_seen "$repo" "$branch" "$seen")
  ahead=$(jq -r .ahead_by <<<"$cmp")
  head=$(jq -r '.commits[-1].sha // empty' <<<"$cmp")
  echo "teesimulator: $branch is $ahead commit(s) past ${seen:0:8}"
  [ "$ahead" -gt 0 ] || return 0

  local dirs
  dirs=$(jq -r '[.files[].filename | split("/")[0:2] | join("/")] | unique | .[]' <<<"$cmp")
  echo "TEESimulator: $ahead new commit(s)" > "$out/title"
  {
    echo "TEESimulator \`$branch\` has **$ahead** commit(s) after the last one Cam read (\`${seen:0:8}\` → [\`${head:0:8}\`](https://github.com/$repo/compare/$seen...$head))."
    echo
    echo "### Commits"
    jq -r '.commits[] | "- [`\(.sha[0:8])`](\(.html_url)) \(.commit.message | split("\n")[0])"' <<<"$cmp"
    echo
    echo "### Changed directories"
    bullets "$dirs" code
    echo
    echo "### What to do"
    echo "No TEESimulator code is in Cam (hide bootloader is properties only). Read what changed and decide whether Cam needs any of it, then set \`teesimulator.seen\` in \`.github/upstream-watch.json\` to \`$head\`. The issue closes itself on the next run."
  } > "$out/body.md"
}

# Each check of a Chunqiu check list as "<name>\t<md5 of its text>". A check is a
# <details><summary><b>name</b></summary> block; its text runs to the next one. A name seen again
# gets " (2)", " (3)"... so that every check has its own key.
checks_of() {
  local md=$1 dir=$2
  rm -rf "$dir"
  mkdir -p "$dir"
  awk -v dir="$dir" '
    match($0, /<summary>.*<\/summary>/) {
      name = substr($0, RSTART, RLENGTH)
      gsub(/<[^>]*>/, "", name)
      gsub(/^[ \t]+|[ \t]+$/, "", name)
      if (file != "") close(file)
      n++
      file = dir "/" n
      if (++times[name] > 1) name = name " (" times[name] ")"
      printf "%s\t%s\n", n, name > (dir "/index")
      printf "" > file
      next
    }
    file != "" { print > file }
  ' "$md"
  [ -f "$dir/index" ] || return 0
  local i name
  while IFS=$'\t' read -r i name; do
    printf '%s\t%s\n' "$name" "$(md5sum < "$dir/$i" | cut -c1-32)"
  done < "$dir/index"
}

chunqiu() {
  local repo branch path seen cmp ahead head
  repo=$(pin repo) branch=$(pin branch) path=$(pin path) seen=$(pin seen)
  cmp=$(compare_seen "$repo" "$branch" "$seen")
  ahead=$(jq -r .ahead_by <<<"$cmp")
  head=$(jq -r '.commits[-1].sha // empty' <<<"$cmp")
  if ! jq -e --arg p "$path" 'any(.files[]; .filename == $p)' <<<"$cmp" >/dev/null; then
    echo "chunqiu: $ahead commit(s) past ${seen:0:8}, none change $path"
    return 0
  fi
  echo "chunqiu: $path changed in $ahead commit(s) past ${seen:0:8}"

  # Compare check by check: the version Cam read against the newest.
  local tmp
  tmp=$(mktemp -d)
  # shellcheck disable=SC2064 # expand now: tmp is local
  trap "rm -rf '$tmp'" RETURN
  gh api -H 'Accept: application/vnd.github.raw' "repos/$repo/contents/$path?ref=$seen" > "$tmp/old.md"
  gh api -H 'Accept: application/vnd.github.raw' "repos/$repo/contents/$path?ref=$head" > "$tmp/new.md"
  checks_of "$tmp/old.md" "$tmp/old" > "$tmp/old.tsv"
  checks_of "$tmp/new.md" "$tmp/new" > "$tmp/new.tsv"
  local added changed removed
  added=$(awk -F'\t' 'NR == FNR { old[$1] = 1; next } !($1 in old) { print $1 }' "$tmp/old.tsv" "$tmp/new.tsv")
  changed=$(awk -F'\t' 'NR == FNR { old[$1] = $2; next } ($1 in old) && old[$1] != $2 { print $1 }' "$tmp/old.tsv" "$tmp/new.tsv")
  removed=$(awk -F'\t' 'NR == FNR { now[$1] = 1; next } !($1 in now) { print $1 }' "$tmp/new.tsv" "$tmp/old.tsv")
  local n_added n_changed n_removed
  n_added=$(grep -c . <<<"$added" || true)
  n_changed=$(grep -c . <<<"$changed" || true)
  n_removed=$(grep -c . <<<"$removed" || true)

  local parts=()
  [ "$n_added" -eq 0 ] || parts+=("$n_added new")
  [ "$n_changed" -eq 0 ] || parts+=("$n_changed changed")
  [ "$n_removed" -eq 0 ] || parts+=("$n_removed removed")
  [ "${#parts[@]}" -gt 0 ] || parts+=("text outside the checks")
  local summary
  summary=$(joined "${parts[@]}")
  echo "Chunqiu check list: $summary" > "$out/title"
  {
    echo "Chunqiu's check list (\`$path\`: what each finding of Chunqiu Native Check looks for, and the fix) changed after the version Cam read, in $ahead commit(s) (\`${seen:0:8}\` → [\`${head:0:8}\`](https://github.com/$repo/compare/$seen...$head)). Page: https://mingzun09.github.io/Chunqiu-Detector-Problem-solution/"
    if [ "$n_added" -gt 0 ]; then
      echo
      echo "### New checks"
      bullets "$added"
    fi
    if [ "$n_changed" -gt 0 ]; then
      echo
      echo "### Changed checks (criteria or fix reworded)"
      bullets "$changed"
    fi
    if [ "$n_removed" -gt 0 ]; then
      echo
      echo "### Removed checks"
      bullets "$removed"
    fi
    echo
    echo "### Commits"
    jq -r '.commits[] | "- [`\(.sha[0:8])`](\(.html_url)) \(.commit.message | split("\n")[0])"' <<<"$cmp"
    echo
    echo "### What to do"
    echo "1. Read the new or changed criteria; check whether Cam (root, modules, the Y700 ROM) can trip them."
    echo "2. Scan the tablet with Chunqiu (\`scripts/device_detector_scan.py\`)."
    echo "3. Set \`chunqiu.seen\` in \`.github/upstream-watch.json\` to \`$head\`. The issue closes itself on the next run."
  } > "$out/body.md"
}

case "$source" in
  kernelsu) kernelsu ;;
  teesimulator) teesimulator ;;
  chunqiu) chunqiu ;;
  *) echo "unknown source: $source" >&2; exit 2 ;;
esac

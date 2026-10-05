#!/usr/bin/env python3
"""Watch for GKI releases and susfs4ksu changes the SFS builds should follow.

Usage: watch.py <report.json> [--update]

- New GKI releases: for every KMI in sfs/targets.json, the newest monthly release tag
  of kernel/common (<kmi>-YYYY-MM_rNN, highest rNN) newer than the KMI's newest
  target, once kernel/manifest has its common-<kmi>-YYYY-MM branch. The fixup sets of
  sfs/patches/fixup_susfs.py are tried until susfs4ksu's patch applies with --fuzz=0;
  with --update the target is added to sfs/targets.json.
- susfs4ksu: its gki-<kmi> branch moves on its own and sfs/build.sh always takes the
  head, so every existing target is checked against the current patch too.

Only the files a patch touches are fetched (from gitiles), so nothing is cloned.
The report lists the new targets and, per failure, an issue title and body;
.github/workflows/sfs-watch.yml turns them into a pull request and issues.
"""

import base64
import json
import os
import re
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
TARGETS = os.path.join(HERE, "targets.json")
FIXUP = os.path.join(HERE, "patches", "fixup_susfs.py")

COMMON = "https://android.googlesource.com/kernel/common"
MANIFEST = "https://android.googlesource.com/kernel/manifest"
SUSFS = "https://gitlab.com/simonpunk/susfs4ksu.git"
SUSFS_PATCH = "https://gitlab.com/simonpunk/susfs4ksu/-/raw/{sha}/kernel_patches/50_add_susfs_in_gki-{kmi}.patch"

sys.path.insert(0, os.path.join(HERE, "patches"))
import fixup_susfs  # noqa: E402

_file_cache = {}


def get(url):
    """The body of a GET, or None on 404; retried when the server throttles."""
    for attempt in range(5):
        try:
            with urllib.request.urlopen(url, timeout=60) as response:
                return response.read()
        except urllib.error.HTTPError as e:
            if e.code == 404:
                return None
            if e.code not in (429, 500, 502, 503) or attempt == 4:
                raise
        except urllib.error.URLError:
            if attempt == 4:
                raise
        time.sleep(5 * (attempt + 1))
    return None


def ls_remote(url, pattern):
    out = subprocess.run(
        ["git", "ls-remote", url, pattern], check=True, capture_output=True, text=True
    ).stdout
    refs = {}
    for line in out.splitlines():
        sha, ref = line.split("\t")
        if not ref.endswith("^{}"):
            refs[ref] = sha
    return refs


def gitiles_file(tag, path):
    key = (tag, path)
    if key not in _file_cache:
        data = get(f"{COMMON}/+/refs/tags/{tag}/{path}?format=TEXT")
        _file_cache[key] = None if data is None else base64.b64decode(data)
    return _file_cache[key]


def kernel_version(tag):
    makefile = gitiles_file(tag, "Makefile").decode()
    fields = dict(re.findall(r"^(VERSION|PATCHLEVEL|SUBLEVEL) = (\d+)$", makefile, re.M))
    return f"{fields['VERSION']}.{fields['PATCHLEVEL']}.{fields['SUBLEVEL']}"


def patched_files(patch):
    """The files the patch changes (not the ones it creates)."""
    files = []
    lines = patch.splitlines()
    for old, new in zip(lines, lines[1:]):
        if old.startswith("--- a/") and new.startswith("+++ b/"):
            files.append(old[len("--- a/"):].split("\t")[0].strip())
    return files


def try_apply(tag, patch, fixups):
    """(applied, log) for susfs4ksu's patch with a fixup set on a GKI release tag."""
    with tempfile.TemporaryDirectory() as tmp:
        patch_path = os.path.join(tmp, "susfs.patch")
        with open(patch_path, "w", encoding="utf-8", newline="\n") as f:
            f.write(patch)
        fix = subprocess.run(
            [sys.executable, FIXUP, fixups, patch_path], capture_output=True, text=True
        )
        if fix.returncode != 0:
            return False, fix.stdout + fix.stderr
        with open(patch_path, encoding="utf-8") as f:
            fixed = f.read()
        src = os.path.join(tmp, "src")
        for path in patched_files(fixed):
            data = gitiles_file(tag, path)
            if data is None:
                return False, f"{path} does not exist at {tag}"
            dest = os.path.join(src, path)
            os.makedirs(os.path.dirname(dest), exist_ok=True)
            with open(dest, "wb") as f:
                f.write(data)
        result = subprocess.run(
            ["patch", "-p1", "--dry-run", "--forward", "--fuzz=0", "-i", patch_path],
            cwd=src,
            capture_output=True,
            text=True,
        )
        return result.returncode == 0, result.stdout + result.stderr


def month_of(tag):
    m = re.search(r"-(\d{4})-(\d{2})_r(\d+)$", tag)
    return (int(m.group(1)), int(m.group(2))) if m else (0, 0)


def newest_release(kmi, after):
    """The newest monthly release tag of kmi past `after` (year, month), or None."""
    best = None
    pattern = re.compile(rf"^refs/tags/{re.escape(kmi)}-(\d{{4}})-(\d{{2}})_r(\d+)$")
    for ref in ls_remote(COMMON, f"refs/tags/{kmi}-*"):
        m = pattern.match(ref)
        if not m:
            continue
        key = (int(m.group(1)), int(m.group(2)), int(m.group(3)))
        if key[:2] > after and (best is None or key > best[0]):
            best = (key, ref[len("refs/tags/"):])
    return best


def fixup_candidates(kmi_targets, kernel):
    """`none`, the newest target's set, then every set made for this kernel version."""
    series = ".".join(kernel.split(".")[:2])
    names = ["none", kmi_targets[-1]["fixups"]]
    names += [n for n in fixup_susfs.SETS if n.startswith(series + "-")]
    return list(dict.fromkeys(names))


def failed_hunks(log):
    """patch's output cut down to the files with failed hunks and those hunks."""
    out, current = [], None
    for line in log.splitlines():
        if line.startswith("checking file "):
            current = line
        elif "FAILED" in line or "can't find file" in line or "No such file" in line:
            if current:
                out.append(current)
                current = None
            out.append(line)
    return "\n".join(out) or log


def code_block(text, limit=6000):
    text = text.strip()
    if len(text) > limit:
        text = text[:limit] + "\n..."
    return f"```\n{text}\n```"


def write_targets(data):
    """Same layout as the hand-written file: one target per line, a blank line between KMIs."""
    lines = ["{", '  "_comment": [']
    comment = data["_comment"]
    for i, line in enumerate(comment):
        lines.append("    " + json.dumps(line) + ("," if i < len(comment) - 1 else ""))
    lines += ["  ],", '  "targets": [']
    targets = data["targets"]
    for i, t in enumerate(targets):
        if i > 0 and t["kmi"] != targets[i - 1]["kmi"]:
            lines.append("")
        entry = "{ " + ", ".join(f"{json.dumps(k)}: {json.dumps(v)}" for k, v in t.items()) + " }"
        lines.append("    " + entry + ("," if i < len(targets) - 1 else ""))
    lines += ["  ]", "}", ""]
    with open(TARGETS, "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(lines))


def main():
    if len(sys.argv) < 2:
        print(__doc__, file=sys.stderr)
        return 2
    report_path = sys.argv[1]
    update = "--update" in sys.argv[2:]
    with open(TARGETS, encoding="utf-8") as f:
        data = json.load(f)
    targets = data["targets"]
    kmis = list(dict.fromkeys(t["kmi"] for t in targets))

    new_targets, failures, susfs_heads = [], [], {}
    for kmi in kmis:
        kmi_targets = [t for t in targets if t["kmi"] == kmi]
        heads = ls_remote(SUSFS, f"refs/heads/gki-{kmi}")
        sha = heads.get(f"refs/heads/gki-{kmi}")
        if not sha:
            failures.append({
                "title": f"SFS watch: susfs4ksu has no gki-{kmi} branch",
                "body": f"`git ls-remote {SUSFS} refs/heads/gki-{kmi}` found nothing; "
                        f"sfs/build.sh cannot build {kmi}.",
            })
            continue
        susfs_heads[kmi] = sha
        patch_bytes = get(SUSFS_PATCH.format(sha=sha, kmi=kmi))
        if patch_bytes is None:
            failures.append({
                "title": f"SFS watch: susfs4ksu gki-{kmi} has no GKI patch",
                "body": f"`kernel_patches/50_add_susfs_in_gki-{kmi}.patch` is missing at {sha}.",
            })
            continue
        patch = patch_bytes.decode()
        print(f"== {kmi}: susfs4ksu {sha[:12]}")

        # the targets CI already builds, against the patch the next build will take
        broken = []
        for t in kmi_targets:
            ok, log = try_apply(t["tag"], patch, t["fixups"])
            print(f"  {t['tag']} ({t['fixups']}): {'ok' if ok else 'FAILS'}")
            if not ok:
                broken.append((t, log))
        if broken:
            body = [
                f"susfs4ksu `gki-{kmi}` is now at {sha}, and its patch no longer applies with "
                f"`--fuzz=0` to these targets, so the next release build fails for them. "
                f"Add or adjust a fixup set in `sfs/patches/fixup_susfs.py`.",
            ]
            for t, log in broken:
                body += ["", f"### {t['tag']} (fixups `{t['fixups']}`)", code_block(failed_hunks(log))]
            failures.append({
                "title": f"SFS watch: susfs4ksu {sha[:12]} breaks {kmi} builds",
                "body": "\n".join(body),
            })

        newest = max(month_of(t["tag"]) for t in kmi_targets)
        release = newest_release(kmi, newest)
        if release is None:
            print("  no newer GKI release")
            continue
        (year, month, _), tag = release
        manifest = f"common-{kmi}-{year}-{month:02d}"
        if not ls_remote(MANIFEST, f"refs/heads/{manifest}"):
            print(f"  {tag}: no {manifest} manifest branch yet")
            continue
        kernel = kernel_version(tag)
        logs = []
        found = None
        for fixups in fixup_candidates(kmi_targets, kernel):
            ok, log = try_apply(tag, patch, fixups)
            print(f"  new {tag} ({kernel}) with {fixups}: {'ok' if ok else 'fails'}")
            if ok:
                found = fixups
                break
            logs.append((fixups, log))
        if found is None:
            body = [
                f"GKI release `{tag}` ({kernel}) is out, but susfs4ksu `gki-{kmi}` ({sha}) does not "
                f"apply to it with `--fuzz=0` under any fixup set. Add a set to "
                f"`sfs/patches/fixup_susfs.py`, then the target to `sfs/targets.json`.",
            ]
            for fixups, log in logs:
                body += ["", f"### fixups `{fixups}`", code_block(failed_hunks(log), 3000)]
            failures.append({
                "title": f"SFS watch: susfs4ksu does not apply to {tag}",
                "body": "\n".join(body),
            })
            continue
        entry = {
            "kmi": kmi,
            "kernel": kernel,
            "tag": tag,
            "manifest": manifest,
            "lto": kmi_targets[-1]["lto"],
            "fixups": found,
        }
        new_targets.append(entry)
        if update:
            # right after the KMI's last target
            at = max(i for i, t in enumerate(targets) if t["kmi"] == kmi) + 1
            targets.insert(at, entry)

    if update and new_targets:
        write_targets(data)
    with open(report_path, "w", encoding="utf-8") as f:
        json.dump(
            {"newTargets": new_targets, "failures": failures, "susfsHeads": susfs_heads},
            f,
            indent=2,
        )
    print(f"{len(new_targets)} new target(s), {len(failures)} failure(s)")
    return 0


if __name__ == "__main__":
    sys.exit(main())

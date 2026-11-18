#!/usr/bin/env python3
"""Build Cam's module library (the Manager's "Module repo" page) from modules/sources.json.

Usage: gen_module_repo.py <out-dir> [sources.json]
Writes <out-dir>/modules.json (the list) and <out-dir>/module/<id>.json (one per module,
README and releases), the layout modules.kernelsu.org used, so the Manager parses both
unchanged. Release data comes from the GitHub API (source with "repo" + "asset"; set GH_TOKEN
to avoid the anonymous rate limit) or, for a module hosted outside GitHub, from the
module's own update.json (source with "updateJson": the same file KernelSU/Magisk read,
giving version, zipUrl and changelog; only the latest release is listed). A module whose repo or zip cannot be found is left out with a warning; exits 1
only when no module is left, so one dead repo does not take the whole library down.
"""

import html
import json
import os
import re
import sys
import urllib.error
import urllib.request
from datetime import datetime, timezone
from email.utils import parsedate_to_datetime
from pathlib import Path
from urllib.parse import urlsplit

API = "https://api.github.com"
# Same rule camd applies to module.prop ids: a mismatch would never match the installed module.
MODULE_ID = re.compile(r"^[a-zA-Z][a-zA-Z0-9._-]+$")
MAX_RELEASES = 3


def warn(message: str) -> None:
    prefix = "::warning::" if os.environ.get("GITHUB_ACTIONS") else "warning: "
    print(prefix + message, file=sys.stderr)


def get(path: str, accept: str = "application/vnd.github+json"):
    """GET an API path; JSON for JSON media types, text otherwise. None on 404."""
    request = urllib.request.Request(API + path, headers={
        "Accept": accept,
        "X-GitHub-Api-Version": "2022-11-28",
        "User-Agent": "cam-module-repo",
    })
    token = os.environ.get("GH_TOKEN") or os.environ.get("GITHUB_TOKEN")
    if token:
        request.add_header("Authorization", f"Bearer {token}")
    try:
        with urllib.request.urlopen(request, timeout=60) as response:
            body = response.read().decode("utf-8")
    except urllib.error.HTTPError as e:
        if e.code == 404:
            return None
        raise
    return json.loads(body) if accept.endswith("json") else body


def fetch(url: str, method: str = "GET"):
    """(body, headers) of a plain HTTPS URL; (None, None) on 404 or when the host is unreachable."""
    request = urllib.request.Request(url, method=method, headers={"User-Agent": "cam-module-repo"})
    try:
        with urllib.request.urlopen(request, timeout=60) as response:
            return response.read(), response.headers
    except urllib.error.HTTPError as e:
        if e.code == 404:
            return None, None
        raise
    except urllib.error.URLError as e:
        warn(f"{url}: {e.reason}")
        return None, None


def changelog_html(markdown: str) -> str:
    """The changelog.md a module ships for the managers: "# title", paragraphs and "- " items
    whose continuation lines are indented. Rendered as plain HTML, everything escaped."""
    blocks, items, para = [], [], []

    def flush_para():
        if para:
            blocks.append("<p>" + html.escape(" ".join(para)) + "</p>")
            para.clear()

    def flush_items():
        if items:
            blocks.append("<ul>" + "".join(f"<li>{html.escape(i)}</li>" for i in items) + "</ul>")
            items.clear()

    for line in markdown.splitlines():
        if line.startswith("# "):
            continue
        if line.startswith("- "):
            flush_para()
            items.append(line[2:].strip())
        elif line.strip() and line[:1].isspace() and items:
            items[-1] += " " + line.strip()
        elif line.strip():
            flush_items()
            para.append(line.strip())
        else:
            flush_para()
            flush_items()
    flush_para()
    flush_items()
    return "".join(blocks)


def iso(headers) -> str:
    stamp = headers.get("Last-Modified") if headers else None
    when = parsedate_to_datetime(stamp) if stamp else datetime.now(timezone.utc)
    return when.astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def build_update_json(source: dict) -> tuple[dict, dict] | None:
    module_id, url = source["id"], source["updateJson"]
    body, _ = fetch(url)
    if body is None:
        warn(f"{module_id}: {url} not found")
        return None
    try:
        update = json.loads(body)
        version, zip_url = str(update["version"]), update["zipUrl"]
    except (ValueError, KeyError, TypeError):
        warn(f"{module_id}: {url} is not a valid update.json")
        return None
    _, zip_headers = fetch(zip_url, "HEAD")
    if zip_headers is None:
        warn(f"{module_id}: {zip_url} not found")
        return None
    notes = fetch(update["changelog"])[0] if update.get("changelog") else None
    published = iso(zip_headers)
    home = source.get("homepage") or "{0.scheme}://{0.netloc}/".format(urlsplit(url))
    release = {
        "name": f"v{version}",
        "tagName": f"v{version}",
        "publishedAt": published,
        "descriptionHTML": changelog_html(notes.decode("utf-8")) if notes else "",
        "releaseAssets": [{
            "name": zip_url.rsplit("/", 1)[-1],
            "downloadUrl": zip_url,
            "size": int(zip_headers.get("Content-Length") or 0),
            "downloadCount": 0,
        }],
    }
    entry = {
        "moduleId": module_id,
        "moduleName": source["name"],
        "url": home,
        "authors": source["authors"],
        "summary": source["summary"],
        "metamodule": bool(source.get("metamodule", False)),
        "stargazerCount": 0,
        "updatedAt": published,
        "createdAt": source.get("createdAt") or published,
        "latestRelease": {"name": release["name"], "time": published, "downloadUrl": zip_url},
    }
    detail = dict(entry)
    detail.update({
        "homepageUrl": home,
        "sourceUrl": home,
        "readmeHTML": f'<p>{html.escape(source["summary"])}</p>'
                      f'<p><a href="{html.escape(home)}">{html.escape(home)}</a></p>',
        "releases": [release],
    })
    return entry, detail


def build(source: dict) -> tuple[dict, dict] | None:
    module_id = source["id"]
    if not MODULE_ID.match(module_id):
        warn(f"{module_id}: not a valid module id")
        return None
    if "updateJson" in source:
        return build_update_json(source)
    repo = source["repo"]
    info = get(f"/repos/{repo}")
    if not info:
        warn(f"{module_id}: repo {repo} not found")
        return None
    asset = re.compile(source["asset"])

    releases = []
    for release in get(f"/repos/{repo}/releases?per_page=30", "application/vnd.github.html+json") or []:
        if release["draft"] or release["prerelease"]:
            continue
        zips = [a for a in release["assets"] if asset.search(a["name"])]
        if not zips:
            continue
        releases.append({
            "name": release["name"] or release["tag_name"],
            "tagName": release["tag_name"],
            "publishedAt": release["published_at"],
            "descriptionHTML": release.get("body_html") or "",
            "releaseAssets": [{
                "name": a["name"],
                "downloadUrl": a["browser_download_url"],
                "size": a["size"],
                "downloadCount": a["download_count"],
            } for a in zips],
        })
        if len(releases) == MAX_RELEASES:
            break
    if not releases:
        warn(f"{module_id}: no release of {repo} has an asset matching {source['asset']}")
        return None

    latest = releases[0]
    entry = {
        "moduleId": module_id,
        "moduleName": source["name"],
        "url": info["html_url"],
        "authors": source["authors"],
        "summary": source["summary"],
        "metamodule": bool(source.get("metamodule", False)),
        "stargazerCount": info["stargazers_count"],
        "updatedAt": latest["publishedAt"],
        "createdAt": info["created_at"],
        "latestRelease": {
            "name": latest["name"],
            "time": latest["publishedAt"],
            "downloadUrl": latest["releaseAssets"][0]["downloadUrl"],
        },
    }
    detail = dict(entry)
    detail.update({
        "homepageUrl": info.get("homepage") or "",
        "sourceUrl": info["html_url"],
        # GitHub renders repo-relative links in the README as absolute ones
        "readmeHTML": get(f"/repos/{repo}/readme", "application/vnd.github.html") or "",
        "releases": releases,
    })
    return entry, detail


def write(path: Path, data) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=1) + "\n", encoding="utf-8", newline="\n")


def main() -> int:
    if len(sys.argv) not in (2, 3):
        print(__doc__.strip(), file=sys.stderr)
        return 2
    out = Path(sys.argv[1])
    sources_path = Path(sys.argv[2]) if len(sys.argv) == 3 \
        else Path(__file__).resolve().parent.parent / "modules" / "sources.json"
    sources = json.loads(sources_path.read_text(encoding="utf-8"))["modules"]

    ids = [s["id"] for s in sources]
    if len(ids) != len(set(ids)):
        print("duplicate module id in sources", file=sys.stderr)
        return 1

    entries = []
    for source in sources:
        built = build(source)
        if built is None:
            continue
        entry, detail = built
        entries.append(entry)
        write(out / "module" / f"{entry['moduleId']}.json", detail)
        print(f"{entry['moduleId']}: {entry['latestRelease']['name']}")
    if not entries:
        print("no module could be built", file=sys.stderr)
        return 1
    write(out / "modules.json", entries)
    return 0


if __name__ == "__main__":
    sys.exit(main())

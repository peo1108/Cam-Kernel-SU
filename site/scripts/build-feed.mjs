// Everything the Manager and visitors download, served from caesarvn.xyz instead of GitHub, so the
// repository can go private (docs/CAM_CHANGES.md, section 14). Runs after `vitepress build`:
//
//   dist/dl/<tag>/<file>              release files (latest stable: every asset; the five stable
//                                     releases before it and newer betas: the APK only)
//   dist/feed/releases.json           the GitHub releases API layout the Manager's OTA parses
//                                     (UpdateInfo.kt parseReleases), pointing at /dl/ and carrying
//                                     a sha256 digest computed here
//   dist/feed/hiding-rules.json(.sig) byte-for-byte copies of the signed rules in the repo
//   dist/feed/module-repo/...         the module-repo branch (modules.json, module/<id>.json)
//   dist/_private/releases.json       the same releases plus `urgent` (CHANGELOG heading ends in
//                                     [gấp]) for the API's membership rules; Caddy never serves it
//
// GITHUB_TOKEN (CI) reads releases and the module-repo branch once the repository is private.
// Fails loudly: a broken feed must never replace a working one.
import { createHash } from 'node:crypto'
import { copyFile, mkdir, readFile, stat, writeFile } from 'node:fs/promises'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const REPO = 'peo1108/Cam-Kernel-SU'
const SITE = process.env.SITE_URL || 'https://caesarvn.xyz'
const DIST = fileURLToPath(new URL('../docs/.vitepress/dist/', import.meta.url))
const ASSETS = fileURLToPath(new URL('../../manager/app/src/main/assets/', import.meta.url))
const CHANGELOG = fileURLToPath(new URL('../../CHANGELOG.md', import.meta.url))
const auth = process.env.GITHUB_TOKEN ? { Authorization: `Bearer ${process.env.GITHUB_TOKEN}` } : {}

async function api(path, accept = 'application/vnd.github+json') {
  const res = await fetch(`https://api.github.com/repos/${REPO}/${path}`, { headers: { Accept: accept, ...auth } })
  if (!res.ok) throw new Error(`GitHub API ${path}: HTTP ${res.status}`)
  return res
}

async function sha256(file) {
  return createHash('sha256').update(await readFile(file)).digest('hex')
}

/* ---------------- releases + downloads ---------------- */

// The official line starts at cam-v1.1.1 (launch, 2026-10-19). Once it exists, everything
// published before it (the 3.x beta-test builds) is left out of the site and the OTA feed.
const FIRST_OFFICIAL = 'cam-v1.1.1'
let all = (await (await api('releases?per_page=30')).json()).filter((r) => !r.draft && /^cam-v/.test(r.tag_name))
const official = all.find((r) => r.tag_name === FIRST_OFFICIAL)
if (official) all = all.filter((r) => Date.parse(r.published_at) >= Date.parse(official.published_at))
const firstStable = all.findIndex((r) => !r.prerelease)
const stable = all.filter((r) => !r.prerelease).slice(0, 6)
// Betas newer than the latest stable release; older betas are of no use to anyone.
const betas = all.slice(0, firstStable < 0 ? all.length : firstStable).filter((r) => r.prerelease)
const keep = all.filter((r) => stable.includes(r) || betas.includes(r))

let fetched = 0
const feed = []
for (const r of keep) {
  const everything = r === stable[0]
  const assets = r.assets.filter((a) => everything || a.name.endsWith('.apk'))
  const dir = join(DIST, 'dl', r.tag_name)
  await mkdir(dir, { recursive: true })
  const out = []
  for (const a of assets) {
    const file = join(dir, a.name)
    const have = await stat(file).catch(() => null)
    if (have?.size !== a.size) {
      // The asset API works on private repositories; it redirects to the storage URL.
      const dl = await fetch(a.url, { headers: { Accept: 'application/octet-stream', ...auth }, redirect: 'follow' })
      if (!dl.ok) throw new Error(`${a.name}: HTTP ${dl.status}`)
      const buf = Buffer.from(await dl.arrayBuffer())
      if (buf.length !== a.size) throw new Error(`${a.name}: size ${buf.length} != ${a.size}`)
      await writeFile(file, buf)
      fetched += buf.length
    }
    const digest = await sha256(file)
    if (a.digest && a.digest !== `sha256:${digest}`) throw new Error(`${a.name}: digest differs from GitHub's`)
    out.push({
      name: a.name,
      size: a.size,
      digest: `sha256:${digest}`,
      browser_download_url: `${SITE}/dl/${r.tag_name}/${encodeURIComponent(a.name)}`,
    })
  }
  feed.push({
    tag_name: r.tag_name,
    name: r.name,
    draft: false,
    prerelease: r.prerelease,
    published_at: r.published_at,
    body: r.body ?? '',
    assets: out,
  })
}
await mkdir(join(DIST, 'feed'), { recursive: true })
await writeFile(join(DIST, 'feed', 'releases.json'), JSON.stringify(feed, null, 1))

// Urgent releases skip the members' 20-day wait: `## 1.2.3 - 2026-11-01 [gấp]`.
const urgent = new Set(
  [...(await readFile(CHANGELOG, 'utf8')).matchAll(/^## (\d+\.\d+\.\d+)\s+-\s+\S+.*\[gấp\]/gm)].map((m) => m[1]),
)
await mkdir(join(DIST, '_private'), { recursive: true })
await writeFile(
  join(DIST, '_private', 'releases.json'),
  JSON.stringify(feed.map((r) => ({ ...r, urgent: urgent.has(r.tag_name.replace(/^cam-v/, '').replace(/-beta\.\d+$/, '')) })), null, 1),
)
console.log(`releases: ${feed.map((r) => r.tag_name).join(', ')} (${(fetched / 1048576).toFixed(1)} MB fetched)`)

/* ---------------- hiding rules (signed: copied byte for byte) ---------------- */

for (const f of ['hiding-rules.json', 'hiding-rules.json.sig']) await copyFile(join(ASSETS, f), join(DIST, 'feed', f))
console.log('hiding rules: copied')

/* ---------------- module library ---------------- */

const tree = await (await api('git/trees/module-repo?recursive=1')).json()
const files = tree.tree.filter((n) => n.type === 'blob' && /^(modules\.json|module\/[\w.-]+\.json)$/.test(n.path))
if (!files.some((n) => n.path === 'modules.json')) throw new Error('module-repo: modules.json missing')
for (const n of files) {
  const body = await (await api(`contents/${n.path}?ref=module-repo`, 'application/vnd.github.raw+json')).arrayBuffer()
  const dest = join(DIST, 'feed', 'module-repo', n.path)
  await mkdir(dirname(dest), { recursive: true })
  await writeFile(dest, Buffer.from(body))
}
console.log(`module repo: ${files.length} files`)

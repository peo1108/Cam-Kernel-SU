// Latest Cam releases, read from the GitHub API at build time (GITHUB_TOKEN in CI, so it keeps
// working once the repository is private). Visitors never see GitHub: the files are mirrored to
// /dl/<tag>/ by scripts/build-feed.mjs with the same selection rules (latest: every asset,
// older: the APK only).

export interface Asset {
  name: string
  url: string
  size: number
}

export interface Lkm {
  arch: string
  kmi: string
  url: string
  size: number
}

export interface Release {
  version: string
  tag: string
  date: string
  url: string
  apk?: Asset
  lkm: Lkm[]
  tools: Asset[]
}

export interface ReleaseData {
  latest: Release | null
  older: Release[]
  /** Betas newer than `latest` (APK only; /dl/ opens them to beta testers). */
  betas: Release[]
}

declare const data: ReleaseData
export { data }

const REPO = 'peo1108/Cam-Kernel-SU'

function toRelease(r: any, latest: boolean): Release {
  const assets: Asset[] = r.assets
    .filter((a: any) => latest || a.name.endsWith('.apk'))
    .map((a: any) => ({
      name: a.name,
      url: `/dl/${r.tag_name}/${a.name}`,
      size: a.size,
    }))
  const lkm: Lkm[] = []
  for (const a of assets) {
    const m = /^lkm-([^-]+)-(.+)_camsu\.ko$/.exec(a.name)
    if (m) lkm.push({ arch: m[1], kmi: m[2], url: a.url, size: a.size })
  }
  return {
    version: r.tag_name.replace(/^cam-v/, ''),
    tag: r.tag_name,
    date: (r.published_at ?? r.created_at).slice(0, 10),
    url: '',
    apk: assets.find((a) => a.name.endsWith('.apk')),
    lkm,
    tools: assets.filter((a) => /^(camd|caminit)-/.test(a.name)),
  }
}

export default {
  async load(): Promise<ReleaseData> {
    const headers: Record<string, string> = { Accept: 'application/vnd.github+json' }
    if (process.env.GITHUB_TOKEN) headers.Authorization = `Bearer ${process.env.GITHUB_TOKEN}`
    try {
      const res = await fetch(`https://api.github.com/repos/${REPO}/releases?per_page=30`, { headers })
      if (!res.ok) throw new Error(`GitHub API ${res.status}`)
      // Same cuts as scripts/build-feed.mjs, in publish order (the API lists "Latest" first):
      // from the first official release on; betas only when newer than the latest stable one.
      const list = ((await res.json()) as any[])
        .filter((r) => !r.draft && /^cam-v/.test(r.tag_name))
        .sort((a, b) => Date.parse(b.published_at) - Date.parse(a.published_at))
      const official = list.find((r) => r.tag_name === 'cam-v1.1.1')
      const all = list.filter((r) => !official || Date.parse(r.published_at) >= Date.parse(official.published_at))
      const releases = all.filter((r) => !r.prerelease).map((r, i) => toRelease(r, i === 0))
      const firstStable = all.findIndex((r) => !r.prerelease)
      const betas = all
        .slice(0, firstStable < 0 ? all.length : firstStable)
        .filter((r) => r.prerelease)
        .map((r) => toRelease(r, false))
      return { latest: releases[0] ?? null, older: releases.slice(1, 6), betas }
    } catch (e) {
      console.warn(`[release.data] ${e}; the download page shows no files`)
      return { latest: null, older: [], betas: [] }
    }
  },
}

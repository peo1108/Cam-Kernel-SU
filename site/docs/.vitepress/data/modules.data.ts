// The Cam module library, published by .github/workflows/module-repo.yml to the module-repo
// branch (the same list the Manager's module repo shows). Fetched at build time.

export interface Module {
  id: string
  name: string
  url: string
  summary: string
  authors: { name: string; link: string }[]
  metamodule: boolean
  stars: number
  updated: string
  release?: { name: string; time: string; downloadUrl: string }
}

declare const data: Module[]
export { data }

const URL_MODULES = 'https://api.github.com/repos/peo1108/Cam-Kernel-SU/contents/modules.json?ref=module-repo'

export default {
  async load(): Promise<Module[]> {
    try {
      const headers: Record<string, string> = { Accept: 'application/vnd.github.raw+json' }
      if (process.env.GITHUB_TOKEN) headers.Authorization = `Bearer ${process.env.GITHUB_TOKEN}`
      const res = await fetch(URL_MODULES, { headers })
      if (!res.ok) throw new Error(`HTTP ${res.status}`)
      const list = (await res.json()) as any[]
      return list.map((m) => ({
        id: m.moduleId,
        name: m.moduleName,
        url: m.url,
        summary: m.summary ?? '',
        authors: m.authors ?? [],
        metamodule: !!m.metamodule,
        stars: m.stargazerCount ?? 0,
        updated: (m.updatedAt ?? '').slice(0, 10),
        release: m.latestRelease,
      }))
    } catch (e) {
      console.warn(`[modules.data] ${e}; the module page will be empty`)
      return []
    }
  },
}

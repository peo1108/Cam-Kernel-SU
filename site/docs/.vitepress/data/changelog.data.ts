// Parses the repo's CHANGELOG.md (the same file the Release workflow and the Manager's
// "What's new" read) into entries: "## X.Y.Z - YYYY-MM-DD", "### Group", "- item".
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'

export interface Group {
  title: string
  items: string[]
}

export interface Entry {
  version: string
  date: string
  groups: Group[]
}

declare const data: Entry[]
export { data }

const file = fileURLToPath(new URL('../../../../CHANGELOG.md', import.meta.url))

export function parse(text: string): Entry[] {
  const entries: Entry[] = []
  let entry: Entry | null = null
  let group: Group | null = null
  for (const line of text.split(/\r?\n/)) {
    const head = /^## (\S+) - (\d{4}-\d{2}-\d{2})/.exec(line)
    if (head) {
      entry = { version: head[1], date: head[2], groups: [] }
      group = null
      entries.push(entry)
      continue
    }
    if (!entry) continue
    const sub = /^### (.+)/.exec(line)
    if (sub) {
      group = { title: sub[1].trim(), items: [] }
      entry.groups.push(group)
      continue
    }
    const item = /^- (.+)/.exec(line)
    if (item) {
      if (!group) {
        group = { title: '', items: [] }
        entry.groups.push(group)
      }
      group.items.push(item[1].trim())
    }
  }
  return entries
}

export default {
  watch: [file],
  load(): Entry[] {
    return parse(readFileSync(file, 'utf8'))
  },
}

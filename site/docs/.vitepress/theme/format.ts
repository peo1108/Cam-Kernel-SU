// Tiny inline Markdown for changelog lines: `code`, **bold**, [text](https://...).
// Everything is escaped first, so a changelog line can never inject HTML.
export function inline(text: string): string {
  const esc = text.replace(/[&<>"']/g, (c) => `&#${c.charCodeAt(0)};`)
  return esc
    .replace(/`([^`]+)`/g, '<code>$1</code>')
    .replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>')
    .replace(/\[([^\]]+)\]\((https:\/\/[^)\s]+)\)/g, '<a href="$2" target="_blank" rel="noreferrer">$1</a>')
}

export function mb(bytes: number): string {
  return `${(bytes / 1024 / 1024).toFixed(bytes < 10 * 1024 * 1024 ? 1 : 0)} MB`
}

export function fmtDate(iso: string, lang: 'vi' | 'en' | 'zh'): string {
  if (!iso) return ''
  const [y, m, d] = iso.split('-')
  return lang === 'vi' ? `${d}/${m}/${y}` : `${y}-${m}-${d}`
}

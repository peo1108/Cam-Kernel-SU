// Source access requests from https://caesarvn.xyz/ma-nguon (site/docs/.vitepress/theme/components/SourceRequest.vue).
// Runs on the VPS behind Caddy (`handle /api/*`), listens on 127.0.0.1 only, no dependencies.
// Each request is appended as one JSON line to $DATA_DIR/requests.jsonl; Cam reviews them with
// `cam-requests` and invites approved GitHub accounts by hand.
// Optional Telegram notice: TG_TOKEN and TG_CHAT in the service environment.
import { createServer } from 'node:http'
import { appendFile, mkdir } from 'node:fs/promises'
import { join } from 'node:path'

const PORT = Number(process.env.PORT || 7792)
const DATA_DIR = process.env.DATA_DIR || '/var/lib/cam-site-api'
const FILE = join(DATA_DIR, 'requests.jsonl')
const GH = /^[A-Za-z0-9](?:[A-Za-z0-9-]{0,37}[A-Za-z0-9])?$/
const LIMITS = { name: 80, contact: 120, github: 40, reason: 1000 }

// 5 requests per address per hour, kept in memory.
const hits = new Map()
function limited(ip) {
  const now = Date.now()
  const recent = (hits.get(ip) || []).filter((t) => now - t < 3600_000)
  recent.push(now)
  hits.set(ip, recent)
  if (hits.size > 5000) hits.clear()
  return recent.length > 5
}

function clean(v, max) {
  return typeof v === 'string' ? v.replace(/[\u0000-\u001f\u007f]/g, ' ').trim().slice(0, max) : ''
}

async function notify(entry) {
  const { TG_TOKEN, TG_CHAT } = process.env
  if (!TG_TOKEN || !TG_CHAT) return
  const text = `Yêu cầu mã nguồn\n${entry.name} · ${entry.contact}\nGitHub: ${entry.github}\n\n${entry.reason}`
  try {
    await fetch(`https://api.telegram.org/bot${TG_TOKEN}/sendMessage`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ chat_id: TG_CHAT, text, disable_web_page_preview: true }),
      signal: AbortSignal.timeout(5000),
    })
  } catch {}
}

function send(res, code, body) {
  res.writeHead(code, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' })
  res.end(JSON.stringify(body))
}

const server = createServer((req, res) => {
  if (req.method !== 'POST' || req.url !== '/api/source-request') return send(res, 404, { error: 'not found' })
  if (!String(req.headers['content-type'] || '').startsWith('application/json')) return send(res, 415, { error: 'json only' })
  // Caddy puts the client address first in X-Forwarded-For.
  const ip = String(req.headers['x-forwarded-for'] || req.socket.remoteAddress || '').split(',')[0].trim()
  let size = 0
  const chunks = []
  req.on('data', (c) => {
    size += c.length
    if (size > 8192) {
      send(res, 413, { error: 'too large' })
      req.destroy()
    } else chunks.push(c)
  })
  req.on('end', async () => {
    if (res.writableEnded) return
    if (limited(ip)) return send(res, 429, { error: 'slow down' })
    let body
    try {
      body = JSON.parse(Buffer.concat(chunks).toString('utf8'))
    } catch {
      return send(res, 400, { error: 'bad json' })
    }
    // Honeypot filled: answer like a success, store nothing.
    if (body.website) return send(res, 200, { ok: true })
    const entry = {
      at: new Date().toISOString(),
      ip,
      lang: ['en', 'zh'].includes(body.lang) ? body.lang : 'vi',
      name: clean(body.name, LIMITS.name),
      contact: clean(body.contact, LIMITS.contact),
      github: clean(body.github, LIMITS.github).replace(/^@/, ''),
      reason: clean(body.reason, LIMITS.reason),
    }
    if (!entry.name || !entry.contact || !entry.reason || !GH.test(entry.github)) return send(res, 400, { error: 'invalid' })
    try {
      await mkdir(DATA_DIR, { recursive: true })
      await appendFile(FILE, JSON.stringify(entry) + '\n', { mode: 0o600 })
    } catch (e) {
      console.error('store failed', e)
      return send(res, 500, { error: 'store failed' })
    }
    notify(entry)
    send(res, 200, { ok: true })
  })
})

server.listen(PORT, '127.0.0.1', () => console.log(`source-request on 127.0.0.1:${PORT}`))

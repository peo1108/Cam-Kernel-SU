// Minimal HTTP plumbing: routing, JSON bodies, cookies, errors. No dependencies.

export const DAY = 24 * 3600 * 1000
export const SESSION_COOKIE = 'cam_s'

export class HttpError extends Error {
  constructor(status, code, message = code) {
    super(message)
    this.status = status
    this.code = code
  }
}

export const fail = (status, code) => {
  throw new HttpError(status, code)
}

export function parseCookies(header = '') {
  const out = {}
  for (const part of header.split(';')) {
    const i = part.indexOf('=')
    if (i > 0) out[part.slice(0, i).trim()] = decodeURIComponent(part.slice(i + 1).trim())
  }
  return out
}

export function cookie(name, value, { maxAge, secure = true } = {}) {
  const parts = [`${name}=${encodeURIComponent(value)}`, 'Path=/', 'HttpOnly', 'SameSite=Lax']
  if (secure) parts.push('Secure')
  if (maxAge != null) parts.push(`Max-Age=${Math.floor(maxAge / 1000)}`)
  return parts.join('; ')
}

export async function readJson(req, limit = 16 * 1024) {
  // No body at all (DELETE, or a POST that only names its target in the path): nothing to read.
  const length = req.headers['content-length']
  if (!req.headers['content-type'] && (length === undefined || length === '0') && !req.headers['transfer-encoding']) return {}
  if (!String(req.headers['content-type'] || '').startsWith('application/json')) fail(415, 'json_only')
  let size = 0
  const chunks = []
  for await (const c of req) {
    size += c.length
    if (size > limit) fail(413, 'too_large')
    chunks.push(c)
  }
  if (!size) return {}
  try {
    const v = JSON.parse(Buffer.concat(chunks).toString('utf8'))
    return v && typeof v === 'object' ? v : {}
  } catch {
    fail(400, 'bad_json')
  }
}

/** Raw body (webhooks that sign or authenticate the exact bytes). */
export async function readRaw(req, limit = 64 * 1024) {
  let size = 0
  const chunks = []
  for await (const c of req) {
    size += c.length
    if (size > limit) fail(413, 'too_large')
    chunks.push(c)
  }
  return Buffer.concat(chunks)
}

export function send(res, status, body, headers = {}) {
  if (res.headersSent) return
  const json = body === undefined ? '' : JSON.stringify(body)
  res.writeHead(status, {
    'Content-Type': 'application/json; charset=utf-8',
    'Cache-Control': 'no-store',
    'X-Content-Type-Options': 'nosniff',
    ...headers,
  })
  res.end(json)
}

export class Router {
  routes = []
  add(method, path, handler, opts = {}) {
    // '/api/admin/users/:id' -> /^\/api\/admin\/users\/([^/]+)$/
    const keys = []
    const re = new RegExp(
      '^' + path.replace(/:(\w+)/g, (_, k) => (keys.push(k), '([^/]+)')).replace(/\//g, '\\/') + '$',
    )
    this.routes.push({ method, re, keys, handler, opts })
  }
  match(method, pathname) {
    let methodMismatch = false
    for (const r of this.routes) {
      const m = r.re.exec(pathname)
      if (!m) continue
      if (r.method !== method) {
        methodMismatch = true
        continue
      }
      const params = {}
      r.keys.forEach((k, i) => (params[k] = decodeURIComponent(m[i + 1])))
      return { route: r, params }
    }
    return methodMismatch ? 'method' : null
  }
}

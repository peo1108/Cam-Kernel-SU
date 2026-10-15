// Accounts and sessions: scrypt passwords, opaque session tokens (only their SHA-256 is stored),
// Google sign-in (authorization code flow), password reset by mail (Brevo HTTP API).
import { createHash, randomBytes, scrypt, timingSafeEqual } from 'node:crypto'
import { promisify } from 'node:util'
import { fail } from './http.mjs'

const scryptAsync = promisify(scrypt)
const N = 1 << 15
const KEYLEN = 32
export const WEB_SESSION_MS = 60 * 24 * 3600 * 1000
export const MAX_DEVICES = 3

export const sha256 = (s) => createHash('sha256').update(s).digest('hex')
export const newToken = () => randomBytes(32).toString('base64url')

export async function hashPassword(password) {
  const salt = randomBytes(16)
  const key = await scryptAsync(password, salt, KEYLEN, { N, r: 8, p: 1, maxmem: 64 * 1024 * 1024 })
  return `scrypt$${N}$${salt.toString('base64')}$${key.toString('base64')}`
}

export async function checkPassword(password, stored) {
  if (!stored?.startsWith('scrypt$')) return false
  const [, n, salt, key] = stored.split('$')
  const want = Buffer.from(key, 'base64')
  const got = await scryptAsync(password, Buffer.from(salt, 'base64'), want.length, { N: Number(n), r: 8, p: 1, maxmem: 64 * 1024 * 1024 })
  return want.length === got.length && timingSafeEqual(want, got)
}

const EMAIL = /^[^\s@]{1,64}@[^\s@]{1,190}\.[^\s@]{2,24}$/
export function cleanEmail(v) {
  const e = typeof v === 'string' ? v.trim().toLowerCase() : ''
  if (!EMAIL.test(e)) fail(400, 'bad_email')
  return e
}
export function cleanPassword(v) {
  if (typeof v !== 'string' || v.length < 8 || v.length > 200) fail(400, 'bad_password')
  return v
}

/* ---------------- sessions ---------------- */

/** [mfa]: the session passed the two-step code (admin pages need it once 2FA is on). */
export function createSession(db, userId, kind, deviceName = null, mfa = false) {
  const token = newToken()
  const now = Date.now()
  db.prepare('INSERT INTO sessions(token_hash, user_id, kind, device_name, created_at, last_seen, mfa) VALUES (?, ?, ?, ?, ?, ?, ?)').run(
    sha256(token), userId, kind, deviceName, now, now, mfa ? 1 : 0,
  )
  if (kind === 'device') {
    // Keep the newest MAX_DEVICES devices of the account.
    const old = db
      .prepare("SELECT token_hash FROM sessions WHERE user_id = ? AND kind = 'device' ORDER BY created_at DESC LIMIT -1 OFFSET ?")
      .all(userId, MAX_DEVICES)
    for (const o of old) db.prepare('DELETE FROM sessions WHERE token_hash = ?').run(o.token_hash)
  }
  return token
}

/** The session and user behind a token, or null. Web sessions expire; device ones do not. */
export function lookupSession(db, token) {
  if (!token || token.length > 100) return null
  const hash = sha256(token)
  const s = db.prepare('SELECT * FROM sessions WHERE token_hash = ?').get(hash)
  if (!s) return null
  const now = Date.now()
  if (s.kind === 'web' && now - s.last_seen > WEB_SESSION_MS) {
    db.prepare('DELETE FROM sessions WHERE token_hash = ?').run(hash)
    return null
  }
  const user = db.prepare('SELECT * FROM users WHERE id = ?').get(s.user_id)
  if (!user || user.locked_at) return null
  if (now - s.last_seen > 60_000) db.prepare('UPDATE sessions SET last_seen = ? WHERE token_hash = ?').run(now, hash)
  return { session: s, user }
}

/* ---------------- rate limits (memory) ---------------- */

/**
 * A rate limiter with its own memory (one per app): limited(key, max, windowMs) is true when
 * [key] went over [max] hits in [windowMs]. Past [cap] keys, those whose window is over go first,
 * then the least recently hit: a flood of fresh keys never resets the key being hammered.
 */
export function makeLimiter(cap = 20000) {
  const buckets = new Map() // key -> { hits, windowMs }, least recently hit first
  return function limited(key, max, windowMs) {
    const now = Date.now()
    const hits = (buckets.get(key)?.hits || []).filter((t) => now - t < windowMs)
    hits.push(now)
    buckets.delete(key)
    buckets.set(key, { hits, windowMs })
    if (buckets.size > cap) {
      for (const [k, b] of buckets) if (now - b.hits[b.hits.length - 1] >= b.windowMs) buckets.delete(k)
      for (const k of buckets.keys()) {
        if (buckets.size <= cap * 0.9) break
        buckets.delete(k)
      }
    }
    return hits.length > max
  }
}

/* ---------------- Google ---------------- */

export function googleAuthUrl(cfg, state) {
  const q = new URLSearchParams({
    client_id: cfg.googleClientId,
    redirect_uri: `${cfg.origin}/api/auth/google/callback`,
    response_type: 'code',
    scope: 'openid email profile',
    state,
    prompt: 'select_account',
  })
  return `https://accounts.google.com/o/oauth2/v2/auth?${q}`
}

/** Exchanges the code; returns { sub, email, name } of a verified Google account. */
export async function googleProfile(cfg, code) {
  const tokenRes = await fetch('https://oauth2.googleapis.com/token', {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({
      code,
      client_id: cfg.googleClientId,
      client_secret: cfg.googleClientSecret,
      redirect_uri: `${cfg.origin}/api/auth/google/callback`,
      grant_type: 'authorization_code',
    }),
    signal: AbortSignal.timeout(10000),
  })
  if (!tokenRes.ok) fail(502, 'google_token')
  const { access_token } = await tokenRes.json()
  const infoRes = await fetch('https://openidconnect.googleapis.com/v1/userinfo', {
    headers: { Authorization: `Bearer ${access_token}` },
    signal: AbortSignal.timeout(10000),
  })
  if (!infoRes.ok) fail(502, 'google_userinfo')
  const info = await infoRes.json()
  if (!info.email || !info.email_verified) fail(403, 'google_unverified')
  return { sub: String(info.sub), email: String(info.email).toLowerCase(), name: info.name ? String(info.name).slice(0, 80) : null }
}

/* ---------------- mail (Brevo) ---------------- */

export async function sendMail(cfg, to, subject, text) {
  if (!cfg.brevoKey) fail(503, 'mail_off')
  const res = await fetch('https://api.brevo.com/v3/smtp/email', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'api-key': cfg.brevoKey },
    body: JSON.stringify({ sender: { email: cfg.mailFrom, name: 'Cam Kernel SU' }, to: [{ email: to }], subject, textContent: text }),
    signal: AbortSignal.timeout(10000),
  })
  if (!res.ok) fail(502, 'mail_failed')
}

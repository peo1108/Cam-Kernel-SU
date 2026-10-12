// TOTP (RFC 6238: HMAC-SHA1, 30 s, 6 digits) for admin two-step sign-in. Works with Google
// Authenticator, Authy, 2FAS, Bitwarden... No dependencies.
import { createHmac, randomBytes, timingSafeEqual } from 'node:crypto'

const B32 = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567'

export function base32(buf) {
  let bits = 0
  let value = 0
  let out = ''
  for (const byte of buf) {
    value = (value << 8) | byte
    bits += 8
    while (bits >= 5) {
      out += B32[(value >>> (bits - 5)) & 31]
      bits -= 5
    }
  }
  if (bits > 0) out += B32[(value << (5 - bits)) & 31]
  return out
}

export function unbase32(s) {
  const clean = s.toUpperCase().replace(/[^A-Z2-7]/g, '')
  let bits = 0
  let value = 0
  const out = []
  for (const c of clean) {
    value = (value << 5) | B32.indexOf(c)
    bits += 5
    if (bits >= 8) {
      out.push((value >>> (bits - 8)) & 255)
      bits -= 8
    }
  }
  return Buffer.from(out)
}

export const newSecret = () => base32(randomBytes(20))

export function code(secret, step) {
  const msg = Buffer.alloc(8)
  msg.writeBigUInt64BE(BigInt(step))
  const h = createHmac('sha1', unbase32(secret)).update(msg).digest()
  const o = h[h.length - 1] & 15
  const n = ((h[o] & 127) << 24) | (h[o + 1] << 16) | (h[o + 2] << 8) | h[o + 3]
  return String(n % 1_000_000).padStart(6, '0')
}

// Last accepted step per user: a code works once (no replay within its window).
const used = new Map()

/** True when [input] is the code for now (±1 step for clock drift) and was not used before. */
export function verify(secret, input, key, now = Date.now()) {
  const given = String(input || '').replace(/\s/g, '')
  if (!/^\d{6}$/.test(given) || !secret) return false
  const step = Math.floor(now / 30000)
  for (const s of [step - 1, step, step + 1]) {
    const want = code(secret, s)
    if (timingSafeEqual(Buffer.from(want), Buffer.from(given))) {
      if ((used.get(key) ?? -1) >= s) return false
      used.set(key, s)
      return true
    }
  }
  return false
}

export function otpauthUri(secret, email, issuer = 'Cam Kernel SU') {
  return `otpauth://totp/${encodeURIComponent(`${issuer}:${email}`)}?secret=${secret}&issuer=${encodeURIComponent(issuer)}&algorithm=SHA1&digits=6&period=30`
}

// Accounts: register, sign in (with the two-step code when it is on), Google, password reset, 2FA.
import { tx } from '../db.mjs'
import { SESSION_COOKIE, cookie, fail } from '../http.mjs'
import {
  WEB_SESSION_MS,
  checkPassword,
  cleanEmail,
  cleanPassword,
  createSession,
  googleAuthUrl,
  googleProfile,
  hashPassword,
  newToken,
  sendMail,
  sha256,
} from '../auth.mjs'
import { newSecret, otpauthUri, verify } from '../totp.mjs'

const GOOGLE_COOKIE = 'cam_g'
export const str = (v, max) => (typeof v === 'string' ? v.replace(/[\u0000-\u001f\u007f]/g, ' ').trim().slice(0, max) : '')
const safeNext = (n) => (typeof n === 'string' && /^\/(?!\/)[\w\-/.?=&%#]*$/.test(n) ? n : '/thanh-vien')

export default function authRoutes(app) {
  const { db, cfg, router: r, limited } = app

  const setSession = (ctx, token) =>
    ctx.headers.push(['Set-Cookie', cookie(SESSION_COOKIE, token, { maxAge: WEB_SESSION_MS, secure: cfg.cookieSecure })])
  const clearCookie = (ctx, name) => ctx.headers.push(['Set-Cookie', cookie(name, '', { maxAge: 0, secure: cfg.cookieSecure })])

  r.add('GET', '/api/auth/features', (ctx) => {
    const s = app.settings()
    return {
      google: !!cfg.googleClientId,
      mail: !!cfg.brevoKey,
      signed_in: !!ctx.user && ctx.session.kind === 'web',
      prices: { member: s.price_member, early: s.price_early },
      embargo_days: s.embargo_days,
      sales_open: s.sales_open,
      notice: s.notice,
    }
  })

  r.add('POST', '/api/auth/register', async (ctx) => {
    const email = cleanEmail(ctx.body.email)
    const password = cleanPassword(ctx.body.password)
    if (limited(`reg:${ctx.ip}`, 10, 3600_000)) fail(429, 'slow_down')
    if (db.prepare('SELECT 1 FROM users WHERE email = ?').get(email)) fail(409, 'email_taken')
    const hash = await hashPassword(password)
    const id = db
      .prepare('INSERT INTO users(email, pass_hash, name, created_at) VALUES (?, ?, ?, ?)')
      .run(email, hash, str(ctx.body.name, 80) || null, Date.now()).lastInsertRowid
    setSession(ctx, createSession(db, id, 'web'))
    return { ok: true }
  })

  r.add('POST', '/api/auth/login', async (ctx) => {
    const email = cleanEmail(ctx.body.email)
    if (limited(`login:${ctx.ip}`, 20, 900_000) || limited(`login:${email}`, 8, 900_000)) fail(429, 'slow_down')
    const u = db.prepare('SELECT * FROM users WHERE email = ?').get(email)
    const ok = u?.pass_hash && (await checkPassword(String(ctx.body.password || ''), u.pass_hash))
    if (!ok) fail(401, 'bad_login')
    if (u.locked_at) fail(403, 'locked')
    let mfa = false
    if (u.totp_on) {
      // Password was right; now the code. Asking for it is not a hint that the password was right
      // to anyone without it, since a wrong password stops above.
      if (!ctx.body.totp) fail(401, 'totp_required')
      if (limited(`totp:${u.id}`, 6, 900_000)) fail(429, 'slow_down')
      if (!verify(u.totp_secret, ctx.body.totp, u.id)) fail(401, 'bad_totp')
      mfa = true
    }
    setSession(ctx, createSession(db, u.id, 'web', null, mfa))
    return { ok: true }
  })

  r.add('POST', '/api/auth/logout', (ctx) => {
    if (ctx.session) db.prepare('DELETE FROM sessions WHERE token_hash = ?').run(ctx.session.token_hash)
    clearCookie(ctx, SESSION_COOKIE)
    return { ok: true }
  })

  r.add(
    'POST',
    '/api/auth/password',
    async (ctx) => {
      const u = ctx.user
      if (u.pass_hash && !(await checkPassword(String(ctx.body.current || ''), u.pass_hash))) fail(401, 'bad_login')
      const hash = await hashPassword(cleanPassword(ctx.body.password))
      db.prepare('UPDATE users SET pass_hash = ? WHERE id = ?').run(hash, u.id)
      // Other web sessions end; this one and the devices stay.
      db.prepare("DELETE FROM sessions WHERE user_id = ? AND kind = 'web' AND token_hash != ?").run(u.id, ctx.session.token_hash)
      return { ok: true }
    },
    { auth: 'web' },
  )

  r.add('POST', '/api/auth/reset/request', async (ctx) => {
    const email = cleanEmail(ctx.body.email)
    if (limited(`reset:${ctx.ip}`, 5, 3600_000) || limited(`reset:${email}`, 3, 3600_000)) fail(429, 'slow_down')
    if (!cfg.brevoKey) fail(503, 'mail_off')
    const u = db.prepare('SELECT * FROM users WHERE email = ?').get(email)
    // Same answer whether or not the email has an account.
    if (u && !u.locked_at) {
      const token = newToken()
      db.prepare('INSERT INTO resets(token_hash, user_id, created_at) VALUES (?, ?, ?)').run(sha256(token), u.id, Date.now())
      await sendMail(
        cfg,
        email,
        'Đặt lại mật khẩu · Cam Kernel SU',
        `Mở liên kết này trong 1 giờ để đặt mật khẩu mới:\n${cfg.origin}/tai-khoan?reset=${token}\n\nKhông phải bạn yêu cầu thì bỏ qua email này.`,
      )
    }
    return { ok: true }
  })

  r.add('POST', '/api/auth/reset/confirm', async (ctx) => {
    const token = str(ctx.body.token, 100)
    const row = token && db.prepare('SELECT * FROM resets WHERE token_hash = ?').get(sha256(token))
    if (!row || Date.now() - row.created_at > 3600_000) fail(400, 'bad_token')
    const hash = await hashPassword(cleanPassword(ctx.body.password))
    tx(db, () => {
      db.prepare('UPDATE users SET pass_hash = ? WHERE id = ?').run(hash, row.user_id)
      db.prepare('DELETE FROM resets WHERE user_id = ?').run(row.user_id)
      db.prepare("DELETE FROM sessions WHERE user_id = ? AND kind = 'web'").run(row.user_id)
    })
    return { ok: true }
  })

  /* ----- Google ----- */

  r.add('GET', '/api/auth/google', (ctx) => {
    if (!cfg.googleClientId) fail(503, 'google_off')
    const state = newToken()
    const next = safeNext(ctx.url.searchParams.get('next'))
    ctx.headers.push(['Set-Cookie', cookie(GOOGLE_COOKIE, `${state}|${next}`, { maxAge: 600_000, secure: cfg.cookieSecure })])
    ctx.redirect = googleAuthUrl(cfg, state)
  })

  r.add('GET', '/api/auth/google/callback', async (ctx) => {
    const [state, next] = (ctx.cookies[GOOGLE_COOKIE] || '').split('|')
    clearCookie(ctx, GOOGLE_COOKIE)
    const code = ctx.url.searchParams.get('code')
    if (!state || state !== ctx.url.searchParams.get('state') || !code) {
      ctx.redirect = '/tai-khoan?error=google'
      return
    }
    const p = await googleProfile(cfg, code)
    let u = db.prepare('SELECT * FROM users WHERE google_sub = ?').get(p.sub) || db.prepare('SELECT * FROM users WHERE email = ?').get(p.email)
    if (!u) {
      const id = db.prepare('INSERT INTO users(email, google_sub, name, created_at) VALUES (?, ?, ?, ?)').run(p.email, p.sub, p.name, Date.now()).lastInsertRowid
      u = db.prepare('SELECT * FROM users WHERE id = ?').get(id)
    } else if (!u.google_sub) {
      db.prepare('UPDATE users SET google_sub = ?, name = COALESCE(name, ?) WHERE id = ?').run(p.sub, p.name, u.id)
    }
    if (u.locked_at) {
      ctx.redirect = '/tai-khoan?error=locked'
      return
    }
    // Google proves the email, not the second step: 2FA users enter their code on the next page.
    setSession(ctx, createSession(db, u.id, 'web'))
    ctx.redirect = u.totp_on ? `/tai-khoan?mfa=1&next=${encodeURIComponent(safeNext(next))}` : safeNext(next)
  })

  /* ----- two-step sign-in (TOTP) ----- */

  r.add(
    'POST',
    '/api/auth/totp/setup',
    (ctx) => {
      const u = db.prepare('SELECT totp_on, totp_secret FROM users WHERE id = ?').get(ctx.user.id)
      if (u.totp_on) fail(409, 'totp_on')
      // Keep the key that is waiting for its first code: pressing setup again or reloading the
      // page must not leave the authenticator app holding a key the server already replaced.
      const secret = u.totp_secret || newSecret()
      if (!u.totp_secret) db.prepare('UPDATE users SET totp_secret = ? WHERE id = ?').run(secret, ctx.user.id)
      return { secret, uri: otpauthUri(secret, ctx.user.email) }
    },
    { auth: 'web' },
  )

  r.add(
    'POST',
    '/api/auth/totp/enable',
    (ctx) => {
      const u = db.prepare('SELECT * FROM users WHERE id = ?').get(ctx.user.id)
      if (u.totp_on) fail(409, 'totp_on')
      if (limited(`totp:${u.id}`, 6, 900_000)) fail(429, 'slow_down')
      if (!verify(u.totp_secret, ctx.body.code, u.id)) fail(401, 'bad_totp')
      tx(db, () => {
        db.prepare('UPDATE users SET totp_on = 1 WHERE id = ?').run(u.id)
        db.prepare('UPDATE sessions SET mfa = 1 WHERE token_hash = ?').run(ctx.session.token_hash)
        // Every other web session must sign in again with the code.
        db.prepare("DELETE FROM sessions WHERE user_id = ? AND kind = 'web' AND token_hash != ?").run(u.id, ctx.session.token_hash)
      })
      app.audit(u, 'totp_on', u.email)
      return { ok: true }
    },
    { auth: 'web' },
  )

  r.add(
    'POST',
    '/api/auth/totp/disable',
    (ctx) => {
      const u = db.prepare('SELECT * FROM users WHERE id = ?').get(ctx.user.id)
      if (!u.totp_on) return { ok: true }
      if (limited(`totp:${u.id}`, 6, 900_000)) fail(429, 'slow_down')
      if (!verify(u.totp_secret, ctx.body.code, u.id)) fail(401, 'bad_totp')
      db.prepare('UPDATE users SET totp_on = 0, totp_secret = NULL WHERE id = ?').run(u.id)
      app.audit(u, 'totp_off', u.email)
      return { ok: true }
    },
    { auth: 'web' },
  )

  /** Upgrades the current session after a Google sign-in (or an old session) with the code. */
  r.add(
    'POST',
    '/api/auth/totp/verify',
    (ctx) => {
      const u = ctx.user
      if (!u.totp_on) return { ok: true }
      if (limited(`totp:${u.id}`, 6, 900_000)) fail(429, 'slow_down')
      if (!verify(u.totp_secret, ctx.body.code, u.id)) fail(401, 'bad_totp')
      db.prepare('UPDATE sessions SET mfa = 1 WHERE token_hash = ?').run(ctx.session.token_hash)
      return { ok: true }
    },
    { auth: 'web' },
  )
}

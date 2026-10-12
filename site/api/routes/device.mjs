// The Manager: pairing with an account, OTA for that account, and the /dl/ gate.
//
// Pairing (no password ever typed into the root app):
//   app  POST /api/devices/start {name}     -> { id, code, expires_in }   shows the code / QR
//   web  POST /api/devices/confirm {code}   (signed in)                    links it
//   app  GET  /api/devices/poll?id=          -> { status, token? }          token once, then gone
// The token is a device session (sessions.kind = 'device'), sent as `Authorization: Bearer`.
import { randomInt } from 'node:crypto'
import { fail } from '../http.mjs'
import { createSession, newToken, sha256 } from '../auth.mjs'
import { str } from './auth.mjs'

const PAIR_ALPHA = 'ABCDEFGHJKMNPQRSTUVWXYZ23456789'
const PAIR_MS = 10 * 60 * 1000

export default function deviceRoutes(app) {
  const { db, cfg, router: r, limited } = app

  const cleanPairings = () => db.prepare('DELETE FROM pairings WHERE created_at < ?').run(Date.now() - PAIR_MS)

  r.add('POST', '/api/devices/start', (ctx) => {
    if (limited(`pair:${ctx.ip}`, 12, 3600_000)) fail(429, 'slow_down')
    cleanPairings()
    let code
    do {
      code = Array.from({ length: 6 }, () => PAIR_ALPHA[randomInt(PAIR_ALPHA.length)]).join('')
    } while (db.prepare('SELECT 1 FROM pairings WHERE code = ?').get(code))
    const id = newToken()
    db.prepare('INSERT INTO pairings(id, code, device_name, created_at) VALUES (?, ?, ?, ?)').run(sha256(id), code, str(ctx.body.name, 60) || null, Date.now())
    return { id, code, expires_in: PAIR_MS / 1000, url: `${cfg.origin}/ket-noi?code=${code}` }
  })

  r.add('GET', '/api/devices/poll', (ctx) => {
    cleanPairings()
    const id = str(ctx.url.searchParams.get('id'), 100)
    const p = id && db.prepare('SELECT * FROM pairings WHERE id = ?').get(sha256(id))
    if (!p) return { status: 'expired' }
    if (!p.token) return { status: 'pending' }
    // Handed over once.
    db.prepare('DELETE FROM pairings WHERE id = ?').run(p.id)
    const u = db.prepare('SELECT email FROM users WHERE id = ?').get(p.user_id)
    return { status: 'linked', token: p.token, email: u?.email ?? null }
  })

  r.add(
    'POST',
    '/api/devices/confirm',
    (ctx) => {
      if (limited(`pairc:${ctx.user.id}`, 10, 600_000)) fail(429, 'slow_down')
      cleanPairings()
      const code = str(ctx.body.code, 12).toUpperCase().replace(/[^A-Z0-9]/g, '')
      const p = db.prepare('SELECT * FROM pairings WHERE code = ?').get(code)
      if (!p || p.token) fail(404, 'bad_code')
      const token = createSession(db, ctx.user.id, 'device', p.device_name)
      db.prepare('UPDATE pairings SET user_id = ?, token = ? WHERE id = ?').run(ctx.user.id, token, p.id)
      return { ok: true, device: p.device_name }
    },
    { auth: 'web' },
  )

  /** The app signs itself out. */
  r.add(
    'POST',
    '/api/devices/self/unlink',
    (ctx) => {
      db.prepare('DELETE FROM sessions WHERE token_hash = ?').run(ctx.session.token_hash)
      return { ok: true }
    },
    { auth: 'device' },
  )

  /* ----- OTA ----- */

  /** Account and what is waiting: the Manager shows "join" or "x.y.z opens on <date>". */
  r.add(
    'GET',
    '/api/ota/status',
    (ctx) => {
      const st = app.accountState(ctx.user)
      const s = app.settings()
      const rel = app.releaseStates(st)
      const req = db.prepare('SELECT status FROM tester_requests WHERE user_id = ? ORDER BY created_at DESC LIMIT 1').get(ctx.user.id)
      return {
        email: ctx.user.email,
        member: !!(st.member || st.admin),
        tester: st.tester || st.admin ? 'approved' : req?.status ?? 'none',
        // Releases this account cannot install yet. The notes and the APK's name (its version code)
        // let the app announce one and sell early access; never a download link.
        locked: rel
          .filter((x) => !x.open)
          .map((x) => {
            const r = app.catalog().find((c) => c.tag_name === x.tag)
            const apk = r?.assets?.find((a) => a.name.endsWith('.apk'))
            return { version: x.version, tag: x.tag, open_at: x.open_at, prerelease: x.prerelease, price: s.price_early, body: r?.body ?? '', apk: apk?.name ?? null }
          }),
        // The app offers "get it now" only while early access is on sale and payments are set up.
        can_buy_early: !!(st.member && s.sales_open && s.early_open && cfg.bankAccount && cfg.bankCode),
        join_url: `${cfg.origin}/thanh-vien`,
        buy_url: `${cfg.origin}/thanh-vien#releases`,
      }
    },
    { auth: 'device' },
  )

  /** Releases this account may install, in the GitHub releases API layout UpdateInfo.kt parses. */
  r.add(
    'GET',
    '/api/ota/releases',
    (ctx) => {
      const st = app.accountState(ctx.user)
      if (!st.member && !st.admin) fail(403, 'members_only')
      const open = new Set(app.releaseStates(st).filter((x) => x.open).map((x) => x.tag))
      return app.catalog().filter((x) => open.has(x.tag_name)).map(({ urgent, ...rest }) => rest)
    },
    { auth: 'device' },
  )

  /* ----- /dl/ gate (Caddy forward_auth) ----- */

  r.add('GET', '/api/dl/check', (ctx) => {
    if (!cfg.dlGuard) return { ok: true }
    const uri = decodeURIComponent(String(ctx.req.headers['x-forwarded-uri'] || ''))
    const m = /^\/dl\/(cam-v[\w.-]+)\/[^/]+$/.exec(uri.split('?')[0])
    if (!m) fail(404, 'not_found')
    const tag = m[1]
    if (!ctx.user) {
      // A browser goes to sign in; the Manager gets a status it understands.
      if (String(ctx.req.headers.accept || '').includes('text/html')) {
        ctx.redirect = `/tai-khoan?next=${encodeURIComponent('/thanh-vien')}`
        return
      }
      fail(401, 'sign_in')
    }
    const st = app.accountState(ctx.user)
    const rel = app.releaseStates(st).find((x) => x.tag === tag)
    if (!rel) fail(404, 'not_found')
    if (!rel.open) fail(403, st.member || st.admin ? 'not_open_yet' : 'members_only')
    return { ok: true }
  })
}

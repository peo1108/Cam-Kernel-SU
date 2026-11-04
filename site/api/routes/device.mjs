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

/** The Manager's version code, from the User-Agent it sends (`CamSU/<code>`); 0 when unknown. */
export const appCodeOf = (req) => Number(/^CamSU\/(\d+)/.exec(String(req.headers['user-agent'] || ''))?.[1] ?? 0)

/** A release's APK version code, from its name (`..._<code>-release.apk`); 0 when it has none. */
const apkCodeOf = (release) => Number(/_(\d+)-release\.apk$/.exec(release?.assets?.find((a) => a.name.endsWith('.apk'))?.name ?? '')?.[1] ?? 0)

// The Manager became cam.caesar.vn at version code 33183 (docs/CAM_CHANGES.md row 83). A Manager
// built before (cam.su.kernel, 1.2.3-beta.1 and older) refuses an APK of another package or
// signer ("the file is not an update for this app"), so no release from 33183 on can reach it by
// OTA: it is told how to move to the new app instead (row 89).
export const FIRST_RENAMED_CODE = 33183
export const isOldPackage = (code) => code > 0 && code < FIRST_RENAMED_CODE
export const isRenamed = (release) => apkCodeOf(release) >= FIRST_RENAMED_CODE

export const MOVE_NOTICE = [
  '> **App đã đổi tên thành Cam, phải cài app mới.** Từ bản này Cam là một app khác (tên gói và khoá ký mới), nên app này không tự cập nhật lên được: bấm Cập nhật sẽ báo "File tải về không phải bản cập nhật của app này". Chuyển một lần, giữ nguyên root và module:',
  '>',
  '> 1. Đăng nhập caesarvn.xyz/thanh-vien, tải APK của bản này và cài (app cũ vẫn để đó).',
  '> 2. Mở app cũ, tab **Superuser**, bật root cho **Cam**.',
  '> 3. Mở **Cam**, bấm thẻ "Hiện có phiên bản LKM tích hợp mới hơn", chọn **Cài đặt trực tiếp**, rồi khởi động lại.',
  '> 4. Gỡ app cũ. Trong Cam, đăng nhập lại ở Cài đặt → Tài khoản.',
].join('\n')

const withNotice = (body) => `${MOVE_NOTICE}\n\n${body ?? ''}`.trim()

/**
 * What a Manager from before the rename gets from /api/ota/releases: its own releases as they are,
 * and of the renamed ones only the newest stable and the newest beta (each the newest of its kind,
 * whichever the app's beta switch picks), their notes led by the move notice.
 */
export function forOldPackage(releases) {
  const newest = (beta) =>
    releases.filter((r) => isRenamed(r) && !!r.prerelease === beta).sort((a, b) => apkCodeOf(b) - apkCodeOf(a))[0]
  const keep = new Set([newest(false), newest(true)].filter(Boolean))
  return releases.filter((r) => !isRenamed(r) || keep.has(r)).map((r) => (keep.has(r) ? { ...r, body: withNotice(r.body) } : r))
}

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
      // Managers up to 1.1.3 (version code 32864) crash drawing the notes in the early access
      // popup (nested vertical scroll, fixed in c7b4a565): send them no notes, the popup works.
      const appCode = appCodeOf(ctx.req)
      const notesCrash = appCode > 0 && appCode <= 32864
      const oldPackage = isOldPackage(appCode)
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
            // early access to a renamed release: the old app must not sell it without saying how to install it
            const body = notesCrash ? '' : oldPackage && isRenamed(r) ? withNotice(r?.body) : r?.body ?? ''
            return { version: x.version, tag: x.tag, open_at: x.open_at, prerelease: x.prerelease, price: s.price_early, body, apk: apk?.name ?? null }
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
      const list = app.catalog().filter((x) => open.has(x.tag_name)).map(({ urgent, ...rest }) => rest)
      return isOldPackage(appCodeOf(ctx.req)) ? forOldPackage(list) : list
    },
    { auth: 'device' },
  )

  /* ----- /dl/ gate (Caddy forward_auth) ----- */

  r.add('GET', '/api/dl/check', (ctx) => {
    if (!cfg.dlGuard) return { ok: true }
    // The query goes before decoding: a %3F must not cut the path short, or
    // /dl/<open tag>/x%3F/../../<locked tag>/a.apk would be checked as the open tag.
    let uri
    try {
      uri = decodeURIComponent(String(ctx.req.headers['x-forwarded-uri'] || '').split('?')[0])
    } catch {
      fail(404, 'not_found')
    }
    const m = /^\/dl\/(cam-v[\w.-]+)\/[^/]+$/.exec(uri)
    if (!m || uri.split('/').some((s) => s === '.' || s === '..') || uri.includes('\\')) fail(404, 'not_found')
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

// The signed-in account: /api/me, its devices, source requests.
import { isTester, membership } from '../db.mjs'
import { DAY, fail } from '../http.mjs'
import { str } from './auth.mjs'

const GH = /^[A-Za-z0-9](?:[A-Za-z0-9-]{0,37}[A-Za-z0-9])?$/
export const deviceId = (hash) => hash.slice(0, 12)

export default function memberRoutes(app) {
  const { db, cfg, router: r, limited } = app

  r.add(
    'GET',
    '/api/me',
    (ctx) => {
      const u = ctx.user
      const st = app.accountState(u)
      const s = app.settings()
      const devices = db
        .prepare("SELECT token_hash, device_name, created_at, last_seen FROM sessions WHERE user_id = ? AND kind = 'device' ORDER BY created_at DESC")
        .all(u.id)
        .map((d) => ({ id: deviceId(d.token_hash), name: d.device_name, created_at: d.created_at, last_seen: d.last_seen }))
      const orders = db
        .prepare('SELECT code, kind, tag, amount, received, status, created_at, paid_at, expires_at FROM orders WHERE user_id = ? ORDER BY created_at DESC LIMIT 50')
        .all(u.id)
      const testerReq = db.prepare('SELECT status, created_at, review_note FROM tester_requests WHERE user_id = ? ORDER BY created_at DESC LIMIT 1').get(u.id)
      return {
        tester: { status: st.tester ? 'approved' : testerReq?.status ?? 'none', note: testerReq?.review_note ?? null },
        user: {
          email: u.email,
          name: u.name,
          role: u.role,
          password: !!u.pass_hash,
          google: !!u.google_sub,
          totp: !!u.totp_on,
          mfa: !!ctx.session.mfa,
          created_at: u.created_at,
        },
        member: st.member,
        devices,
        orders,
        releases: app.releaseStates(st),
        prices: { member: s.price_member, early: s.price_early },
        sales_open: s.sales_open,
        early_open: s.early_open,
        embargo_days: s.embargo_days,
        notice: s.notice,
        features: { google: !!cfg.googleClientId, mail: !!cfg.brevoKey, pay: !!(cfg.bankAccount && cfg.bankCode) },
      }
    },
    { auth: 'web' },
  )

  r.add(
    'DELETE',
    '/api/devices/:id',
    (ctx) => {
      const rows = db.prepare("SELECT token_hash FROM sessions WHERE user_id = ? AND kind = 'device'").all(ctx.user.id)
      const hit = rows.find((d) => deviceId(d.token_hash) === ctx.params.id)
      if (!hit) fail(404, 'no_device')
      db.prepare('DELETE FROM sessions WHERE token_hash = ?').run(hit.token_hash)
      return { ok: true }
    },
    { auth: 'web' },
  )

  /**
   * Ask to be a beta tester: from the app's beta switch (device token) or the web. Members only;
   * one open request at a time; Cam approves in /quan-tri. Approved testers get every release
   * at once, betas included (server.mjs releaseStates).
   */
  r.add(
    'POST',
    '/api/tester/request',
    (ctx) => {
      const u = ctx.user
      if (isTester(db, u.email)) return { status: 'approved' }
      if (u.role !== 'admin' && !membership(db, u.email)) fail(403, 'members_only')
      const open = db.prepare("SELECT * FROM tester_requests WHERE user_id = ? AND status = 'pending'").get(u.id)
      if (open) return { status: 'pending' }
      if (limited(`tester:${u.id}`, 5, DAY)) fail(429, 'slow_down')
      db.prepare('INSERT INTO tester_requests(user_id, device_name, note, created_at) VALUES (?, ?, ?, ?)').run(
        u.id,
        ctx.session.kind === 'device' ? ctx.session.device_name : null,
        str(ctx.body.note, 300) || null,
        Date.now(),
      )
      app.notify(`🧪 Xin làm beta tester: ${u.email}${ctx.session.device_name ? ` (${ctx.session.device_name})` : ''}
Duyệt ở /quan-tri → Beta tester`)
      return { status: 'pending' }
    },
    { auth: 'user' },
  )

  r.add(
    'POST',
    '/api/source-request',
    (ctx) => {
      const u = ctx.user
      if (u.role !== 'admin' && !membership(db, u.email)) fail(403, 'members_only')
      if (limited(`src:${u.id}`, 3, DAY)) fail(429, 'slow_down')
      const github = str(ctx.body.github, 40).replace(/^@/, '')
      const contact = str(ctx.body.contact, 120)
      const reason = str(ctx.body.reason, 1000)
      if (!GH.test(github) || !contact || !reason) fail(400, 'invalid')
      db.prepare('INSERT INTO source_requests(user_id, name, contact, github, reason, lang, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)').run(
        u.id,
        str(ctx.body.name, 80) || u.name,
        contact,
        github,
        reason,
        ['en', 'zh'].includes(ctx.body.lang) ? ctx.body.lang : 'vi',
        Date.now(),
      )
      app.notify(`📩 Yêu cầu mã nguồn\n${u.email} · ${contact}\nGitHub: ${github}\n\n${reason}`)
      return { ok: true }
    },
    { auth: 'web' },
  )
}

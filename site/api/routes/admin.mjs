// /quan-tri: accounts, grants by hand, source requests, settings, audit log, overview.
// Orders and the payments ledger are in pay.mjs. Every write is in the audit log.
import { isTester, membership, tx } from '../db.mjs'
import { DAY, fail } from '../http.mjs'
import { cleanEmail, newToken, sha256 } from '../auth.mjs'
import { readSettings, writeSettings } from '../settings.mjs'
import { str } from './auth.mjs'
import { deviceId } from './member.mjs'

const TAG = /^cam-v\d+\.\d+\.\d+(-beta\.\d+)?$/

export default function adminRoutes(app) {
  const { db, cfg, router: r } = app
  const A = { auth: 'admin' }
  const userRow = (id) => db.prepare('SELECT * FROM users WHERE id = ?').get(Number(id)) ?? fail(404, 'no_user')
  const one = (sql, ...a) => Object.values(db.prepare(sql).get(...a))[0]

  r.add(
    'GET',
    '/api/admin/overview',
    (ctx) => {
      const now = Date.now()
      return {
        users: one('SELECT count(*) FROM users'),
        members: one(
          `SELECT count(DISTINCT email) FROM grants WHERE kind = 'member' AND email != '*' AND revoked_at IS NULL AND (expires_at IS NULL OR expires_at > ?)`,
          now,
        ),
        devices: one("SELECT count(*) FROM sessions WHERE kind = 'device'"),
        pending_orders: one("SELECT count(*) FROM orders WHERE status = 'pending'"),
        paid_total: one("SELECT coalesce(sum(amount), 0) FROM orders WHERE status = 'paid'"),
        paid_30d: one("SELECT coalesce(sum(amount), 0) FROM orders WHERE status = 'paid' AND paid_at > ?", now - 30 * DAY),
        paid_today: one("SELECT coalesce(sum(amount), 0) FROM orders WHERE status = 'paid' AND paid_at > ?", now - DAY),
        unmatched_payments: one("SELECT count(*) FROM payments WHERE status IN ('unmatched', 'underpaid', 'duplicate', 'refunded')"),
        pending_requests: one("SELECT count(*) FROM source_requests WHERE status = 'pending'"),
        pending_testers: one("SELECT count(*) FROM tester_requests WHERE status = 'pending'"),
        testers: one(`SELECT count(DISTINCT email) FROM grants WHERE kind = 'tester' AND revoked_at IS NULL AND (expires_at IS NULL OR expires_at > ?)`, now),
        // As a regular member sees them; betas listed too (they open only for testers).
        releases: app.releaseStates({ member: true, early: new Set(), admin: false, all: true }),
        settings: app.settings(),
        health: {
          pay: !!(cfg.bankAccount && cfg.bankCode),
          webhook: !!cfg.sepayWebhookKey,
          reconcile: !!cfg.sepayApiToken,
          google: !!cfg.googleClientId,
          mail: !!cfg.brevoKey,
          telegram: !!(cfg.tgToken && cfg.tgChat),
          dl_guard: cfg.dlGuard,
          catalog: app.catalog().length,
          totp: !!ctx.user.totp_on,
        },
      }
    },
    A,
  )

  /* ----- settings ----- */

  r.add('GET', '/api/admin/settings', () => readSettings(db, cfg), A)

  r.add(
    'POST',
    '/api/admin/settings',
    (ctx) => {
      const changed = writeSettings(db, ctx.body ?? {}, ctx.user.email)
      app.audit(ctx.user, 'settings', null, changed)
      return readSettings(db, cfg)
    },
    A,
  )

  /* ----- accounts ----- */

  r.add(
    'GET',
    '/api/admin/users',
    (ctx) => {
      const q = `%${str(ctx.url.searchParams.get('q'), 100)}%`
      return db
        .prepare(
          `SELECT u.id, u.email, u.name, u.role, u.created_at, u.locked_at, u.totp_on, u.google_sub IS NOT NULL AS google,
            (SELECT count(*) FROM sessions s WHERE s.user_id = u.id AND s.kind = 'device') AS devices,
            EXISTS (SELECT 1 FROM grants g WHERE g.email = u.email AND g.kind = 'member' AND g.revoked_at IS NULL AND (g.expires_at IS NULL OR g.expires_at > ?)) AS member,
            (SELECT coalesce(sum(amount), 0) FROM orders o WHERE o.user_id = u.id AND o.status = 'paid') AS spent,
            (SELECT max(last_seen) FROM sessions s WHERE s.user_id = u.id) AS last_seen
           FROM users u WHERE u.email LIKE ? OR coalesce(u.name, '') LIKE ? ORDER BY u.created_at DESC LIMIT 300`,
        )
        .all(Date.now(), q, q)
    },
    A,
  )

  r.add(
    'GET',
    '/api/admin/users/:id',
    (ctx) => {
      const u = userRow(ctx.params.id)
      return {
        user: {
          id: u.id,
          email: u.email,
          name: u.name,
          role: u.role,
          created_at: u.created_at,
          locked_at: u.locked_at,
          google: !!u.google_sub,
          password: !!u.pass_hash,
          totp: !!u.totp_on,
          verified: !!u.email_verified_at,
        },
        member: membership(db, u.email),
        grants: db.prepare('SELECT * FROM grants WHERE email = ? ORDER BY created_at DESC').all(u.email),
        sessions: db
          .prepare('SELECT token_hash, kind, device_name, created_at, last_seen FROM sessions WHERE user_id = ? ORDER BY last_seen DESC')
          .all(u.id)
          .map((s) => ({ id: deviceId(s.token_hash), kind: s.kind, name: s.device_name, created_at: s.created_at, last_seen: s.last_seen })),
        orders: db.prepare('SELECT * FROM orders WHERE user_id = ? ORDER BY created_at DESC').all(u.id),
        requests: db.prepare('SELECT * FROM source_requests WHERE user_id = ? ORDER BY created_at DESC').all(u.id),
      }
    },
    A,
  )

  r.add(
    'POST',
    '/api/admin/users/:id/lock',
    (ctx) => {
      const u = userRow(ctx.params.id)
      if (u.id === ctx.user.id) fail(400, 'not_yourself')
      const lock = ctx.body.locked !== false
      tx(db, () => {
        db.prepare('UPDATE users SET locked_at = ? WHERE id = ?').run(lock ? Date.now() : null, u.id)
        if (lock) db.prepare('DELETE FROM sessions WHERE user_id = ?').run(u.id)
      })
      app.audit(ctx.user, lock ? 'lock' : 'unlock', u.email, str(ctx.body.note, 200) || null)
      return { ok: true }
    },
    A,
  )

  r.add(
    'POST',
    '/api/admin/users/:id/role',
    (ctx) => {
      const u = userRow(ctx.params.id)
      const role = ctx.body.role === 'admin' ? 'admin' : 'user'
      if (u.id === ctx.user.id && role !== 'admin') fail(400, 'not_yourself')
      db.prepare('UPDATE users SET role = ? WHERE id = ?').run(role, u.id)
      app.audit(ctx.user, 'role', u.email, role)
      return { ok: true }
    },
    A,
  )

  /** Lost phone: turn off someone's two-step sign-in so they can set it up again. */
  // A password reset link Cam sends by hand (Zalo, Messenger) when mail is late or lands in spam.
  // Same token and 1-hour lifetime as the mailed one (/api/auth/reset/confirm); only its hash is kept.
  r.add(
    'POST',
    '/api/admin/users/:id/reset-link',
    (ctx) => {
      const u = userRow(ctx.params.id)
      if (u.id === ctx.user.id) fail(400, 'not_yourself')
      if (u.locked_at) fail(409, 'locked')
      const token = newToken()
      db.prepare('INSERT INTO resets(token_hash, user_id, created_at) VALUES (?, ?, ?)').run(sha256(token), u.id, Date.now())
      app.audit(ctx.user, 'reset_link', u.email)
      return { url: `${cfg.origin}/tai-khoan?reset=${token}`, expires_at: Date.now() + 3600_000 }
    },
    A,
  )

  r.add(
    'POST',
    '/api/admin/users/:id/totp-reset',
    (ctx) => {
      const u = userRow(ctx.params.id)
      if (u.id === ctx.user.id) fail(400, 'not_yourself')
      tx(db, () => {
        db.prepare('UPDATE users SET totp_on = 0, totp_secret = NULL WHERE id = ?').run(u.id)
        db.prepare("DELETE FROM sessions WHERE user_id = ? AND kind = 'web'").run(u.id)
      })
      app.audit(ctx.user, 'totp_reset', u.email)
      return { ok: true }
    },
    A,
  )

  r.add(
    'DELETE',
    '/api/admin/users/:id/sessions/:sid',
    (ctx) => {
      const u = userRow(ctx.params.id)
      const hit = db
        .prepare('SELECT token_hash, kind, device_name FROM sessions WHERE user_id = ?')
        .all(u.id)
        .find((s) => deviceId(s.token_hash) === ctx.params.sid)
      if (!hit) fail(404, 'no_session')
      db.prepare('DELETE FROM sessions WHERE token_hash = ?').run(hit.token_hash)
      app.audit(ctx.user, 'unlink', u.email, hit.device_name || hit.kind)
      return { ok: true }
    },
    A,
  )

  /* ----- grants by hand ----- */

  r.add(
    'GET',
    '/api/admin/grants',
    (ctx) => {
      const q = `%${str(ctx.url.searchParams.get('q'), 100)}%`
      const all = ctx.url.searchParams.get('all') === '1'
      return db
        .prepare(
          `SELECT g.*, (SELECT id FROM users u WHERE u.email = g.email) AS user_id FROM grants g
           WHERE (g.email LIKE ? OR coalesce(g.note, '') LIKE ? OR coalesce(g.tag, '') LIKE ?) ${all ? '' : 'AND g.revoked_at IS NULL'}
           ORDER BY g.created_at DESC LIMIT 300`,
        )
        .all(q, q, q)
    },
    A,
  )

  r.add(
    'POST',
    '/api/admin/grants',
    (ctx) => {
      const kind = ['early', 'tester'].includes(ctx.body.kind) ? ctx.body.kind : 'member'
      const email = ctx.body.email === '*' && kind === 'early' ? '*' : cleanEmail(ctx.body.email)
      const tag = kind === 'early' ? str(ctx.body.tag, 40) : null
      if (kind === 'early' && !TAG.test(tag)) fail(400, 'bad_tag')
      const expires = ctx.body.expires_at ? Number(new Date(ctx.body.expires_at)) : null
      if (expires != null && !(expires > Date.now())) fail(400, 'bad_expiry')
      const note = str(ctx.body.note, 200) || null
      const id = db
        .prepare('INSERT INTO grants(email, kind, tag, note, expires_at, by, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)')
        .run(email, kind, tag, note, expires, ctx.user.email, Date.now()).lastInsertRowid
      app.audit(ctx.user, `grant_${kind}`, email, { tag, note, expires_at: expires })
      return { ok: true, id }
    },
    A,
  )

  r.add(
    'POST',
    '/api/admin/grants/:id/revoke',
    (ctx) => {
      const g = db.prepare('SELECT * FROM grants WHERE id = ?').get(Number(ctx.params.id)) ?? fail(404, 'no_grant')
      if (g.revoked_at) return { ok: true }
      db.prepare('UPDATE grants SET revoked_at = ? WHERE id = ?').run(Date.now(), g.id)
      app.audit(ctx.user, `revoke_${g.kind}`, g.email, { tag: g.tag, note: str(ctx.body.note, 200) || null })
      return { ok: true }
    },
    A,
  )

  /* ----- source requests ----- */

  r.add(
    'GET',
    '/api/admin/requests',
    () => db.prepare('SELECT s.*, u.email FROM source_requests s LEFT JOIN users u ON u.id = s.user_id ORDER BY s.created_at DESC LIMIT 300').all(),
    A,
  )

  r.add(
    'POST',
    '/api/admin/requests/:id',
    (ctx) => {
      const s = db.prepare('SELECT * FROM source_requests WHERE id = ?').get(Number(ctx.params.id)) ?? fail(404, 'no_request')
      const status = ['approved', 'rejected', 'pending'].includes(ctx.body.status) ? ctx.body.status : fail(400, 'bad_status')
      db.prepare('UPDATE source_requests SET status = ?, note = ? WHERE id = ?').run(status, str(ctx.body.note, 300) || null, s.id)
      app.audit(ctx.user, `request_${status}`, s.github, ctx.body.note)
      return { ok: true }
    },
    A,
  )

  /* ----- beta testers ----- */

  r.add(
    'GET',
    '/api/admin/testers',
    () => {
      const now = Date.now()
      return db
        .prepare(
          `SELECT t.*, u.email,
            EXISTS (SELECT 1 FROM grants g WHERE g.email = u.email AND g.kind = 'member' AND g.revoked_at IS NULL AND (g.expires_at IS NULL OR g.expires_at > ?)) AS member,
            EXISTS (SELECT 1 FROM grants g WHERE g.email = u.email AND g.kind = 'tester' AND g.revoked_at IS NULL AND (g.expires_at IS NULL OR g.expires_at > ?)) AS tester
           FROM tester_requests t JOIN users u ON u.id = t.user_id ORDER BY (t.status = 'pending') DESC, t.created_at DESC LIMIT 300`,
        )
        .all(now, now)
    },
    A,
  )

  r.add(
    'POST',
    '/api/admin/testers/:id',
    (ctx) => {
      const t = db.prepare('SELECT t.*, u.email FROM tester_requests t JOIN users u ON u.id = t.user_id WHERE t.id = ?').get(Number(ctx.params.id)) ?? fail(404, 'no_request')
      const status = ['approved', 'rejected'].includes(ctx.body.status) ? ctx.body.status : fail(400, 'bad_status')
      const note = str(ctx.body.note, 200) || null
      tx(db, () => {
        db.prepare('UPDATE tester_requests SET status = ?, reviewed_by = ?, reviewed_at = ?, review_note = ? WHERE id = ?').run(status, ctx.user.email, Date.now(), note, t.id)
        // made for this account, so it counts before its email is proven (db.mjs grantScope)
        if (status === 'approved' && !isTester(db, t.email, Date.now(), t.user_id)) {
          db.prepare("INSERT INTO grants(email, kind, note, by, user_id, created_at) VALUES (?, 'tester', ?, ?, ?, ?)").run(t.email, note ?? 'duyệt yêu cầu beta', ctx.user.email, t.user_id, Date.now())
        }
      })
      app.audit(ctx.user, `tester_${status}`, t.email, note)
      return { ok: true }
    },
    A,
  )

  r.add('GET', '/api/admin/audit', () => db.prepare('SELECT * FROM audit ORDER BY at DESC LIMIT 500').all(), A)
}

// Problem reports: the Manager sends what Cam needs to help a customer without adb (versions,
// modules, boot guard, the root hiding check, the end of the logs), Cam reads them in /quan-tri.
//   app    POST /api/reports {meta, note, sections}   -> { code }   (device token)
//   admin  GET  /api/admin/reports                     list, newest unread first
//          GET  /api/admin/reports/:code               one report with its sections
//          POST /api/admin/reports/:code {status, note}
//          DELETE /api/admin/reports/:code
// The customer tells Cam the code (Zalo, Messenger); Telegram gets a line too. Kept gzipped,
// 180 days, the newest 20 per account. docs/CAM_CHANGES.md row 98.
import { randomInt } from 'node:crypto'
import { gunzipSync, gzipSync } from 'node:zlib'
import { DAY, fail } from '../http.mjs'
import { str } from './auth.mjs'
import { appCodeOf } from './device.mjs'

const CODE_ALPHA = 'ABCDEFGHJKMNPQRSTUVWXYZ23456789'
export const REPORT_BODY_LIMIT = 2 * 1024 * 1024
const SECTION_MAX = 512 * 1024
const TOTAL_MAX = 1536 * 1024
const SECTIONS_MAX = 24
const SECTION_NAME = /^[a-z][a-z0-9_]{0,31}$/
const META_KEYS = ['model', 'android', 'cam', 'kernel', 'lkm', 'camd', 'rules']
const KEEP_DAYS = 180
const KEEP_PER_USER = 20
const STATUS = ['new', 'seen', 'done']

/** Free text that keeps its line breaks and tabs (logs, the customer's note); other controls go. */
const text = (v, max) => (typeof v === 'string' ? v.replace(/[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f]/g, ' ').slice(0, max) : '')

/**
 * The report as kept, or an HttpError: sections are named texts (at most 24, each at most 512 KiB,
 * 1.5 MiB in all), meta a few short strings for the list, note what the customer wrote.
 */
export function cleanReport(body) {
  const raw = body?.sections
  if (!raw || typeof raw !== 'object' || Array.isArray(raw)) fail(400, 'no_sections')
  const names = Object.keys(raw)
  if (!names.length || names.length > SECTIONS_MAX) fail(400, 'bad_sections')
  const sections = {}
  let total = 0
  for (const name of names) {
    if (!SECTION_NAME.test(name) || typeof raw[name] !== 'string') fail(400, 'bad_sections')
    if (raw[name].length > SECTION_MAX) fail(413, 'too_large')
    sections[name] = text(raw[name], SECTION_MAX)
    total += sections[name].length
  }
  if (total > TOTAL_MAX) fail(413, 'too_large')
  const meta = {}
  for (const k of META_KEYS) {
    const v = str(body?.meta?.[k], 120)
    if (v) meta[k] = v
  }
  return { sections, meta, note: text(body?.note, 2000).trim() }
}

export default function reportRoutes(app) {
  const { db, cfg, router: r, limited } = app
  const A = { auth: 'admin' }

  const row = (code) =>
    db.prepare('SELECT r.*, u.email FROM reports r JOIN users u ON u.id = r.user_id WHERE r.code = ?').get(str(code, 12).toUpperCase()) ?? fail(404, 'no_report')

  const listed = (x) => ({
    code: x.code,
    email: x.email,
    user_id: x.user_id,
    device: x.device_name,
    app_code: x.app_code,
    meta: JSON.parse(x.meta || '{}'),
    note: x.note,
    size: x.size,
    created_at: x.created_at,
    status: x.status,
    admin_note: x.admin_note,
  })

  r.add(
    'POST',
    '/api/reports',
    (ctx) => {
      const { sections, meta, note } = cleanReport(ctx.body)
      // only reports that would be kept count (checking is cheap; readJson already capped the size)
      if (limited(`report:${ctx.user.id}`, 6, 3600_000)) fail(429, 'slow_down')
      const json = Buffer.from(JSON.stringify(sections))
      let code
      do {
        code = 'R' + Array.from({ length: 6 }, () => CODE_ALPHA[randomInt(CODE_ALPHA.length)]).join('')
      } while (db.prepare('SELECT 1 FROM reports WHERE code = ?').get(code))
      const now = Date.now()
      db.prepare(
        'INSERT INTO reports(code, user_id, device_name, app_code, meta, note, body, size, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)',
      ).run(code, ctx.user.id, ctx.session.device_name ?? null, appCodeOf(ctx.req), JSON.stringify(meta), note || null, gzipSync(json), json.length, now)
      db.prepare('DELETE FROM reports WHERE created_at < ?').run(now - KEEP_DAYS * DAY)
      db.prepare('DELETE FROM reports WHERE user_id = ? AND id NOT IN (SELECT id FROM reports WHERE user_id = ? ORDER BY created_at DESC, id DESC LIMIT ?)').run(
        ctx.user.id,
        ctx.user.id,
        KEEP_PER_USER,
      )
      const where = [ctx.session.device_name, meta.cam && `Cam ${meta.cam}`].filter(Boolean).join(' · ')
      app.notify(`🐞 Báo cáo ${code}: ${ctx.user.email}${where ? ` (${where})` : ''}${note ? `\n${note.slice(0, 500)}` : ''}\nXem ở ${cfg.origin}/quan-tri → Báo cáo`)
      return { code }
    },
    { auth: 'device', limit: REPORT_BODY_LIMIT },
  )

  r.add(
    'GET',
    '/api/admin/reports',
    (ctx) => {
      const q = `%${str(ctx.url.searchParams.get('q'), 100)}%`
      return db
        .prepare(
          `SELECT r.code, r.user_id, r.device_name, r.app_code, r.meta, r.note, r.size, r.created_at, r.status, r.admin_note, u.email
           FROM reports r JOIN users u ON u.id = r.user_id
           WHERE u.email LIKE ? OR r.code LIKE ? OR coalesce(r.note, '') LIKE ? OR coalesce(r.device_name, '') LIKE ?
           ORDER BY (r.status = 'new') DESC, r.created_at DESC LIMIT 300`,
        )
        .all(q, q, q, q)
        .map(listed)
    },
    A,
  )

  r.add(
    'GET',
    '/api/admin/reports/:code',
    (ctx) => {
      const x = row(ctx.params.code)
      return { ...listed(x), sections: JSON.parse(gunzipSync(x.body).toString('utf8')) }
    },
    A,
  )

  r.add(
    'POST',
    '/api/admin/reports/:code',
    (ctx) => {
      const x = row(ctx.params.code)
      const status = STATUS.includes(ctx.body.status) ? ctx.body.status : x.status
      const note = ctx.body.note === undefined ? x.admin_note : str(ctx.body.note, 300) || null
      db.prepare('UPDATE reports SET status = ?, admin_note = ? WHERE id = ?').run(status, note, x.id)
      app.audit(ctx.user, `report_${status}`, x.code, note)
      return { ok: true }
    },
    A,
  )

  r.add(
    'DELETE',
    '/api/admin/reports/:code',
    (ctx) => {
      const x = row(ctx.params.code)
      db.prepare('DELETE FROM reports WHERE id = ?').run(x.id)
      app.audit(ctx.user, 'report_delete', x.code, x.email)
      return { ok: true }
    },
    A,
  )
}

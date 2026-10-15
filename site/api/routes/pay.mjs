// Payments: orders, VietQR (SePay), the SePay webhook, a reconcile job, refunds.
//
// Money rules, all decided here:
// - Prices come from settings at order time and are stored on the order; the browser sends only
//   what is bought (membership, or early access to one release).
// - Every incoming transfer is written to `payments` once (provider + transaction id), matched to
//   an order by its code ("CAMXXXXXX") in the transfer content, and never deleted.
// - An order activates when the money received reaches its amount. Less: it waits (the rest can
//   follow). More, or paying an order twice: noted for a refund. Late payments still count.
// - Transfers that match no order are kept as "unmatched" for Cam to assign or ignore.
// - The webhook and the reconcile job (SePay API) feed the same function, so a missed webhook is
//   picked up a few minutes later and a repeated one does nothing.
import { randomInt, timingSafeEqual } from 'node:crypto'
import { tx } from '../db.mjs'
import { fail, readRaw } from '../http.mjs'
import { sendMail } from '../auth.mjs'
import { str } from './auth.mjs'

const ALPHA = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789'
const CODE_IN_TEXT = /CAM[A-HJ-NP-Z2-9]{6}/g
export const vnd = (n) => `${Number(n).toLocaleString('vi-VN')}đ`
const KIND = { member: 'Thành viên', early: 'Truy cập sớm' }

export default function payRoutes(app) {
  const { db, cfg, router: r, limited } = app
  const payOn = () => !!(cfg.bankAccount && cfg.bankCode)

  function newCode() {
    for (;;) {
      let c = 'CAM'
      for (let i = 0; i < 6; i++) c += ALPHA[randomInt(ALPHA.length)]
      if (!db.prepare('SELECT 1 FROM orders WHERE code = ?').get(c)) return c
    }
  }

  function qr(o) {
    const q = new URLSearchParams({ acc: cfg.bankAccount, bank: cfg.bankCode, amount: String(Math.max(0, o.amount - o.received)), des: o.code, template: 'compact' })
    return { image: `https://qr.sepay.vn/img?${q}`, bank: cfg.bankCode, account: cfg.bankAccount, holder: cfg.bankHolder, content: o.code }
  }

  function publicOrder(o) {
    return {
      code: o.code,
      kind: o.kind,
      tag: o.tag,
      version: o.tag ? o.tag.replace(/^cam-v/, '') : null,
      amount: o.amount,
      received: o.received,
      status: o.status,
      created_at: o.created_at,
      expires_at: o.expires_at,
      paid_at: o.paid_at,
      pay: o.status === 'pending' || o.status === 'expired' ? qr(o) : null,
    }
  }

  /** Orders past their time stop being offered, but a late transfer still pays them. */
  function expireOld(now = Date.now()) {
    db.prepare("UPDATE orders SET status = 'expired' WHERE status = 'pending' AND expires_at IS NOT NULL AND expires_at < ?").run(now)
  }

  /** Grants what the order bought. Caller holds the transaction and checked the status. */
  function markPaid(o, { by, note = null }) {
    const now = Date.now()
    db.prepare("UPDATE orders SET status = 'paid', paid_at = ?, note = coalesce(?, note) WHERE id = ?").run(now, note, o.id)
    const email = db.prepare('SELECT email FROM users WHERE id = ?').get(o.user_id).email
    db.prepare('INSERT INTO grants(email, kind, tag, note, by, order_code, user_id, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)').run(
      email,
      o.kind,
      o.kind === 'early' ? o.tag : null,
      `đơn ${o.code}`,
      by,
      o.code,
      o.user_id,
      now,
    )
    return email
  }
  app.markPaid = markPaid

  /** After the transaction: tell Cam, send the buyer a receipt. */
  function announcePaid(o, email) {
    const what = o.kind === 'early' ? `${KIND.early} ${o.tag.replace(/^cam-v/, '')}` : KIND.member
    app.notify(`💰 Đã nhận ${vnd(o.amount)} · ${o.code}\n${email} · ${what}`)
    if (cfg.brevoKey) {
      sendMail(
        cfg,
        email,
        `Đã nhận thanh toán ${o.code} · Cam Kernel SU`,
        `Cảm ơn bạn. Đơn ${o.code} (${what}, ${vnd(o.amount)}) đã được thanh toán và kích hoạt.\n\nTài khoản: ${cfg.origin}/thanh-vien`,
      ).catch(() => {})
    }
  }

  /** Adds money to an order inside the caller's transaction; returns what happened. */
  function credit(o, amount) {
    const received = o.received + amount
    db.prepare('UPDATE orders SET received = ? WHERE id = ?').run(received, o.id)
    if (o.status === 'paid') return { result: 'duplicate', note: `đơn đã trả trước đó, nhận thêm ${vnd(amount)}: cần hoàn` }
    if (o.status === 'refunded') return { result: 'refunded', note: `đơn đã hoàn, nhận thêm ${vnd(amount)}: cần hoàn` }
    if (received < o.amount) {
      db.prepare('UPDATE orders SET note = ? WHERE id = ?').run(`thiếu ${vnd(o.amount - received)}`, o.id)
      return { result: 'underpaid', note: `thiếu ${vnd(o.amount - received)}` }
    }
    const extra = received - o.amount
    const email = markPaid(o, { by: 'sepay', note: extra ? `thừa ${vnd(extra)}: cần hoàn` : null })
    return { result: 'paid', email, note: extra ? `thừa ${vnd(extra)}` : null }
  }

  /**
   * One incoming transfer from the webhook or the reconcile job. Idempotent on (provider, id).
   * t: { id, amount, content, account, reference, paid_at, raw }
   */
  function applyTransfer(t) {
    const now = Date.now()
    const text = String(t.content ?? '').toUpperCase().replace(/[^A-Z0-9]/g, '')
    const out = tx(db, () => {
      const ins = db
        .prepare(
          "INSERT INTO payments(provider, ext_id, amount, content, account, reference, paid_at, status, created_at, raw) VALUES ('sepay', ?, ?, ?, ?, ?, ?, 'unmatched', ?, ?) ON CONFLICT(provider, ext_id) DO NOTHING",
        )
        .run(String(t.id), t.amount, str(t.content, 500), t.account ?? null, t.reference ?? null, t.paid_at ?? null, now, JSON.stringify(t.raw ?? null).slice(0, 4000))
      if (!ins.changes) return { result: 'seen' }
      const pid = ins.lastInsertRowid
      if (cfg.bankAccount && t.account && String(t.account) !== cfg.bankAccount) {
        db.prepare("UPDATE payments SET status = 'ignored', note = 'tài khoản khác' WHERE id = ?").run(pid)
        return { result: 'other_account' }
      }
      const codes = [...new Set(text.match(CODE_IN_TEXT) ?? [])]
      const o = codes.map((c) => db.prepare('SELECT * FROM orders WHERE code = ?').get(c)).find(Boolean)
      if (!o) return { result: 'unmatched', pid }
      const res = credit(o, t.amount)
      db.prepare('UPDATE payments SET order_code = ?, status = ?, note = ? WHERE id = ?').run(
        o.code,
        res.result === 'paid' ? 'matched' : res.result,
        res.note,
        pid,
      )
      return { ...res, order: o, pid }
    })
    if (out.result === 'paid') announcePaid(out.order, out.email)
    else if (out.result === 'unmatched') app.notify(`❓ Tiền vào không khớp đơn: ${vnd(t.amount)}\nNội dung: ${t.content}\nGán tay ở /quan-tri → Giao dịch`)
    else if (['underpaid', 'duplicate', 'refunded'].includes(out.result)) app.notify(`⚠️ ${out.order.code}: ${out.note}`)
    return out
  }
  app.applyTransfer = applyTransfer

  /* ----- buying ----- */

  // Orders come from the website or from the Manager (device token): early access is bought in
  // the app, which shows the same VietQR and polls GET /api/orders/:code until it is paid.
  r.add(
    'POST',
    '/api/orders',
    (ctx) => {
      if (!payOn()) fail(503, 'pay_off')
      const u = ctx.user
      if (limited(`order:${u.id}`, 20, 3600_000)) fail(429, 'slow_down')
      const s = app.settings()
      if (!s.sales_open) fail(403, 'sales_closed')
      const kind = ctx.body.kind === 'early' ? 'early' : ctx.body.kind === 'member' ? 'member' : fail(400, 'bad_kind')
      const st = app.accountState(u)
      let tag = null
      let amount
      if (kind === 'member') {
        if (st.member) fail(409, 'already_member')
        amount = s.price_member
      } else {
        if (!s.early_open) fail(403, 'sales_closed')
        if (!st.member) fail(403, 'members_only')
        tag = str(ctx.body.tag, 40)
        const rel = app.releaseStates(st).find((x) => x.tag === tag)
        if (!rel) fail(404, 'no_release')
        if (rel.open) fail(409, 'already_open')
        amount = s.price_early
      }
      expireOld()
      // Still waiting for an earlier identical order: show that one again.
      const again = db
        .prepare("SELECT * FROM orders WHERE user_id = ? AND kind = ? AND coalesce(tag, '') = ? AND status = 'pending' ORDER BY created_at DESC LIMIT 1")
        .get(u.id, kind, tag ?? '')
      if (again) return publicOrder(again)
      const now = Date.now()
      const id = db
        .prepare('INSERT INTO orders(code, user_id, kind, tag, amount, status, created_at, expires_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)')
        .run(newCode(), u.id, kind, tag, amount, 'pending', now, now + s.order_hours * 3600_000).lastInsertRowid
      return publicOrder(db.prepare('SELECT * FROM orders WHERE id = ?').get(id))
    },
    { auth: 'user' },
  )

  r.add(
    'GET',
    '/api/orders/:code',
    (ctx) => {
      expireOld()
      const o = db.prepare('SELECT * FROM orders WHERE code = ?').get(ctx.params.code)
      if (!o || (o.user_id !== ctx.user.id && ctx.user.role !== 'admin')) fail(404, 'no_order')
      return publicOrder(o)
    },
    { auth: 'user' },
  )

  r.add(
    'POST',
    '/api/orders/:code/cancel',
    (ctx) => {
      const o = db.prepare('SELECT * FROM orders WHERE code = ? AND user_id = ?').get(ctx.params.code, ctx.user.id)
      if (!o) fail(404, 'no_order')
      if (o.status !== 'pending' || o.received > 0) fail(409, 'cannot_cancel')
      db.prepare("UPDATE orders SET status = 'expired', note = 'người mua huỷ' WHERE id = ?").run(o.id)
      return { ok: true }
    },
    { auth: 'user' },
  )

  /* ----- SePay webhook ----- */

  r.add(
    'POST',
    '/api/pay/sepay',
    async (ctx) => {
      if (!cfg.sepayWebhookKey) fail(503, 'pay_off')
      const want = Buffer.from(`Apikey ${cfg.sepayWebhookKey}`)
      const got = Buffer.from(String(ctx.req.headers.authorization || ''))
      if (got.length !== want.length || !timingSafeEqual(got, want)) fail(401, 'bad_key')
      const raw = await readRaw(ctx.req)
      let b
      try {
        b = JSON.parse(raw.toString('utf8'))
      } catch {
        fail(400, 'bad_json')
      }
      // Money leaving the account is not ours to handle; SePay still gets its success answer.
      if (b.transferType === 'in' && Number(b.transferAmount) > 0 && b.id != null) {
        applyTransfer({
          id: b.id,
          amount: Math.round(Number(b.transferAmount)),
          content: [b.content, b.code, b.description].filter(Boolean).join(' '),
          account: b.accountNumber,
          reference: b.referenceCode,
          paid_at: b.transactionDate,
          raw: b,
        })
      }
      ctx.status = 200
      return { success: true }
    },
    { raw: true },
  )

  /* ----- reconcile: SePay API, in case a webhook never arrived ----- */

  async function reconcile() {
    expireOld()
    if (!cfg.sepayApiToken || !cfg.bankAccount) return { skipped: true }
    const q = new URLSearchParams({ account_number: cfg.bankAccount, limit: '100' })
    const res = await fetch(`https://my.sepay.vn/userapi/transactions/list?${q}`, {
      headers: { Authorization: `Bearer ${cfg.sepayApiToken}`, 'Content-Type': 'application/json' },
      signal: AbortSignal.timeout(15000),
    })
    if (!res.ok) throw new Error(`SePay API HTTP ${res.status}`)
    const data = await res.json()
    let fresh = 0
    for (const t of data.transactions ?? []) {
      const amount = Math.round(Number(t.amount_in))
      if (!(amount > 0)) continue
      const out = applyTransfer({
        id: t.id,
        amount,
        content: [t.transaction_content, t.code].filter(Boolean).join(' '),
        account: t.account_number,
        reference: t.reference_number,
        paid_at: t.transaction_date,
        raw: t,
      })
      if (out.result !== 'seen') fresh++
    }
    return { fresh }
  }
  app.reconcile = reconcile

  app.startJobs = () => {
    const tick = () => reconcile().catch((e) => console.error('reconcile:', e.message))
    app.timers.push(setInterval(tick, cfg.reconcileMs))
    setTimeout(tick, 5000)
  }

  /* ----- admin: orders and the payments ledger ----- */

  const A = { auth: 'admin' }
  const order = (code) => db.prepare('SELECT * FROM orders WHERE code = ?').get(code) ?? fail(404, 'no_order')

  r.add(
    'GET',
    '/api/admin/orders',
    (ctx) => {
      expireOld()
      const status = ctx.url.searchParams.get('status')
      const where = ['pending', 'paid', 'expired', 'refunded'].includes(status) ? 'WHERE o.status = ?' : ''
      return db
        .prepare(`SELECT o.*, u.email FROM orders o JOIN users u ON u.id = o.user_id ${where} ORDER BY o.created_at DESC LIMIT 300`)
        .all(...(where ? [status] : []))
    },
    A,
  )

  r.add(
    'POST',
    '/api/admin/orders/:code/paid',
    (ctx) => {
      const o = order(ctx.params.code)
      if (o.status === 'paid') return { ok: true }
      if (o.status === 'refunded') fail(409, 'refunded')
      const note = str(ctx.body.note, 200) || 'xác nhận tay'
      const email = tx(db, () => markPaid(o, { by: ctx.user.email, note }))
      app.audit(ctx.user, 'order_paid', email, { code: o.code, amount: o.amount, note })
      announcePaid(o, email)
      return { ok: true }
    },
    A,
  )

  r.add(
    'POST',
    '/api/admin/orders/:code/refund',
    (ctx) => {
      const o = order(ctx.params.code)
      if (o.status === 'refunded') return { ok: true }
      const note = str(ctx.body.note, 200) || 'hoàn tiền'
      tx(db, () => {
        db.prepare("UPDATE orders SET status = 'refunded', refunded_at = ?, note = ? WHERE id = ?").run(Date.now(), note, o.id)
        // What the order granted goes away with the money.
        db.prepare('UPDATE grants SET revoked_at = ? WHERE order_code = ? AND revoked_at IS NULL').run(Date.now(), o.code)
      })
      app.audit(ctx.user, 'order_refund', o.code, { amount: o.amount, received: o.received, note })
      return { ok: true }
    },
    A,
  )

  r.add(
    'POST',
    '/api/admin/orders/:code/note',
    (ctx) => {
      const o = order(ctx.params.code)
      db.prepare('UPDATE orders SET note = ? WHERE id = ?').run(str(ctx.body.note, 300) || null, o.id)
      app.audit(ctx.user, 'order_note', o.code, ctx.body.note)
      return { ok: true }
    },
    A,
  )

  r.add(
    'GET',
    '/api/admin/payments',
    (ctx) => {
      const status = ctx.url.searchParams.get('status')
      const where = ['unmatched', 'matched', 'underpaid', 'duplicate', 'refunded', 'ignored'].includes(status) ? 'WHERE p.status = ?' : ''
      return db
        .prepare(
          `SELECT p.id, p.provider, p.ext_id, p.amount, p.content, p.account, p.reference, p.paid_at, p.order_code, p.status, p.note, p.created_at, u.email
           FROM payments p LEFT JOIN orders o ON o.code = p.order_code LEFT JOIN users u ON u.id = o.user_id ${where}
           ORDER BY p.created_at DESC LIMIT 300`,
        )
        .all(...(where ? [status] : []))
    },
    A,
  )

  /** An unmatched transfer (buyer typed the code wrong) is applied to the order Cam picks. */
  r.add(
    'POST',
    '/api/admin/payments/:id/assign',
    (ctx) => {
      const p = db.prepare('SELECT * FROM payments WHERE id = ?').get(Number(ctx.params.id)) ?? fail(404, 'no_payment')
      if (!['unmatched', 'ignored'].includes(p.status)) fail(409, 'already_assigned')
      const o = order(str(ctx.body.code, 20).toUpperCase())
      const res = tx(db, () => {
        const out = credit(o, p.amount)
        db.prepare('UPDATE payments SET order_code = ?, status = ?, note = ? WHERE id = ?').run(
          o.code,
          out.result === 'paid' ? 'matched' : out.result,
          `gán tay${out.note ? `: ${out.note}` : ''}`,
          p.id,
        )
        return out
      })
      app.audit(ctx.user, 'payment_assign', o.code, { payment: p.id, amount: p.amount, result: res.result })
      if (res.result === 'paid') announcePaid(o, res.email)
      return { ok: true, result: res.result }
    },
    A,
  )

  r.add(
    'POST',
    '/api/admin/payments/:id/ignore',
    (ctx) => {
      const p = db.prepare('SELECT * FROM payments WHERE id = ?').get(Number(ctx.params.id)) ?? fail(404, 'no_payment')
      if (p.status !== 'unmatched') fail(409, 'already_assigned')
      const note = str(ctx.body.note, 200) || 'bỏ qua'
      db.prepare("UPDATE payments SET status = 'ignored', note = ? WHERE id = ?").run(note, p.id)
      app.audit(ctx.user, 'payment_ignore', String(p.id), { amount: p.amount, note })
      return { ok: true }
    },
    A,
  )

  r.add(
    'POST',
    '/api/admin/reconcile',
    async (ctx) => {
      const out = await reconcile()
      app.audit(ctx.user, 'reconcile', null, out)
      return out
    },
    A,
  )

}

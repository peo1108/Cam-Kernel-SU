// Payments, devices, OTA, /dl gate, 2FA, settings.  node --test site/api/test
import { writeSettings } from '../settings.mjs'
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { createServer } from 'node:http'
import { mkdtempSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { config, createApp } from '../server.mjs'
import { sha256 } from '../auth.mjs'
import { code as totpCode } from '../totp.mjs'

const DAY = 24 * 3600 * 1000
const KEY = 'test-webhook-key-123'

async function start(t, env = {}) {
  const dir = mkdtempSync(join(tmpdir(), 'cam-biz-'))
  const catalog = join(dir, 'releases.json')
  const now = Date.now()
  const rel = (tag, daysAgo, urgent = false) => ({
    tag_name: tag,
    prerelease: false,
    published_at: new Date(now - daysAgo * DAY).toISOString(),
    urgent,
    body: '- notes',
    assets: [{ name: `SU_Kernel_${tag.slice(5)}_1-release.apk`, size: 1, digest: 'sha256:00', browser_download_url: `https://x/dl/${tag}/a.apk` }],
  })
  const beta = { ...rel('cam-v1.3.0-beta.1', 1), prerelease: true }
  writeFileSync(catalog, JSON.stringify([beta, rel('cam-v1.2.0', 2), rel('cam-v1.1.2', 3, true), rel('cam-v1.1.1', 30)]))
  const app = createApp(
    config({
      DATA_DIR: dir,
      ORIGIN: 'http://test.local',
      COOKIE_SECURE: '0',
      CATALOG: catalog,
      BANK_CODE: 'MBBank',
      BANK_ACCOUNT: '0123456789',
      BANK_HOLDER: 'CAM',
      SEPAY_WEBHOOK_KEY: KEY,
      DL_GUARD: '1',
      ...env,
    }),
  )
  const server = createServer(app.handle)
  await new Promise((r) => server.listen(0, '127.0.0.1', r))
  t.after(() => {
    server.closeAllConnections()
    server.close()
  })
  return { app, base: `http://127.0.0.1:${server.address().port}` }
}

function client(base) {
  let jar = ''
  const call = async (method, path, body, headers = {}) => {
    const res = await fetch(base + path, {
      method,
      redirect: 'manual',
      headers: { ...(body ? { 'Content-Type': 'application/json' } : {}), ...(jar ? { Cookie: jar } : {}), Origin: 'http://test.local', ...headers },
      body: body ? JSON.stringify(body) : undefined,
    })
    for (const c of res.headers.getSetCookie?.() ?? []) {
      const [kv] = c.split(';')
      if (kv.startsWith('cam_s=')) jar = kv.endsWith('=') ? '' : kv
    }
    const text = await res.text()
    return { status: res.status, body: text ? JSON.parse(text) : null, headers: res.headers }
  }
  return call
}

const webhook = (base, body, key = KEY) =>
  fetch(base + '/api/pay/sepay', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: `Apikey ${key}` },
    body: JSON.stringify({ gateway: 'MBBank', accountNumber: '0123456789', transferType: 'in', transactionDate: '2026-10-12 10:00:00', ...body }),
  })

async function adminClient(app, base) {
  const c = client(base)
  await c('POST', '/api/auth/register', { email: 'cam@x.io', password: 'password1' })
  app.db.prepare("UPDATE users SET role = 'admin' WHERE email = 'cam@x.io'").run()
  return c
}


/** The user opens the mailed verification link (mail is off in tests: the token is put in directly). */
async function prove(app, base, email) {
  const { id } = app.db.prepare('SELECT id FROM users WHERE email = ?').get(email)
  const token = `verify-${id}-${Math.random()}`
  app.db.prepare('INSERT INTO verifications(token_hash, user_id, created_at) VALUES (?, ?, ?)').run(sha256(token), id, Date.now())
  const res = await fetch(`${base}/api/auth/verify?token=${encodeURIComponent(token)}`, { redirect: 'manual' })
  if (res.headers.get('location') !== '/thanh-vien?verified=1') throw new Error(`verify failed: ${res.status} ${res.headers.get('location')}`)
}

test('buy membership: order, webhook pays it once, duplicate webhook does nothing', async (t) => {
  const { app, base } = await start(t)
  const c = client(base)
  await c('POST', '/api/auth/register', { email: 'buyer@x.io', password: 'password1' })
  const o = (await c('POST', '/api/orders', { kind: 'member', amount: 1 })).body
  assert.equal(o.amount, 200000, 'price comes from the server, not the request')
  assert.match(o.code, /^CAM[A-HJ-NP-Z2-9]{6}$/)
  assert.match(o.pay.image, /qr\.sepay\.vn.*amount=200000.*des=CAM/)
  // Asking again gives the same pending order.
  assert.equal((await c('POST', '/api/orders', { kind: 'member' })).body.code, o.code)

  assert.equal((await webhook(base, { id: 1, transferAmount: 200000, content: 'x' }, 'wrong')).status, 401)
  const res = await webhook(base, { id: 1001, transferAmount: 200000, content: `MBVCB.123 ${o.code.toLowerCase()} chuyen tien` })
  assert.deepEqual(await res.json(), { success: true })
  assert.equal((await c('GET', `/api/orders/${o.code}`)).body.status, 'paid')
  assert.ok((await c('GET', '/api/me')).body.member)

  await webhook(base, { id: 1001, transferAmount: 200000, content: o.code })
  assert.equal(app.db.prepare("SELECT count(*) n FROM grants WHERE kind = 'member'").get().n, 1)
  assert.equal(app.db.prepare('SELECT received FROM orders WHERE code = ?').get(o.code).received, 200000)
  assert.equal((await c('POST', '/api/orders', { kind: 'member' })).status, 409, 'already a member')
})

test('underpaid then topped up; overpaid noted; late payment on an expired order still counts', async (t) => {
  const { app, base } = await start(t)
  const c = client(base)
  await c('POST', '/api/auth/register', { email: 'b@x.io', password: 'password1' })
  const o = (await c('POST', '/api/orders', { kind: 'member' })).body
  await webhook(base, { id: 1, transferAmount: 150000, content: o.code })
  assert.equal((await c('GET', `/api/orders/${o.code}`)).body.status, 'pending')
  assert.equal(app.db.prepare('SELECT status FROM payments WHERE ext_id = ?').get('1').status, 'underpaid')
  await webhook(base, { id: 2, transferAmount: 60000, content: o.code })
  assert.equal((await c('GET', `/api/orders/${o.code}`)).body.status, 'paid')
  assert.match(app.db.prepare('SELECT note FROM orders WHERE code = ?').get(o.code).note, /thừa/)

  const c2 = client(base)
  await c2('POST', '/api/auth/register', { email: 'late@x.io', password: 'password1' })
  const o2 = (await c2('POST', '/api/orders', { kind: 'member' })).body
  app.db.prepare('UPDATE orders SET expires_at = ? WHERE code = ?').run(Date.now() - 1000, o2.code)
  assert.equal((await c2('GET', `/api/orders/${o2.code}`)).body.status, 'expired')
  await webhook(base, { id: 3, transferAmount: 200000, content: o2.code })
  assert.equal((await c2('GET', `/api/orders/${o2.code}`)).body.status, 'paid')
})

test('unmatched transfer is assigned by hand; refund revokes what was bought', async (t) => {
  const { app, base } = await start(t)
  const admin = await adminClient(app, base)
  const c = client(base)
  await c('POST', '/api/auth/register', { email: 'typo@x.io', password: 'password1' })
  const o = (await c('POST', '/api/orders', { kind: 'member' })).body
  await webhook(base, { id: 77, transferAmount: 200000, content: 'chuyen tien thanh vien' })
  const pays = (await admin('GET', '/api/admin/payments?status=unmatched')).body
  assert.equal(pays.length, 1)
  assert.equal((await admin('POST', `/api/admin/payments/${pays[0].id}/assign`, { code: o.code })).body.result, 'paid')
  assert.ok((await c('GET', '/api/me')).body.member)
  assert.equal((await admin('POST', `/api/admin/payments/${pays[0].id}/assign`, { code: o.code })).status, 409)

  await admin('POST', `/api/admin/orders/${o.code}/refund`, { note: 'khách đổi ý' })
  assert.equal((await c('GET', '/api/me')).body.member, null)
  const log = (await admin('GET', '/api/admin/audit')).body.map((a) => a.action)
  assert.ok(log.includes('payment_assign') && log.includes('order_refund'))
})

test('early access: members only, only for releases not open yet, opens after payment', async (t) => {
  const { app, base } = await start(t)
  const c = client(base)
  await c('POST', '/api/auth/register', { email: 'm@x.io', password: 'password1' })
  await prove(app, base, 'm@x.io')
  assert.equal((await c('POST', '/api/orders', { kind: 'early', tag: 'cam-v1.2.0' })).status, 403)
  app.db.prepare("INSERT INTO grants(email, kind, by, created_at) VALUES ('m@x.io', 'member', 't', ?)").run(Date.now())
  assert.equal((await c('POST', '/api/orders', { kind: 'early', tag: 'cam-v1.1.1' })).status, 409, 'already open')
  const o = (await c('POST', '/api/orders', { kind: 'early', tag: 'cam-v1.2.0' })).body
  assert.equal(o.amount, 20000)
  await webhook(base, { id: 9, transferAmount: 20000, content: o.code })
  const rel = (await c('GET', '/api/me')).body.releases.find((r) => r.tag === 'cam-v1.2.0')
  assert.equal(rel.open, true)
})

test('early access bought in the app: device token orders, polls, then the release is in its OTA list', async (t) => {
  const { app, base } = await start(t)
  const c = client(base)
  await c('POST', '/api/auth/register', { email: 'a@x.io', password: 'password1' })
  await prove(app, base, 'a@x.io')
  app.db.prepare("INSERT INTO grants(email, kind, by, created_at) VALUES ('a@x.io', 'member', 't', ?)").run(Date.now())
  const pair = await (await fetch(base + '/api/devices/start', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ name: 'Pixel' }) })).json()
  await c('POST', '/api/devices/confirm', { code: pair.code })
  const { token } = await (await fetch(`${base}/api/devices/poll?id=${pair.id}`)).json()
  // The Manager: Bearer token, JSON body, no cookie, no Origin.
  const dev = (method, path, body) =>
    fetch(base + path, { method, headers: { Authorization: `Bearer ${token}`, ...(body ? { 'Content-Type': 'application/json' } : {}) }, body: body ? JSON.stringify(body) : undefined }).then(async (r) => ({ status: r.status, body: await r.json() }))

  const status = (await dev('GET', '/api/ota/status')).body
  assert.equal(status.can_buy_early, true)
  assert.deepEqual(status.locked.map((r) => r.tag), ['cam-v1.2.0'])
  assert.equal(status.locked[0].apk, 'SU_Kernel_1.2.0_1-release.apk')
  assert.equal(status.locked[0].body, '- notes')
  assert.ok(!JSON.stringify(status.locked).includes('https://'), 'no download link for a locked release')
  // Managers up to 1.1.3 crash drawing the notes: they get none, newer ones get them.
  const asApp = (code) =>
    fetch(base + '/api/ota/status', { headers: { Authorization: `Bearer ${token}`, 'User-Agent': `CamSU/${code}` } }).then((r) => r.json())
  assert.equal((await asApp(32864)).locked[0].body, '')
  assert.equal((await asApp(32865)).locked[0].body, '- notes')

  const o = (await dev('POST', '/api/orders', { kind: 'early', tag: 'cam-v1.2.0' })).body
  assert.equal(o.amount, 20000)
  assert.equal(o.pay.content, o.code)
  assert.equal((await dev('POST', '/api/orders', { kind: 'early', tag: 'cam-v1.2.0' })).body.code, o.code, 'same pending order again')
  assert.equal((await dev('GET', `/api/orders/${o.code}`)).body.status, 'pending')
  assert.ok(!(await dev('GET', '/api/ota/releases')).body.some((r) => r.tag_name === 'cam-v1.2.0'))

  await webhook(base, { id: 77, transferAmount: 20000, content: `CK ${o.code}` })
  assert.equal((await dev('GET', `/api/orders/${o.code}`)).body.status, 'paid')
  assert.ok((await dev('GET', '/api/ota/releases')).body.some((r) => r.tag_name === 'cam-v1.2.0'))
  assert.equal((await dev('GET', '/api/ota/status')).body.locked.length, 0)

  // Someone else's device cannot read this order; sales closed hides the offer.
  const other = client(base)
  await other('POST', '/api/auth/register', { email: 'b@x.io', password: 'password1' })
  assert.equal((await other('GET', `/api/orders/${o.code}`)).status, 404)
  writeSettings(app.db, { early_open: false }, 'test')
  assert.equal((await dev('GET', '/api/ota/status')).body.can_buy_early, false)
})

test('settings from the panel change prices and stop sales', async (t) => {
  const { app, base } = await start(t)
  const admin = await adminClient(app, base)
  assert.equal((await admin('POST', '/api/admin/settings', { price_member: 250000, sales_open: false })).status, 200)
  assert.equal((await admin('POST', '/api/admin/settings', { price_member: -5 })).status, 400)
  const c = client(base)
  await c('POST', '/api/auth/register', { email: 'x@x.io', password: 'password1' })
  assert.equal((await c('POST', '/api/orders', { kind: 'member' })).status, 403)
  await admin('POST', '/api/admin/settings', { sales_open: true })
  assert.equal((await c('POST', '/api/orders', { kind: 'member' })).body.amount, 250000)
})

test('pair a device, OTA lists only open releases, /dl gate follows the same rules', async (t) => {
  const { app, base } = await start(t)
  const c = client(base)
  await c('POST', '/api/auth/register', { email: 'p@x.io', password: 'password1' })
  await prove(app, base, 'p@x.io')
  // The Manager sends no Origin and no cookie.
  const start1 = await fetch(base + '/api/devices/start', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ name: 'Pixel 9' }) })
  assert.equal(start1.status, 200)
  const pair = await start1.json()
  assert.equal((await (await fetch(`${base}/api/devices/poll?id=${pair.id}`)).json()).status, 'pending')
  assert.equal((await c('POST', '/api/devices/confirm', { code: pair.code.toLowerCase() })).status, 200)
  const linked = await (await fetch(`${base}/api/devices/poll?id=${pair.id}`)).json()
  assert.equal(linked.status, 'linked')
  assert.equal((await (await fetch(`${base}/api/devices/poll?id=${pair.id}`)).json()).status, 'expired', 'token handed over once')
  const dev = { Authorization: `Bearer ${linked.token}` }

  assert.equal((await fetch(base + '/api/ota/releases', { headers: dev })).status, 403, 'not a member')
  const status = await (await fetch(base + '/api/ota/status', { headers: dev })).json()
  assert.equal(status.member, false)
  // Device tokens cannot use web or admin endpoints.
  assert.equal((await fetch(base + '/api/me', { headers: dev })).status, 403)

  app.db.prepare("INSERT INTO grants(email, kind, by, created_at) VALUES ('p@x.io', 'member', 't', ?)").run(Date.now())
  const list = await (await fetch(base + '/api/ota/releases', { headers: dev })).json()
  assert.deepEqual(list.map((r) => r.tag_name), ['cam-v1.1.2', 'cam-v1.1.1'])
  assert.ok(!('urgent' in list[0]))

  const check = (tag, headers) => fetch(base + '/api/dl/check', { redirect: 'manual', headers: { 'X-Forwarded-Uri': `/dl/${tag}/a.apk`, ...headers } })
  assert.equal((await check('cam-v1.1.1', dev)).status, 200)
  assert.equal((await check('cam-v1.2.0', dev)).status, 403)
  assert.equal((await check('cam-v1.1.1', {})).status, 401)
  assert.equal((await check('cam-v1.1.1', { Accept: 'text/html' })).status, 302)

  // Unlinked from the web: the token stops working.
  const me = (await c('GET', '/api/me')).body
  await c('DELETE', `/api/devices/${me.devices[0].id}`)
  assert.equal((await fetch(base + '/api/ota/status', { headers: dev })).status, 401)
})

test('two-step sign-in: setup, login needs the code, admin pages need it, codes work once', async (t) => {
  const { app, base } = await start(t)
  const admin = await adminClient(app, base)
  const { secret } = (await admin('POST', '/api/auth/totp/setup', {})).body
  assert.equal((await admin('POST', '/api/auth/totp/setup', {})).body.secret, secret, 'setup again keeps the waiting key')
  const now = Math.floor(Date.now() / 30000)
  assert.equal((await admin('POST', '/api/auth/totp/enable', { code: '000000' })).status, 401)
  assert.equal((await admin('POST', '/api/auth/totp/enable', { code: totpCode(secret, now) })).status, 200)
  assert.equal((await admin('GET', '/api/admin/overview')).status, 200, 'this session passed the code')

  const fresh = client(base)
  assert.equal((await fresh('POST', '/api/auth/login', { email: 'cam@x.io', password: 'password1' })).body.error, 'totp_required')
  assert.equal((await fresh('POST', '/api/auth/login', { email: 'cam@x.io', password: 'password1', totp: totpCode(secret, now) })).status, 401, 'same code twice')
  assert.equal((await fresh('POST', '/api/auth/login', { email: 'cam@x.io', password: 'password1', totp: totpCode(secret, now + 1) })).status, 200)
  assert.equal((await fresh('GET', '/api/admin/overview')).status, 200)

  // A session without the code (e.g. after Google sign-in) is kept out of admin pages.
  // Another admin, so its codes are not the ones used above.
  const second = client(base)
  await second('POST', '/api/auth/register', { email: 'cam2@x.io', password: 'password1' })
  app.db.prepare("UPDATE users SET role = 'admin' WHERE email = 'cam2@x.io'").run()
  const s2 = (await second('POST', '/api/auth/totp/setup', {})).body.secret
  await second('POST', '/api/auth/totp/enable', { code: totpCode(s2, now) })
  const uid = app.db.prepare("SELECT id FROM users WHERE email = 'cam2@x.io'").get().id
  app.db.prepare('UPDATE sessions SET mfa = 0 WHERE user_id = ?').run(uid)
  assert.equal((await second('GET', '/api/admin/overview')).body.error, 'mfa_required')
  assert.equal((await second('POST', '/api/auth/totp/verify', { code: totpCode(s2, now) })).status, 401, 'used code')
  assert.equal((await second('POST', '/api/auth/totp/verify', { code: totpCode(s2, now + 1) })).status, 200)
  assert.equal((await second('GET', '/api/admin/overview')).status, 200)
})

test('admin reset link: works once, not for yourself, not for locked accounts', async (t) => {
  const { app, base } = await start(t)
  const admin = await adminClient(app, base)
  const user = client(base)
  await user('POST', '/api/auth/register', { email: 'quen@x.io', password: 'password1' })
  const uid = app.db.prepare("SELECT id FROM users WHERE email = 'quen@x.io'").get().id
  const selfId = app.db.prepare("SELECT id FROM users WHERE role = 'admin'").get().id
  assert.equal((await admin('POST', `/api/admin/users/${selfId}/reset-link`, {})).body.error, 'not_yourself')

  const { url } = (await admin('POST', `/api/admin/users/${uid}/reset-link`, {})).body
  const token = new URL(url).searchParams.get('reset')
  assert.ok(token)
  assert.equal(app.db.prepare('SELECT COUNT(*) n FROM resets WHERE token_hash = ?').get(token).n, 0, 'only the hash is stored')
  const anon = client(base)
  assert.equal((await anon('POST', '/api/auth/reset/confirm', { token, password: 'newpassword1' })).status, 200)
  assert.equal((await anon('POST', '/api/auth/reset/confirm', { token, password: 'other12345' })).status, 400, 'used once')
  assert.equal((await anon('POST', '/api/auth/login', { email: 'quen@x.io', password: 'newpassword1' })).status, 200)

  app.db.prepare('UPDATE users SET locked_at = ? WHERE id = ?').run(Date.now(), uid)
  assert.equal((await admin('POST', `/api/admin/users/${uid}/reset-link`, {})).body.error, 'locked')
  assert.ok(app.db.prepare("SELECT 1 FROM audit WHERE action = 'reset_link'").get())
})

test('beta testers: members ask, Cam approves, testers get every release at once (betas too)', async (t) => {
  const { app, base } = await start(t)
  const admin = await adminClient(app, base)
  const c = client(base)
  await c('POST', '/api/auth/register', { email: 'tester@x.io', password: 'password1' })
  await prove(app, base, 'tester@x.io')
  assert.equal((await c('POST', '/api/tester/request', {})).status, 403, 'members only')
  app.db.prepare("INSERT INTO grants(email, kind, by, created_at) VALUES ('tester@x.io', 'member', 't', ?)").run(Date.now())
  let me = (await c('GET', '/api/me')).body
  assert.ok(!me.releases.some((r) => r.prerelease), 'members do not see betas')
  assert.equal(me.tester.status, 'none')

  // From the app's beta switch: a device token.
  const pair = await (await fetch(base + '/api/devices/start', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ name: 'Pixel' }) })).json()
  await c('POST', '/api/devices/confirm', { code: pair.code })
  const { token } = await (await fetch(`${base}/api/devices/poll?id=${pair.id}`)).json()
  const dev = { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' }
  assert.equal((await (await fetch(base + '/api/tester/request', { method: 'POST', headers: dev, body: '{}' })).json()).status, 'pending')
  assert.equal((await (await fetch(base + '/api/tester/request', { method: 'POST', headers: dev, body: '{}' })).json()).status, 'pending', 'one open request')
  assert.equal((await (await fetch(base + '/api/ota/status', { headers: dev })).json()).tester, 'pending')

  const reqs = (await admin('GET', '/api/admin/testers')).body
  assert.equal(reqs.length, 1)
  assert.equal(reqs[0].device_name, 'Pixel')
  await admin('POST', `/api/admin/testers/${reqs[0].id}`, { status: 'approved', note: 'ok' })
  const list = await (await fetch(base + '/api/ota/releases', { headers: dev })).json()
  assert.deepEqual(list.map((r) => r.tag_name), ['cam-v1.3.0-beta.1', 'cam-v1.2.0', 'cam-v1.1.2', 'cam-v1.1.1'])
  me = (await c('GET', '/api/me')).body
  assert.equal(me.tester.status, 'approved')
  assert.ok(me.releases.every((r) => r.open))

  // Revoked: back to a regular member.
  const g = (await admin('GET', '/api/admin/grants?q=tester@x.io')).body.find((x) => x.kind === 'tester')
  await admin('POST', `/api/admin/grants/${g.id}/revoke`, {})
  const after = await (await fetch(base + '/api/ota/releases', { headers: dev })).json()
  assert.deepEqual(after.map((r) => r.tag_name), ['cam-v1.1.2', 'cam-v1.1.1'])
})

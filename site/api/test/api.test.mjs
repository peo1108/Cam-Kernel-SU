// node --test site/api/test  (Node 24, no dependencies)
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { createServer } from 'node:http'
import { mkdtempSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { config, createApp } from '../server.mjs'

const DAY = 24 * 3600 * 1000

async function start(t, extra = {}) {
  const dir = mkdtempSync(join(tmpdir(), 'cam-api-'))
  const catalog = join(dir, 'releases.json')
  const now = Date.now()
  writeFileSync(
    catalog,
    JSON.stringify([
      { tag_name: 'cam-v1.2.0', prerelease: false, published_at: new Date(now - 2 * DAY).toISOString(), urgent: false, assets: [] },
      { tag_name: 'cam-v1.1.2', prerelease: false, published_at: new Date(now - 3 * DAY).toISOString(), urgent: true, assets: [] },
      { tag_name: 'cam-v1.1.1', prerelease: false, published_at: new Date(now - 30 * DAY).toISOString(), urgent: false, assets: [] },
    ]),
  )
  const cfg = { ...config({ DATA_DIR: dir, ORIGIN: 'http://test.local', COOKIE_SECURE: '0', CATALOG: catalog }), ...extra }
  const app = createApp(cfg)
  const server = createServer(app.handle)
  await new Promise((r) => server.listen(0, '127.0.0.1', r))
  const base = `http://127.0.0.1:${server.address().port}`
  // Always shut down, even when an assertion fails half-way.
  t.after(() => {
    server.closeAllConnections()
    server.close()
  })
  return { app, server, base }
}

/** A browser-ish client keeping the session cookie. */
function client(base) {
  let jar = ''
  return async function call(method, path, body) {
    const res = await fetch(base + path, {
      method,
      redirect: 'manual',
      headers: { ...(body ? { 'Content-Type': 'application/json' } : {}), ...(jar ? { Cookie: jar } : {}), Origin: 'http://test.local' },
      body: body ? JSON.stringify(body) : undefined,
    })
    const set = res.headers.getSetCookie?.() ?? []
    for (const c of set) {
      const [kv] = c.split(';')
      if (kv.startsWith('cam_s=')) jar = kv.endsWith('=') ? '' : kv
    }
    const text = await res.text()
    return { status: res.status, body: text ? JSON.parse(text) : null }
  }
}

test('register, sign in, me, wrong password, duplicate email', async (t) => {
  const { server, base } = await start(t)
  const c = client(base)
  assert.equal((await c('POST', '/api/auth/register', { email: 'A@x.io', password: 'password1' })).status, 200)
  const me = await c('GET', '/api/me')
  assert.equal(me.status, 200)
  assert.equal(me.body.user.email, 'a@x.io')
  assert.equal(me.body.member, null)
  assert.ok(me.body.releases.every((r) => r.open === false), 'non-members get nothing')
  assert.equal((await client(base)('POST', '/api/auth/register', { email: 'a@x.io', password: 'password1' })).status, 409)
  const other = client(base)
  assert.equal((await other('POST', '/api/auth/login', { email: 'a@x.io', password: 'nope-nope' })).status, 401)
  assert.equal((await other('POST', '/api/auth/login', { email: 'a@x.io', password: 'password1' })).status, 200)
  assert.equal((await c('POST', '/api/auth/logout', {})).status, 200)
  assert.equal((await c('GET', '/api/me')).status, 401)
})

test('admin grants membership by email before the account exists; releases open by the rules', async (t) => {
  const { app, server, base } = await start(t)
  const admin = client(base)
  await admin('POST', '/api/auth/register', { email: 'cam@x.io', password: 'password1' })
  // Not an admin yet.
  assert.equal((await admin('GET', '/api/admin/overview')).status, 403)
  app.db.prepare("UPDATE users SET role = 'admin' WHERE email = 'cam@x.io'").run()
  assert.equal((await admin('GET', '/api/admin/overview')).status, 200)

  assert.equal((await admin('POST', '/api/admin/grants', { email: 'friend@x.io', kind: 'member', note: 'bạn bè' })).status, 200)
  const friend = client(base)
  await friend('POST', '/api/auth/register', { email: 'friend@x.io', password: 'password1' })
  const me = (await friend('GET', '/api/me')).body
  assert.equal(me.member.note, 'bạn bè')
  const open = Object.fromEntries(me.releases.map((r) => [r.tag, r.open]))
  assert.deepEqual(open, { 'cam-v1.2.0': false, 'cam-v1.1.2': true, 'cam-v1.1.1': true }, '20-day wait; urgent opens at once')

  // Early access for one release, then for everyone.
  await admin('POST', '/api/admin/grants', { email: 'friend@x.io', kind: 'early', tag: 'cam-v1.2.0' })
  assert.equal((await friend('GET', '/api/me')).body.releases[0].open, true)
  const users = (await admin('GET', '/api/admin/users?q=friend')).body
  assert.equal(users.length, 1)
  assert.equal(users[0].member, 1)

  // Revoke membership: nothing opens any more, early access or not.
  const grants = (await admin('GET', '/api/admin/grants?q=friend')).body
  const m = grants.find((g) => g.kind === 'member')
  await admin('POST', `/api/admin/grants/${m.id}/revoke`, {})
  assert.equal((await friend('GET', '/api/me')).body.member, null)
  assert.ok((await friend('GET', '/api/me')).body.releases.every((r) => !r.open))

  // Lock ends the sessions.
  await admin('POST', `/api/admin/users/${users[0].id}/lock`, { locked: true })
  assert.equal((await friend('GET', '/api/me')).status, 401)
  assert.equal((await friend('POST', '/api/auth/login', { email: 'friend@x.io', password: 'password1' })).status, 403)

  const log = (await admin('GET', '/api/admin/audit')).body.map((a) => a.action)
  for (const a of ['grant_member', 'grant_early', 'revoke_member', 'lock']) assert.ok(log.includes(a), a)
})

test('admin marks an order paid by hand and it grants membership', async (t) => {
  const { app, server, base } = await start(t)
  const admin = client(base)
  await admin('POST', '/api/auth/register', { email: 'cam@x.io', password: 'password1' })
  app.db.prepare("UPDATE users SET role = 'admin' WHERE email = 'cam@x.io'").run()
  const buyer = client(base)
  await buyer('POST', '/api/auth/register', { email: 'b@x.io', password: 'password1' })
  const uid = app.db.prepare("SELECT id FROM users WHERE email = 'b@x.io'").get().id
  app.db.prepare("INSERT INTO orders(code, user_id, kind, amount, created_at) VALUES ('CAMTEST01', ?, 'member', 200000, ?)").run(uid, Date.now())
  assert.equal((await admin('POST', '/api/admin/orders/CAMTEST01/paid', { note: 'chuyển khoản tay' })).status, 200)
  assert.ok((await buyer('GET', '/api/me')).body.member)
  assert.equal((await admin('GET', '/api/admin/overview')).body.paid_total, 200000)
})

test('source requests are for members only', async (t) => {
  const { app, server, base } = await start(t)
  const c = client(base)
  await c('POST', '/api/auth/register', { email: 'm@x.io', password: 'password1' })
  const req = { contact: 'tg @m', github: 'octocat', reason: 'đọc code' }
  assert.equal((await c('POST', '/api/source-request', req)).status, 403)
  app.db.prepare("INSERT INTO grants(email, kind, by, created_at) VALUES ('m@x.io', 'member', 'test', ?)").run(Date.now())
  assert.equal((await c('POST', '/api/source-request', req)).status, 200)
  assert.equal((await c('POST', '/api/source-request', { ...req, github: 'bad name!' })).status, 400)
})

test('cross-site writes are refused', async (t) => {
  const { server, base } = await start(t)
  const res = await fetch(base + '/api/auth/register', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Origin: 'https://evil.example' },
    body: JSON.stringify({ email: 'e@x.io', password: 'password1' }),
  })
  assert.equal(res.status, 403)
})

test('rate limits survive a flood of other keys; malformed escapes are a 400', async (t) => {
  const { makeLimiter } = await import('../auth.mjs')
  const limited = makeLimiter(100)
  // As /api/auth/login does, from many addresses (a botnet) at one account: each new address
  // adds a key, and that flood must not reset the account's own count.
  let blocked = 0
  for (let i = 0; i < 1000; i++) if (limited(`login:10.0.${i >> 8}.${i & 255}`, 20, 60_000) || limited('login:victim@x.io', 8, 60_000)) blocked++
  assert.equal(blocked, 992, 'only 8 guesses at the account got through')

  const { base } = await start(t)
  assert.equal((await fetch(`${base}/api/orders/%E0%A4%A`)).status, 400)
  assert.equal((await fetch(`${base}/api/auth/features`, { headers: { Cookie: 'cam_s=%E0%A4%A' } })).status, 200)
})

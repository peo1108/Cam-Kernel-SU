// node --test site/api/test  (Node 24, no dependencies)
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { createServer } from 'node:http'
import { mkdtempSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { config, createApp } from '../server.mjs'
import { sha256 } from '../auth.mjs'

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


/** The user opens the mailed verification link (mail is off in tests: the token is put in directly). */
async function prove(app, base, email) {
  const { id } = app.db.prepare('SELECT id FROM users WHERE email = ?').get(email)
  const token = `verify-${id}-${Math.random()}`
  app.db.prepare('INSERT INTO verifications(token_hash, user_id, created_at) VALUES (?, ?, ?)').run(sha256(token), id, Date.now())
  const res = await fetch(`${base}/api/auth/verify?token=${encodeURIComponent(token)}`, { redirect: 'manual' })
  if (res.headers.get('location') !== '/thanh-vien?verified=1') throw new Error(`verify failed: ${res.status} ${res.headers.get('location')}`)
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
  await prove(app, base, 'friend@x.io')
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
  await prove(app, base, 'm@x.io')
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

test('an address typed into the register form gets nothing granted to it until it is proven', async (t) => {
  const { app, base } = await start(t)
  const admin = client(base)
  await admin('POST', '/api/auth/register', { email: 'cam@x.io', password: 'password1' })
  app.db.prepare("UPDATE users SET role = 'admin' WHERE email = 'cam@x.io'").run()
  // Cam grants a customer by email before they have an account.
  await admin('POST', '/api/admin/grants', { email: 'khach@x.io', kind: 'member', note: 'tặng' })
  await admin('POST', '/api/admin/grants', { email: 'khach@x.io', kind: 'early', tag: 'cam-v1.2.0' })

  // Someone else registers that address first.
  const squatter = client(base)
  const reg = await squatter('POST', '/api/auth/register', { email: 'khach@x.io', password: 'password1' })
  assert.equal(reg.body.verify, 'mail_off')
  const me = (await squatter('GET', '/api/me')).body
  assert.equal(me.user.verified, false)
  assert.equal(me.member, null, 'not proven: the grant is not theirs')
  assert.ok(me.releases.every((r) => !r.open))
  assert.equal((await squatter('POST', '/api/source-request', { github: 'someone', contact: 'x', reason: 'y' })).status, 403)

  // The owner proves the address with a reset link (the mailbox, or Cam handing it over).
  const id = app.db.prepare("SELECT id FROM users WHERE email = 'khach@x.io'").get().id
  const link = (await admin('POST', `/api/admin/users/${id}/reset-link`, {})).body.url
  const token = new URL(link).searchParams.get('reset')
  assert.equal((await client(base)('POST', '/api/auth/reset/confirm', { token, password: 'new-password' })).status, 200)
  assert.equal((await squatter('GET', '/api/me')).status, 401, 'reset ends the other sessions')
  const owner = client(base)
  assert.equal((await owner('POST', '/api/auth/login', { email: 'khach@x.io', password: 'new-password' })).status, 200)
  const proven = (await owner('GET', '/api/me')).body
  assert.equal(proven.user.verified, true)
  assert.equal(proven.member.note, 'tặng')
  assert.equal(proven.releases.find((r) => r.tag === 'cam-v1.2.0').open, true)
  assert.equal((await owner('POST', '/api/auth/verify/resend', {})).body.verify, 'done')
})

test('verification links: once, not after three days, not someone else\'s token', async (t) => {
  const { app, base } = await start(t)
  const c = client(base)
  await c('POST', '/api/auth/register', { email: 'v@x.io', password: 'password1' })
  const id = app.db.prepare("SELECT id FROM users WHERE email = 'v@x.io'").get().id
  const put = (token, age) =>
    app.db.prepare('INSERT INTO verifications(token_hash, user_id, created_at) VALUES (?, ?, ?)').run(sha256(token), id, Date.now() - age)
  const open = async (token) => (await fetch(`${base}/api/auth/verify?token=${token}`, { redirect: 'manual' })).headers.get('location')
  put('old', 4 * DAY)
  assert.equal(await open('old'), '/tai-khoan?error=verify')
  assert.equal(await open('made-up'), '/tai-khoan?error=verify')
  put('fresh', 0)
  assert.equal(await open('fresh'), '/thanh-vien?verified=1')
  assert.equal(await open('fresh'), '/tai-khoan?error=verify', 'used up')
  assert.equal((await c('GET', '/api/me')).body.user.verified, true)
})

test('Google sign-in takes back an address someone registered with a password and never proved', async (t) => {
  const { app, base } = await start(t, { googleClientId: 'g-id', googleClientSecret: 'g-secret' })
  // Google's two endpoints, answered here; every other request goes out as usual.
  const realFetch = globalThis.fetch
  globalThis.fetch = async (url, init) => {
    const u = String(url)
    if (u.startsWith('https://oauth2.googleapis.com/token')) return Response.json({ access_token: 'at' })
    if (u.startsWith('https://openidconnect.googleapis.com/')) return Response.json({ sub: 'google-123', email: 'owner@x.io', email_verified: true, name: 'Owner' })
    return realFetch(url, init)
  }
  t.after(() => (globalThis.fetch = realFetch))

  const googleSignIn = async () => {
    const start = await fetch(`${base}/api/auth/google?next=/thanh-vien`, { redirect: 'manual' })
    const g = start.headers.getSetCookie().find((x) => x.startsWith('cam_g='))
    const state = decodeURIComponent(g.split(';')[0].slice('cam_g='.length)).split('|')[0]
    const back = await fetch(`${base}/api/auth/google/callback?state=${state}&code=c`, {
      redirect: 'manual',
      headers: { Cookie: g.split(';')[0] },
    })
    const session = back.headers.getSetCookie().find((x) => x.startsWith('cam_s=')).split(';')[0]
    return { location: back.headers.get('location'), session }
  }

  // The attacker registers the owner's address first, with their own password and two-step key.
  const attacker = client(base)
  await attacker('POST', '/api/auth/register', { email: 'owner@x.io', password: 'attacker-pw' })
  await attacker('POST', '/api/auth/totp/setup', {})

  const { location, session } = await googleSignIn()
  assert.equal(location, '/thanh-vien', 'no two-step prompt: the attacker\'s key is gone')
  const me = await (await fetch(`${base}/api/me`, { headers: { Cookie: session } })).json()
  assert.equal(me.user.verified, true)
  assert.equal(me.user.password, false, 'the attacker\'s password is gone')
  assert.equal((await attacker('GET', '/api/me')).status, 401, 'the attacker\'s session is gone')
  assert.equal((await client(base)('POST', '/api/auth/login', { email: 'owner@x.io', password: 'attacker-pw' })).status, 401)

  // A proven account keeps its password when it adds Google.
  const c = client(base)
  await c('POST', '/api/auth/register', { email: 'both@x.io', password: 'password1' })
  await prove(app, base, 'both@x.io')
  globalThis.fetch = async (url, init) => {
    const u = String(url)
    if (u.startsWith('https://oauth2.googleapis.com/token')) return Response.json({ access_token: 'at' })
    if (u.startsWith('https://openidconnect.googleapis.com/')) return Response.json({ sub: 'google-456', email: 'both@x.io', email_verified: true })
    return realFetch(url, init)
  }
  await googleSignIn()
  assert.equal((await c('GET', '/api/me')).status, 200)
  assert.equal((await client(base)('POST', '/api/auth/login', { email: 'both@x.io', password: 'password1' })).status, 200)
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

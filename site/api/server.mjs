// caesarvn.xyz API: accounts, membership, payments, devices, OTA, admin.
// Runs on the VPS behind Caddy (`handle /api/*`) as the cam-site-api service; no dependencies.
// Design: docs/superpowers/specs/2026-10-12-membership-design.md.
//
// This file: configuration, the shared app object, who is asking (sessions), dispatch.
// Business rules live in routes/*.mjs; every price, date and permission is decided here on the
// server, never in the browser or the Manager.
import { createServer } from 'node:http'
import { mkdirSync, readFileSync, statSync } from 'node:fs'
import { join } from 'node:path'
import { pathToFileURL } from 'node:url'
import { audit, earlyTags, isTester, membership, openDb } from './db.mjs'
import { DAY, HttpError, Router, SESSION_COOKIE, fail, parseCookies, readJson, send } from './http.mjs'
import { lookupSession, makeLimiter } from './auth.mjs'
import { readSettings } from './settings.mjs'
import authRoutes from './routes/auth.mjs'
import memberRoutes from './routes/member.mjs'
import payRoutes from './routes/pay.mjs'
import deviceRoutes from './routes/device.mjs'
import adminRoutes from './routes/admin.mjs'

export function config(env = process.env) {
  const dataDir = env.DATA_DIR || '/var/lib/cam-site-api'
  return {
    port: Number(env.PORT || 7792),
    dataDir,
    dbFile: env.DB_FILE || join(dataDir, 'cam.db'),
    origin: (env.ORIGIN || 'https://caesarvn.xyz').replace(/\/$/, ''),
    cookieSecure: env.COOKIE_SECURE !== '0',
    catalog: env.CATALOG || '/var/www/caesarvn/_private/releases.json',
    // Defaults only: the live values are edited in /quan-tri (settings.mjs).
    embargoDays: Number(env.EMBARGO_DAYS || 20),
    priceMember: Number(env.PRICE_MEMBER || 200000),
    priceEarly: Number(env.PRICE_EARLY || 20000),
    // Launch switch: /dl/ downloads need an entitled account (Caddy forward_auth -> /api/dl/check).
    dlGuard: env.DL_GUARD === '1',
    googleClientId: env.GOOGLE_CLIENT_ID || '',
    googleClientSecret: env.GOOGLE_CLIENT_SECRET || '',
    brevoKey: env.BREVO_API_KEY || '',
    mailFrom: env.MAIL_FROM || 'noreply@caesarvn.xyz',
    tgToken: env.TG_TOKEN || '',
    tgChat: env.TG_CHAT || '',
    // SePay: bank shown on the VietQR, webhook key, API token for the reconcile job.
    bankCode: env.BANK_CODE || '',
    bankAccount: env.BANK_ACCOUNT || '',
    bankHolder: env.BANK_HOLDER || '',
    sepayWebhookKey: env.SEPAY_WEBHOOK_KEY || '',
    sepayApiToken: env.SEPAY_API_TOKEN || '',
    reconcileMs: Number(env.RECONCILE_MS || 180000),
  }
}

export { DAY, SESSION_COOKIE }

/* ---------------- release catalog (built by CI: site/scripts/build-feed.mjs) ---------------- */

function catalogReader(file) {
  let cache = { mtime: -1, list: [] }
  return () => {
    try {
      const m = statSync(file).mtimeMs
      if (m !== cache.mtime) cache = { mtime: m, list: JSON.parse(readFileSync(file, 'utf8')) }
    } catch {
      cache = { mtime: -1, list: [] }
    }
    return cache.list
  }
}

/* ---------------- the app ---------------- */

export function createApp(cfg = config()) {
  mkdirSync(cfg.dataDir, { recursive: true })
  const db = openDb(cfg.dbFile)
  const router = new Router()
  const catalog = catalogReader(cfg.catalog)
  const timers = []

  const app = {
    db,
    cfg,
    router,
    catalog,
    timers,
    limited: makeLimiter(),
    settings: () => readSettings(db, cfg),
    audit: (admin, action, target, detail) => audit(db, admin, action, target, detail),

    /** What an account may have: membership, bought early access, beta tester, admin. */
    accountState(user) {
      return {
        member: membership(db, user.email),
        early: earlyTags(db, user.email),
        tester: isTester(db, user.email),
        admin: user.role === 'admin',
      }
    },

    /**
     * Every release this account may see, with when it opens and whether it can have it now.
     * Members: stable releases after the waiting days (or early access, or urgent). Beta testers
     * (members Cam approved): every release at once, betas included. Others never see betas.
     */
    releaseStates({ member, early, tester = false, admin, all = false }, now = Date.now()) {
      const days = app.settings().embargo_days
      const insider = admin || (member && tester)
      return catalog()
        .filter((r) => all || insider || !r.prerelease)
        .map((r) => {
        const openAt = Date.parse(r.published_at) + days * DAY
        const urgent = !!r.urgent
        const bought = early.has(r.tag_name)
        const open = !!(insider || (member && (urgent || openAt <= now || bought)))
        return {
          tag: r.tag_name,
          version: r.tag_name.replace(/^cam-v/, ''),
          prerelease: !!r.prerelease,
          published_at: r.published_at,
          open_at: openAt,
          urgent,
          early: bought,
          open,
        }
      })
    },

    async notify(text) {
      if (!cfg.tgToken || !cfg.tgChat) return
      try {
        await fetch(`https://api.telegram.org/bot${cfg.tgToken}/sendMessage`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ chat_id: cfg.tgChat, text, disable_web_page_preview: true }),
          signal: AbortSignal.timeout(5000),
        })
      } catch {}
    },
  }

  authRoutes(app)
  memberRoutes(app)
  payRoutes(app)
  deviceRoutes(app)
  adminRoutes(app)

  /* ----- dispatch ----- */

  async function handle(req, res) {
    const url = new URL(req.url, cfg.origin)
    const ctx = {
      req,
      res,
      url,
      headers: [],
      redirect: null,
      status: 200,
      body: {},
      params: {},
      ip: String(req.headers['x-forwarded-for'] || req.socket.remoteAddress || '').split(',')[0].trim(),
      cookies: parseCookies(req.headers.cookie),
    }
    try {
      const m = router.match(req.method, url.pathname)
      if (m === 'method') fail(405, 'method')
      if (!m) fail(404, 'not_found')
      ctx.params = m.params
      // Who is asking: a device token (the Manager) or the web session cookie.
      const bearer = /^Bearer (\S+)$/.exec(req.headers.authorization || '')?.[1]
      ctx.bearer = !!bearer
      const found = lookupSession(db, bearer || ctx.cookies[SESSION_COOKIE])
      if (found) Object.assign(ctx, found)
      const need = m.route.opts.auth
      if (need && !ctx.user) fail(401, 'sign_in')
      if ((need === 'web' || need === 'admin') && ctx.session.kind !== 'web') fail(403, 'web_only')
      if (need === 'device' && ctx.session.kind !== 'device') fail(403, 'device_only')
      if (need === 'admin') {
        if (ctx.user.role !== 'admin') fail(403, 'admin_only')
        // Once 2FA is on, admin pages need a session that passed the code.
        if (ctx.user.totp_on && !ctx.session.mfa) fail(403, 'mfa_required')
      }
      if (req.method !== 'GET' && req.method !== 'HEAD') {
        // CSRF: a foreign Origin is always refused, and a write riding on the session cookie must
        // say it comes from this site. The Manager (no cookie, maybe no Origin) is unaffected.
        if (!m.route.opts.raw) {
          const origin = req.headers.origin
          const viaCookie = !bearer && !!ctx.cookies[SESSION_COOKIE]
          if (origin ? origin !== cfg.origin : viaCookie) fail(403, 'bad_origin')
        }
        if (!m.route.opts.raw) ctx.body = await readJson(req)
      }
      const out = await m.route.handler(ctx)
      if (res.headersSent) return
      if (ctx.redirect) {
        res.writeHead(302, { Location: ctx.redirect, 'Cache-Control': 'no-store', ...headerObj(ctx.headers) })
        return res.end()
      }
      send(res, ctx.status, out ?? { ok: true }, headerObj(ctx.headers))
    } catch (e) {
      if (e instanceof HttpError) return send(res, e.status, { error: e.code }, headerObj(ctx.headers))
      console.error(req.method, url.pathname, e)
      send(res, 500, { error: 'server' })
    }
  }

  return { ...app, handle, close: () => timers.forEach(clearInterval) }
}

/** Set-Cookie may repeat: node accepts an array value. */
function headerObj(list) {
  const out = {}
  for (const [k, v] of list) out[k] = k in out ? [].concat(out[k], v) : v
  return out
}

/* ---------------- run ---------------- */

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const cfg = config()
  const app = createApp(cfg)
  app.startJobs?.()
  createServer(app.handle).listen(cfg.port, '127.0.0.1', () => console.log(`cam-site-api on 127.0.0.1:${cfg.port}`))
}

// SQLite schema and queries for the caesarvn.xyz API (docs/superpowers/specs/2026-10-12-membership-design.md).
// node:sqlite, no dependencies. Times are milliseconds since the epoch.
import { DatabaseSync } from 'node:sqlite'

// Each entry runs once, in order; PRAGMA user_version remembers how far a database got.
const MIGRATIONS = [
  `
  CREATE TABLE users (
    id INTEGER PRIMARY KEY,
    email TEXT NOT NULL UNIQUE COLLATE NOCASE,
    pass_hash TEXT,
    google_sub TEXT UNIQUE,
    name TEXT,
    role TEXT NOT NULL DEFAULT 'user',
    created_at INTEGER NOT NULL,
    locked_at INTEGER
  );
  CREATE TABLE sessions (
    token_hash TEXT PRIMARY KEY,
    user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    kind TEXT NOT NULL,
    device_name TEXT,
    created_at INTEGER NOT NULL,
    last_seen INTEGER NOT NULL
  );
  CREATE INDEX sessions_user ON sessions(user_id);
  CREATE TABLE pairings (
    id TEXT PRIMARY KEY,
    code TEXT NOT NULL UNIQUE,
    device_name TEXT,
    created_at INTEGER NOT NULL,
    user_id INTEGER REFERENCES users(id) ON DELETE CASCADE,
    token TEXT
  );
  CREATE TABLE orders (
    id INTEGER PRIMARY KEY,
    code TEXT NOT NULL UNIQUE,
    user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    kind TEXT NOT NULL,
    tag TEXT,
    amount INTEGER NOT NULL,
    status TEXT NOT NULL DEFAULT 'pending',
    created_at INTEGER NOT NULL,
    paid_at INTEGER,
    sepay_id TEXT UNIQUE,
    note TEXT
  );
  CREATE INDEX orders_user ON orders(user_id);
  -- Membership and early access, paid or granted by hand. email '*' = every member.
  CREATE TABLE grants (
    id INTEGER PRIMARY KEY,
    email TEXT NOT NULL COLLATE NOCASE,
    kind TEXT NOT NULL,
    tag TEXT,
    note TEXT,
    expires_at INTEGER,
    by TEXT NOT NULL,
    order_code TEXT,
    created_at INTEGER NOT NULL,
    revoked_at INTEGER
  );
  CREATE INDEX grants_email ON grants(email);
  CREATE TABLE source_requests (
    id INTEGER PRIMARY KEY,
    user_id INTEGER REFERENCES users(id) ON DELETE SET NULL,
    name TEXT,
    contact TEXT,
    github TEXT,
    reason TEXT,
    lang TEXT,
    created_at INTEGER NOT NULL,
    status TEXT NOT NULL DEFAULT 'pending',
    note TEXT
  );
  CREATE TABLE resets (
    token_hash TEXT PRIMARY KEY,
    user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at INTEGER NOT NULL
  );
  CREATE TABLE audit (
    id INTEGER PRIMARY KEY,
    at INTEGER NOT NULL,
    admin_id INTEGER,
    admin_email TEXT,
    action TEXT NOT NULL,
    target TEXT,
    detail TEXT
  );
  `,
  // 2: payments ledger, business settings, admin 2FA, order money tracking.
  `
  ALTER TABLE users ADD COLUMN totp_secret TEXT;
  ALTER TABLE users ADD COLUMN totp_on INTEGER NOT NULL DEFAULT 0;
  ALTER TABLE sessions ADD COLUMN mfa INTEGER NOT NULL DEFAULT 0;
  ALTER TABLE orders ADD COLUMN expires_at INTEGER;
  ALTER TABLE orders ADD COLUMN received INTEGER NOT NULL DEFAULT 0;
  ALTER TABLE orders ADD COLUMN refunded_at INTEGER;
  -- Every incoming bank transfer SePay reports, matched to an order or not. Never deleted.
  CREATE TABLE payments (
    id INTEGER PRIMARY KEY,
    provider TEXT NOT NULL,
    ext_id TEXT NOT NULL,
    amount INTEGER NOT NULL,
    content TEXT,
    account TEXT,
    reference TEXT,
    paid_at TEXT,
    order_code TEXT,
    status TEXT NOT NULL,
    note TEXT,
    created_at INTEGER NOT NULL,
    raw TEXT,
    UNIQUE (provider, ext_id)
  );
  CREATE INDEX payments_order ON payments(order_code);
  CREATE TABLE settings (
    key TEXT PRIMARY KEY,
    value TEXT NOT NULL,
    updated_at INTEGER NOT NULL,
    by TEXT
  );
  `,
  // 3: beta tester program. Members ask (from the app's beta switch or the web), Cam approves;
  // approval is a grant of kind 'tester'.
  `
  CREATE TABLE tester_requests (
    id INTEGER PRIMARY KEY,
    user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    device_name TEXT,
    note TEXT,
    created_at INTEGER NOT NULL,
    status TEXT NOT NULL DEFAULT 'pending',
    reviewed_by TEXT,
    reviewed_at INTEGER,
    review_note TEXT
  );
  CREATE INDEX tester_requests_user ON tester_requests(user_id);
  `,
  // 4: proven email. Anyone can type any address into the register form, and grants are kept by
  // email (Cam grants by hand, sometimes before the account exists): only a proven address gets
  // what was granted to it. Accounts made before this keep what they have.
  `
  ALTER TABLE users ADD COLUMN email_verified_at INTEGER;
  UPDATE users SET email_verified_at = created_at;
  -- The account a grant was made for (its own order, its approved request), when there is one.
  ALTER TABLE grants ADD COLUMN user_id INTEGER;
  UPDATE grants SET user_id = (SELECT user_id FROM orders o WHERE o.code = grants.order_code) WHERE order_code IS NOT NULL;
  CREATE TABLE verifications (
    token_hash TEXT PRIMARY KEY,
    user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at INTEGER NOT NULL
  );
  `,
]

export function openDb(file) {
  const db = new DatabaseSync(file)
  db.exec('PRAGMA journal_mode = WAL; PRAGMA foreign_keys = ON; PRAGMA busy_timeout = 3000;')
  const version = db.prepare('PRAGMA user_version').get().user_version
  for (let v = version; v < MIGRATIONS.length; v++) {
    db.exec('BEGIN')
    try {
      db.exec(MIGRATIONS[v])
      db.exec(`PRAGMA user_version = ${v + 1}`)
      db.exec('COMMIT')
    } catch (e) {
      db.exec('ROLLBACK')
      throw e
    }
  }
  return db
}

/** Runs [fn] in a transaction. */
export function tx(db, fn) {
  db.exec('BEGIN IMMEDIATE')
  try {
    const r = fn()
    db.exec('COMMIT')
    return r
  } catch (e) {
    db.exec('ROLLBACK')
    throw e
  }
}

/**
 * Live grants of one kind for an email (its own, plus '*' ones for early access). With
 * [ordersOf] (a user id: see grantScope), only the email's grants made for that very account (its
 * own orders, its approved requests) count.
 */
export function liveGrants(db, email, kind, now = Date.now(), ordersOf = null) {
  const own = ordersOf == null ? 'email = ?' : 'email = ? AND user_id = ?'
  return db
    .prepare(
      `SELECT * FROM grants WHERE kind = ? AND revoked_at IS NULL AND (expires_at IS NULL OR expires_at > ?)
       AND ((${own}) ${kind === 'early' ? "OR email = '*'" : ''}) ORDER BY created_at`,
    )
    .all(kind, now, email, ...(ordersOf == null ? [] : [ordersOf]))
}

/** What liveGrants needs for an account: null when its email is proven, else its id. */
export const grantScope = (user) => (user.email_verified_at ? null : user.id)

export function membership(db, email, now = Date.now(), ordersOf = null) {
  const g = liveGrants(db, email, 'member', now, ordersOf)
  if (!g.length) return null
  // The earliest live grant says since when; the furthest expiry wins (null = forever).
  const forever = g.some((x) => x.expires_at == null)
  return {
    since: g[0].created_at,
    expires_at: forever ? null : Math.max(...g.map((x) => x.expires_at)),
    note: g[g.length - 1].note,
  }
}

export function isTester(db, email, now = Date.now(), ordersOf = null) {
  return liveGrants(db, email, 'tester', now, ordersOf).length > 0
}

export function earlyTags(db, email, now = Date.now(), ordersOf = null) {
  return new Set(liveGrants(db, email, 'early', now, ordersOf).map((g) => g.tag))
}

export function audit(db, admin, action, target, detail) {
  db.prepare('INSERT INTO audit(at, admin_id, admin_email, action, target, detail) VALUES (?, ?, ?, ?, ?, ?)').run(
    Date.now(),
    admin?.id ?? null,
    admin?.email ?? 'system',
    action,
    target == null ? null : String(target),
    detail == null ? null : typeof detail === 'string' ? detail : JSON.stringify(detail),
  )
}

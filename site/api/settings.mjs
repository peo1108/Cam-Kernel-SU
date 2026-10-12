// Business settings Cam changes from /quan-tri (prices, waiting days, selling on/off).
// Stored in the settings table; the environment only gives the first defaults. Secrets
// (SePay keys, Google, mail) stay in /etc/cam-site-api.env, never here.
import { fail } from './http.mjs'

const int = (min, max) => (v) => {
  const n = Number(v)
  if (!Number.isInteger(n) || n < min || n > max) fail(400, 'bad_setting')
  return String(n)
}
const bool = (v) => (v === true || v === '1' || v === 1 ? '1' : v === false || v === '0' || v === 0 ? '0' : fail(400, 'bad_setting'))
const text = (max) => (v) => (typeof v === 'string' && v.length <= max ? v.trim() : fail(400, 'bad_setting'))

/** key -> [validator, default from config] */
export const SETTINGS = {
  price_member: [int(1000, 100_000_000), (cfg) => String(cfg.priceMember)],
  price_early: [int(1000, 100_000_000), (cfg) => String(cfg.priceEarly)],
  embargo_days: [int(0, 365), (cfg) => String(cfg.embargoDays)],
  sales_open: [bool, () => '1'],
  early_open: [bool, () => '1'],
  order_hours: [int(1, 24 * 14), () => '24'],
  notice: [text(300), () => ''],
}

export function readSettings(db, cfg) {
  const rows = Object.fromEntries(db.prepare('SELECT key, value FROM settings').all().map((r) => [r.key, r.value]))
  const raw = Object.fromEntries(Object.entries(SETTINGS).map(([k, [, d]]) => [k, rows[k] ?? d(cfg)]))
  return {
    price_member: Number(raw.price_member),
    price_early: Number(raw.price_early),
    embargo_days: Number(raw.embargo_days),
    sales_open: raw.sales_open === '1',
    early_open: raw.early_open === '1',
    order_hours: Number(raw.order_hours),
    notice: raw.notice,
  }
}

/** Validates and saves the given keys; returns what changed, for the audit log. */
export function writeSettings(db, patch, by) {
  const changed = {}
  for (const [k, v] of Object.entries(patch)) {
    const spec = SETTINGS[k]
    if (!spec) fail(400, 'bad_setting')
    const value = spec[0](v)
    db.prepare(
      'INSERT INTO settings(key, value, updated_at, by) VALUES (?, ?, ?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value, updated_at = excluded.updated_at, by = excluded.by',
    ).run(k, value, Date.now(), by)
    changed[k] = value
  }
  return changed
}

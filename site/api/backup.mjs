#!/usr/bin/env node
// Encrypted backups of the API database (accounts, orders, payments, grants).
//   node backup.mjs                    snapshot -> $BACKUP_DIR/cam-YYYYMMDD-HHMM.db.gz.enc, keep 30,
//                                      and send it to Telegram when TG_TOKEN/TG_CHAT are set
//   node backup.mjs restore <file> <out.db>   decrypt a snapshot into a database file
// The key is BACKUP_KEY (32 random bytes, base64) from /etc/cam-site-api.env. Keep a copy of it
// somewhere other than the VPS: without it the backups cannot be opened.
// Runs nightly from the cam-site-backup systemd timer.
import { createCipheriv, createDecipheriv, randomBytes } from 'node:crypto'
import { mkdirSync, readFileSync, readdirSync, rmSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'
import { gunzipSync, gzipSync } from 'node:zlib'
import { DatabaseSync } from 'node:sqlite'
import { config } from './server.mjs'

const MAGIC = Buffer.from('CAMBAK1')
const cfg = config()
const dir = process.env.BACKUP_DIR || '/var/backups/cam-site-api'
const KEEP = Number(process.env.BACKUP_KEEP || 30)

function key() {
  const k = Buffer.from(process.env.BACKUP_KEY || '', 'base64')
  if (k.length !== 32) {
    console.error('BACKUP_KEY must be 32 bytes in base64 (openssl rand -base64 32)')
    process.exit(2)
  }
  return k
}

function encrypt(plain) {
  const iv = randomBytes(12)
  const c = createCipheriv('aes-256-gcm', key(), iv)
  const body = Buffer.concat([c.update(plain), c.final()])
  return Buffer.concat([MAGIC, iv, c.getAuthTag(), body])
}

function decrypt(blob) {
  if (!blob.subarray(0, MAGIC.length).equals(MAGIC)) throw new Error('not a Cam backup')
  const iv = blob.subarray(MAGIC.length, MAGIC.length + 12)
  const tag = blob.subarray(MAGIC.length + 12, MAGIC.length + 28)
  const d = createDecipheriv('aes-256-gcm', key(), iv)
  d.setAuthTag(tag)
  return Buffer.concat([d.update(blob.subarray(MAGIC.length + 28)), d.final()])
}

async function snapshot() {
  mkdirSync(dir, { recursive: true, mode: 0o700 })
  const stamp = new Date().toISOString().slice(0, 16).replace(/[-:]/g, '').replace('T', '-')
  const tmp = join(dir, `.tmp-${stamp}.db`)
  rmSync(tmp, { force: true })
  // A consistent copy while the service keeps running.
  const db = new DatabaseSync(cfg.dbFile)
  db.exec(`VACUUM INTO '${tmp.replace(/'/g, "''")}'`)
  db.close()
  const out = join(dir, `cam-${stamp}.db.gz.enc`)
  writeFileSync(out, encrypt(gzipSync(readFileSync(tmp))), { mode: 0o600 })
  rmSync(tmp)
  const old = readdirSync(dir).filter((f) => /^cam-\d{8}-\d{4}\.db\.gz\.enc$/.test(f)).sort().slice(0, -KEEP)
  for (const f of old) rmSync(join(dir, f))
  console.log(`backup ${out}`)
  if (cfg.tgToken && cfg.tgChat) {
    const form = new FormData()
    form.append('chat_id', cfg.tgChat)
    form.append('caption', `Sao lưu caesarvn.xyz ${stamp} (mã hoá, mở bằng BACKUP_KEY)`)
    form.append('document', new Blob([readFileSync(out)]), `cam-${stamp}.db.gz.enc`)
    const res = await fetch(`https://api.telegram.org/bot${cfg.tgToken}/sendDocument`, { method: 'POST', body: form, signal: AbortSignal.timeout(60000) })
    console.log(`telegram ${res.status}`)
  }
}

const [cmd, file, outFile] = process.argv.slice(2)
if (cmd === 'restore') {
  if (!file || !outFile) {
    console.error('usage: backup.mjs restore <cam-....db.gz.enc> <out.db>')
    process.exit(2)
  }
  writeFileSync(outFile, gunzipSync(decrypt(readFileSync(file))), { mode: 0o600 })
  console.log(`restored to ${outFile}`)
} else {
  await snapshot()
}

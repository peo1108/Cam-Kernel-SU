#!/usr/bin/env node
// cam-admin: the few things done on the VPS shell rather than in /quan-tri.
//   cam-admin promote <email>          make an account admin (it must exist: register first)
//   cam-admin demote <email>
//   cam-admin grant <email> [note]     membership by hand (works before the email registers)
//   cam-admin users                    list accounts
//   cam-admin requests                 list source requests
import { audit, membership, openDb } from './db.mjs'
import { config } from './server.mjs'

const cfg = config()
const db = openDb(cfg.dbFile)
const [cmd, arg, ...rest] = process.argv.slice(2)
const date = (t) => (t ? new Date(t).toISOString().slice(0, 16).replace('T', ' ') : '-')

function user(email) {
  const u = db.prepare('SELECT * FROM users WHERE email = ?').get(String(email || '').toLowerCase())
  if (!u) {
    console.error(`No account for ${email}. Register on the site first.`)
    process.exit(1)
  }
  return u
}

switch (cmd) {
  case 'promote':
  case 'demote': {
    const u = user(arg)
    const role = cmd === 'promote' ? 'admin' : 'user'
    db.prepare('UPDATE users SET role = ? WHERE id = ?').run(role, u.id)
    audit(db, null, 'role', u.email, `${role} (cli)`)
    console.log(`${u.email} is now ${role}`)
    break
  }
  case 'grant': {
    const email = String(arg || '').toLowerCase()
    if (!email.includes('@')) {
      console.error('usage: cam-admin grant <email> [note]')
      process.exit(2)
    }
    const note = rest.join(' ') || null
    db.prepare("INSERT INTO grants(email, kind, note, by, created_at) VALUES (?, 'member', ?, 'cli', ?)").run(email, note, Date.now())
    audit(db, null, 'grant_member', email, `${note ?? ''} (cli)`)
    console.log(`membership granted to ${email}`)
    break
  }
  case 'users':
    for (const u of db.prepare('SELECT * FROM users ORDER BY created_at').all()) {
      const m = membership(db, u.email)
      console.log(`${String(u.id).padStart(4)}  ${u.email.padEnd(36)} ${u.role.padEnd(6)} ${m ? 'member' : '      '} ${u.locked_at ? 'LOCKED' : ''} ${date(u.created_at)}`)
    }
    break
  case 'requests':
    for (const s of db.prepare('SELECT s.*, u.email FROM source_requests s LEFT JOIN users u ON u.id = s.user_id ORDER BY s.created_at').all()) {
      console.log(`${date(s.created_at)}  [${s.status}] ${s.email ?? '-'} <${s.contact}> github:${s.github}\n    ${String(s.reason).replace(/\n/g, ' ')}\n`)
    }
    break
  default:
    console.log('usage: cam-admin promote|demote <email> | grant <email> [note] | users | requests')
    process.exit(cmd ? 2 : 0)
}

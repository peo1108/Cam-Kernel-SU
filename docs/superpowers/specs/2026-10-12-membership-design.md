# Membership, early access and private OTA

Date: 2026-10-12
Launch: Monday 2026-10-19 with version 1.1.1; the GitHub repository goes private the same day.
Scope: `site/` (pages + API on the VPS), `manager/` (account link + authenticated OTA), CI feed.
No kernel, camd or supercall changes. Signing key and package name stay as they are.

## 1. Product

| What | Price | Gives |
|---|---|---|
| Membership | 200,000 VND once | Download the app; every release 20 days after it ships (forever); source requests |
| Early access | 20,000 VND per release | That release right away (needs membership) |
| Urgent release | free for members | Marked `[gấp]` in CHANGELOG.md: no 20-day wait |
| Hiding rules, module library | free for everyone | Unchanged public `/feed/` |

Decisions made with Cam: OTA for members only (signed-in devices); email + password and Google
sign-in; VietQR payments confirmed automatically by SePay; beta testers get no special deal;
version numbering restarts at 1.1.1 but `versionCode` keeps increasing (commit count), so OTA
and Android upgrades keep working.

## 2. Architecture

```text
GitHub (private) --Release--> Deploy Site (workflow_run)
   build-feed.mjs: /dl/<tag>/*  +  _private/releases.json (all releases, with urgent flag)
                        |
VPS  Caddy  /            static VitePress pages (account pages are client-side)
            /api/*   --> cam-site-api (Node 24, node:sqlite, no dependencies) 127.0.0.1:7792
            /dl/*    --> forward_auth /api/dl/check, then file_server
            /_private/*  404 (read from disk by the API only)
            /feed/*  public: hiding rules, module library (releases.json removed)
```

- One Node process, no npm dependencies: `node:http`, `node:sqlite`, `node:crypto`, `fetch`.
- Database: `/var/lib/cam-site-api/cam.db` (WAL), nightly copy to `/var/backups/cam-site-api/`.
- Config in `/etc/cam-site-api.env`: prices, embargo days, SePay key and bank account, Google
  OAuth client, mail API key, Telegram bot. Missing optional settings disable their feature.

## 3. Data

```text
users(id, email UNIQUE, pass_hash, google_sub, role 'user'|'admin', member_since, created_at)
sessions(token_hash PK, user_id, kind 'web'|'device', device_name, created_at, last_seen)
pairings(id PK, code UNIQUE, device_name, created_at, user_id, token)     -- 10 min lifetime
orders(id, code UNIQUE 'CAMxxxxxx', user_id, kind 'member'|'early', tag, amount,
       status 'pending'|'paid'|'expired', created_at, paid_at, sepay_id UNIQUE)
early(user_id, tag, PK(user_id, tag))
source_requests(id, user_id, contact, github, reason, created_at, status)
```

Tokens are 32 random bytes; only their SHA-256 is stored. Passwords: scrypt (N=2^15, r=8, p=1).

## 4. Rules

- A release is open to a member when it is urgent, or `published_at + 20 days <= now`, or the
  member bought early access to it. Non-members get nothing (direction B).
- `/dl/<tag>/<file>`: allowed for an admin, or a member to whom `<tag>` is open. Web pages send
  the session cookie; the Manager sends `Authorization: Bearer <device token>`.
- Up to 3 devices per account; linking a 4th unlinks the oldest.
- Membership is bought once; early access only by members, only for releases not yet open.

## 5. API

| Method | Path | Who | Does |
|---|---|---|---|
| POST | `/api/auth/register` `{email,password}` | anyone | create account, sign in |
| POST | `/api/auth/login` | anyone | sign in (rate limited) |
| POST | `/api/auth/logout` | web | end session |
| GET | `/api/auth/google`, `/api/auth/google/callback` | anyone | Google sign-in |
| POST | `/api/auth/reset/request`, `/api/auth/reset/confirm` | anyone | password reset by mail |
| GET | `/api/me` | web | account, membership, devices, open/locked releases, orders |
| POST | `/api/orders` `{kind, tag?}` | web | create order, returns VietQR data |
| GET | `/api/orders/:code` | web | poll status |
| POST | `/api/pay/sepay` | SePay | webhook (`Authorization: Apikey ...`), marks paid, grants |
| POST | `/api/devices/start` `{name}` | Manager | pairing code + poll id |
| GET | `/api/devices/poll?id=` | Manager | token once the web confirmed |
| POST | `/api/devices/confirm` `{code}` | web | link the device to the account |
| DELETE | `/api/devices/:id` | web | unlink |
| GET | `/api/ota/releases` | Manager | GitHub-layout releases open to this device's account |
| GET | `/api/ota/status` | Manager | member?, locked releases with unlock date and price |
| GET | `/api/dl/check` | Caddy | 200/401/403 for `X-Forwarded-Uri` |
| POST | `/api/source-request` | member | stored; Telegram notice |
| GET/POST | `/api/admin/*` | admin | users, orders, grant membership/early, mark paid, requests |

POST requests need `Content-Type: application/json` and a same-site `Origin` (CSRF).

## 5b. Admin (`/quan-tri`, role `admin`)

Paying is not the only way in: Cam grants and revokes by hand.

- **Members:** search by email; grant membership (with a note: "beta tester", "friend", "paid
  cash"...), revoke it, set an expiry date if wanted (empty = forever).
- **Add by email:** grant membership to an email that has no account yet; it applies when that
  email registers or signs in with Google.
- **Early access:** give one person a release early, or open a release early for every member.
- **Accounts:** lock / unlock (locked = no sign-in, devices and downloads stop), unlink devices,
  make admin.
- **Orders:** list with status; mark a pending order paid (SePay missed it), refund note.
- **Source requests:** list, approve / reject with a note (inviting on GitHub stays manual).
- **Audit log:** every admin action with time, admin and target, newest first.

Tables added: `grants(email, kind 'member'|'early', tag, note, expires_at, by, created_at,
revoked_at)` (membership and early access are both read from grants, paid ones included),
`audit(at, admin_id, action, target, detail)`; `users.locked_at`.
The first admin is made on the VPS: `cam-admin promote <email>`.

## 6. Pages (vi / en / zh)

`/tai-khoan` sign in / register, `/thanh-vien` dashboard (membership, downloads, early access
buttons, devices, orders), `/mua` checkout (VietQR image from SePay, live status),
`/ket-noi?code=` confirm a device, `/quan-tri` admin. Download page shows files but links
need membership. Source page requires membership.

## 7. Manager

- Settings → Account: "Link account" shows a 6-character code and QR for
  `caesarvn.xyz/ket-noi?code=...`, polls until confirmed, stores the device token in app
  preferences. Shows the linked email; "Unlink".
- OTA reads `/api/ota/releases` with the token (no GitHub fallback any more). No token or 401:
  Home shows "Sign in to get updates"; locked releases: "1.2.0 out, open on <date> or get it now".
- `UpdateInstaller` adds the token to downloads from caesarvn.xyz.

## 8. CI

- `build-feed.mjs`: full catalog to `_private/releases.json` with `urgent` from the CHANGELOG
  heading suffix `[gấp]`; public `/feed/releases.json` removed.
- Heading grammar already allows a suffix after the date (`ChangelogParser`,
  `scripts/changelog_section.py`).

## 9. Order of work

1. API core: DB, auth, sessions, `/api/me`, admin CLI. 2. Orders + SePay + Telegram.
3. Device pairing + OTA + `/dl` guard + feed split. 4. Pages. 5. Manager. 6. End-to-end test with a
beta tag. 7. Launch day: CHANGELOG 1.1.1, tag, private.

## 10. Added while building (2026-10-12)

- **Beta testers.** Members ask from the Manager's beta switch (`POST /api/tester/request`, device
  token) or the web; Cam approves in `/quan-tri → Beta tester` (grant kind `tester`). Testers get
  every release at once, betas included; everyone else never sees betas. Table `tester_requests`.
- **Admin two-step sign-in (TOTP).** `/api/auth/totp/*`; once on, admin endpoints need a session
  that passed the code (`sessions.mfa`); Google sign-in asks for the code after. Codes work once.
- **Business settings in the panel** (`settings` table): prices, waiting days, order lifetime,
  selling on/off for membership and early access, a notice. Secrets stay in the env file.
- **Payments ledger** (`payments`): every SePay transfer once (provider + id), statuses matched /
  underpaid / duplicate / refunded / unmatched / ignored; assign or ignore by hand; refunds revoke
  what the order granted; reconcile job over the SePay API when `SEPAY_API_TOKEN` is set.
- **Encrypted nightly backups** (`backup.mjs`, systemd timer), restore command.
- Code layout: `server.mjs` (config, sessions, dispatch) + `routes/{auth,member,pay,device,admin}.mjs`.
- Sales stay off on the live site until launch day (`sales_open = 0`).

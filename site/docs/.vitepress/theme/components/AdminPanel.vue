<script setup lang="ts">
// /quan-tri: Cam's panel (role admin). Grants and revokes membership and early access by hand,
// locks accounts, unlinks devices, confirms orders, reviews source requests, shows the audit log.
// API: /api/admin/* in site/api/server.mjs.
import { computed, onMounted, reactive, ref } from 'vue'
import { ApiError, api, day, errorText, vnd, when } from '../api'

type Tab = 'overview' | 'users' | 'grants' | 'orders' | 'payments' | 'testers' | 'requests' | 'settings' | 'audit'
const TABS: [Tab, string][] = [
  ['overview', 'Tổng quan'],
  ['users', 'Thành viên'],
  ['grants', 'Cấp quyền'],
  ['orders', 'Đơn hàng'],
  ['payments', 'Giao dịch'],
  ['testers', 'Beta tester'],
  ['requests', 'Mã nguồn'],
  ['settings', 'Cài đặt'],
  ['audit', 'Nhật ký'],
]

const state = ref<'loading' | 'signin' | 'denied' | 'mfa' | 'ready'>('loading')
const mfaCode = ref('')
const tab = ref<Tab>('overview')
const busy = ref(false)
const toast = ref<{ text: string; bad?: boolean } | null>(null)

const overview = ref<any>(null)
const users = ref<any[]>([])
const userQ = ref('')
const detail = ref<any>(null)
const grants = ref<any[]>([])
const grantQ = ref('')
const showRevoked = ref(false)
const orders = ref<any[]>([])
const orderStatus = ref('pending')
const requests = ref<any[]>([])
const auditLog = ref<any[]>([])
const payments = ref<any[]>([])
const testers = ref<any[]>([])
const payStatus = ref('unmatched')
const settings = reactive<any>({})

const form = reactive({ email: '', kind: 'member', tag: '', note: '', expires: '' })
const memberForm = reactive({ note: '', expires: '' })
const earlyTag = ref('')

const releases = computed<any[]>(() => overview.value?.releases ?? [])
const lockedReleases = computed(() => releases.value.filter((r) => !r.open))

function say(text: string, bad = false) {
  toast.value = { text, bad }
  setTimeout(() => (toast.value = null), 3200)
}

async function run(fn: () => Promise<unknown>, ok?: string) {
  busy.value = true
  try {
    await fn()
    if (ok) say(ok)
  } catch (e) {
    say(errorText(e, 'vi'), true)
  } finally {
    busy.value = false
  }
}

async function load(t: Tab = tab.value) {
  tab.value = t
  if (t === 'overview') overview.value = await api('GET', '/api/admin/overview')
  if (t === 'users') users.value = await api('GET', `/api/admin/users?q=${encodeURIComponent(userQ.value)}`)
  if (t === 'grants') {
    grants.value = await api('GET', `/api/admin/grants?q=${encodeURIComponent(grantQ.value)}${showRevoked.value ? '&all=1' : ''}`)
    if (!overview.value) overview.value = await api('GET', '/api/admin/overview')
  }
  if (t === 'orders') orders.value = await api('GET', `/api/admin/orders?status=${orderStatus.value}`)
  if (t === 'requests') requests.value = await api('GET', '/api/admin/requests')
  if (t === 'audit') auditLog.value = await api('GET', '/api/admin/audit')
  if (t === 'testers') testers.value = await api('GET', '/api/admin/testers')
  if (t === 'payments') payments.value = await api('GET', `/api/admin/payments?status=${payStatus.value}`)
  if (t === 'settings') Object.assign(settings, await api('GET', '/api/admin/settings'))
}

async function openUser(id: number) {
  await run(async () => {
    detail.value = await api('GET', `/api/admin/users/${id}`)
    memberForm.note = ''
    memberForm.expires = ''
    earlyTag.value = lockedReleases.value[0]?.tag ?? ''
  })
}
const refreshUser = () => detail.value && openUser(detail.value.user.id)

/* ----- actions ----- */

const grant = () =>
  run(async () => {
    await api('POST', '/api/admin/grants', {
      email: form.email.trim(),
      kind: form.kind,
      tag: form.kind === 'early' ? form.tag : undefined,
      note: form.note,
      expires_at: form.kind === 'member' && form.expires ? form.expires : undefined,
    })
    form.email = form.note = form.expires = ''
    await load('grants')
  }, 'Đã cấp quyền.')

const grantMember = () =>
  run(async () => {
    await api('POST', '/api/admin/grants', { email: detail.value.user.email, kind: 'member', note: memberForm.note, expires_at: memberForm.expires || undefined })
    await refreshUser()
  }, 'Đã cấp thành viên.')

const grantEarly = () =>
  run(async () => {
    await api('POST', '/api/admin/grants', { email: detail.value.user.email, kind: 'early', tag: earlyTag.value, note: 'tặng' })
    await refreshUser()
  }, 'Đã tặng truy cập sớm.')

function revoke(g: any) {
  const what = g.kind === 'member' ? 'thành viên' : `truy cập sớm ${g.tag}`
  if (!confirm(`Thu hồi ${what} của ${g.email}?`)) return
  run(async () => {
    await api('POST', `/api/admin/grants/${g.id}/revoke`, {})
    if (detail.value) await refreshUser()
    if (tab.value === 'grants') await load('grants')
  }, 'Đã thu hồi.')
}

function lock(locked: boolean) {
  const u = detail.value.user
  if (locked && !confirm(`Khoá ${u.email}? Người này bị đăng xuất khỏi mọi thiết bị.`)) return
  run(async () => {
    await api('POST', `/api/admin/users/${u.id}/lock`, { locked })
    await refreshUser()
    if (tab.value === 'users') await load('users')
  }, locked ? 'Đã khoá.' : 'Đã mở khoá.')
}

function setRole(role: 'admin' | 'user') {
  const u = detail.value.user
  if (!confirm(role === 'admin' ? `Cho ${u.email} làm admin?` : `Bỏ quyền admin của ${u.email}?`)) return
  run(async () => {
    await api('POST', `/api/admin/users/${u.id}/role`, { role })
    await refreshUser()
  }, 'Đã đổi quyền.')
}

function unlink(s: any) {
  if (!confirm(`Gỡ ${s.name || s.kind}?`)) return
  run(async () => {
    await api('DELETE', `/api/admin/users/${detail.value.user.id}/sessions/${s.id}`)
    await refreshUser()
  }, 'Đã gỡ.')
}

function markPaid(o: any) {
  const note = prompt(`Xác nhận đã nhận ${vnd(o.amount)} cho đơn ${o.code}? Ghi chú:`, 'chuyển khoản, đối soát tay')
  if (note === null) return
  run(async () => {
    await api('POST', `/api/admin/orders/${o.code}/paid`, { note })
    await load('orders')
  }, 'Đã xác nhận đơn.')
}

function noteOrder(o: any) {
  const note = prompt(`Ghi chú cho đơn ${o.code}:`, o.note ?? '')
  if (note === null) return
  run(async () => {
    await api('POST', `/api/admin/orders/${o.code}/note`, { note })
    await load('orders')
  })
}

function review(r: any, status: 'approved' | 'rejected' | 'pending') {
  const note = prompt(status === 'approved' ? 'Duyệt. Ghi chú (ví dụ: đã mời trên GitHub):' : status === 'rejected' ? 'Từ chối. Lý do:' : 'Đưa về chờ duyệt. Ghi chú:', r.note ?? '')
  if (note === null) return
  run(async () => {
    await api('POST', `/api/admin/requests/${r.id}`, { status, note })
    await load('requests')
  }, 'Đã cập nhật.')
}

function openForAll(r: any) {
  if (!confirm(`Mở ${r.version} ngay cho mọi thành viên (không chờ tới ${day(r.open_at)})?`)) return
  run(async () => {
    await api('POST', '/api/admin/grants', { email: '*', kind: 'early', tag: r.tag, note: 'mở sớm cho tất cả' })
    await load('overview')
  }, `Đã mở ${r.version} cho mọi thành viên.`)
}

function refund(o: any) {
  const note = prompt(`Hoàn tiền đơn ${o.code} (${vnd(o.received || o.amount)})? Quyền đã mua sẽ bị thu hồi. Ghi chú:`, 'đã chuyển trả')
  if (note === null) return
  run(async () => {
    await api('POST', `/api/admin/orders/${o.code}/refund`, { note })
    await load('orders')
  }, 'Đã ghi nhận hoàn tiền.')
}

function assignPayment(p: any) {
  const code = prompt(`Gán ${vnd(p.amount)} ("${p.content}") vào đơn nào? Nhập mã đơn CAM…:`)
  if (!code) return
  run(async () => {
    const r: any = await api('POST', `/api/admin/payments/${p.id}/assign`, { code: code.trim().toUpperCase() })
    await load('payments')
    say(r.result === 'paid' ? 'Đã gán, đơn đã kích hoạt.' : `Đã gán (${r.result}).`)
  })
}

function ignorePayment(p: any) {
  const note = prompt(`Bỏ qua giao dịch ${vnd(p.amount)}? Lý do:`, 'không phải tiền mua hàng')
  if (note === null) return
  run(async () => {
    await api('POST', `/api/admin/payments/${p.id}/ignore`, { note })
    await load('payments')
  }, 'Đã bỏ qua.')
}

const reconcileNow = () =>
  run(async () => {
    const r: any = await api('POST', '/api/admin/reconcile', {})
    await load('payments')
    say(r.skipped ? 'Chưa cấu hình API SePay.' : `Đối soát xong: ${r.fresh} giao dịch mới.`)
  })

const saveSettings = () =>
  run(async () => {
    const out = await api('POST', '/api/admin/settings', {
      price_member: Number(settings.price_member),
      price_early: Number(settings.price_early),
      embargo_days: Number(settings.embargo_days),
      order_hours: Number(settings.order_hours),
      sales_open: !!settings.sales_open,
      early_open: !!settings.early_open,
      notice: settings.notice ?? '',
    })
    Object.assign(settings, out)
  }, 'Đã lưu cài đặt.')

function resetTotp() {
  const u = detail.value.user
  if (!confirm(`Tắt xác thực 2 bước của ${u.email}? Người này cần cài lại.`)) return
  run(async () => {
    await api('POST', `/api/admin/users/${u.id}/totp-reset`, {})
    await refreshUser()
  }, 'Đã tắt 2FA.')
}

async function submitMfa() {
  try {
    await api('POST', '/api/auth/totp/verify', { code: mfaCode.value })
    mfaCode.value = ''
    await load('overview')
    state.value = 'ready'
  } catch (e) {
    say(errorText(e, 'vi'), true)
  }
}

function reviewTester(x: any, status: 'approved' | 'rejected') {
  const warn = status === 'approved' && !x.member ? ' (người này CHƯA là thành viên: duyệt xong vẫn chưa nhận được gì cho tới khi tham gia)' : ''
  const note = prompt(status === 'approved' ? `Duyệt ${x.email} làm beta tester${warn}? Ghi chú:` : `Từ chối ${x.email}? Lý do:`, '')
  if (note === null) return
  run(async () => {
    await api('POST', `/api/admin/testers/${x.id}`, { status, note })
    await load('testers')
  }, status === 'approved' ? 'Đã duyệt tester.' : 'Đã từ chối.')
}

/* ----- labels ----- */

const ACTIONS: Record<string, string> = {
  grant_member: 'Cấp thành viên',
  grant_early: 'Tặng truy cập sớm',
  revoke_member: 'Thu hồi thành viên',
  revoke_early: 'Thu hồi truy cập sớm',
  lock: 'Khoá',
  unlock: 'Mở khoá',
  role: 'Đổi quyền',
  unlink: 'Gỡ thiết bị',
  order_paid: 'Xác nhận đơn',
  order_note: 'Ghi chú đơn',
  request_approved: 'Duyệt mã nguồn',
  request_rejected: 'Từ chối mã nguồn',
  request_pending: 'Chờ duyệt lại',
  order_refund: 'Hoàn tiền',
  grant_tester: 'Cấp beta tester',
  revoke_tester: 'Thu hồi beta tester',
  tester_approved: 'Duyệt tester',
  tester_rejected: 'Từ chối tester',
  payment_assign: 'Gán giao dịch',
  payment_ignore: 'Bỏ qua giao dịch',
  reconcile: 'Đối soát',
  settings: 'Đổi cài đặt',
  totp_on: 'Bật 2FA',
  totp_off: 'Tắt 2FA',
  totp_reset: 'Reset 2FA',
}
const STATUS: Record<string, string> = { pending: 'Chờ', paid: 'Đã trả', expired: 'Hết hạn', refunded: 'Đã hoàn', approved: 'Đã duyệt', rejected: 'Từ chối' }
const PAY: Record<string, string> = { unmatched: 'Không khớp', matched: 'Đã khớp', underpaid: 'Thiếu tiền', duplicate: 'Trả trùng', refunded: 'Đơn đã hoàn', ignored: 'Bỏ qua' }
const HEALTH: [string, string][] = [
  ['pay', 'Ngân hàng nhận tiền'],
  ['webhook', 'Webhook SePay'],
  ['reconcile', 'Đối soát SePay API'],
  ['telegram', 'Báo Telegram'],
  ['mail', 'Gửi email'],
  ['google', 'Đăng nhập Google'],
  ['dl_guard', 'Khoá /dl/ (bật khi ra mắt)'],
  ['totp', 'Bạn đã bật 2FA'],
]
const KIND: Record<string, string> = { member: 'Thành viên', early: 'Truy cập sớm', tester: 'Beta tester' }

function grantState(g: any) {
  if (g.revoked_at) return ['Đã thu hồi', 'muted']
  if (g.expires_at && g.expires_at < Date.now()) return ['Hết hạn', 'muted']
  return ['Hiệu lực', 'ok']
}

onMounted(async () => {
  try {
    await load('overview')
    state.value = 'ready'
  } catch (e) {
    state.value = e instanceof ApiError && e.status === 401 ? 'signin' : e instanceof ApiError && e.code === 'mfa_required' ? 'mfa' : 'denied'
  }
})
</script>

<template>
  <div class="cam-page adm">
    <header class="adm-head">
      <h1 class="lg-display">Quản trị.</h1>
      <div v-if="state === 'ready'" class="lg-seg tabs" role="tablist">
        <button v-for="[k, label] in TABS" :key="k" role="tab" :class="{ on: tab === k }" :aria-selected="tab === k" @click="run(() => load(k))">
          {{ label }}
          <b v-if="k === 'orders' && overview?.pending_orders" class="dot-count">{{ overview.pending_orders }}</b>
          <b v-if="k === 'requests' && overview?.pending_requests" class="dot-count">{{ overview.pending_requests }}</b>
          <b v-if="k === 'testers' && overview?.pending_testers" class="dot-count">{{ overview.pending_testers }}</b>
        </button>
      </div>
    </header>

    <div v-if="state === 'loading'" class="panel lg">Đang tải…</div>
    <div v-else-if="state === 'signin'" class="panel lg">
      <p>Cần đăng nhập bằng tài khoản admin.</p>
      <a class="lg lg-btn lg-tinted lg-press small" href="/tai-khoan?next=/quan-tri">Đăng nhập</a>
    </div>
    <div v-else-if="state === 'denied'" class="panel lg"><p>Tài khoản này không có quyền quản trị.</p></div>
    <form v-else-if="state === 'mfa'" class="panel lg mfa" @submit.prevent="submitMfa">
      <p>Nhập mã xác thực 2 bước để vào trang quản trị.</p>
      <input v-model="mfaCode" inputmode="numeric" maxlength="6" placeholder="6 số" required autofocus />
      <button class="lg lg-btn lg-tinted lg-press small" type="submit">Xác nhận</button>
    </form>

    <template v-else>
      <!-- overview -->
      <section v-if="tab === 'overview' && overview" class="grid-stats">
        <div class="stat lg"><small>Tài khoản</small><b class="lg-display">{{ overview.users }}</b></div>
        <div class="stat lg"><small>Thành viên</small><b class="lg-display">{{ overview.members }}</b></div>
        <button class="stat lg lg-press" @click="run(() => load('testers'))"><small>Beta tester · chờ duyệt</small><b class="lg-display">{{ overview.testers }} · {{ overview.pending_testers }}</b></button>
        <div class="stat lg"><small>Thiết bị đã liên kết</small><b class="lg-display">{{ overview.devices }}</b></div>
        <div class="stat lg"><small>Doanh thu hôm nay</small><b class="lg-display">{{ vnd(overview.paid_today) }}</b></div>
        <div class="stat lg"><small>Doanh thu 30 ngày</small><b class="lg-display">{{ vnd(overview.paid_30d) }}</b></div>
        <div class="stat lg"><small>Tổng doanh thu</small><b class="lg-display">{{ vnd(overview.paid_total) }}</b></div>
        <button class="stat lg lg-press" @click="run(() => load('orders'))"><small>Đơn chờ</small><b class="lg-display">{{ overview.pending_orders }}</b></button>
        <button class="stat lg lg-press" @click="run(() => load('requests'))"><small>Yêu cầu mã nguồn chờ</small><b class="lg-display">{{ overview.pending_requests }}</b></button>
        <button class="stat lg lg-press" :class="{ alert: overview.unmatched_payments }" @click="run(() => load('payments'))"><small>Giao dịch cần xem</small><b class="lg-display">{{ overview.unmatched_payments }}</b></button>
      </section>
      <section v-if="tab === 'overview' && overview" class="panel lg">
        <h2 class="lg-display">Tình trạng hệ thống</h2>
        <p v-if="!overview.health.totp" class="warn">⚠ Tài khoản admin của bạn chưa bật xác thực 2 bước. Bật ở <a href="/thanh-vien">trang thành viên</a>.</p>
        <p v-if="!overview.settings.sales_open" class="warn">⏸ Đang tạm ngừng bán (thẻ Cài đặt).</p>
        <ul class="health">
          <li v-for="[k, label] in HEALTH" :key="k"><span :class="overview.health[k] ? 'on' : 'off'">{{ overview.health[k] ? '●' : '○' }}</span> {{ label }}</li>
          <li><span :class="overview.health.catalog ? 'on' : 'off'">●</span> Danh mục: {{ overview.health.catalog }} bản</li>
        </ul>
      </section>
      <section v-if="tab === 'overview' && overview" class="panel lg">
        <h2 class="lg-display">Bản phát hành</h2>
        <p class="hint">Thành viên nhận mỗi bản sau 20 ngày. Bản <b>gấp</b> mở ngay. Có thể mở sớm cho tất cả.</p>
        <div class="table-wrap">
          <table>
            <thead><tr><th>Bản</th><th>Ngày ra</th><th>Mở cho thành viên</th><th /></tr></thead>
            <tbody>
              <tr v-for="r in releases" :key="r.tag">
                <td><b class="cam-mono">{{ r.version }}</b> <span v-if="r.urgent" class="cam-chip">gấp</span> <span v-if="r.prerelease" class="cam-chip muted">beta</span></td>
                <td>{{ day(Date.parse(r.published_at)) }}</td>
                <td><span v-if="r.open" class="cam-chip ok">Đã mở</span><span v-else>{{ day(r.open_at) }}</span></td>
                <td class="right"><button v-if="!r.open" class="mini" @click="openForAll(r)">Mở ngay cho tất cả</button></td>
              </tr>
              <tr v-if="!releases.length"><td colspan="4" class="muted">Chưa có bản nào trong danh mục.</td></tr>
            </tbody>
          </table>
        </div>
      </section>

      <!-- users -->
      <section v-if="tab === 'users'" class="panel lg">
        <form class="bar" @submit.prevent="run(() => load('users'))">
          <input v-model="userQ" type="search" placeholder="Tìm email hoặc tên…" />
          <button class="lg lg-btn lg-drop lg-press small" type="submit">Tìm</button>
        </form>
        <div class="table-wrap">
          <table>
            <thead><tr><th>Email</th><th>Thành viên</th><th>Thiết bị</th><th>Hoạt động</th><th>Ngày tạo</th></tr></thead>
            <tbody>
              <tr v-for="u in users" :key="u.id" class="click" @click="openUser(u.id)">
                <td>
                  <b>{{ u.email }}</b><span v-if="u.name" class="muted"> · {{ u.name }}</span>
                  <span v-if="u.role === 'admin'" class="cam-chip">admin</span>
                  <span v-if="u.locked_at" class="cam-chip bad">khoá</span>
                  <span v-if="u.google" class="cam-chip muted">Google</span>
                </td>
                <td><span v-if="u.member" class="cam-chip ok">Có</span><span v-else class="muted">—</span></td>
                <td>{{ u.devices }}</td>
                <td>{{ when(u.last_seen) }}</td>
                <td>{{ day(u.created_at) }}</td>
              </tr>
              <tr v-if="!users.length"><td colspan="5" class="muted">Không có ai.</td></tr>
            </tbody>
          </table>
        </div>
      </section>

      <!-- grants -->
      <section v-if="tab === 'grants'" class="panel lg">
        <h2 class="lg-display">Cấp quyền bằng tay</h2>
        <p class="hint">Email chưa có tài khoản vẫn cấp được: quyền tự áp dụng khi người đó đăng ký hoặc đăng nhập Google bằng email này.</p>
        <form class="grant-form" @submit.prevent="grant">
          <label>Email<input v-model="form.email" type="email" required placeholder="ten@email.com" /></label>
          <label>Loại
            <select v-model="form.kind">
              <option value="member">Thành viên</option>
              <option value="early">Truy cập sớm một bản</option>
              <option value="tester">Beta tester (mọi bản ngay, cả beta)</option>
            </select>
          </label>
          <label v-if="form.kind === 'early'">Bản
            <select v-model="form.tag" required>
              <option v-for="r in releases" :key="r.tag" :value="r.tag">{{ r.version }}{{ r.open ? ' (đã mở)' : '' }}</option>
            </select>
          </label>
          <label v-else>Hết hạn (bỏ trống = vĩnh viễn)<input v-model="form.expires" type="date" /></label>
          <label class="wide">Ghi chú<input v-model="form.note" maxlength="200" placeholder="bạn bè, trả tiền mặt, beta tester…" /></label>
          <button class="lg lg-btn lg-tinted lg-press" type="submit" :disabled="busy">Cấp</button>
        </form>
      </section>
      <section v-if="tab === 'grants'" class="panel lg">
        <form class="bar" @submit.prevent="run(() => load('grants'))">
          <input v-model="grantQ" type="search" placeholder="Tìm email, ghi chú, bản…" />
          <label class="check"><input v-model="showRevoked" type="checkbox" @change="run(() => load('grants'))" /> Hiện cả đã thu hồi</label>
        </form>
        <div class="table-wrap">
          <table>
            <thead><tr><th>Email</th><th>Loại</th><th>Ghi chú</th><th>Hạn</th><th>Bởi</th><th>Trạng thái</th><th /></tr></thead>
            <tbody>
              <tr v-for="g in grants" :key="g.id">
                <td>
                  <a v-if="g.user_id" href="#" @click.prevent="openUser(g.user_id)">{{ g.email }}</a>
                  <span v-else>{{ g.email === '*' ? 'mọi thành viên' : g.email }} <span v-if="g.email !== '*'" class="cam-chip muted">chưa đăng ký</span></span>
                </td>
                <td>{{ KIND[g.kind] }}<span v-if="g.tag" class="cam-mono"> {{ g.tag.replace('cam-v', '') }}</span></td>
                <td>{{ g.note || '—' }}</td>
                <td>{{ g.expires_at ? day(g.expires_at) : 'vĩnh viễn' }}</td>
                <td class="muted">{{ g.by }}</td>
                <td><span class="cam-chip" :class="grantState(g)[1]">{{ grantState(g)[0] }}</span></td>
                <td class="right"><button v-if="!g.revoked_at" class="mini bad" @click="revoke(g)">Thu hồi</button></td>
              </tr>
              <tr v-if="!grants.length"><td colspan="7" class="muted">Không có.</td></tr>
            </tbody>
          </table>
        </div>
      </section>

      <!-- orders -->
      <section v-if="tab === 'orders'" class="panel lg">
        <div class="bar">
          <div class="lg-seg">
            <button v-for="s in ['pending', 'paid', 'expired', 'refunded', 'all']" :key="s" :class="{ on: orderStatus === s }" @click="orderStatus = s; run(() => load('orders'))">
              {{ s === 'all' ? 'Tất cả' : STATUS[s] }}
            </button>
          </div>
        </div>
        <div class="table-wrap">
          <table>
            <thead><tr><th>Mã</th><th>Email</th><th>Gói</th><th>Số tiền</th><th>Đã nhận</th><th>Tạo</th><th>Trạng thái</th><th>Ghi chú</th><th /></tr></thead>
            <tbody>
              <tr v-for="o in orders" :key="o.code">
                <td class="cam-mono">{{ o.code }}</td>
                <td><a href="#" @click.prevent="openUser(o.user_id)">{{ o.email }}</a></td>
                <td>{{ KIND[o.kind] }}<span v-if="o.tag" class="cam-mono"> {{ o.tag.replace('cam-v', '') }}</span></td>
                <td>{{ vnd(o.amount) }}</td>
                <td :class="{ warnc: o.received && o.received !== o.amount }">{{ vnd(o.received || 0) }}</td>
                <td>{{ when(o.created_at) }}</td>
                <td><span class="cam-chip" :class="o.status === 'paid' ? 'ok' : 'muted'">{{ STATUS[o.status] }}</span></td>
                <td class="note" @click="noteOrder(o)">{{ o.note || '＋' }}</td>
                <td class="right">
                  <button v-if="o.status === 'pending' || o.status === 'expired'" class="mini" @click="markPaid(o)">Đã nhận tiền</button>
                  <button v-if="o.status === 'paid'" class="mini bad" @click="refund(o)">Hoàn tiền</button>
                </td>
              </tr>
              <tr v-if="!orders.length"><td colspan="9" class="muted">Không có đơn.</td></tr>
            </tbody>
          </table>
        </div>
      </section>

      <!-- payments ledger -->
      <section v-if="tab === 'payments'" class="panel lg">
        <div class="bar">
          <div class="lg-seg">
            <button v-for="s in ['unmatched', 'underpaid', 'duplicate', 'matched', 'ignored', 'all']" :key="s" :class="{ on: payStatus === s }" @click="payStatus = s; run(() => load('payments'))">
              {{ s === 'all' ? 'Tất cả' : PAY[s] }}
            </button>
          </div>
          <button class="mini" @click="reconcileNow">Đối soát ngay</button>
        </div>
        <p class="hint">Mọi khoản tiền vào tài khoản đều ghi ở đây và không bao giờ bị xoá. "Không khớp": người mua ghi sai nội dung, gán tay vào đúng đơn.</p>
        <div class="table-wrap">
          <table>
            <thead><tr><th>Lúc</th><th>Số tiền</th><th>Nội dung</th><th>Đơn</th><th>Trạng thái</th><th>Ghi chú</th><th /></tr></thead>
            <tbody>
              <tr v-for="p in payments" :key="p.id">
                <td>{{ p.paid_at || when(p.created_at) }}</td>
                <td><b>{{ vnd(p.amount) }}</b></td>
                <td class="detail">{{ p.content }}</td>
                <td><span v-if="p.order_code" class="cam-mono">{{ p.order_code }}</span><span v-if="p.email" class="muted"> · {{ p.email }}</span></td>
                <td><span class="cam-chip" :class="p.status === 'matched' ? 'ok' : p.status === 'ignored' ? 'muted' : 'bad'">{{ PAY[p.status] ?? p.status }}</span></td>
                <td class="muted detail">{{ p.note }}</td>
                <td class="right">
                  <template v-if="p.status === 'unmatched' || p.status === 'ignored'">
                    <button class="mini" @click="assignPayment(p)">Gán vào đơn</button>
                    <button v-if="p.status === 'unmatched'" class="mini" @click="ignorePayment(p)">Bỏ qua</button>
                  </template>
                </td>
              </tr>
              <tr v-if="!payments.length"><td colspan="7" class="muted">Không có giao dịch.</td></tr>
            </tbody>
          </table>
        </div>
      </section>

      <!-- settings -->
      <section v-if="tab === 'settings'" class="panel lg">
        <h2 class="lg-display">Cài đặt kinh doanh</h2>
        <p class="hint">Áp dụng ngay cho đơn mới. Đơn đã tạo giữ nguyên giá lúc tạo.</p>
        <form class="grant-form" @submit.prevent="saveSettings">
          <label>Giá thành viên (đ)<input v-model="settings.price_member" type="number" min="1000" step="1000" required /></label>
          <label>Giá truy cập sớm (đ)<input v-model="settings.price_early" type="number" min="1000" step="1000" required /></label>
          <label>Số ngày chờ mở cho thành viên<input v-model="settings.embargo_days" type="number" min="0" max="365" required /></label>
          <label>Đơn hết hạn sau (giờ)<input v-model="settings.order_hours" type="number" min="1" max="336" required /></label>
          <label class="check"><input v-model="settings.sales_open" type="checkbox" /> Đang bán thành viên</label>
          <label class="check"><input v-model="settings.early_open" type="checkbox" /> Đang bán truy cập sớm</label>
          <label class="wide">Thông báo hiện trên trang thành viên (để trống = không hiện)<input v-model="settings.notice" maxlength="300" /></label>
          <button class="lg lg-btn lg-tinted lg-press" type="submit" :disabled="busy">Lưu</button>
        </form>
      </section>

      <!-- beta testers -->
      <section v-if="tab === 'testers'" class="panel lg">
        <h2 class="lg-display">Beta tester</h2>
        <p class="hint">Thành viên bật "Nhận bản thử nghiệm" trong app (hoặc bấm trên web) để xin. Tester nhận mọi bản ngay khi ra, cả bản beta. Thu hồi ở thẻ Cấp quyền.</p>
        <div class="table-wrap">
          <table>
            <thead><tr><th>Email</th><th>Thành viên</th><th>Thiết bị</th><th>Gửi lúc</th><th>Trạng thái</th><th>Ghi chú</th><th /></tr></thead>
            <tbody>
              <tr v-for="x in testers" :key="x.id">
                <td><a href="#" @click.prevent="openUser(x.user_id)">{{ x.email }}</a></td>
                <td><span v-if="x.member" class="cam-chip ok">Có</span><span v-else class="cam-chip bad">Chưa</span></td>
                <td>{{ x.device_name || 'web' }}</td>
                <td>{{ when(x.created_at) }}</td>
                <td>
                  <span v-if="x.tester" class="cam-chip ok">Đang là tester</span>
                  <span v-else class="cam-chip" :class="x.status === 'rejected' ? 'bad' : 'muted'">{{ STATUS[x.status] }}</span>
                </td>
                <td class="muted detail">{{ x.review_note || x.note || '' }}</td>
                <td class="right">
                  <template v-if="x.status === 'pending'">
                    <button class="mini" @click="reviewTester(x, 'approved')">Duyệt</button>
                    <button class="mini bad" @click="reviewTester(x, 'rejected')">Từ chối</button>
                  </template>
                </td>
              </tr>
              <tr v-if="!testers.length"><td colspan="7" class="muted">Chưa có ai xin.</td></tr>
            </tbody>
          </table>
        </div>
      </section>

      <!-- source requests -->
      <section v-if="tab === 'requests'" class="panel lg">
        <p class="hint">Duyệt xong thì mời tài khoản GitHub của họ vào repo với quyền Read.</p>
        <div class="reqs">
          <article v-for="r in requests" :key="r.id" class="req">
            <header>
              <b>{{ r.email || r.name || '—' }}</b>
              <span class="cam-chip" :class="r.status === 'approved' ? 'ok' : r.status === 'rejected' ? 'bad' : 'muted'">{{ STATUS[r.status] }}</span>
              <span class="muted">{{ when(r.created_at) }}</span>
            </header>
            <p class="meta">
              GitHub: <a :href="`https://github.com/${r.github}`" target="_blank" rel="noreferrer">{{ r.github }}</a> · Liên lạc: {{ r.contact }}
            </p>
            <p class="reason">{{ r.reason }}</p>
            <p v-if="r.note" class="muted">Ghi chú: {{ r.note }}</p>
            <div class="btns">
              <button class="mini" @click="review(r, 'approved')">Duyệt</button>
              <button class="mini bad" @click="review(r, 'rejected')">Từ chối</button>
              <button v-if="r.status !== 'pending'" class="mini" @click="review(r, 'pending')">Chờ lại</button>
            </div>
          </article>
          <p v-if="!requests.length" class="muted">Chưa có yêu cầu.</p>
        </div>
      </section>

      <!-- audit -->
      <section v-if="tab === 'audit'" class="panel lg">
        <div class="table-wrap">
          <table>
            <thead><tr><th>Lúc</th><th>Ai</th><th>Việc</th><th>Đối tượng</th><th>Chi tiết</th></tr></thead>
            <tbody>
              <tr v-for="a in auditLog" :key="a.id">
                <td>{{ when(a.at) }}</td>
                <td class="muted">{{ a.admin_email }}</td>
                <td>{{ ACTIONS[a.action] ?? a.action }}</td>
                <td>{{ a.target }}</td>
                <td class="muted detail">{{ a.detail }}</td>
              </tr>
              <tr v-if="!auditLog.length"><td colspan="5" class="muted">Trống.</td></tr>
            </tbody>
          </table>
        </div>
      </section>
    </template>

    <!-- one person -->
    <Transition name="sheet">
      <div v-if="detail" class="veil" @click.self="detail = null">
        <aside class="sheet lg lg-dialog" role="dialog" aria-modal="true">
          <header class="sheet-head">
            <div>
              <h2 class="lg-display">{{ detail.user.email }}</h2>
              <p class="muted">
                {{ detail.user.name || 'không tên' }} · tạo {{ day(detail.user.created_at) }}
                · {{ detail.user.password ? 'mật khẩu' : '' }}{{ detail.user.password && detail.user.google ? ' + ' : '' }}{{ detail.user.google ? 'Google' : '' }}
              </p>
            </div>
            <button class="close" aria-label="Đóng" @click="detail = null">✕</button>
          </header>

          <div class="chips">
            <span v-if="detail.member" class="cam-chip ok">Thành viên từ {{ day(detail.member.since) }}{{ detail.member.expires_at ? ` · hết hạn ${day(detail.member.expires_at)}` : '' }}</span>
            <span v-else class="cam-chip muted">Chưa là thành viên</span>
            <span v-if="detail.user.role === 'admin'" class="cam-chip">admin</span>
            <span v-if="detail.user.locked_at" class="cam-chip bad">Khoá từ {{ day(detail.user.locked_at) }}</span>
          </div>

          <h3>Cấp thành viên</h3>
          <form class="inline" @submit.prevent="grantMember">
            <input v-model="memberForm.note" placeholder="Ghi chú" maxlength="200" />
            <input v-model="memberForm.expires" type="date" title="Hết hạn (bỏ trống = vĩnh viễn)" />
            <button class="mini" type="submit" :disabled="busy">Cấp</button>
          </form>

          <h3>Tặng truy cập sớm</h3>
          <form v-if="lockedReleases.length" class="inline" @submit.prevent="grantEarly">
            <select v-model="earlyTag">
              <option v-for="r in lockedReleases" :key="r.tag" :value="r.tag">{{ r.version }} (mở {{ day(r.open_at) }})</option>
            </select>
            <button class="mini" type="submit" :disabled="busy">Tặng</button>
          </form>
          <p v-else class="muted">Mọi bản đều đã mở.</p>

          <h3>Quyền đã cấp</h3>
          <ul class="list">
            <li v-for="g in detail.grants" :key="g.id">
              <span>{{ KIND[g.kind] }}<span v-if="g.tag" class="cam-mono"> {{ g.tag.replace('cam-v', '') }}</span> · {{ g.note || '—' }} · {{ day(g.created_at) }}</span>
              <span class="cam-chip" :class="grantState(g)[1]">{{ grantState(g)[0] }}</span>
              <button v-if="!g.revoked_at" class="mini bad" @click="revoke(g)">Thu hồi</button>
            </li>
            <li v-if="!detail.grants.length" class="muted">Chưa có.</li>
          </ul>

          <h3>Thiết bị và phiên</h3>
          <ul class="list">
            <li v-for="s in detail.sessions" :key="s.id">
              <span>{{ s.kind === 'device' ? '📱 ' + (s.name || 'thiết bị') : '🌐 trình duyệt' }} · dùng {{ when(s.last_seen) }}</span>
              <button class="mini bad" @click="unlink(s)">Gỡ</button>
            </li>
            <li v-if="!detail.sessions.length" class="muted">Không có.</li>
          </ul>

          <h3>Đơn hàng</h3>
          <ul class="list">
            <li v-for="o in detail.orders" :key="o.code">
              <span class="cam-mono">{{ o.code }}</span>
              <span>{{ KIND[o.kind] }} · {{ vnd(o.amount) }} · {{ day(o.created_at) }}</span>
              <span class="cam-chip" :class="o.status === 'paid' ? 'ok' : 'muted'">{{ STATUS[o.status] }}</span>
            </li>
            <li v-if="!detail.orders.length" class="muted">Chưa có.</li>
          </ul>

          <h3>Tài khoản</h3>
          <div class="btns">
            <button v-if="!detail.user.locked_at" class="mini bad" @click="lock(true)">Khoá tài khoản</button>
            <button v-else class="mini" @click="lock(false)">Mở khoá</button>
            <button v-if="detail.user.totp" class="mini" @click="resetTotp">Tắt 2FA (mất điện thoại)</button>
            <button v-if="detail.user.role !== 'admin'" class="mini" @click="setRole('admin')">Cho làm admin</button>
            <button v-else class="mini" @click="setRole('user')">Bỏ admin</button>
          </div>
        </aside>
      </div>
    </Transition>

    <Transition name="toast">
      <div v-if="toast" class="toast lg" :class="{ bad: toast.bad }">{{ toast.text }}</div>
    </Transition>
  </div>
</template>

<style scoped>
.adm { max-width: 1180px; }
.adm-head { display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: 14px; margin-bottom: 22px; }
.adm-head h1 { font-size: clamp(34px, 6vw, 50px); font-weight: 800; margin: 0; letter-spacing: -0.03em; }
.tabs { flex-wrap: wrap; }
.tabs button { position: relative; }
.dot-count {
  display: inline-grid;
  place-items: center;
  min-width: 18px;
  height: 18px;
  margin-left: 6px;
  padding: 0 5px;
  border-radius: 999px;
  font-size: 11px;
  color: #fff;
  background: #ef4444;
}

.panel { --lg-r: 26px; padding: 22px; margin-bottom: 18px; }
.panel h2 { font-size: 24px; font-weight: 800; margin: 0 0 6px; }
.hint { color: var(--vp-c-text-2); font-size: 14px; margin: 0 0 14px; }

.grid-stats {
  display: grid;
  gap: 14px;
  grid-template-columns: repeat(auto-fill, minmax(min(100%, 170px), 1fr));
  margin-bottom: 18px;
}
.stat { --lg-r: 22px; padding: 16px 18px; text-align: left; display: flex; flex-direction: column; gap: 4px; color: var(--vp-c-text-1); }
.stat small { color: var(--vp-c-text-2); font-size: 13px; }
.stat b { font-size: 28px; font-weight: 800; line-height: 1.1; }

.bar { display: flex; flex-wrap: wrap; gap: 10px; align-items: center; margin-bottom: 14px; }
.bar input[type='search'] { flex: 1 1 240px; }
input,
select {
  padding: 9px 14px;
  border-radius: 14px;
  font-size: 14.5px;
  color: var(--vp-c-text-1);
  background: rgba(var(--lg-surface), 0.55);
  box-shadow: inset 0 1px 3px rgba(0, 0, 0, 0.1), inset 0 0 0 1px rgba(255, 255, 255, var(--lg-rim-base));
  font-family: inherit;
}
.dark input,
.dark select { background: rgba(0, 0, 0, 0.25); }
input:focus,
select:focus { outline: none; box-shadow: 0 0 0 2px rgba(var(--lg-accent), 0.6); }
.check { display: flex; align-items: center; gap: 6px; font-size: 14px; color: var(--vp-c-text-2); }
.check input { width: auto; padding: 0; box-shadow: none; }

.table-wrap { overflow-x: auto; margin: 0 -6px; }
table { width: 100%; border-collapse: collapse; font-size: 14px; }
th { text-align: left; font-size: 12px; font-weight: 700; letter-spacing: 0.04em; text-transform: uppercase; color: var(--vp-c-text-3); padding: 8px 10px; white-space: nowrap; }
td { padding: 10px; border-top: 1px solid var(--vp-c-divider); vertical-align: middle; }
td .cam-chip { margin-left: 4px; }
tr.click { cursor: pointer; }
tr.click:hover td { background: rgba(var(--lg-accent), 0.06); }
.right { text-align: right; white-space: nowrap; }
.muted { color: var(--vp-c-text-3); }
.note { cursor: pointer; color: var(--vp-c-text-2); max-width: 220px; }
.detail { max-width: 320px; overflow-wrap: anywhere; font-size: 12.5px; }
a { color: rgb(var(--lg-accent)); text-decoration: none; }
a:hover { text-decoration: underline; }

.health { list-style: none; margin: 0; padding: 0; display: grid; gap: 6px 18px; grid-template-columns: repeat(auto-fill, minmax(min(100%, 240px), 1fr)); font-size: 14px; }
.health .on { color: #16a34a; }
.health .off { color: var(--vp-c-text-3); }
.warn { color: #b45309; font-size: 14px; margin: 0 0 10px; }
.warnc { color: #b45309; font-weight: 600; }
.stat.alert b { color: #dc2626; }
.mfa { display: flex; flex-wrap: wrap; align-items: center; gap: 10px; }
.mfa p { flex-basis: 100%; margin: 0; }
.mfa input { width: 140px; text-align: center; letter-spacing: 0.2em; }
.cam-chip.bad { color: #dc2626; background: rgba(239, 68, 68, 0.14); box-shadow: none; }
.dark .cam-chip.bad { color: #f87171; }

.mini {
  padding: 6px 12px;
  border-radius: 999px;
  font-size: 13px;
  font-weight: 600;
  color: var(--vp-c-text-1);
  background: rgba(var(--lg-surface), 0.6);
  box-shadow: inset 0 1px 1px var(--lg-bloom), inset 0 0 0 1px rgba(255, 255, 255, var(--lg-rim-base)), 0 3px 10px -5px rgba(0, 0, 0, 0.3);
  transition: transform 0.15s ease, background-color 0.2s ease;
  white-space: nowrap;
}
.mini:hover { background: rgba(var(--lg-accent), 0.14); }
.mini:active { transform: scale(0.95); }
.mini.bad { color: #dc2626; }
.dark .mini.bad { color: #f87171; }
.mini:disabled { opacity: 0.5; }

.grant-form { display: grid; gap: 12px; grid-template-columns: repeat(auto-fill, minmax(min(100%, 220px), 1fr)); align-items: end; }
.grant-form label { display: flex; flex-direction: column; gap: 6px; font-size: 13px; font-weight: 600; color: var(--vp-c-text-2); }
.grant-form .wide { grid-column: 1 / -1; }
.grant-form .check { flex-direction: row; align-items: center; align-self: center; }
.grant-form button { justify-self: start; }

.reqs { display: grid; gap: 12px; }
.req { padding: 14px 16px; border-radius: 18px; background: rgba(var(--lg-surface), 0.35); box-shadow: inset 0 0 0 1px rgba(255, 255, 255, var(--lg-rim-base)); }
.req header { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; }
.req .meta { margin: 6px 0; font-size: 14px; color: var(--vp-c-text-2); }
.req .reason { margin: 0 0 8px; white-space: pre-wrap; }
.btns { display: flex; flex-wrap: wrap; gap: 8px; }

/* detail sheet */
.veil {
  position: fixed;
  inset: 0;
  z-index: 90;
  display: flex;
  justify-content: flex-end;
  background: rgba(0, 0, 0, 0.3);
  -webkit-backdrop-filter: blur(4px);
  backdrop-filter: blur(4px);
}
.sheet {
  width: min(520px, 100%);
  height: 100%;
  overflow-y: auto;
  padding: 24px 22px 40px;
  --lg-r: 28px 0 0 28px;
  border-radius: 28px 0 0 28px;
}
.sheet-head { display: flex; justify-content: space-between; gap: 12px; }
.sheet-head h2 { font-size: 22px; font-weight: 800; margin: 0; overflow-wrap: anywhere; }
.sheet-head p { margin: 4px 0 0; font-size: 13.5px; }
.close { width: 36px; height: 36px; border-radius: 50%; flex: none; background: rgba(var(--lg-ink), 0.07); font-size: 15px; }
.sheet h3 { font-size: 13px; font-weight: 700; letter-spacing: 0.05em; text-transform: uppercase; color: var(--vp-c-text-3); margin: 22px 0 8px; }
.chips { display: flex; flex-wrap: wrap; gap: 6px; margin-top: 14px; }
.inline { display: flex; flex-wrap: wrap; gap: 8px; }
.inline input,
.inline select { flex: 1 1 140px; }
.list { list-style: none; margin: 0; padding: 0; }
.list li { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; padding: 8px 0; border-top: 1px solid var(--vp-c-divider); font-size: 14px; }
.list li > span:first-child { flex: 1; }
.sheet-enter-active,
.sheet-leave-active { transition: opacity 0.25s ease; }
.sheet-enter-active .sheet,
.sheet-leave-active .sheet { transition: transform 0.35s cubic-bezier(0.2, 0.9, 0.3, 1); }
.sheet-enter-from,
.sheet-leave-to { opacity: 0; }
.sheet-enter-from .sheet,
.sheet-leave-to .sheet { transform: translateX(40px); }

.toast {
  position: fixed;
  left: 50%;
  bottom: 24px;
  z-index: 120;
  transform: translateX(-50%);
  padding: 12px 20px;
  --lg-r: 999px;
  --lg-tint: rgba(var(--lg-surface), 0.85);
  font-weight: 600;
  font-size: 14.5px;
}
.toast.bad { color: #dc2626; }
.toast-enter-active,
.toast-leave-active { transition: opacity 0.2s ease, transform 0.3s ease; }
.toast-enter-from,
.toast-leave-to { opacity: 0; transform: translate(-50%, 12px); }
</style>

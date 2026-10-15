<script setup lang="ts">
// /thanh-vien: the signed-in account. Membership and buying it, releases (open now, opening on a
// date, or early access to buy), devices, orders, password and two-step sign-in.
// Prices and rules come from /api/me; buying creates an order on the server and goes to /mua.
import { computed, onMounted, ref } from 'vue'
import qrcode from 'qrcode-generator'
import { ApiError, api, day, errorText, vnd, type Lang } from '../api'

const props = defineProps<{ lang: Lang }>()

const T = {
  vi: {
    title: 'Thành viên.', member: 'Thành viên', since: 'từ', until: 'tới', forever: 'vĩnh viễn', notMember: 'Chưa là thành viên',
    join: (p: string, d: number) => `Tham gia ${p}: tải app, nhận mọi bản sau ${d} ngày, xin mã nguồn.`, joinBtn: (p: string) => `Tham gia · ${p}`,
    closed: 'Tạm ngừng bán.', payOff: 'Thanh toán sẽ mở khi ra mắt.',
    releases: 'Bản phát hành', open: 'Tải được', opensOn: 'Mở ngày', urgent: 'gấp', early: 'vé sớm', buyEarly: (p: string) => `Nhận ngay · ${p}`,
    devices: 'Thiết bị', noDevices: 'Chưa liên kết thiết bị nào. Mở SU Kernel → Cài đặt → Tài khoản.', unlink: 'Gỡ', lastSeen: 'dùng', addDevice: 'Kết nối thiết bị',
    orders: 'Đơn hàng', noOrders: 'Chưa có đơn.', pay: 'Thanh toán', status: { pending: 'Chờ thanh toán', paid: 'Đã trả', expired: 'Hết hạn', refunded: 'Đã hoàn' },
    security: 'Bảo mật', current: 'Mật khẩu hiện tại', newPass: 'Mật khẩu mới', change: 'Đổi mật khẩu', changed: 'Đã đổi mật khẩu.',
    twoStep: 'Xác thực 2 bước', twoStepOn: 'Đang bật', twoStepOff: 'Đang tắt', twoStepLead: 'Mỗi lần đăng nhập cần thêm mã 6 số từ app Google Authenticator, Authy, 2FAS…',
    setup: 'Bật xác thực 2 bước', scanKey: 'Mở Google Authenticator (hoặc Authy, 2FAS…) → thêm → quét mã QR này. Không quét được thì nhập khoá bên dưới.', keyLabel: 'Khoá', copyKey: 'Chép khoá', copied: 'Đã chép', openApp: 'Đang dùng điện thoại? Mở thẳng app xác thực', step2: 'Nhập mã 6 số app vừa hiện để bật', totpHint: 'Mã đổi mỗi 30 giây. Nếu app có nhiều mục Cam Kernel SU, xoá mục cũ và quét lại mã QR ở trên.', enterCode: 'Mã 6 số', enable: 'Xác nhận và bật',
    disable: 'Tắt', disableCode: 'Nhập mã để tắt', adminWarn: 'Tài khoản admin nên bật xác thực 2 bước.',
    verifyNote: 'Email chưa được xác minh: quyền Cam cấp cho email này chỉ có hiệu lực sau khi bạn mở liên kết trong thư xác minh.', verifyBtn: 'Gửi lại thư xác minh', verifySent: 'Đã gửi, kiểm tra hộp thư (cả mục Spam).', verifyDone: 'Đã xác minh email.',
    beta: 'Beta tester', betaLead: 'Nhận mọi bản mới ngay khi ra, cả bản thử nghiệm. Cần được admin duyệt.', betaAsk: 'Xin làm beta tester', betaState: { none: 'Chưa xin', pending: 'Đang chờ duyệt', approved: 'Bạn là beta tester', rejected: 'Chưa được duyệt' },
    logout: 'Đăng xuất', admin: 'Quản trị', signin: '/tai-khoan?next=/thanh-vien', buy: '/mua', connect: '/ket-noi',
    kind: { member: 'Thành viên', early: 'Vé sớm' },
  },
  en: {
    title: 'Member.', member: 'Member', since: 'since', until: 'until', forever: 'forever', notMember: 'Not a member yet',
    join: (p: string, d: number) => `Join for ${p}: download the app, every release ${d} days after it ships, source requests.`, joinBtn: (p: string) => `Join · ${p}`,
    closed: 'Sales are paused.', payOff: 'Payments open at launch.',
    releases: 'Releases', open: 'Available', opensOn: 'Opens', urgent: 'urgent', early: 'early', buyEarly: (p: string) => `Get it now · ${p}`,
    devices: 'Devices', noDevices: 'No device linked. Open SU Kernel → Settings → Account.', unlink: 'Unlink', lastSeen: 'used', addDevice: 'Link a device',
    orders: 'Orders', noOrders: 'No orders.', pay: 'Pay', status: { pending: 'Awaiting payment', paid: 'Paid', expired: 'Expired', refunded: 'Refunded' },
    security: 'Security', current: 'Current password', newPass: 'New password', change: 'Change password', changed: 'Password changed.',
    twoStep: 'Two-step sign-in', twoStepOn: 'On', twoStepOff: 'Off', twoStepLead: 'Each sign-in also asks for a 6-digit code from Google Authenticator, Authy, 2FAS…',
    setup: 'Turn on two-step sign-in', scanKey: 'Open Google Authenticator (or Authy, 2FAS…) → add → scan this QR code. Cannot scan? Type the key below.', keyLabel: 'Key', copyKey: 'Copy key', copied: 'Copied', openApp: 'On this phone? Open your authenticator app directly', step2: 'Enter the 6-digit code it shows to turn it on', totpHint: 'The code changes every 30 seconds. If the app lists Cam Kernel SU more than once, delete the old entries and scan the QR above again.', enterCode: '6-digit code', enable: 'Confirm and turn on',
    disable: 'Turn off', disableCode: 'Code to turn off', adminWarn: 'Admin accounts should use two-step sign-in.',
    verifyNote: 'Your email is not verified: what Cam granted to this address applies once you open the link in the verification mail.', verifyBtn: 'Send the mail again', verifySent: 'Sent. Check your inbox (and spam).', verifyDone: 'Email verified.',
    beta: 'Beta tester', betaLead: 'Get every release as soon as it ships, betas included. Needs the admin\'s approval.', betaAsk: 'Ask to be a beta tester', betaState: { none: 'Not requested', pending: 'Waiting for approval', approved: 'You are a beta tester', rejected: 'Not approved' },
    logout: 'Sign out', admin: 'Admin', signin: '/en/account?next=/en/member', buy: '/en/buy', connect: '/en/connect',
    kind: { member: 'Membership', early: 'Early access' },
  },
  zh: {
    title: '会员。', member: '会员', since: '自', until: '至', forever: '永久', notMember: '尚未成为会员',
    join: (p: string, d: number) => `${p} 加入：下载应用，每个版本发布 ${d} 天后免费获取，可申请源码。`, joinBtn: (p: string) => `加入 · ${p}`,
    closed: '暂停销售。', payOff: '付款将在正式发布时开放。',
    releases: '版本', open: '可下载', opensOn: '开放于', urgent: '紧急', early: '抢先', buyEarly: (p: string) => `立即获取 · ${p}`,
    devices: '设备', noDevices: '尚未关联设备。打开 SU Kernel → 设置 → 账号。', unlink: '移除', lastSeen: '使用于', addDevice: '关联设备',
    orders: '订单', noOrders: '暂无订单。', pay: '付款', status: { pending: '等待付款', paid: '已付款', expired: '已过期', refunded: '已退款' },
    security: '安全', current: '当前密码', newPass: '新密码', change: '修改密码', changed: '密码已修改。',
    twoStep: '两步验证', twoStepOn: '已开启', twoStepOff: '未开启', twoStepLead: '每次登录还需输入身份验证器（Google Authenticator、Authy、2FAS…）中的 6 位代码。',
    setup: '开启两步验证', scanKey: '打开 Google Authenticator（或 Authy、2FAS…）→ 添加 → 扫描此二维码。无法扫描时请手动输入下方密钥。', keyLabel: '密钥', copyKey: '复制密钥', copied: '已复制', openApp: '正在用手机？直接打开身份验证器', step2: '输入应用显示的 6 位代码以开启', totpHint: '代码每 30 秒更换一次。若应用中有多个 Cam Kernel SU 条目，请删除旧条目并重新扫描上方二维码。', enterCode: '6 位代码', enable: '确认并开启',
    disable: '关闭', disableCode: '输入代码以关闭', adminWarn: '管理员账号应开启两步验证。',
    verifyNote: '邮箱尚未验证：Cam 授予该邮箱的权限需在打开验证邮件中的链接后才生效。', verifyBtn: '重新发送验证邮件', verifySent: '已发送，请查看收件箱（含垃圾邮件）。', verifyDone: '邮箱已验证。',
    beta: '测试者', betaLead: '新版本一发布即可获取，包括测试版。需要管理员批准。', betaAsk: '申请成为测试者', betaState: { none: '未申请', pending: '等待批准', approved: '你是测试者', rejected: '未获批准' },
    logout: '退出登录', admin: '管理', signin: '/zh/account?next=/zh/member', buy: '/zh/buy', connect: '/zh/connect',
    kind: { member: '会员', early: '抢先体验' },
  },
}
const t = computed(() => T[props.lang])

const me = ref<any>(null)
const err = ref('')
const msg = ref('')
const cur = ref('')
const pw = ref('')
const totp = ref<{ secret: string; uri: string } | null>(null)
const totpCode = ref('')
const keyCopied = ref(false)

// The QR is drawn here in the browser: the secret never goes to an outside QR service.
const totpQr = computed(() => {
  if (!totp.value) return ''
  const q = qrcode(0, 'M')
  q.addData(totp.value.uri)
  q.make()
  return q.createSvgTag({ cellSize: 5, margin: 0, scalable: true })
})

async function copyKey() {
  try {
    await navigator.clipboard.writeText(totp.value!.secret)
    keyCopied.value = true
    setTimeout(() => (keyCopied.value = false), 1500)
  } catch {}
}
const busy = ref(false)

async function load() {
  try {
    me.value = await api('GET', '/api/me')
  } catch (e) {
    if (e instanceof ApiError && e.status === 401) location.href = t.value.signin
    else err.value = errorText(e, props.lang)
  }
}

// Where the last error belongs: the page top, or next to the two-step form the person is looking at.
const errAt = ref<'top' | 'totp'>('top')
const badTotp = ref(false)

async function act(fn: () => Promise<unknown>, at: 'top' | 'totp' = 'top') {
  err.value = msg.value = ''
  badTotp.value = false
  errAt.value = at
  busy.value = true
  try {
    await fn()
  } catch (e) {
    err.value = errorText(e, props.lang)
    badTotp.value = e instanceof ApiError && e.code === 'bad_totp'
  } finally {
    busy.value = false
  }
}

const buy = (kind: 'member' | 'early', tag?: string) =>
  act(async () => {
    const o = await api<any>('POST', '/api/orders', { kind, tag })
    location.href = `${t.value.buy}?code=${o.code}`
  })

const unlink = (id: string) => act(async () => (await api('DELETE', `/api/devices/${id}`), await load()))

const changePassword = () =>
  act(async () => {
    await api('POST', '/api/auth/password', { current: cur.value, password: pw.value })
    cur.value = pw.value = ''
    msg.value = t.value.changed
  })

const askBeta = () => act(async () => (await api('POST', '/api/tester/request', {}), await load()))
const resendVerify = () =>
  act(async () => {
    const r = await api<{ verify: string }>('POST', '/api/auth/verify/resend', {})
    if (r.verify === 'done') await load()
    else if (r.verify === 'sent') msg.value = t.value.verifySent
    else throw new ApiError(503, 'mail_off')
  })
const startTotp = () => act(async () => (totp.value = await api('POST', '/api/auth/totp/setup', {})))
const enableTotp = () =>
  act(async () => {
    await api('POST', '/api/auth/totp/enable', { code: totpCode.value })
    totp.value = null
    totpCode.value = ''
    await load()
  }, 'totp')
const disableTotp = () =>
  act(async () => {
    await api('POST', '/api/auth/totp/disable', { code: totpCode.value })
    totpCode.value = ''
    await load()
  }, 'totp')

async function logout() {
  await api('POST', '/api/auth/logout', {})
  location.href = props.lang === 'vi' ? '/' : `/${props.lang}/`
}

const canPay = computed(() => me.value?.features.pay && me.value?.sales_open)
onMounted(load)
</script>

<template>
  <div class="cam-page mem">
    <header class="cam-page-head">
      <h1>{{ t.title }}</h1>
      <p v-if="me">{{ me.user.email }}<a v-if="me.user.role === 'admin'" class="adm" href="/quan-tri"> · {{ t.admin }} →</a></p>
    </header>

    <p v-if="me?.notice" class="notice lg">{{ me.notice }}</p>
    <p v-if="err && errAt === 'top'" class="err">{{ err }}</p>
    <p v-if="me && !me.user.verified" class="warn">
      ⚠ {{ t.verifyNote }}
      <button class="lg lg-btn lg-press" :disabled="busy" @click="resendVerify">{{ t.verifyBtn }}</button>
    </p>

    <template v-if="me">
      <section class="status lg lg-dialog lg-in" data-lens="dialog">
        <template v-if="me.member">
          <span class="badge lg-display">✦ {{ t.member }}</span>
          <p>{{ t.since }} {{ day(me.member.since, lang) }} · {{ me.member.expires_at ? `${t.until} ${day(me.member.expires_at, lang)}` : t.forever }}</p>
        </template>
        <template v-else>
          <span class="badge muted lg-display">{{ t.notMember }}</span>
          <p>{{ t.join(vnd(me.prices.member), me.embargo_days) }}</p>
          <button v-if="canPay" class="lg lg-btn lg-tinted lg-press" :disabled="busy" @click="buy('member')">{{ t.joinBtn(vnd(me.prices.member)) }}</button>
          <p v-else class="soon">{{ me.features.pay ? t.closed : t.payOff }}</p>
        </template>
      </section>

      <section id="releases" class="panel lg lg-in">
        <h2 class="lg-display">{{ t.releases }}</h2>
        <ul class="rels">
          <li v-for="r in me.releases" :key="r.tag">
            <b class="cam-mono">{{ r.version }}</b>
            <span v-if="r.urgent" class="cam-chip">{{ t.urgent }}</span>
            <span v-if="r.early" class="cam-chip">{{ t.early }}</span>
            <span class="grow" />
            <span v-if="r.open" class="cam-chip ok">{{ t.open }}</span>
            <template v-else>
              <span class="muted">{{ t.opensOn }} {{ day(r.open_at, lang) }}</span>
              <button v-if="me.member && me.early_open && canPay" class="mini accent" :disabled="busy" @click="buy('early', r.tag)">{{ t.buyEarly(vnd(me.prices.early)) }}</button>
            </template>
          </li>
        </ul>
      </section>

      <section v-if="me.member" class="panel lg lg-in">
        <h2 class="lg-display">{{ t.beta }} · <span :class="me.tester.status === 'approved' ? 'ok' : 'muted'">{{ t.betaState[me.tester.status] }}</span></h2>
        <p class="muted small">{{ t.betaLead }}</p>
        <p v-if="me.tester.note && me.tester.status !== 'approved'" class="muted small">{{ me.tester.note }}</p>
        <button v-if="me.tester.status === 'none' || me.tester.status === 'rejected'" class="mini accent" :disabled="busy" @click="askBeta">{{ t.betaAsk }}</button>
      </section>

      <div class="two">
        <section class="panel lg lg-in">
          <h2 class="lg-display">{{ t.devices }}</h2>
          <ul v-if="me.devices.length" class="list">
            <li v-for="d in me.devices" :key="d.id">
              <span>📱 {{ d.name || '—' }} <small class="muted">· {{ t.lastSeen }} {{ day(d.last_seen, lang) }}</small></span>
              <button class="mini" :disabled="busy" @click="unlink(d.id)">{{ t.unlink }}</button>
            </li>
          </ul>
          <p v-else class="muted">{{ t.noDevices }}</p>
          <a class="mini link" :href="t.connect">{{ t.addDevice }} →</a>
        </section>

        <section class="panel lg lg-in">
          <h2 class="lg-display">{{ t.orders }}</h2>
          <ul v-if="me.orders.length" class="list">
            <li v-for="o in me.orders" :key="o.code">
              <span class="cam-mono">{{ o.code }}</span>
              <span>{{ t.kind[o.kind] }}<template v-if="o.tag"> {{ o.tag.replace('cam-v', '') }}</template> · {{ vnd(o.amount) }}</span>
              <a v-if="o.status === 'pending'" class="mini accent" :href="`${t.buy}?code=${o.code}`">{{ t.pay }}</a>
              <span v-else class="cam-chip" :class="o.status === 'paid' ? 'ok' : 'muted'">{{ t.status[o.status] }}</span>
            </li>
          </ul>
          <p v-else class="muted">{{ t.noOrders }}</p>
        </section>
      </div>

      <section class="panel lg lg-in">
        <h2 class="lg-display">{{ t.security }}</h2>
        <p v-if="me.user.role === 'admin' && !me.user.totp" class="warn">⚠ {{ t.adminWarn }}</p>

        <h3>{{ t.twoStep }} · <span :class="me.user.totp ? 'ok' : 'muted'">{{ me.user.totp ? t.twoStepOn : t.twoStepOff }}</span></h3>
        <p class="muted small">{{ t.twoStepLead }}</p>
        <template v-if="!me.user.totp">
          <button v-if="!totp" class="mini" :disabled="busy" @click="startTotp">{{ t.setup }}</button>
          <div v-else class="totp">
            <p class="small">① {{ t.scanKey }}</p>
            <div class="qr" role="img" :aria-label="t.scanKey" v-html="totpQr" />
            <div class="keyrow">
              <span class="muted small">{{ t.keyLabel }}</span>
              <code class="key">{{ totp.secret.match(/.{1,4}/g)?.join(' ') }}</code>
              <button class="mini" type="button" @click="copyKey">{{ keyCopied ? t.copied : t.copyKey }}</button>
            </div>
            <a class="small applink" :href="totp.uri">{{ t.openApp }} →</a>
            <p class="small">② {{ t.step2 }}</p>
            <form class="row" @submit.prevent="enableTotp">
              <input v-model="totpCode" inputmode="numeric" maxlength="6" :placeholder="t.enterCode" required />
              <button class="mini accent" type="submit" :disabled="busy">{{ t.enable }}</button>
            </form>
            <p v-if="err && errAt === 'totp'" class="err" role="alert">{{ err }}</p>
            <p v-if="badTotp" class="muted small">{{ t.totpHint }}</p>
          </div>
        </template>
        <form v-else class="row" @submit.prevent="disableTotp">
          <input v-model="totpCode" inputmode="numeric" maxlength="6" :placeholder="t.disableCode" required />
          <button class="mini" type="submit" :disabled="busy">{{ t.disable }}</button>
        </form>
        <p v-if="me.user.totp && err && errAt === 'totp'" class="err" role="alert">{{ err }}</p>

        <h3>{{ t.change }}</h3>
        <form class="row" @submit.prevent="changePassword">
          <input v-if="me.user.password" v-model="cur" type="password" autocomplete="current-password" :placeholder="t.current" required />
          <input v-model="pw" type="password" autocomplete="new-password" minlength="8" :placeholder="t.newPass" required />
          <button class="mini" type="submit" :disabled="busy">{{ t.change }}</button>
        </form>
        <p v-if="msg" class="okmsg">{{ msg }}</p>
        <button class="lg lg-btn lg-drop lg-press small logout" @click="logout">{{ t.logout }}</button>
      </section>
    </template>
  </div>
</template>

<style scoped>
.mem { max-width: 860px; }
.adm { color: rgb(var(--lg-accent)); text-decoration: none; font-weight: 600; }
.notice { --lg-r: 18px; padding: 12px 16px; margin: 0 0 16px; }
.status { padding: 26px; margin-bottom: 18px; display: flex; flex-direction: column; align-items: flex-start; gap: 8px; }
.status p { margin: 0; color: var(--vp-c-text-2); }
.badge { font-size: 28px; font-weight: 800; background: linear-gradient(110deg, rgb(var(--lg-accent)), #8b5cf6, rgb(var(--lg-accent-2))); -webkit-background-clip: text; background-clip: text; color: transparent; }
.badge.muted { background: none; color: var(--vp-c-text-2); }
.soon { font-size: 13.5px; }
.panel { --lg-r: 24px; padding: 20px 22px; margin-bottom: 16px; }
.panel h2 { font-size: 20px; font-weight: 800; margin: 0 0 10px; }
.panel h3 { font-size: 15px; font-weight: 700; margin: 18px 0 4px; }
.two { display: grid; gap: 0 16px; }
@media (min-width: 760px) {
  .two { grid-template-columns: 1fr 1fr; }
}
.rels,
.list { list-style: none; margin: 0; padding: 0; }
.rels li,
.list li { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; padding: 9px 0; border-top: 1px solid var(--vp-c-divider); font-size: 14.5px; }
.list li > span:first-child { flex: 1; }
.grow { flex: 1; }
.muted { color: var(--vp-c-text-3); }
.small { font-size: 13.5px; margin: 4px 0 10px; }
.ok { color: #16a34a; }
.okmsg { color: #16a34a; font-size: 14px; }
.warn { color: #b45309; font-size: 14px; }
.err { color: #dc2626; font-size: 14px; }
.row { display: flex; flex-wrap: wrap; gap: 8px; }
.row input {
  flex: 1 1 160px;
  padding: 9px 14px;
  border-radius: 14px;
  color: var(--vp-c-text-1);
  background: rgba(var(--lg-surface), 0.55);
  box-shadow: inset 0 1px 3px rgba(0, 0, 0, 0.1), inset 0 0 0 1px rgba(255, 255, 255, var(--lg-rim-base));
}
.totp { display: flex; flex-direction: column; align-items: flex-start; gap: 8px; }
.qr { width: 220px; max-width: 70vw; padding: 14px; border-radius: 20px; background: #fff; box-shadow: 0 12px 30px -14px rgba(0, 0, 0, 0.35); }
.qr :deep(svg) { display: block; width: 100%; height: auto; }
.keyrow { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; }
.applink { color: rgb(var(--lg-accent)); text-decoration: none; font-weight: 600; }
.key { font-size: 17px; letter-spacing: 0.08em; padding: 8px 12px; border-radius: 12px; background: rgba(var(--lg-ink), 0.06); user-select: all; }
.mini {
  padding: 7px 14px;
  border-radius: 999px;
  font-size: 13.5px;
  font-weight: 600;
  text-decoration: none;
  color: var(--vp-c-text-1);
  background: rgba(var(--lg-surface), 0.6);
  box-shadow: inset 0 1px 1px var(--lg-bloom), inset 0 0 0 1px rgba(255, 255, 255, var(--lg-rim-base));
}
.mini.accent { color: #fff; background: linear-gradient(135deg, rgb(var(--lg-accent)), rgb(var(--lg-accent-2))); }
.mini:disabled { opacity: 0.5; }
.link { display: inline-block; margin-top: 10px; }
.logout { margin-top: 18px; }
</style>

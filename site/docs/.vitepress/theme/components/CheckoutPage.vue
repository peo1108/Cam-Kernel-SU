<script setup lang="ts">
// /mua?code=CAMXXXXXX: pay one order by VietQR. Everything shown (amount, account, code) comes
// from the server's order; this page only polls its status until SePay reports the transfer.
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { ApiError, api, errorText, vnd, when, type Lang } from '../api'

const props = defineProps<{ lang: Lang }>()

const T = {
  vi: {
    title: 'Thanh toán.', member: 'Thành viên', early: 'Truy cập sớm', scan: 'Quét mã bằng app ngân hàng', or: 'hoặc chuyển khoản',
    bank: 'Ngân hàng', account: 'Số tài khoản', holder: 'Chủ tài khoản', amount: 'Số tiền', content: 'Nội dung',
    must: 'Ghi đúng nội dung để được kích hoạt tự động.', waiting: 'Đang chờ tiền về…', received: 'Đã nhận', missing: 'còn thiếu',
    paid: 'Đã thanh toán. Cảm ơn bạn!', paidLead: 'Quyền đã được kích hoạt.', goMember: 'Về trang thành viên', expires: 'Hết hạn lúc',
    expired: 'Đơn đã hết hạn. Nếu bạn đã chuyển tiền, đơn vẫn được ghi nhận khi tiền về.', refunded: 'Đơn đã được hoàn tiền.',
    copy: 'Chép', copied: 'Đã chép', cancel: 'Huỷ đơn', notFound: 'Không tìm thấy đơn.', member_href: '/thanh-vien', signin: '/tai-khoan?next=',
  },
  en: {
    title: 'Payment.', member: 'Membership', early: 'Early access', scan: 'Scan with your banking app', or: 'or transfer',
    bank: 'Bank', account: 'Account number', holder: 'Account holder', amount: 'Amount', content: 'Transfer note',
    must: 'Use exactly this note so it activates automatically.', waiting: 'Waiting for the transfer…', received: 'Received', missing: 'still missing',
    paid: 'Paid. Thank you!', paidLead: 'Your access is active.', goMember: 'Back to your account', expires: 'Expires at',
    expired: 'This order expired. If you already paid, it still counts when the money arrives.', refunded: 'This order was refunded.',
    copy: 'Copy', copied: 'Copied', cancel: 'Cancel order', notFound: 'Order not found.', member_href: '/en/member', signin: '/en/account?next=',
  },
  zh: {
    title: '付款。', member: '会员', early: '抢先体验', scan: '使用银行应用扫码', or: '或转账',
    bank: '银行', account: '账号', holder: '户名', amount: '金额', content: '转账备注',
    must: '请准确填写备注，以便自动开通。', waiting: '等待到账…', received: '已收到', missing: '仍差',
    paid: '已付款，谢谢！', paidLead: '权限已开通。', goMember: '返回会员页', expires: '截止于',
    expired: '订单已过期。如果你已经转账，到账后仍会记入。', refunded: '订单已退款。',
    copy: '复制', copied: '已复制', cancel: '取消订单', notFound: '找不到订单。', member_href: '/zh/member', signin: '/zh/account?next=',
  },
}
const t = computed(() => T[props.lang])

const order = ref<any>(null)
const err = ref('')
const copied = ref('')
let timer = 0

async function load(code: string) {
  try {
    order.value = await api('GET', `/api/orders/${encodeURIComponent(code)}`)
    if (order.value.status === 'paid' || order.value.status === 'refunded') clearInterval(timer)
  } catch (e) {
    if (e instanceof ApiError && e.status === 401) location.href = t.value.signin + encodeURIComponent(location.pathname + location.search)
    else err.value = e instanceof ApiError && e.status === 404 ? t.value.notFound : errorText(e, props.lang)
    clearInterval(timer)
  }
}

async function copy(what: string, text: string) {
  try {
    await navigator.clipboard.writeText(text)
    copied.value = what
    setTimeout(() => (copied.value = ''), 1500)
  } catch {}
}

async function cancel() {
  await api('POST', `/api/orders/${order.value.code}/cancel`, {})
  location.href = t.value.member_href
}

const left = computed(() => (order.value ? Math.max(0, order.value.amount - order.value.received) : 0))

onMounted(() => {
  const code = new URLSearchParams(location.search).get('code') || ''
  if (!code) {
    err.value = t.value.notFound
    return
  }
  load(code)
  timer = window.setInterval(() => load(code), 3000)
})
onBeforeUnmount(() => clearInterval(timer))
</script>

<template>
  <div class="cam-page buy">
    <header class="cam-page-head">
      <h1>{{ t.title }}</h1>
      <p v-if="order">
        {{ order.kind === 'early' ? `${t.early} ${order.version}` : t.member }} · <b>{{ vnd(order.amount) }}</b>
        <span class="cam-mono muted"> · {{ order.code }}</span>
      </p>
    </header>

    <p v-if="err" class="err">{{ err }}</p>

    <section v-if="order?.status === 'paid'" class="done lg lg-dialog" data-lens="dialog">
      <span class="seal">✓</span>
      <h2 class="lg-display">{{ t.paid }}</h2>
      <p>{{ t.paidLead }}</p>
      <a class="lg lg-btn lg-tinted lg-press" :href="t.member_href">{{ t.goMember }} →</a>
    </section>

    <section v-else-if="order?.status === 'refunded'" class="panel lg"><p>{{ t.refunded }}</p></section>

    <section v-else-if="order?.pay" class="pay lg lg-dialog" data-lens="dialog">
      <div class="qr">
        <img :src="order.pay.image" :alt="order.code" width="300" height="300" />
        <small>{{ t.scan }}</small>
      </div>
      <div class="info">
        <p class="or">{{ t.or }}</p>
        <dl>
          <dt>{{ t.bank }}</dt><dd>{{ order.pay.bank }}</dd>
          <dt>{{ t.account }}</dt>
          <dd><b class="cam-mono">{{ order.pay.account }}</b> <button class="mini" @click="copy('acc', order.pay.account)">{{ copied === 'acc' ? t.copied : t.copy }}</button></dd>
          <dt>{{ t.holder }}</dt><dd>{{ order.pay.holder }}</dd>
          <dt>{{ t.amount }}</dt>
          <dd><b>{{ vnd(left) }}</b> <button class="mini" @click="copy('amt', String(left))">{{ copied === 'amt' ? t.copied : t.copy }}</button></dd>
          <dt>{{ t.content }}</dt>
          <dd><b class="cam-mono big">{{ order.pay.content }}</b> <button class="mini" @click="copy('code', order.pay.content)">{{ copied === 'code' ? t.copied : t.copy }}</button></dd>
        </dl>
        <p class="must">⚠ {{ t.must }}</p>
        <p v-if="order.received > 0" class="part">{{ t.received }} {{ vnd(order.received) }} · {{ t.missing }} {{ vnd(left) }}</p>
        <p v-if="order.status === 'expired'" class="muted">{{ t.expired }}</p>
        <p v-else class="wait"><span class="pulse" /> {{ t.waiting }} <span class="muted">· {{ t.expires }} {{ when(order.expires_at, lang) }}</span></p>
        <button v-if="order.received === 0 && order.status === 'pending'" class="link" @click="cancel">{{ t.cancel }}</button>
      </div>
    </section>
  </div>
</template>

<style scoped>
.buy { max-width: 820px; }
.muted { color: var(--vp-c-text-3); }
.err { color: #dc2626; }
.pay { --lg-r: 30px; padding: 24px; display: grid; gap: 24px; }
@media (min-width: 720px) {
  .pay { grid-template-columns: 300px 1fr; align-items: start; }
}
.qr { display: flex; flex-direction: column; align-items: center; gap: 8px; }
.qr img { width: 100%; max-width: 300px; height: auto; border-radius: 20px; background: #fff; padding: 8px; box-shadow: 0 16px 40px -18px rgba(0, 0, 0, 0.35); }
.qr small { color: var(--vp-c-text-2); }
.or { margin: 0 0 8px; font-size: 13px; text-transform: uppercase; letter-spacing: 0.06em; color: var(--vp-c-text-3); font-weight: 700; }
dl { display: grid; grid-template-columns: auto 1fr; gap: 8px 14px; margin: 0; font-size: 15px; }
dt { color: var(--vp-c-text-2); }
dd { margin: 0; display: flex; align-items: center; flex-wrap: wrap; gap: 8px; }
.big { font-size: 18px; letter-spacing: 0.06em; color: rgb(var(--lg-accent)); }
.must { margin: 14px 0 0; font-size: 14px; color: #b45309; }
.dark .must { color: #fbbf24; }
.part { margin: 8px 0 0; font-weight: 600; }
.wait { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; margin: 14px 0 0; font-size: 14.5px; }
.pulse { width: 10px; height: 10px; border-radius: 50%; background: #22c55e; animation: pulse 1.4s ease-in-out infinite; }
@keyframes pulse { 50% { opacity: 0.3; transform: scale(0.7); } }
.mini { padding: 3px 10px; border-radius: 999px; font-size: 12.5px; font-weight: 600; background: rgba(var(--lg-surface), 0.6); box-shadow: inset 0 0 0 1px rgba(255, 255, 255, var(--lg-rim-base)); }
.link { margin-top: 12px; font-size: 13.5px; color: var(--vp-c-text-3); text-decoration: underline; }
.done { --lg-r: 30px; padding: 32px 24px; text-align: center; display: flex; flex-direction: column; align-items: center; gap: 10px; }
.done h2 { margin: 0; font-size: 28px; font-weight: 800; }
.done p { margin: 0 0 8px; color: var(--vp-c-text-2); }
.seal { display: grid; place-items: center; width: 72px; height: 72px; border-radius: 50%; font-size: 34px; font-weight: 800; color: #fff; background: linear-gradient(135deg, #22c55e, #14b8a6); box-shadow: 0 14px 34px -12px #22c55e; }
.panel { --lg-r: 24px; padding: 22px; }
</style>

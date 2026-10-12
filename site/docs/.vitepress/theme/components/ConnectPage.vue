<script setup lang="ts">
// /ket-noi?code=XXXXXX: link the SU Kernel app on a phone to the signed-in account. The app shows
// the code (and a QR to this page); confirming here hands the app its device token.
import { computed, onMounted, ref } from 'vue'
import { ApiError, api, errorText, type Lang } from '../api'

const props = defineProps<{ lang: Lang }>()
const T = {
  vi: {
    title: 'Kết nối thiết bị.', lead: 'Nhập mã 6 ký tự đang hiện trong SU Kernel → Cài đặt → Tài khoản.', code: 'Mã trên điện thoại',
    link: 'Kết nối', done: 'Đã kết nối', doneLead: 'Quay lại điện thoại: app sẽ tự nhận trong vài giây.', bad: 'Mã sai hoặc đã hết hạn. Tạo mã mới trên app.',
    member: '/thanh-vien', back: 'Về trang thành viên', signin: '/tai-khoan?next=',
  },
  en: {
    title: 'Link a device.', lead: 'Enter the 6-character code shown in SU Kernel → Settings → Account.', code: 'Code on the phone',
    link: 'Link', done: 'Linked', doneLead: 'Back to the phone: the app picks it up in a few seconds.', bad: 'Wrong or expired code. Make a new one in the app.',
    member: '/en/member', back: 'Back to your account', signin: '/en/account?next=',
  },
  zh: {
    title: '关联设备。', lead: '输入 SU Kernel → 设置 → 账号 中显示的 6 位代码。', code: '手机上的代码',
    link: '关联', done: '已关联', doneLead: '回到手机，应用会在几秒内自动完成。', bad: '代码错误或已过期，请在应用中重新生成。',
    member: '/zh/member', back: '返回会员页', signin: '/zh/account?next=',
  },
}
const t = computed(() => T[props.lang])
const code = ref('')
const err = ref('')
const device = ref<string | null>(null)
const done = ref(false)
const busy = ref(false)

async function link() {
  err.value = ''
  busy.value = true
  try {
    const r = await api<any>('POST', '/api/devices/confirm', { code: code.value })
    device.value = r.device
    done.value = true
  } catch (e) {
    if (e instanceof ApiError && e.status === 401) location.href = t.value.signin + encodeURIComponent(location.pathname + location.search)
    else err.value = e instanceof ApiError && e.status === 404 ? t.value.bad : errorText(e, props.lang)
  } finally {
    busy.value = false
  }
}

onMounted(async () => {
  code.value = (new URLSearchParams(location.search).get('code') || '').toUpperCase()
  try {
    const f = await api<any>('GET', '/api/auth/features')
    if (!f.signed_in) location.href = t.value.signin + encodeURIComponent(location.pathname + location.search)
  } catch {}
})
</script>

<template>
  <div class="cam-page con">
    <div class="card lg lg-dialog" data-lens="dialog">
      <template v-if="!done">
        <h1 class="lg-display">{{ t.title }}</h1>
        <p class="lead">{{ t.lead }}</p>
        <form @submit.prevent="link">
          <input v-model="code" class="code cam-mono" maxlength="8" autocomplete="one-time-code" autocapitalize="characters" spellcheck="false" :placeholder="t.code" required />
          <p v-if="err" class="err">{{ err }}</p>
          <button class="lg lg-btn lg-tinted lg-press" type="submit" :disabled="busy">{{ t.link }}</button>
        </form>
      </template>
      <template v-else>
        <span class="seal">✓</span>
        <h1 class="lg-display">{{ t.done }}<span v-if="device"> · {{ device }}</span></h1>
        <p class="lead">{{ t.doneLead }}</p>
        <a class="lg lg-btn lg-drop lg-press small" :href="t.member">{{ t.back }} →</a>
      </template>
    </div>
  </div>
</template>

<style scoped>
.con { max-width: 480px; padding-top: 48px; }
.card { padding: 28px 24px; display: flex; flex-direction: column; align-items: center; text-align: center; gap: 10px; }
.card h1 { margin: 0; font-size: 28px; font-weight: 800; }
.lead { margin: 0 0 8px; color: var(--vp-c-text-2); }
form { display: flex; flex-direction: column; gap: 12px; width: 100%; }
.code {
  text-align: center;
  font-size: 30px;
  letter-spacing: 0.3em;
  padding: 14px;
  border-radius: 18px;
  text-transform: uppercase;
  color: var(--vp-c-text-1);
  background: rgba(var(--lg-surface), 0.55);
  box-shadow: inset 0 1px 3px rgba(0, 0, 0, 0.1), inset 0 0 0 1px rgba(255, 255, 255, var(--lg-rim-base));
}
.code:focus { outline: none; box-shadow: 0 0 0 2px rgba(var(--lg-accent), 0.6); }
.err { margin: 0; color: #dc2626; font-size: 14px; }
.seal { display: grid; place-items: center; width: 68px; height: 68px; border-radius: 50%; font-size: 32px; font-weight: 800; color: #fff; background: linear-gradient(135deg, #22c55e, #14b8a6); }
</style>

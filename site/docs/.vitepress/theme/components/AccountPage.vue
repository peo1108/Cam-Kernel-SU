<script setup lang="ts">
// /tai-khoan: sign in, register, Google, forgotten password, reset from the mailed link.
import { computed, onMounted, ref } from 'vue'
import { ApiError, api, errorText, type Lang } from '../api'

const props = defineProps<{ lang: Lang }>()

const T = {
  vi: {
    signin: 'Đăng nhập', register: 'Đăng ký', email: 'Email', password: 'Mật khẩu', password2: 'Mật khẩu mới',
    name: 'Tên hiển thị (không bắt buộc)', google: 'Tiếp tục với Google', or: 'hoặc', forgot: 'Quên mật khẩu?',
    sendReset: 'Gửi liên kết đặt lại', resetSent: 'Nếu email có tài khoản, liên kết đã được gửi. Xem hộp thư (cả mục Spam).',
    setPassword: 'Đặt mật khẩu mới', done: 'Xong. Đăng nhập bằng mật khẩu mới.', back: 'Quay lại', min: 'Ít nhất 8 ký tự',
    lockedErr: 'Tài khoản đang bị khoá.', googleErr: 'Đăng nhập Google không thành công.', verifyErr: 'Liên kết xác minh đã hết hạn hoặc đã dùng. Đăng nhập rồi gửi lại thư ở trang Thành viên.', home: '/thanh-vien',
    code: 'Mã xác thực 6 số', codeLead: 'Nhập mã từ app xác thực (Google Authenticator…).', verify: 'Xác nhận',
  },
  en: {
    signin: 'Sign in', register: 'Register', email: 'Email', password: 'Password', password2: 'New password',
    name: 'Display name (optional)', google: 'Continue with Google', or: 'or', forgot: 'Forgot password?',
    sendReset: 'Send reset link', resetSent: 'If the email has an account, a link is on its way. Check spam too.',
    setPassword: 'Set new password', done: 'Done. Sign in with the new password.', back: 'Back', min: 'At least 8 characters',
    lockedErr: 'This account is locked.', googleErr: 'Google sign-in failed.', verifyErr: 'The verification link expired or was used. Sign in and send it again from the member page.', home: '/en/member',
    code: '6-digit code', codeLead: 'Enter the code from your authenticator app.', verify: 'Confirm',
  },
  zh: {
    signin: '登录', register: '注册', email: '邮箱', password: '密码', password2: '新密码',
    name: '显示名称（可选）', google: '使用 Google 继续', or: '或', forgot: '忘记密码？',
    sendReset: '发送重置链接', resetSent: '如果该邮箱已注册，重置链接已发出。也请检查垃圾邮件。',
    setPassword: '设置新密码', done: '完成。请用新密码登录。', back: '返回', min: '至少 8 个字符',
    lockedErr: '账号已被锁定。', googleErr: 'Google 登录失败。', verifyErr: '验证链接已过期或已使用。请登录后在会员页面重新发送。', home: '/zh/member',
    code: '6 位验证码', codeLead: '输入身份验证器中的代码。', verify: '确认',
  },
}
const t = computed(() => T[props.lang])

const mode = ref<'signin' | 'register' | 'forgot' | 'reset' | 'mfa'>('signin')
const needCode = ref(false)
const code = ref('')
const email = ref('')
const password = ref('')
const name = ref('')
const msg = ref('')
const err = ref('')
const busy = ref(false)
const features = ref<{ google: boolean; mail: boolean } | null>(null)
let next = ''
let resetToken = ''

function go() {
  location.href = next || t.value.home
}

async function submit() {
  err.value = msg.value = ''
  busy.value = true
  try {
    if (mode.value === 'signin') {
      try {
        await api('POST', '/api/auth/login', { email: email.value, password: password.value, totp: needCode.value ? code.value : undefined })
      } catch (e) {
        if (e instanceof ApiError && e.code === 'totp_required') {
          needCode.value = true
          return
        }
        throw e
      }
      go()
    } else if (mode.value === 'mfa') {
      await api('POST', '/api/auth/totp/verify', { code: code.value })
      go()
    } else if (mode.value === 'register') {
      await api('POST', '/api/auth/register', { email: email.value, password: password.value, name: name.value })
      go()
    } else if (mode.value === 'forgot') {
      await api('POST', '/api/auth/reset/request', { email: email.value })
      msg.value = t.value.resetSent
    } else {
      await api('POST', '/api/auth/reset/confirm', { token: resetToken, password: password.value })
      history.replaceState(null, '', location.pathname)
      mode.value = 'signin'
      password.value = ''
      msg.value = t.value.done
    }
  } catch (e) {
    err.value = errorText(e, props.lang)
  } finally {
    busy.value = false
  }
}

const googleHref = computed(() => `/api/auth/google?next=${encodeURIComponent(next || t.value.home)}`)

onMounted(async () => {
  const q = new URLSearchParams(location.search)
  next = q.get('next') && /^\/(?!\/)/.test(q.get('next')!) ? q.get('next')! : ''
  if (q.get('mfa')) mode.value = 'mfa'
  if (q.get('reset')) {
    resetToken = q.get('reset')!
    mode.value = 'reset'
  }
  if (q.get('error') === 'locked') err.value = t.value.lockedErr
  if (q.get('error') === 'google') err.value = t.value.googleErr
  if (q.get('error') === 'verify') err.value = t.value.verifyErr
  try {
    const f = await api<any>('GET', '/api/auth/features')
    features.value = f
    if (f.signed_in && mode.value !== 'reset' && mode.value !== 'mfa') go()
  } catch {
    features.value = { google: false, mail: false }
  }
})
</script>

<template>
  <div class="cam-page acct">
    <div class="card lg lg-dialog lg-in" data-lens="dialog">
      <div v-if="mode === 'signin' || mode === 'register'" class="lg-seg switch">
        <button :class="{ on: mode === 'signin' }" @click="mode = 'signin'; err = msg = ''">{{ t.signin }}</button>
        <button :class="{ on: mode === 'register' }" @click="mode = 'register'; err = msg = ''">{{ t.register }}</button>
      </div>
      <h1 v-else class="lg-display">{{ mode === 'forgot' ? t.forgot : mode === 'mfa' ? t.code : t.setPassword }}</h1>

      <template v-if="(mode === 'signin' || mode === 'register') && features?.google">
        <a class="google lg lg-btn lg-drop lg-press" :href="googleHref">
          <svg width="18" height="18" viewBox="0 0 48 48" aria-hidden="true"><path fill="#EA4335" d="M24 9.5c3.5 0 6.6 1.2 9.1 3.6l6.8-6.8C35.8 2.4 30.3 0 24 0 14.6 0 6.6 5.4 2.6 13.2l7.9 6.2C12.4 13.6 17.7 9.5 24 9.5z" /><path fill="#4285F4" d="M46.1 24.6c0-1.6-.1-3.1-.4-4.6H24v9.1h12.4c-.5 2.9-2.2 5.3-4.6 7l7.1 5.5c4.2-3.9 7.2-9.6 7.2-17z" /><path fill="#FBBC05" d="M10.5 28.6c-.5-1.4-.8-3-.8-4.6s.3-3.2.8-4.6l-7.9-6.2C1 16.4 0 20.1 0 24s1 7.6 2.6 10.8l7.9-6.2z" /><path fill="#34A853" d="M24 48c6.5 0 11.9-2.1 15.9-5.8l-7.1-5.5c-2.2 1.5-5 2.3-8.8 2.3-6.3 0-11.6-4.1-13.5-9.9l-7.9 6.2C6.6 42.6 14.6 48 24 48z" /></svg>
          {{ t.google }}
        </a>
        <div class="or"><span>{{ t.or }}</span></div>
      </template>

      <form @submit.prevent="submit">
        <label v-if="mode !== 'reset' && mode !== 'mfa'">
          <span>{{ t.email }}</span>
          <input v-model="email" type="email" autocomplete="email" required />
        </label>
        <label v-if="mode === 'register'">
          <span>{{ t.name }}</span>
          <input v-model="name" maxlength="80" autocomplete="nickname" />
        </label>
        <label v-if="mode !== 'forgot' && mode !== 'mfa'">
          <span>{{ mode === 'reset' ? t.password2 : t.password }}</span>
          <input v-model="password" type="password" :autocomplete="mode === 'signin' ? 'current-password' : 'new-password'" minlength="8" required :placeholder="mode === 'signin' ? '' : t.min" />
        </label>
        <label v-if="needCode || mode === 'mfa'">
          <span>{{ t.code }}</span>
          <input v-model="code" inputmode="numeric" autocomplete="one-time-code" maxlength="6" required autofocus />
          <small class="hint">{{ t.codeLead }}</small>
        </label>
        <p v-if="err" class="err">{{ err }}</p>
        <p v-if="msg" class="ok">{{ msg }}</p>
        <button class="lg lg-btn lg-tinted lg-press" type="submit" :disabled="busy">
          {{ mode === 'mfa' ? t.verify : mode === 'signin' ? t.signin : mode === 'register' ? t.register : mode === 'forgot' ? t.sendReset : t.setPassword }}
        </button>
      </form>

      <div class="links">
        <button v-if="mode === 'signin' && features?.mail" class="link" @click="mode = 'forgot'; err = msg = ''">{{ t.forgot }}</button>
        <button v-if="mode === 'forgot'" class="link" @click="mode = 'signin'; err = msg = ''">{{ t.back }}</button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.acct { max-width: 460px; padding-top: 48px; }
.card { padding: 26px 24px 22px; display: flex; flex-direction: column; gap: 16px; }
.card h1 { font-size: 26px; font-weight: 800; margin: 0; }
.switch { align-self: flex-start; }
.google { gap: 10px; width: 100%; }
.or { display: flex; align-items: center; gap: 10px; color: var(--vp-c-text-3); font-size: 13px; }
.or::before,
.or::after { content: ''; flex: 1; height: 1px; background: var(--vp-c-divider); }
form { display: flex; flex-direction: column; gap: 14px; }
label { display: flex; flex-direction: column; gap: 6px; }
label span { font-size: 13px; font-weight: 700; color: var(--vp-c-text-2); }
input {
  padding: 12px 16px;
  border-radius: 16px;
  font-size: 15.5px;
  color: var(--vp-c-text-1);
  background: rgba(var(--lg-surface), 0.55);
  box-shadow: inset 0 1px 3px rgba(0, 0, 0, 0.1), inset 0 0 0 1px rgba(255, 255, 255, var(--lg-rim-base));
}
.dark input { background: rgba(0, 0, 0, 0.25); }
input:focus { outline: none; box-shadow: inset 0 1px 3px rgba(0, 0, 0, 0.1), 0 0 0 2px rgba(var(--lg-accent), 0.6); }
.err { margin: 0; color: #dc2626; font-size: 14px; }
.hint { color: var(--vp-c-text-3); font-size: 12.5px; }
.dark .err { color: #f87171; }
.ok { margin: 0; color: #16a34a; font-size: 14px; }
.dark .ok { color: #4ade80; }
button[type='submit'] { width: 100%; }
button:disabled { opacity: 0.6; }
.links { display: flex; justify-content: center; }
.link { font-size: 14px; color: rgb(var(--lg-accent)); }
</style>

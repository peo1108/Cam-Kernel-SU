<script setup lang="ts">
// Source access by request: the form posts to /api/source-request on the VPS
// (site/server/source-request.mjs), which keeps the request for Cam to review.
import { computed, ref } from 'vue'

const props = defineProps<{ lang: 'vi' | 'en' | 'zh' }>()

const T = {
  vi: {
    title: 'Mã nguồn.',
    lead: 'Không công khai. Gửi lời, chờ duyệt.',
    name: 'Bạn là ai',
    namePh: 'Tên hoặc biệt danh',
    contact: 'Liên lạc',
    contactPh: 'Telegram, email…',
    github: 'Tài khoản GitHub',
    githubPh: 'để được mời vào repo',
    reason: 'Để làm gì',
    reasonPh: 'Vài dòng là đủ',
    send: 'Gửi',
    sending: 'Đang gửi…',
    sent: 'Đã nhận. Nếu được duyệt, lời mời sẽ tới tài khoản GitHub của bạn.',
    again: 'Gửi lại',
    tooMany: 'Gửi nhiều quá. Thử lại sau.',
    failed: 'Không gửi được. Thử lại sau.',
    invalid: 'Điền đủ các ô, tài khoản GitHub viết đúng.',
  },
  en: {
    title: 'Source.',
    lead: 'Not public. Ask, and wait.',
    name: 'Who are you',
    namePh: 'Name or nickname',
    contact: 'Contact',
    contactPh: 'Telegram, email…',
    github: 'GitHub account',
    githubPh: 'to be invited to the repo',
    reason: 'What for',
    reasonPh: 'A few lines will do',
    send: 'Send',
    sending: 'Sending…',
    sent: 'Received. If approved, the invitation goes to your GitHub account.',
    again: 'Send another',
    tooMany: 'Too many requests. Try again later.',
    failed: 'Could not send. Try again later.',
    invalid: 'Fill in every field, with a valid GitHub account.',
  },
  zh: {
    title: '源码。',
    lead: '不公开。提出申请，等待审核。',
    name: '你是谁',
    namePh: '名字或昵称',
    contact: '联系方式',
    contactPh: 'Telegram、邮箱…',
    github: 'GitHub 账号',
    githubPh: '用于邀请你加入仓库',
    reason: '用途',
    reasonPh: '几行就够了',
    send: '发送',
    sending: '发送中…',
    sent: '已收到。如获批准，邀请会发送到你的 GitHub 账号。',
    again: '再发一条',
    tooMany: '请求过多，请稍后再试。',
    failed: '发送失败，请稍后再试。',
    invalid: '请填写所有字段，并使用有效的 GitHub 账号。',
  },
}

const t = computed(() => T[props.lang])
const form = ref({ name: '', contact: '', github: '', reason: '', website: '' })
const state = ref<'idle' | 'sending' | 'sent' | 'error'>('idle')
const error = ref('')

const GH = /^[A-Za-z0-9](?:[A-Za-z0-9-]{0,37}[A-Za-z0-9])?$/

async function submit() {
  const f = form.value
  f.github = f.github.trim().replace(/^@/, '')
  if (!f.name.trim() || !f.contact.trim() || !f.reason.trim() || !GH.test(f.github)) {
    state.value = 'error'
    error.value = t.value.invalid
    return
  }
  state.value = 'sending'
  try {
    const res = await fetch('/api/source-request', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ ...f, lang: props.lang }),
    })
    if (res.ok) {
      state.value = 'sent'
      return
    }
    state.value = 'error'
    error.value = res.status === 429 ? t.value.tooMany : res.status === 400 ? t.value.invalid : t.value.failed
  } catch {
    state.value = 'error'
    error.value = t.value.failed
  }
}

function reset() {
  form.value = { name: '', contact: '', github: '', reason: '', website: '' }
  state.value = 'idle'
}
</script>

<template>
  <div class="cam-page src">
    <header class="cam-page-head lg-in">
      <h1>{{ t.title }}</h1>
      <p>{{ t.lead }}</p>
    </header>

    <div class="panel lg lg-dialog lg-in" data-lens="dialog">
      <Transition name="swap" mode="out-in">
        <div v-if="state === 'sent'" key="sent" class="done">
          <span class="seal" aria-hidden="true">✓</span>
          <p>{{ t.sent }}</p>
          <button class="lg lg-btn lg-drop lg-press small" type="button" @click="reset">{{ t.again }}</button>
        </div>
        <form v-else key="form" @submit.prevent="submit">
          <label>
            <span>{{ t.name }}</span>
            <input v-model="form.name" maxlength="80" :placeholder="t.namePh" autocomplete="nickname" required />
          </label>
          <label>
            <span>{{ t.contact }}</span>
            <input v-model="form.contact" maxlength="120" :placeholder="t.contactPh" required />
          </label>
          <label>
            <span>{{ t.github }}</span>
            <input v-model="form.github" maxlength="40" :placeholder="t.githubPh" autocapitalize="off" spellcheck="false" required />
          </label>
          <label>
            <span>{{ t.reason }}</span>
            <textarea v-model="form.reason" maxlength="1000" rows="4" :placeholder="t.reasonPh" required />
          </label>
          <!-- Left empty by people; bots fill it. -->
          <input v-model="form.website" class="hp" tabindex="-1" autocomplete="off" aria-hidden="true" />
          <p v-if="state === 'error'" class="err">{{ error }}</p>
          <button class="lg lg-btn lg-tinted lg-press" type="submit" :disabled="state === 'sending'">
            {{ state === 'sending' ? t.sending : t.send }}
          </button>
        </form>
      </Transition>
    </div>
  </div>
</template>

<style scoped>
.src { max-width: 640px; }
.panel { padding: 26px 24px; }
@media (min-width: 640px) {
  .panel { padding: 32px; }
}
form { display: flex; flex-direction: column; gap: 16px; align-items: stretch; }
label { display: flex; flex-direction: column; gap: 6px; }
label span { font-size: 13px; font-weight: 700; letter-spacing: 0.04em; text-transform: uppercase; color: var(--vp-c-text-3); }
input,
textarea {
  width: 100%;
  padding: 12px 16px;
  border-radius: 16px;
  font-size: 15.5px;
  color: var(--vp-c-text-1);
  background: rgba(var(--lg-surface), 0.55);
  box-shadow: inset 0 1px 3px rgba(0, 0, 0, 0.1), inset 0 0 0 1px rgba(255, 255, 255, var(--lg-rim-base));
  transition: box-shadow 0.2s ease;
  resize: vertical;
  font-family: inherit;
}
.dark input,
.dark textarea { background: rgba(0, 0, 0, 0.25); }
input:focus,
textarea:focus { outline: none; box-shadow: inset 0 1px 3px rgba(0, 0, 0, 0.1), 0 0 0 2px rgba(var(--lg-accent), 0.6); }
.hp { position: absolute; left: -9999px; width: 1px; height: 1px; opacity: 0; }
.err { margin: 0; color: #dc2626; font-size: 14px; }
.dark .err { color: #f87171; }
button[type='submit'] { align-self: flex-start; margin-top: 4px; }
button:disabled { opacity: 0.6; }

.done { display: flex; flex-direction: column; align-items: center; text-align: center; gap: 12px; padding: 12px 0; }
.done p { margin: 0; color: var(--vp-c-text-2); max-width: 380px; line-height: 1.6; }
.seal {
  display: grid;
  place-items: center;
  width: 64px;
  height: 64px;
  border-radius: 50%;
  font-size: 30px;
  font-weight: 800;
  color: #fff;
  background: linear-gradient(135deg, rgb(var(--lg-accent)), rgb(var(--lg-accent-2)));
  box-shadow: 0 12px 30px -10px rgb(var(--lg-accent));
}
.swap-enter-active,
.swap-leave-active { transition: opacity 0.25s ease, transform 0.3s ease; }
.swap-enter-from { opacity: 0; transform: translateY(10px); }
.swap-leave-to { opacity: 0; }
</style>

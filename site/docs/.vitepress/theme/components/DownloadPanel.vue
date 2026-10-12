<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { api } from '../api'
import { data as release } from '../../data/release.data'
import { data as changelog } from '../../data/changelog.data'
import { mb, fmtDate } from '../format'

const props = defineProps<{ lang: 'vi' | 'en' | 'zh' }>()

const T = {
  vi: {
    title: 'Tải về',
    lead: 'Chỉ cần APK. App tự mang theo camd và module nhân cho mọi KMI, tự chọn đúng bản khi vá ảnh boot.',
    latest: 'Bản mới nhất',
    apk: 'Tải APK',
    notes: 'Ghi chú phát hành',
    noData: 'Chưa có bản nào. Quay lại sau.',
    ota: 'Đã cài rồi thì app tự báo khi có bản mới (kiểm tra mỗi 12 giờ), không cần tải lại tay.',
    locked: 'Tải về dành cho thành viên.', lockedIn: 'Đăng nhập hoặc tham gia', memberHref: '/thanh-vien', notYet: 'Bản này chưa mở cho bạn. Xem ngày mở hoặc nhận sớm', here: 'ở trang thành viên',
    lkmTitle: 'Module nhân (LKM)',
    lkmLead: 'Chỉ cần khi chọn "Sử dụng file LKM cục bộ" lúc cài. KMI xem bằng uname -r: 5.15.x-android13-... là android13-5.15.',
    toolsTitle: 'Công cụ dòng lệnh',
    toolsLead: 'camd cho máy tính (vá ảnh boot không cần điện thoại) và caminit. Người dùng thường không cần.',
    older: 'Bản trước',
    changelog: 'Nhật ký',
    changelogLink: '/nhat-ky',
    req: 'Yêu cầu',
    reqs: ['Bootloader đã mở khoá', 'Nhân GKI 5.10 trở lên (Android 12+)', 'Ảnh init_boot hoặc boot gốc của đúng bản ROM đang chạy'],
  },
  en: {
    title: 'Download',
    lead: 'The APK is all you need. It carries camd and the kernel module for every KMI and picks the right one when it patches your boot image.',
    latest: 'Latest release',
    apk: 'Download APK',
    notes: 'Release notes',
    noData: 'Nothing here yet. Come back later.',
    ota: 'Once installed, the app tells you about new releases (it checks every 12 hours); no need to come back here.',
    locked: 'Downloads are for members.', lockedIn: 'Sign in or join', memberHref: '/en/member', notYet: 'This release is not open for you yet. See when it opens or get it early', here: 'on your account page',
    lkmTitle: 'Kernel module (LKM)',
    lkmLead: 'Only needed for "Use local LKM file" during install. Find your KMI with uname -r: 5.15.x-android13-... means android13-5.15.',
    toolsTitle: 'Command-line tools',
    toolsLead: 'camd for your computer (patch a boot image without the phone) and caminit. Most people never need them.',
    older: 'Earlier releases',
    changelog: 'Changelog',
    changelogLink: '/en/changelog',
    req: 'Requirements',
    reqs: ['Unlocked bootloader', 'GKI kernel 5.10 or newer (Android 12+)', 'The stock init_boot or boot image of the ROM you are running'],
  },
  zh: {
    title: '下载',
    lead: '只需要 APK。它自带 camd 和所有 KMI 的内核模块，修补启动镜像时会自动选择。',
    latest: '最新版本',
    apk: '下载 APK',
    notes: '更新说明',
    noData: '暂时没有。稍后再来。',
    ota: '安装后，应用会自动提示新版本（每 12 小时检查一次），无需再回来这里。',
    locked: '下载仅限会员。', lockedIn: '登录或加入', memberHref: '/zh/member', notYet: '该版本尚未对你开放。查看开放日期或抢先获取', here: '请到会员页',
    lkmTitle: '内核模块（LKM）',
    lkmLead: '仅在安装时选择“使用本地 LKM 文件”才需要。用 uname -r 查看 KMI：5.15.x-android13-... 即 android13-5.15。',
    toolsTitle: '命令行工具',
    toolsLead: '电脑版 camd（无需手机即可修补启动镜像）和 caminit。大多数人用不到。',
    older: '早期版本',
    changelog: '更新日志',
    changelogLink: '/zh/changelog',
    req: '要求',
    reqs: ['已解锁 Bootloader', 'GKI 5.10 或更新的内核（Android 12+）', '当前 ROM 的原厂 init_boot 或 boot 镜像'],
  },
}

const t = computed(() => T[props.lang])
const latest = release.latest

// What this visitor may download (the server decides; /dl/ enforces it). Until we know, links
// point at the account page, so nobody lands on an error.
const openTags = ref<Set<string> | null>(null)
const signedIn = ref(false)
onMounted(async () => {
  try {
    const f = await api<any>('GET', '/api/auth/features')
    signedIn.value = !!f.signed_in
    if (!f.signed_in) return (openTags.value = new Set())
    const me = await api<any>('GET', '/api/me')
    openTags.value = new Set(me.releases.filter((r: any) => r.open).map((r: any) => r.tag))
  } catch {
    openTags.value = new Set()
  }
})
const can = (tag: string) => !!openTags.value?.has(tag)
const href = (tag: string, url: string) => (can(tag) ? url : t.value.memberHref)
const arch = ref<'aarch64' | 'x86_64'>('aarch64')
const lkm = computed(() => (latest?.lkm ?? []).filter((l) => l.arch === arch.value))
// Prefer the changelog date (local release day) over GitHub's UTC timestamp.
const dateOf = (v: string, fallback: string) => changelog.find((e) => e.version === v)?.date ?? fallback
const archs = computed(() => [...new Set((latest?.lkm ?? []).map((l) => l.arch))])
</script>

<template>
  <div class="cam-page">
    <header class="cam-page-head">
      <h1>{{ t.title }}</h1>
      <p>{{ t.lead }}</p>
    </header>

    <section v-if="latest" class="hero-card lg lg-hover lg-in">
      <div class="hc-main">
        <img src="/logo.png" alt="" width="76" height="76" />
        <div>
          <div class="cam-chip ok">{{ t.latest }}</div>
          <div class="hc-title lg-display">SU Kernel <span class="lg-grad-text">v{{ latest.version }}</span></div>
          <div class="hc-sub">{{ fmtDate(dateOf(latest.version, latest.date), lang) }}<template v-if="latest.apk"> · {{ mb(latest.apk.size) }}</template></div>
        </div>
      </div>
      <div class="hc-actions">
        <a v-if="latest.apk" class="lg lg-btn lg-tinted lg-press" data-lens="droplet" :href="href(latest.tag, latest.apk.url)">
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 4v11M7.5 10.5L12 15l4.5-4.5M5 19h14" /></svg>
          {{ t.apk }}
        </a>
        <a class="lg lg-btn lg-drop lg-press" data-lens="droplet" :href="`${t.changelogLink}#v${latest.version.replaceAll('.', '-')}`">{{ t.notes }}</a>
      </div>
      <p class="hc-ota">{{ t.ota }}</p>
      <p v-if="openTags && !can(latest.tag)" class="hc-lock">
        🔒 <template v-if="!signedIn">{{ t.locked }} <a :href="t.memberHref">{{ t.lockedIn }} →</a></template>
        <template v-else>{{ t.notYet }} <a :href="t.memberHref">{{ t.here }} →</a></template>
      </p>
    </section>

    <section v-else class="hero-card lg lg-hover lg-in">
      <p>{{ t.noData }}</p>
    </section>

    <div class="grid">
      <section class="card lg lg-hover lg-in">
        <h2 class="lg-display">{{ t.req }}</h2>
        <ul class="reqs">
          <li v-for="r in t.reqs" :key="r">{{ r }}</li>
        </ul>
      </section>

      <section v-if="latest?.lkm.length" class="card lg lg-hover lg-in wide">
        <div class="card-head">
          <h2 class="lg-display">{{ t.lkmTitle }}</h2>
          <div class="lg-seg" role="tablist">
            <button v-for="a in archs" :key="a" role="tab" :aria-selected="arch === a" :class="{ on: arch === a }" @click="arch = a as any">{{ a }}</button>
          </div>
        </div>
        <p class="muted">{{ t.lkmLead }}</p>
        <div class="kmi">
          <a v-for="l in lkm" :key="l.kmi" class="kmi-item" :href="href(latest.tag, l.url)">
            <span class="cam-mono">{{ l.kmi }}</span>
            <span class="size">{{ mb(l.size) }}</span>
          </a>
        </div>
      </section>

      <section v-if="latest?.tools.length" class="card lg lg-hover lg-in wide">
        <h2 class="lg-display">{{ t.toolsTitle }}</h2>
        <p class="muted">{{ t.toolsLead }}</p>
        <div class="tools">
          <a v-for="a in latest.tools" :key="a.name" class="tool" :href="href(latest.tag, a.url)">
            <span class="cam-mono">{{ a.name }}</span><span class="size">{{ mb(a.size) }}</span>
          </a>
        </div>
      </section>

      <section v-if="release.older.length" class="card lg lg-hover lg-in">
        <h2 class="lg-display">{{ t.older }}</h2>
        <ul class="older">
          <li v-for="r in release.older" :key="r.tag">
            <a :href="`${t.changelogLink}#v${r.version.replaceAll('.', '-')}`" class="cam-mono">v{{ r.version }}</a>
            <span class="muted">{{ fmtDate(dateOf(r.version, r.date), lang) }}</span>
            <a v-if="r.apk" :href="href(r.tag, r.apk.url)" class="apk">APK</a>
          </li>
        </ul>
        <a class="lg lg-btn lg-drop lg-press small" data-lens="droplet" :href="t.changelogLink">{{ t.changelog }} →</a>
      </section>
    </div>
  </div>
</template>

<style scoped>
.hero-card {
  --lg-r: 30px;
  padding: 26px;
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 20px;
  margin-bottom: 20px;
}
.hc-main { display: flex; align-items: center; gap: 18px; }
.hc-main img { border-radius: 20px; box-shadow: 0 10px 26px -10px rgba(34, 211, 238, 0.7); }
.hc-title { font-size: clamp(26px, 5vw, 34px); font-weight: 800; line-height: 1.15; margin-top: 6px; }
.hc-sub { color: var(--vp-c-text-2); font-size: 14px; }
.hc-actions { display: flex; flex-wrap: wrap; gap: 10px; }
.hc-lock { flex-basis: 100%; margin: 0; font-size: 14.5px; font-weight: 600; }
.hc-lock a { color: rgb(var(--lg-accent)); }
.hc-ota {
  flex-basis: 100%;
  margin: 0;
  padding-top: 14px;
  border-top: 1px solid var(--vp-c-divider);
  color: var(--vp-c-text-2);
  font-size: 14px;
}

.grid {
  display: grid;
  gap: 16px;
}
@media (min-width: 900px) {
  .grid { grid-template-columns: 1fr 2fr; }
  .wide { grid-column: span 1; }
}

.card { padding: 24px; min-width: 0; --lg-r: 26px; }
.card h2 { font-size: 22px; font-weight: 800; margin: 0 0 8px; }
.card-head { display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: 10px; margin-bottom: 4px; }
.card-head h2 { margin: 0; }
.muted { color: var(--vp-c-text-2); font-size: 14px; line-height: 1.6; margin: 0 0 14px; }

.reqs { list-style: disc; margin: 0; padding-left: 20px; color: var(--vp-c-text-2); font-size: 14.5px; line-height: 1.7; }


.kmi {
  display: grid;
  gap: 8px;
  grid-template-columns: repeat(auto-fill, minmax(min(100%, 170px), 1fr));
}
.kmi-item,
.tool {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 10px;
  padding: 11px 14px;
  border-radius: 16px;
  background: rgba(var(--lg-surface), 0.32);
  box-shadow: inset 0 1px 1.5px var(--lg-bloom), inset 0 0 0 1px rgba(255, 255, 255, var(--lg-rim-base)), 0 4px 14px -8px rgba(0, 0, 0, var(--lg-shadow-a));
  color: var(--vp-c-text-1);
  text-decoration: none;
  font-size: 14px;
  transition: background-color 0.2s ease, box-shadow 0.2s ease;
  min-width: 0;
}
.kmi-item:hover,
.tool:hover {
  background: rgba(var(--lg-accent), 0.14);
  box-shadow: inset 0 1px 1.5px var(--lg-bloom), inset 0 0 0 1px rgba(var(--lg-accent), 0.35), 0 6px 18px -8px rgba(var(--lg-accent), 0.5);
}
.kmi-item .cam-mono,
.tool .cam-mono { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.size { color: var(--vp-c-text-3); font-size: 12.5px; flex: none; }

.tools {
  display: grid;
  gap: 8px;
  grid-template-columns: repeat(auto-fill, minmax(min(100%, 260px), 1fr));
}

.older { list-style: none; margin: 0 0 14px; padding: 0; }
.older li { display: flex; align-items: center; gap: 12px; padding: 8px 0; font-size: 14.5px; }
.older li + li { border-top: 1px solid var(--vp-c-divider); }
.older li .muted { margin: 0; flex: 1; }
.older a { color: var(--vp-c-brand-1); text-decoration: none; font-weight: 600; }
.older .apk { font-size: 13px; }
</style>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { data as release } from '../../data/release.data'
import { data as changelog } from '../../data/changelog.data'
import { data as modules } from '../../data/modules.data'
import { inline, fmtDate } from '../format'
import SlimeWorld from './SlimeWorld.vue'
import FeatureShowcase from './FeatureShowcase.vue'

const props = defineProps<{ lang: 'vi' | 'en' | 'zh' }>()

const T = {
  vi: {
    eyebrow: 'Mới',
    title1: 'Root,',
    title2: 'từ bên trong.',
    lead: 'Nằm sâu trong nhân. Lặng lẽ. Khó thấy.',
    download: 'Tải về',
    guide: 'Mày mò',
    meta: ['Android 12+', 'GKI 5.10+', 'arm64 · x86_64'],
    floatGuard: 'Chống bootloop',
    floatHide: '0 điểm lộ',
    stepsTitle: 'Ba bước.',
    steps: [
      ['Tải', 'Cài SU Kernel.'],
      ['Vá', 'Đưa app ảnh init_boot gốc.'],
      ['Flash', 'fastboot, rồi khởi động lại.'],
    ],
    stepsMore: 'Chi tiết',
    copy: 'Chép',
    copied: 'Đã chép',
    newTitle: 'Mới',
    newMore: 'Nhật ký',
    modTitle: 'Module',
    modLead: (n: number) => `${n} cái, chọn lọc.`,
    modMore: 'Xem hết',
    secretTitle: '前辈内部',
    secretText: 'Bạn đã vào trong.',
    secretAsk: 'Xin mã nguồn',
    secretClose: 'Đóng',
    links: { download: '/tai-ve', guide: '/huong-dan/', install: '/huong-dan/cai-dat', changelog: '/nhat-ky', modules: '/module', source: '/ma-nguon' },
  },
  en: {
    eyebrow: 'New',
    title1: 'Root,',
    title2: 'from within.',
    lead: 'Deep in the kernel. Quiet. Hard to see.',
    download: 'Download',
    guide: 'Explore',
    meta: ['Android 12+', 'GKI 5.10+', 'arm64 · x86_64'],
    floatGuard: 'Bootloop guard',
    floatHide: '0 leaks',
    stepsTitle: 'Three steps.',
    steps: [
      ['Get', 'Install SU Kernel.'],
      ['Patch', 'Hand it your stock init_boot.'],
      ['Flash', 'fastboot, then reboot.'],
    ],
    stepsMore: 'Details',
    copy: 'Copy',
    copied: 'Copied',
    newTitle: 'New',
    newMore: 'Changelog',
    modTitle: 'Modules',
    modLead: (n: number) => `${n}, hand-picked.`,
    modMore: 'See all',
    secretTitle: '前辈内部',
    secretText: 'You are inside now.',
    secretAsk: 'Ask for the source',
    secretClose: 'Close',
    links: { download: '/en/download', guide: '/en/guide/', install: '/en/guide/install', changelog: '/en/changelog', modules: '/en/modules', source: '/en/source' },
  },
  zh: {
    eyebrow: '新',
    title1: 'Root，',
    title2: '自内而生。',
    lead: '深藏内核。安静。难以察觉。',
    download: '下载',
    guide: '探索',
    meta: ['Android 12+', 'GKI 5.10+', 'arm64 · x86_64'],
    floatGuard: '防卡开机',
    floatHide: '0 处泄露',
    stepsTitle: '三步。',
    steps: [
      ['获取', '安装 SU Kernel。'],
      ['修补', '把原厂 init_boot 交给它。'],
      ['刷入', 'fastboot，然后重启。'],
    ],
    stepsMore: '详情',
    copy: '复制',
    copied: '已复制',
    newTitle: '新',
    newMore: '更新日志',
    modTitle: '模块',
    modLead: (n: number) => `${n} 个，精挑细选。`,
    modMore: '查看全部',
    secretTitle: '前辈内部',
    secretText: '你已经进来了。',
    secretAsk: '申请源码',
    secretClose: '关闭',
    links: { download: '/zh/download', guide: '/zh/guide/', install: '/zh/guide/install', changelog: '/zh/changelog', modules: '/zh/modules', source: '/zh/source' },
  },
}

const t = computed(() => T[props.lang])
const latest = release.latest
const version = computed(() => latest?.version ?? changelog[0]?.version ?? '')
// The changelog date is the one the project writes; GitHub's published_at is UTC.
const date = computed(() => fmtDate(changelog.find((e) => e.version === version.value)?.date ?? latest?.date ?? '', props.lang))
const news = computed(() => (changelog[0]?.groups.flatMap((g) => g.items) ?? []).slice(0, 4))

const downloadIcon = 'M12 4v11M7.5 10.5L12 15l4.5-4.5M5 19h14'

const commands = ['adb reboot bootloader', 'fastboot flash init_boot camsu_patched_xxx.img', 'fastboot reboot']
const copied = ref(false)
async function copyCommands() {
  try {
    await navigator.clipboard.writeText(commands.join('\n'))
    copied.value = true
    setTimeout(() => (copied.value = false), 1600)
  } catch {}
}

/* Seven taps on the emblem, like Android's developer options. */
const secret = ref(false)
let taps: number[] = []
function tapEmblem() {
  const now = Date.now()
  taps = [...taps.filter((x) => now - x < 2500), now]
  if (taps.length >= 7) {
    taps = []
    secret.value = true
    window.dispatchEvent(new CustomEvent('lg-party'))
  }
}

/* The free glass drop: drag it over the page to see the edge refraction. */
const hero = ref<HTMLElement | null>(null)
const orb = ref({ x: 0, y: 0, placed: false, dragging: false })
let grab = { dx: 0, dy: 0 }

function placeOrb() {
  const h = hero.value
  if (!h || orb.value.placed) return
  const w = h.clientWidth
  // Start over the headline so the bending is visible right away.
  orb.value.x = w >= 960 ? w * 0.3 : w - 110
  orb.value.y = w >= 960 ? 150 : 56
}

function orbDown(e: PointerEvent) {
  const el = e.currentTarget as HTMLElement
  el.setPointerCapture(e.pointerId)
  orb.value.dragging = true
  orb.value.placed = true
  grab = { dx: e.clientX - orb.value.x, dy: e.clientY - orb.value.y }
}
function orbMove(e: PointerEvent) {
  if (!orb.value.dragging || !hero.value) return
  const max = hero.value.getBoundingClientRect()
  orb.value.x = Math.min(Math.max(e.clientX - grab.dx, -40), max.width - 100)
  orb.value.y = Math.min(Math.max(e.clientY - grab.dy, -40), max.height - 100)
}
function orbUp() {
  orb.value.dragging = false
}

/* Electric strands crawling along the emblem's triangle (glass/electric.ts). */
const zap = ref<HTMLElement | null>(null)
let stopZap: (() => void) | undefined

onMounted(async () => {
  placeOrb()
  addEventListener('resize', placeOrb)
  if (!zap.value) return
  const { startElectric } = await import('../glass/electric')
  if (!zap.value) return
  stopZap = startElectric(zap.value, {
    src: '/emblem-edge.svg',
    frame: true,
    color: '#dcfffd',
    glowColor: '#2de2e6',
    intensity: 0.9,
    glow: 0.85,
    thickness: 1.4,
  })
})
onBeforeUnmount(() => {
  removeEventListener('resize', placeOrb)
  stopZap?.()
})
</script>

<template>
  <div class="home">
    <SlimeWorld />
    <!-- hero -->
    <section ref="hero" class="hero">
      <div class="hero-text">
        <a v-if="version" class="eyebrow lg lg-drop lg-press lg-in" data-lens="droplet" data-ledge :href="t.links.changelog">
          <span class="cam-chip ok">{{ t.eyebrow }}</span>
          <span class="cam-mono">v{{ version }}</span>
          <span class="dim">· {{ date }}</span>
          <span class="arrow">→</span>
        </a>
        <h1 class="lg-display lg-in">
          {{ t.title1 }}<br />
          <span class="lg-grad-text">{{ t.title2 }}</span>
        </h1>
        <p class="lead lg-in">{{ t.lead }}</p>
        <div class="actions lg-in">
          <a class="lg lg-btn lg-tinted lg-press" data-lens="droplet" data-ledge :href="t.links.download">
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><path :d="downloadIcon" /></svg>
            {{ t.download }}<span v-if="version" class="lg-btn-ver">v{{ version }}</span>
          </a>
          <a class="lg lg-btn lg-drop lg-press" data-lens="droplet" data-ledge :href="t.links.guide">{{ t.guide }}</a>
        </div>
        <ul class="meta lg-in">
          <li v-for="m in t.meta" :key="m" class="cam-chip muted">{{ m }}</li>
        </ul>
      </div>

      <div class="hero-visual">
        <div class="stage-wrap lg-in">
          <figure class="stage lg lg-dialog" data-lens="dialog" data-ledge data-tilt="9" @click="tapEmblem">
            <img
              src="/emblem.webp"
              srcset="/emblem-640.webp 640w, /emblem.webp 960w"
              sizes="(min-width: 960px) 440px, 82vw"
              alt="Cam Kernel SU · Inner Veterans"
              width="960"
              height="960"
              fetchpriority="high"
              draggable="false"
            />
            <span ref="zap" class="zap" aria-hidden="true" />
            <span class="sheen" aria-hidden="true" />
            <span class="glare" aria-hidden="true" />
          </figure>
          <div class="reflection" aria-hidden="true">
            <img src="/emblem-640.webp" alt="" width="640" height="640" draggable="false" />
          </div>
          <div class="float f1 lg" data-ledge><span class="dot ok" />{{ t.floatGuard }}</div>
          <div class="float f2 lg" data-ledge><span class="dot cyan" />{{ t.floatHide }}</div>
        </div>
      </div>

      <div
        class="orb-wrap"
        :class="{ dragging: orb.dragging }"
        :style="{ transform: `translate3d(${orb.x}px, ${orb.y}px, 0)` }"
        @pointerdown="orbDown"
        @pointermove="orbMove"
        @pointerup="orbUp"
        @pointercancel="orbUp"
      >
        <div class="orb lg lg-drop lg-press" data-lens="orb" data-press="1.14" />
      </div>
    </section>

    <FeatureShowcase :lang="lang" />

    <section class="install">
      <h2 class="sec-title lg-display lg-in">{{ t.stepsTitle }}</h2>
      <ol class="track lg-in">
        <li v-for="([title, body], i) in t.steps" :key="title">
          <span class="node lg lg-drop lg-display" data-lens="droplet">{{ i + 1 }}</span>
          <h3 class="lg-display">{{ title }}</h3>
          <p>{{ body }}</p>
        </li>
      </ol>
      <div class="term lg lg-dialog lg-in" data-ledge>
        <div class="term-bar">
          <span class="dots"><i /><i /><i /></span>
          <span class="term-title cam-mono">~</span>
          <button class="term-copy" type="button" @click="copyCommands">{{ copied ? t.copied : t.copy }}</button>
        </div>
        <pre class="cam-mono"><code><span v-for="c in commands" :key="c" class="cmd"><b>$</b> {{ c }}
</span></code></pre>
      </div>
      <a class="more-link lg-in" :href="t.links.install">{{ t.stepsMore }} →</a>
    </section>

    <section v-if="news.length" class="news-strip lg lg-hover lg-in" data-ledge>
      <div class="ns-left">
        <span class="ns-label">{{ t.newTitle }}</span>
        <span class="ns-ver lg-display lg-grad-text">v{{ version }}</span>
        <span class="ns-date">{{ date }}</span>
        <a class="ns-link" :href="t.links.changelog">{{ t.newMore }} →</a>
      </div>
      <ul class="ns-list">
        <li v-for="(n, i) in news.slice(0, 3)" :key="i" v-html="inline(n)" />
      </ul>
    </section>

    <section v-if="modules.length" class="mods">
      <div class="mods-head lg-in">
        <h2 class="sec-title lg-display">{{ t.modTitle }}</h2>
        <span class="mods-lead">{{ t.modLead(modules.length) }}</span>
        <a class="more-link" :href="t.links.modules">{{ t.modMore }} →</a>
      </div>
      <div class="marquee lg-in" aria-hidden="true">
        <div class="marquee-track">
          <a v-for="(m, i) in [...modules, ...modules]" :key="i" class="pill lg lg-drop" :href="t.links.modules" tabindex="-1">
            <span class="pill-dot" :class="{ 'is-meta': m.metamodule }" />
            <b>{{ m.name }}</b>
          </a>
        </div>
      </div>
    </section>

    <Transition name="secret">
      <div v-if="secret" class="secret-veil" @click.self="secret = false">
        <div class="secret lg lg-dialog" data-lens="dialog" role="dialog" aria-modal="true">
          <img src="/emblem-640.webp" alt="" width="120" height="120" />
          <b class="lg-display">{{ t.secretTitle }}</b>
          <p>{{ t.secretText }}</p>
          <div class="secret-btns">
            <button class="lg lg-btn lg-drop lg-press small" type="button" @click="secret = false">{{ t.secretClose }}</button>
            <a class="lg lg-btn lg-tinted lg-press small" :href="t.links.source">{{ t.secretAsk }}</a>
          </div>
        </div>
      </div>
    </Transition>
  </div>
</template>

<style scoped>
.home {
  position: relative;
  max-width: 1180px;
  margin: 0 auto;
  padding: 16px 16px 96px;
  overflow-x: clip;
}
@media (min-width: 768px) {
  .home { padding: 40px 32px 120px; }
}

/* ---------- hero ---------- */
.hero {
  position: relative;
  display: grid;
  gap: 40px;
  align-items: center;
  padding: 12px 0 48px;
}
@media (min-width: 960px) {
  .hero { grid-template-columns: 1.1fr 1fr; gap: 48px; padding: 40px 0 80px; }
}

.eyebrow {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 6px 16px 6px 7px;
  font-size: 14px;
  color: var(--vp-c-text-1);
  text-decoration: none;
  margin-bottom: 24px;
}
.eyebrow .dim { color: var(--vp-c-text-2); }
.eyebrow .arrow { color: var(--vp-c-text-3); }

.hero h1 {
  font-size: clamp(42px, 8.6vw, 78px);
  line-height: 1;
  font-weight: 800;
  letter-spacing: -0.035em;
  margin: 0 0 22px;
  --lg-delay: 60ms;
}

.lead {
  font-size: clamp(16px, 2.2vw, 19px);
  line-height: 1.65;
  color: var(--vp-c-text-2);
  max-width: 560px;
  margin: 0 0 30px;
  --lg-delay: 120ms;
}

.actions { display: flex; flex-wrap: wrap; gap: 12px; --lg-delay: 180ms; }

.meta {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  list-style: none;
  padding: 0;
  margin: 28px 0 0;
  --lg-delay: 240ms;
}

/* ---------- stage: glass box holding the emblem ---------- */
.hero-visual {
  position: relative;
  display: flex;
  justify-content: center;
  padding: 56px 0 0;
}

.stage-wrap {
  position: relative;
  width: min(440px, 82vw);
  perspective: 1000px;
}


.stage {
  margin: 0;
  padding: 12px;
  --lg-r: 40px;
  transform: rotateX(var(--lg-rx, 0deg)) rotateY(var(--lg-ry, 0deg));
  will-change: transform;
}
.stage img {
  display: block;
  width: 100%;
  height: auto;
  border-radius: 30px;
  background: #000;
  box-shadow: 0 24px 50px -24px rgba(var(--lg-accent-2), 0.6), 0 0 0 1px rgba(255, 255, 255, 0.06);
  user-select: none;
}

/* Canvas for the electric strands, over the emblem picture (same box as the img). */
.zap {
  position: absolute;
  inset: 12px;
  border-radius: 30px;
  overflow: hidden;
}

/* A band of light sweeping across, like a reflection on glass. */
.sheen {
  position: absolute;
  inset: 12px;
  border-radius: 30px;
  pointer-events: none;
  background: linear-gradient(105deg, transparent 38%, rgba(255, 255, 255, 0.28) 48%, rgba(255, 255, 255, 0.05) 54%, transparent 62%);
  background-size: 260% 100%;
  background-position: 130% 0;
  mix-blend-mode: screen;
  animation: sweep 7s cubic-bezier(0.45, 0, 0.2, 1) 1.2s infinite;
}
@keyframes sweep {
  0% { background-position: 130% 0; }
  38%, 100% { background-position: -30% 0; }
}

/* Glare that follows the mouse across the glass. */
.glare {
  position: absolute;
  inset: 0;
  border-radius: inherit;
  pointer-events: none;
  background: radial-gradient(55% 45% at var(--lg-sx, 30%) var(--lg-sy, 15%), rgba(255, 255, 255, 0.28), transparent 70%);
  mix-blend-mode: soft-light;
}

/* Floor reflection under the box. */
.reflection {
  position: relative;
  height: 120px;
  margin: 6px 12px 0;
  overflow: hidden;
  pointer-events: none;
  -webkit-mask: linear-gradient(rgba(0, 0, 0, 0.38), transparent 75%);
  mask: linear-gradient(rgba(0, 0, 0, 0.38), transparent 75%);
}
.reflection img {
  display: block;
  width: 100%;
  height: auto;
  border-radius: 30px;
  transform: scaleY(-1);
  filter: blur(2px) saturate(1.3);
}

.float {
  --lg-r: 999px;
  --lg-tint: var(--lg-dialog-tint);
  --lg-blur: 14px;
  position: absolute;
  z-index: 2;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 16px;
  font-size: 13.5px;
  font-weight: 600;
  white-space: nowrap;
  animation: bob 6s ease-in-out infinite;
}
.f1 { left: -36px; top: 22%; }
.f2 { right: -40px; top: 52%; animation-delay: -3s; }
.dot { width: 9px; height: 9px; border-radius: 50%; }
.dot.ok { background: #22c55e; box-shadow: 0 0 12px #22c55e; }
.dot.cyan { background: #22d3ee; box-shadow: 0 0 12px #22d3ee; }
@keyframes bob {
  0%, 100% { translate: 0 0; }
  50% { translate: 0 -9px; }
}
@media (max-width: 640px) {
  .f1 { left: -6px; top: 6%; }
  .f2 { display: none; }
}

/* ---------- the free glass drop ---------- */
.orb-wrap {
  position: absolute;
  left: 0;
  top: 0;
  z-index: 4;
  width: 132px;
  touch-action: none;
  cursor: grab;
  animation: orb-float 9s ease-in-out infinite;
}
.orb-wrap.dragging { cursor: grabbing; animation-play-state: paused; }
@keyframes orb-float {
  0%, 100% { translate: 0 0; }
  33% { translate: 10px -14px; }
  66% { translate: -8px 8px; }
}
.orb {
  width: 132px;
  height: 132px;
  --lg-glow-r: 1.6;
  --lg-tint: rgba(255, 255, 255, 0.04);
  --lg-blur: 0.5px;
  --lg-sat: 120%;
  --lg-rim-w: 1.5px;
  box-shadow:
    0 20px 40px -14px rgba(0, 0, 0, 0.25),
    inset 0 2px 3px rgba(255, 255, 255, 0.7),
    inset 0 -6px 14px rgba(255, 255, 255, 0.18),
    inset 0 0 22px rgba(255, 255, 255, 0.12);
}
@media (max-width: 640px) {
  .orb-wrap, .orb { width: 92px; }
  .orb { height: 92px; }
}

/* ---------- lower sections ---------- */
.sec-title {
  font-size: clamp(32px, 5vw, 48px);
  font-weight: 800;
  letter-spacing: -0.03em;
  line-height: 1.05;
  margin: 0 0 32px;
}
.more-link {
  display: inline-block;
  font-size: 15px;
  font-weight: 600;
  color: rgb(var(--lg-accent));
  text-decoration: none;
}
.more-link:hover { text-decoration: underline; }

/* install track */
.install { padding: 72px 0 40px; }
.track {
  position: relative;
  list-style: none;
  margin: 0 0 28px;
  padding: 0;
  display: grid;
  gap: 28px;
}
@media (min-width: 860px) {
  .track { grid-template-columns: repeat(3, 1fr); gap: 36px; }
  .track::before {
    content: '';
    position: absolute;
    top: 23px;
    left: 24px;
    right: 24px;
    height: 2px;
    border-radius: 2px;
    background: linear-gradient(90deg, rgba(var(--lg-accent), 0.5), rgba(var(--lg-accent-2), 0.5));
  }
}
@media (max-width: 859px) {
  .track { padding-left: 64px; }
  .track::before {
    content: '';
    position: absolute;
    left: 23px;
    top: 24px;
    bottom: 24px;
    width: 2px;
    background: linear-gradient(rgba(var(--lg-accent), 0.5), rgba(var(--lg-accent-2), 0.5));
  }
  .track li { position: relative; }
  .track .node { position: absolute; left: -64px; top: -4px; }
}
.node {
  display: grid;
  place-items: center;
  width: 48px;
  height: 48px;
  margin-bottom: 14px;
  font-size: 21px;
  font-weight: 800;
  color: rgb(var(--lg-accent));
  --lg-tint: rgba(var(--lg-surface), 0.6);
  --lg-blur: 8px;
}
.track h3 { font-size: 24px; font-weight: 800; margin: 0 0 2px; }
.track p { font-size: 15px; line-height: 1.6; color: var(--vp-c-text-2); margin: 0; }

.term {
  --lg-r: 24px;
  --lg-tint: rgba(12, 14, 22, 0.78);
  --lg-blur: 16px;
  color: #e7ebf5;
  max-width: 640px;
  margin-bottom: 20px;
  overflow: hidden;
}
.term-bar { display: flex; align-items: center; gap: 12px; padding: 12px 16px; border-bottom: 1px solid rgba(255, 255, 255, 0.08); }
.dots { display: flex; gap: 6px; }
.dots i { width: 11px; height: 11px; border-radius: 50%; background: #ff5f57; }
.dots i:nth-child(2) { background: #febc2e; }
.dots i:nth-child(3) { background: #28c840; }
.term-title { flex: 1; font-size: 13px; color: rgba(231, 235, 245, 0.5); }
.term-copy {
  font-size: 12.5px;
  font-weight: 600;
  padding: 4px 12px;
  border-radius: 999px;
  color: #e7ebf5;
  background: rgba(255, 255, 255, 0.1);
  box-shadow: inset 0 1px 1px rgba(255, 255, 255, 0.15);
  transition: background-color 0.2s ease;
}
.term-copy:hover { background: rgba(255, 255, 255, 0.18); }
.term pre { margin: 0; padding: 16px 18px 18px; overflow-x: auto; font-size: 13.5px; line-height: 1.9; }
.term .cmd b { color: rgb(var(--lg-accent-2)); font-weight: 600; margin-right: 6px; }

/* what's new strip */
.news-strip {
  --lg-r: 30px;
  display: grid;
  gap: 18px 40px;
  padding: 28px 30px;
  margin: 48px 0;
}
@media (min-width: 860px) {
  .news-strip { grid-template-columns: 220px 1fr; align-items: center; }
}
.ns-left { display: flex; flex-direction: column; align-items: flex-start; gap: 2px; }
.ns-label { font-size: 12.5px; font-weight: 700; letter-spacing: 0.08em; text-transform: uppercase; color: var(--vp-c-text-3); }
.ns-ver { font-size: 50px; font-weight: 800; line-height: 1.05; letter-spacing: -0.03em; }
.ns-date { font-size: 14px; color: var(--vp-c-text-2); }
.ns-link { margin-top: 10px; font-size: 14px; font-weight: 600; color: rgb(var(--lg-accent)); text-decoration: none; }
.ns-link:hover { text-decoration: underline; }
.ns-list { list-style: none; margin: 0; padding: 0; display: grid; gap: 12px; }
.ns-list li { position: relative; padding-left: 22px; font-size: 15px; line-height: 1.6; }
.ns-list li::before {
  content: '';
  position: absolute;
  left: 2px;
  top: 0.62em;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: linear-gradient(135deg, rgb(var(--lg-accent)), rgb(var(--lg-accent-2)));
}
.ns-list :deep(code) { font-size: 0.85em; padding: 1px 5px; border-radius: 6px; background: var(--vp-c-default-soft); }

/* module marquee */
.mods { padding: 40px 0 20px; }
.mods-head { display: flex; flex-wrap: wrap; align-items: baseline; gap: 6px 18px; margin-bottom: 18px; }
.mods-head .sec-title { margin: 0; }
.mods-lead { color: var(--vp-c-text-2); font-size: 16px; flex: 1; }
.marquee {
  margin: 0 -16px;
  padding: 8px 0 18px;
  overflow: hidden;
  -webkit-mask: linear-gradient(90deg, transparent, #000 8%, #000 92%, transparent);
  mask: linear-gradient(90deg, transparent, #000 8%, #000 92%, transparent);
}
.marquee-track { display: flex; gap: 12px; width: max-content; animation: marquee 38s linear infinite; }
.marquee:hover .marquee-track { animation-play-state: paused; }
@keyframes marquee {
  to { transform: translateX(calc(-50% - 6px)); }
}
.pill {
  display: inline-flex;
  align-items: center;
  gap: 10px;
  padding: 12px 20px;
  --lg-tint: rgba(var(--lg-surface), 0.42);
  --lg-blur: 10px;
  color: var(--vp-c-text-1);
  text-decoration: none;
  white-space: nowrap;
  font-size: 15px;
}
.pill-dot { width: 8px; height: 8px; border-radius: 50%; background: rgba(var(--lg-ink), 0.3); }
.pill-dot.is-meta { background: rgb(var(--lg-accent)); box-shadow: 0 0 10px rgb(var(--lg-accent)); }

/* the secret */
.stage { cursor: pointer; }
.secret-veil {
  position: fixed;
  inset: 0;
  z-index: 100;
  display: grid;
  place-items: center;
  padding: 16px;
  background: rgba(0, 0, 0, 0.35);
  -webkit-backdrop-filter: blur(8px);
  backdrop-filter: blur(8px);
}
.secret {
  width: min(360px, 100%);
  padding: 28px 24px 22px;
  text-align: center;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
}
.secret img { border-radius: 24px; margin-bottom: 8px; box-shadow: 0 16px 40px -12px rgba(var(--lg-accent-2), 0.7); }
.secret b { font-size: 30px; font-weight: 800; letter-spacing: 0.08em; }
.secret p { margin: 0 0 14px; color: var(--vp-c-text-2); }
.secret-btns { display: flex; gap: 10px; }
.secret-enter-active { transition: opacity 0.3s ease; }
.secret-enter-active .secret { transition: transform var(--lg-press-dur, 500ms) var(--lg-press-ease, ease); }
.secret-leave-active { transition: opacity 0.2s ease; }
.secret-enter-from,
.secret-leave-to { opacity: 0; }
.secret-enter-from .secret { transform: scale(0.85); }
</style>

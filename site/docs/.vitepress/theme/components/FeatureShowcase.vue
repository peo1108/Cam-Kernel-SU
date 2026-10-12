<script setup lang="ts">
// The app's own panels, floating on the page, one feature at a time. Texts on them are the
// Manager's strings (values/strings.xml, values-vi/strings.xml); the data is illustrative.
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { data as changelog } from '../../data/changelog.data'
import { data as release } from '../../data/release.data'

const props = defineProps<{ lang: 'vi' | 'en' | 'zh' }>()

const T = {
  vi: {
    title: 'Bên trong.',
    lead: '',
    items: [
      { key: 'guard', name: 'Chống bootloop', desc: 'Ngã ba lần, tự đứng dậy.' },
      { key: 'conflict', name: 'Xung đột module', desc: 'Biết ai giẫm chân ai.' },
      { key: 'boot', name: 'Tự ẩn bootloader', desc: 'Khoá, dù chưa khoá.' },
      { key: 'hide', name: 'Kiểm tra ẩn root', desc: 'Nhìn mình bằng mắt kẻ khác.' },
      { key: 'ota', name: 'Tự cập nhật', desc: 'Lặng lẽ mới lên.' },
    ],
    s: {
      guardNotice: 'SU Kernel đã tắt 1 module vì máy khởi động lỗi liên tục. Nhấn để xem',
      guardTitle: 'Bị tắt do lỗi khởi động',
      guardSummary: 'Module bật lại sẽ có hiệu lực sau khi khởi động lại.',
      guardBadge: 'Tự tắt do lỗi khởi động',
      guardModule: 'Module mới cài',
      dismiss: 'Bỏ qua',
      reenable: 'Bật lại',
      conflictTitle: 'Chống xung đột module',
      conflictCount: '1 xung đột',
      conflictBadge: 'Xung đột',
      conflictFile: 'File /system/etc/hosts: bindhosts, AdAway',
      bootTitle: 'Tự ẩn bootloader',
      bootOn: 'Đang bật · không còn gì lộ',
      bootProps: 'Prop hệ thống',
      hideClean: 'Không phát hiện điểm lộ',
      hidePassed: 'Đã ẩn tốt',
      hideSame: 'Như cũ',
      hideRows: ['Mount của module', 'Đường dẫn su', 'Context SELinux', 'Prop bootloader', 'Hồ sơ ứng dụng'],
      hideRules: 'Rule phát hiện',
      otaTitle: 'đã có',
      otaDownloading: 'Đang tải bản cập nhật…',
      otaInstalling: 'Đang cài bản cập nhật…',
    },
  },
  en: {
    title: 'Inside.',
    lead: '',
    items: [
      { key: 'guard', name: 'Bootloop guard', desc: 'Falls three times, gets back up.' },
      { key: 'conflict', name: 'Module conflicts', desc: 'Knows who steps on whom.' },
      { key: 'boot', name: 'Bootloader hiding', desc: 'Locked, even when it isn’t.' },
      { key: 'hide', name: 'Root hiding check', desc: 'See yourself through their eyes.' },
      { key: 'ota', name: 'Updates itself', desc: 'Quietly new.' },
    ],
    s: {
      guardNotice: 'SU Kernel disabled 1 module(s) because the device kept failing to boot. Tap to review',
      guardTitle: 'Disabled by boot guard',
      guardSummary: 'A module enabled again takes effect after a reboot.',
      guardBadge: 'Disabled by boot guard',
      guardModule: 'Newly installed module',
      dismiss: 'Dismiss',
      reenable: 'Enable again',
      conflictTitle: 'Module conflicts',
      conflictCount: '1 conflict',
      conflictBadge: 'Conflict',
      conflictFile: 'File /system/etc/hosts: bindhosts, AdAway',
      bootTitle: 'Hide bootloader',
      bootOn: 'On · nothing leaks',
      bootProps: 'System props',
      hideClean: 'No leaks found',
      hidePassed: 'Hidden well',
      hideSame: 'Unchanged',
      hideRows: ['Module mounts', 'su paths', 'SELinux context', 'Bootloader props', 'App profiles'],
      hideRules: 'Detection rules',
      otaTitle: 'is available',
      otaDownloading: 'Downloading update…',
      otaInstalling: 'Installing update…',
    },
  },
  zh: {
    title: '内里。',
    lead: '',
    items: [
      { key: 'guard', name: '防卡开机', desc: '摔倒三次，自己站起来。' },
      { key: 'conflict', name: '模块冲突', desc: '知道谁踩了谁的脚。' },
      { key: 'boot', name: '隐藏 Bootloader', desc: '已上锁，即使并没有。' },
      { key: 'hide', name: 'Root 隐藏检测', desc: '用别人的眼睛看自己。' },
      { key: 'ota', name: '自动更新', desc: '悄悄变新。' },
    ],
    s: {
      guardNotice: 'SU Kernel 因设备多次启动失败已停用 1 个模块。点按查看',
      guardTitle: '已被防卡开机停用',
      guardSummary: '重新启用的模块将在重启后生效。',
      guardBadge: '因启动失败自动停用',
      guardModule: '新安装的模块',
      dismiss: '忽略',
      reenable: '重新启用',
      conflictTitle: '模块冲突',
      conflictCount: '1 处冲突',
      conflictBadge: '冲突',
      conflictFile: '文件 /system/etc/hosts：bindhosts、AdAway',
      bootTitle: '隐藏 Bootloader',
      bootOn: '已开启 · 没有泄露',
      bootProps: '系统属性',
      hideClean: '未发现泄露',
      hidePassed: '隐藏良好',
      hideSame: '无变化',
      hideRows: ['模块挂载', 'su 路径', 'SELinux 上下文', 'Bootloader 属性', '应用配置'],
      hideRules: '检测规则',
      otaTitle: '已可更新',
      otaDownloading: '正在下载更新…',
      otaInstalling: '正在安装更新…',
    },
  },
}

const t = computed(() => T[props.lang])
const version = release.latest?.version ?? changelog[0]?.version ?? ''
const otaNotes = (changelog[0]?.groups.flatMap((g) => g.items) ?? []).slice(0, 3).map((s) => s.replace(/`/g, ''))

const DURATION = 6500
const active = ref(0)
const paused = ref(false)
const visible = ref(false)
const progress = ref(0) // 0..1 for the active item, driven by requestAnimationFrame
const root = ref<HTMLElement | null>(null)
let io: IntersectionObserver | null = null

function pick(i: number) {
  active.value = i
  progress.value = 0
}
function next() {
  pick((active.value + 1) % t.value.items.length)
}

const running = computed(() => visible.value && !paused.value)
let raf = 0
let last = 0
function tick(now: number) {
  const dt = last ? now - last : 0
  last = now
  if (running.value) {
    progress.value += dt / DURATION
    if (progress.value >= 1) next()
  }
  raf = requestAnimationFrame(tick)
}

onMounted(() => {
  io = new IntersectionObserver(([e]) => (visible.value = e.isIntersecting), { threshold: 0.35 })
  if (root.value) io.observe(root.value)
  raf = requestAnimationFrame(tick)
})
onBeforeUnmount(() => {
  io?.disconnect()
  cancelAnimationFrame(raf)
})

const key = computed(() => t.value.items[active.value].key)
</script>

<template>
  <section ref="root" class="show" @pointerenter="paused = true" @pointerleave="paused = false">
    <header class="show-head lg-in">
      <h2 class="lg-display">{{ t.title }}</h2>
      <p v-if="t.lead">{{ t.lead }}</p>
    </header>

    <div class="show-grid">
      <ol class="show-list lg-in" role="tablist">
        <li v-for="(it, i) in t.items" :key="it.key">
          <button
            role="tab"
            :aria-selected="i === active"
            :class="{ on: i === active }"
            @click="pick(i)"
          >
            <span class="n cam-mono">{{ String(i + 1).padStart(2, '0') }}</span>
            <span class="name lg-display">{{ it.name }}</span>
            <span class="desc">{{ it.desc }}</span>
            <span class="track" aria-hidden="true">
              <i v-if="i === active" :style="{ transform: `scaleX(${progress})` }" />
            </span>
          </button>
        </li>
      </ol>

      <div class="sim lg-in">
        <Transition name="scr" mode="out-in">
              <!-- boot guard -->
              <div v-if="key === 'guard'" key="guard" class="pane">
                <div class="tile danger">{{ t.s.guardNotice }}</div>
                <div class="sheet">
                  <b class="lg-display">{{ t.s.guardTitle }}</b>
                  <p>{{ t.s.guardSummary }}</p>
                  <div class="tile mod">
                    <span>{{ t.s.guardModule }}</span>
                    <em class="badge red">{{ t.s.guardBadge }}</em>
                  </div>
                  <div class="btns"><span class="b ghost">{{ t.s.dismiss }}</span><span class="b solid">{{ t.s.reenable }}</span></div>
                </div>
              </div>

              <!-- conflicts -->
              <div v-else-if="key === 'conflict'" key="conflict" class="pane">
                <div class="tile mod col">
                  <div class="mod-top"><b>bindhosts</b><em class="badge amber">{{ t.s.conflictBadge }} · 1</em></div>
                  <small>{{ t.s.conflictFile }}</small>
                </div>
                <div class="tile mod col">
                  <div class="mod-top"><b>AdAway</b><em class="badge amber">{{ t.s.conflictBadge }} · 1</em></div>
                  <small>{{ t.s.conflictFile }}</small>
                </div>
                <div class="tile mod col dim">
                  <div class="mod-top"><b>Zygisk Next</b></div>
                </div>
              </div>

              <!-- bootloader -->
              <div v-else-if="key === 'boot'" key="boot" class="pane">
                <div class="tile switch-row">
                  <div><b>{{ t.s.bootTitle }}</b><small class="ok">{{ t.s.bootOn }}</small></div>
                  <span class="sw on"><i /></span>
                </div>
                <div class="tile col">
                  <small class="cap">{{ t.s.bootProps }}</small>
                  <div v-for="[k, v] in [['ro.boot.verifiedbootstate', 'green'], ['ro.boot.vbmeta.device_state', 'locked'], ['ro.boot.flash.locked', '1'], ['ro.boot.warranty_bit', '0']]" :key="k" class="prop">
                    <span class="cam-mono">{{ k }}</span><b class="cam-mono">{{ v }}</b>
                  </div>
                </div>
              </div>

              <!-- hiding check -->
              <div v-else-if="key === 'hide'" key="hide" class="pane">
                <div class="tile verdict">
                  <span class="ring"><i /></span>
                  <b class="lg-display">{{ t.s.hideClean }}</b>
                </div>
                <div class="tile col">
                  <small class="cap">{{ t.s.hidePassed }}</small>
                  <div v-for="(r, i) in t.s.hideRows" :key="r" class="prop" :style="{ '--i': i }">
                    <span>{{ r }}</span><em class="badge green">{{ t.s.hideSame }}</em>
                  </div>
                </div>
                <div class="tile mod"><span>{{ t.s.hideRules }}</span><small class="cam-mono">v6 · Duck Detector</small></div>
              </div>

              <!-- OTA -->
              <div v-else key="ota" class="pane">
                <div class="sheet">
                  <b class="lg-display">SU Kernel {{ version }} {{ t.s.otaTitle }}</b>
                  <ul>
                    <li v-for="(n, i) in otaNotes" :key="i">{{ n }}</li>
                  </ul>
                  <div class="progress"><i /></div>
                  <small class="prog-label"><span class="dl">{{ t.s.otaDownloading }}</span><span class="in">{{ t.s.otaInstalling }}</span></small>
                </div>
              </div>
        </Transition>
      </div>
    </div>
  </section>
</template>

<style scoped>
.show { padding: 56px 0 40px; }
.show-head { margin-bottom: 28px; max-width: 620px; }
.show-head h2 {
  font-size: clamp(32px, 5vw, 48px);
  font-weight: 800;
  letter-spacing: -0.03em;
  line-height: 1.05;
  margin: 0 0 10px;
}
.show-head p { color: var(--vp-c-text-2); font-size: 17px; margin: 0; }

.show-grid {
  display: grid;
  gap: 32px;
  align-items: center;
}
@media (min-width: 960px) {
  .show-grid { grid-template-columns: 1fr 380px; gap: 72px; }
}

/* ---------- list (plain text, no cards) ---------- */
.show-list { list-style: none; margin: 0; padding: 0; }
.show-list button {
  display: grid;
  grid-template-columns: 34px 1fr;
  column-gap: 12px;
  width: 100%;
  text-align: left;
  padding: 16px 4px 14px;
  border-top: 1px solid var(--vp-c-divider);
  color: var(--vp-c-text-2);
  transition: color 0.25s ease;
}
.show-list li:last-child button { border-bottom: 1px solid var(--vp-c-divider); }
.show-list .n { grid-row: 1; padding-top: 6px; font-size: 12.5px; color: var(--vp-c-text-3); }
.show-list .name { grid-row: 1; grid-column: 2; font-size: 22px; font-weight: 700; line-height: 1.3; transition: color 0.25s ease; }
.show-list .desc {
  grid-column: 2;
  font-size: 15px;
  line-height: 1.6;
  max-height: 0;
  opacity: 0;
  overflow: hidden;
  transition: max-height 0.45s var(--lg-in-ease, ease), opacity 0.3s ease, margin 0.3s ease;
}
.show-list .track {
  grid-column: 2;
  display: block;
  height: 2px;
  margin-top: 12px;
  border-radius: 2px;
  background: transparent;
  overflow: hidden;
}
.show-list button.on { color: var(--vp-c-text-1); }
.show-list button.on .n { color: rgb(var(--lg-accent)); }
.show-list button.on .desc { max-height: 120px; opacity: 1; margin-top: 6px; color: var(--vp-c-text-2); }
.show-list button.on .track { background: rgba(var(--lg-ink), 0.08); }
.show-list .track i {
  display: block;
  height: 100%;
  width: 100%;
  transform-origin: left;
  background: linear-gradient(90deg, rgb(var(--lg-accent)), rgb(var(--lg-accent-2)));
}
@media (hover: hover) {
  .show-list button:not(.on):hover .name { color: var(--vp-c-text-1); }
}

/* Phones: the list becomes a scrolling row of names above the panels. */
@media (max-width: 959px) {
  .show-grid { gap: 20px; }
  .show-list {
    display: flex;
    gap: 6px;
    overflow-x: auto;
    scrollbar-width: none;
    margin: 0 -16px;
    padding: 0 16px 4px;
    order: 0;
  }
  .show-list::-webkit-scrollbar { display: none; }
  .show-list button,
  .show-list li:last-child button {
    display: block;
    width: auto;
    white-space: nowrap;
    padding: 8px 14px;
    border: none;
    border-radius: 999px;
  }
  .show-list .n,
  .show-list .track { display: none; }
  .show-list .name { font-size: 15px; }
  .show-list .desc { display: none; }
  .show-list button.on {
    background: rgba(var(--lg-surface), 0.6);
    box-shadow: inset 0 1px 1px var(--lg-bloom), 0 4px 14px -6px rgba(0, 0, 0, 0.2);
  }
}

/* ---------- floating panels ---------- */
.sim {
  position: relative;
  min-height: 440px;
  display: flex;
  align-items: center;
  justify-content: center;
  perspective: 1200px;
}
.pane {
  width: min(380px, 100%);
  display: flex;
  flex-direction: column;
  gap: 12px;
  transform: rotateY(-8deg) rotateX(4deg);
  transform-style: preserve-3d;
}
@media (max-width: 959px) {
  .sim { min-height: 0; }
  .pane { transform: none; }
}

/* Panels arrive one after another, like the app's cards. */
.pane > * {
  animation: panel-in 0.7s var(--lg-in-ease, cubic-bezier(0.2, 0.9, 0.3, 1.15)) both;
}
.pane > :nth-child(2) { animation-delay: 0.08s; }
.pane > :nth-child(3) { animation-delay: 0.16s; }
.pane > :nth-child(4) { animation-delay: 0.24s; }
@keyframes panel-in {
  from { opacity: 0; transform: translate3d(0, 28px, 0); }
}
/* A little depth between panels. */
.pane > :nth-child(2) { margin-left: 18px; }
.pane > :nth-child(3) { margin-left: 36px; }
@media (max-width: 959px) {
  .pane > :nth-child(2),
  .pane > :nth-child(3) { margin-left: 0; }
}

/* Glass panels: page glass (tint 35 %, blur, rim), sampled from the real background. */
.tile,
.sheet {
  position: relative;
  border-radius: 22px;
  padding: 14px 16px;
  font-size: 14px;
  color: var(--vp-c-text-1);
  background: rgba(var(--lg-surface), 0.42);
  -webkit-backdrop-filter: blur(14px) saturate(160%);
  backdrop-filter: blur(14px) saturate(160%);
  box-shadow:
    0 18px 40px -18px rgba(0, 0, 0, calc(var(--lg-shadow-a) * 2)),
    inset 0 1px 2px var(--lg-bloom),
    inset 0 0 0 1px rgba(255, 255, 255, var(--lg-rim-base));
}
.sheet .tile {
  background: rgba(var(--lg-surface), 0.5);
  -webkit-backdrop-filter: none;
  backdrop-filter: none;
  box-shadow: inset 0 1px 1.5px var(--lg-bloom), inset 0 0 0 1px rgba(255, 255, 255, var(--lg-rim-base));
}
.dark .sheet .tile { background: rgba(255, 255, 255, 0.05); }
.tile small,
.sheet small { display: block; color: var(--vp-c-text-2); font-size: 12.5px; }

.col { display: flex; flex-direction: column; gap: 6px; }
.dim { opacity: 0.6; }
.cap { text-transform: uppercase; letter-spacing: 0.05em; font-weight: 700; opacity: 0.55 !important; font-size: 11px !important; }


.danger { background: rgba(239, 68, 68, 0.88) !important; color: #fff; font-weight: 600; line-height: 1.45; }
.sheet b { font-size: 17px; display: block; margin-bottom: 4px; }
.sheet p { margin: 0 0 10px; opacity: 0.75; font-size: 12.5px; line-height: 1.45; }
.mod { display: flex; justify-content: space-between; align-items: center; gap: 8px; }
.mod.col { align-items: stretch; }
.mod-top { display: flex; justify-content: space-between; align-items: center; gap: 8px; }
.badge { font-style: normal; font-size: 10.5px; font-weight: 700; padding: 2px 8px; border-radius: 999px; white-space: nowrap; }
.badge.red { background: rgba(239, 68, 68, 0.16); color: #dc2626; }
.badge.amber { background: rgba(245, 158, 11, 0.18); color: #b45309; }
.badge.green { background: rgba(34, 197, 94, 0.16); color: #15803d; }
.dark .badge.red { color: #f87171; }
.dark .badge.amber { color: #fbbf24; }
.dark .badge.green { color: #4ade80; }
.btns { display: flex; gap: 8px; margin-top: 12px; }
.b { flex: 1; text-align: center; padding: 9px 0; border-radius: 999px; font-weight: 700; }
.b.ghost { background: rgba(127, 127, 160, 0.16); }
.b.solid { background: #3482ff; color: #fff; }

.switch-row { display: flex; justify-content: space-between; align-items: center; }
.switch-row b { display: block; font-size: 14.5px; }
.ok { color: #16a34a; opacity: 1 !important; }
.dark .ok { color: #4ade80; }
.sw { width: 44px; height: 26px; border-radius: 999px; background: #3482ff; position: relative; flex: none; }
.sw i { position: absolute; top: 3px; left: 21px; width: 20px; height: 20px; border-radius: 50%; background: #fff; box-shadow: 0 1px 3px rgba(0, 0, 0, 0.3); }
.prop { display: flex; justify-content: space-between; align-items: center; gap: 8px; padding: 5px 0; border-top: 1px solid rgba(127, 127, 160, 0.15); font-size: 12px; }
.prop .cam-mono { font-size: 11px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.prop b { color: #16a34a; }
.dark .prop b { color: #4ade80; }

.verdict { display: flex; align-items: center; gap: 12px; }
.verdict b { font-size: 17px; }
.ring { position: relative; width: 38px; height: 38px; flex: none; border-radius: 50%; background: conic-gradient(#22c55e var(--p, 100%), rgba(127, 127, 160, 0.2) 0); animation: ring 1.4s ease-out both; }
.ring i { position: absolute; inset: 5px; border-radius: 50%; background: rgba(255, 255, 255, 0.85); }
.dark .ring i { background: rgba(20, 20, 26, 0.9); }
.ring::after { content: '✓'; position: absolute; inset: 0; display: grid; place-items: center; color: #16a34a; font-weight: 800; font-size: 16px; }
@property --p { syntax: '<percentage>'; inherits: false; initial-value: 0%; }
@keyframes ring { from { --p: 0%; } to { --p: 100%; } }
.verdict + .col .prop { animation: row-in 0.4s ease both; animation-delay: calc(0.5s + var(--i) * 0.12s); }
@keyframes row-in { from { opacity: 0; transform: translateX(-8px); } }

.sheet ul { margin: 6px 0 12px; padding-left: 16px; list-style: disc; font-size: 12px; line-height: 1.45; opacity: 0.85; }
.sheet li + li { margin-top: 4px; }
.progress { height: 6px; border-radius: 6px; background: rgba(127, 127, 160, 0.2); overflow: hidden; }
.progress i { display: block; height: 100%; width: 100%; transform-origin: left; background: linear-gradient(90deg, #3482ff, #2de2e6); animation: dl 4.2s cubic-bezier(0.3, 0.1, 0.3, 1) 0.4s both; }
@keyframes dl { from { transform: scaleX(0); } to { transform: scaleX(1); } }
.prog-label { position: relative; height: 18px; margin-top: 6px; }
.prog-label span { position: absolute; left: 0; }
.prog-label .dl { animation: lbl-out 0.3s ease 4.6s both; }
.prog-label .in { animation: lbl-in 0.3s ease 4.6s both; }
@keyframes lbl-out { to { opacity: 0; } }
@keyframes lbl-in { from { opacity: 0; } to { opacity: 1; } }

/* Feature change: the old panels fade, the new ones rise in one by one (.pane > *). */
.scr-enter-active { transition: opacity 0.2s ease; }
.scr-leave-active { transition: opacity 0.18s ease; }
.scr-enter-from { opacity: 0; }
.scr-leave-to { opacity: 0; }

</style>

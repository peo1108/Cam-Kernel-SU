<script setup lang="ts">
import { computed, ref } from 'vue'
import { data as modules } from '../../data/modules.data'
import { fmtDate } from '../format'

const props = defineProps<{ lang: 'vi' | 'en' | 'zh' }>()

const T = {
  vi: {
    title: 'Thư viện module',
    lead: 'Module chọn lọc chạy tốt với SU Kernel. Cũng có trong app: tab Module → Thư viện Module.',
    search: 'Tìm module...',
    all: 'Tất cả',
    meta: 'Metamodule',
    normal: 'Module',
    metaNote: 'Chỉ cài một metamodule: nó lo việc mount module khác vào /system.',
    zip: 'Tải zip',
    source: 'Mã nguồn',
    updated: 'Cập nhật',
    empty: 'Không có module nào khớp.',
    unavailable: 'Chưa lấy được danh sách module lúc dựng trang.',
  },
  en: {
    title: 'Module library',
    lead: 'Hand-picked modules that work well with SU Kernel. Also in the app: Modules tab → Repos.',
    search: 'Search modules...',
    all: 'All',
    meta: 'Metamodule',
    normal: 'Module',
    metaNote: 'Install only one metamodule: it mounts the other modules into /system.',
    zip: 'Download zip',
    source: 'Source',
    updated: 'Updated',
    empty: 'No module matches.',
    unavailable: 'The module list could not be fetched when this page was built.',
  },
  zh: {
    title: '模块库',
    lead: '与 SU Kernel 配合良好的精选模块。应用内也有：模块页 → 模块库。',
    search: '搜索模块…',
    all: '全部',
    meta: '元模块',
    normal: '模块',
    metaNote: '只安装一个元模块：它负责把其他模块挂载到 /system。',
    zip: '下载 zip',
    source: '源码',
    updated: '更新于',
    empty: '没有匹配的模块。',
    unavailable: '构建页面时未能获取模块列表。',
  },
}

const t = computed(() => T[props.lang])
const q = ref('')
const kind = ref<'all' | 'meta' | 'normal'>('all')

const shown = computed(() => {
  const s = q.value.trim().toLowerCase()
  return modules
    .filter((m) => (kind.value === 'all' ? true : kind.value === 'meta' ? m.metamodule : !m.metamodule))
    .filter((m) => !s || `${m.name} ${m.id} ${m.summary} ${m.authors.map((a) => a.name).join(' ')}`.toLowerCase().includes(s))
    .sort((a, b) => Number(b.metamodule) - Number(a.metamodule) || b.stars - a.stars)
})

const stars = (n: number) => (n >= 1000 ? `${(n / 1000).toFixed(1)}k` : String(n))
</script>

<template>
  <div class="cam-page">
    <header class="cam-page-head">
      <h1>{{ t.title }}</h1>
      <p>{{ t.lead }}</p>
    </header>

    <div class="bar lg lg-hover lg-in">
      <label class="search">
        <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><circle cx="11" cy="11" r="7" /><path d="M20 20l-3.5-3.5" /></svg>
        <input v-model="q" type="search" :placeholder="t.search" :aria-label="t.search" />
      </label>
      <div class="lg-seg" role="tablist">
        <button role="tab" :aria-selected="kind === 'all'" :class="{ on: kind === 'all' }" @click="kind = 'all'">{{ t.all }}</button>
        <button role="tab" :aria-selected="kind === 'meta'" :class="{ on: kind === 'meta' }" @click="kind = 'meta'">{{ t.meta }}</button>
        <button role="tab" :aria-selected="kind === 'normal'" :class="{ on: kind === 'normal' }" @click="kind = 'normal'">{{ t.normal }}</button>
      </div>
    </div>

    <p v-if="!modules.length" class="note">{{ t.unavailable }}</p>
    <p v-else-if="kind !== 'normal'" class="note">ⓘ {{ t.metaNote }}</p>

    <div class="grid">
      <article v-for="m in shown" :key="m.id" class="mod lg lg-hover lg-in">
        <header>
          <h2 class="lg-display">{{ m.name }}</h2>
          <span v-if="m.metamodule" class="cam-chip">{{ t.meta }}</span>
        </header>
        <div class="by">
          <template v-for="(a, i) in m.authors" :key="a.name">
            <span v-if="i">, </span><a :href="a.link" target="_blank" rel="noreferrer">{{ a.name }}</a>
          </template>
        </div>
        <p class="sum">{{ m.summary }}</p>
        <div class="facts">
          <span class="cam-chip muted">★ {{ stars(m.stars) }}</span>
          <span v-if="m.release" class="cam-chip muted cam-mono">{{ m.release.name }}</span>
          <span class="when">{{ t.updated }} {{ fmtDate(m.updated, lang) }}</span>
        </div>
        <footer>
          <a v-if="m.release" class="lg lg-btn lg-tinted lg-press small" data-lens="droplet" :href="m.release.downloadUrl">{{ t.zip }}</a>
          <a class="lg lg-btn lg-drop lg-press small" data-lens="droplet" :href="m.url" target="_blank" rel="noreferrer">{{ t.source }}</a>
        </footer>
      </article>
    </div>
    <p v-if="modules.length && !shown.length" class="note">{{ t.empty }}</p>
  </div>
</template>

<style scoped>
.bar {
  --lg-r: 999px;
  --lg-tint: var(--lg-dialog-tint);
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
  padding: 8px;
  margin-bottom: 14px;
}

.search {
  flex: 1 1 220px;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 0 12px;
  color: var(--vp-c-text-3);
}
.search input {
  flex: 1;
  min-width: 0;
  height: 40px;
  font-size: 15px;
  color: var(--vp-c-text-1);
  background: transparent;
}


.note { color: var(--vp-c-text-2); font-size: 14px; margin: 0 4px 16px; }

.grid {
  display: grid;
  gap: 16px;
  grid-template-columns: repeat(auto-fill, minmax(min(100%, 320px), 1fr));
}

.mod {
  --lg-r: 26px;
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 20px 22px;
  min-width: 0;
}
.mod header { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.mod h2 { font-size: 22px; font-weight: 800; margin: 0; line-height: 1.2; }
.by { font-size: 13.5px; color: var(--vp-c-text-3); }
.by a { color: var(--vp-c-text-2); text-decoration: none; }
.by a:hover { color: var(--vp-c-brand-1); }
.sum { flex: 1; margin: 0; font-size: 14.5px; line-height: 1.6; color: var(--vp-c-text-2); }
.facts { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; }
.when { font-size: 12.5px; color: var(--vp-c-text-3); }
.mod footer { display: flex; flex-wrap: wrap; gap: 8px; margin-top: 4px; }
</style>

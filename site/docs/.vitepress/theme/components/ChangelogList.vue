<script setup lang="ts">
import { computed } from 'vue'
import { data as changelog } from '../../data/changelog.data'
import { data as release } from '../../data/release.data'
import { inline, fmtDate } from '../format'

const props = defineProps<{ lang: 'vi' | 'en' | 'zh' }>()

const T = {
  vi: {
    title: 'Nhật ký cập nhật',
    lead: 'Mọi bản phát hành của SU Kernel. Cùng nội dung với màn "Có gì mới" trong app.',
    latest: 'Mới nhất',
    download: 'Tải bản này',
  },
  en: {
    title: 'Changelog',
    lead: 'Every SU Kernel release. Same text as the "What’s new" screen in the app. Entries are written in Vietnamese.',
    latest: 'Latest',
    download: 'Download',
  },
  zh: {
    title: '更新日志',
    lead: 'SU Kernel 的每一个版本。与应用内“新功能”页面内容相同，日志以越南语撰写。',
    latest: '最新',
    download: '下载',
  },
}

const t = computed(() => T[props.lang])
const apkFor = (v: string) =>
  [release.latest, ...release.older].find((r) => r?.version === v)?.apk?.url
</script>

<template>
  <div class="cam-page">
    <header class="cam-page-head">
      <h1>{{ t.title }}</h1>
      <p>{{ t.lead }}</p>
    </header>

    <ol class="timeline">
      <li v-for="(e, i) in changelog" :id="'v' + e.version.replaceAll('.', '-')" :key="e.version" class="entry">
        <div class="rail"><span class="node" :class="{ first: i === 0 }" /></div>
        <article class="card lg lg-hover lg-in">
          <header class="card-head">
            <h2 class="lg-display">
              <a :href="'#v' + e.version.replaceAll('.', '-')">v{{ e.version }}</a>
            </h2>
            <span class="date">{{ fmtDate(e.date, lang) }}</span>
            <span v-if="i === 0" class="cam-chip ok">{{ t.latest }}</span>
            <a v-if="apkFor(e.version)" class="lg lg-btn lg-drop lg-press small dl" data-lens="droplet" :href="apkFor(e.version)">{{ t.download }}</a>
          </header>
          <section v-for="(g, gi) in e.groups" :key="gi" class="group">
            <h3 v-if="g.title">{{ g.title }}</h3>
            <ul>
              <li v-for="(it, ii) in g.items" :key="ii" v-html="inline(it)" />
            </ul>
          </section>
        </article>
      </li>
    </ol>
  </div>
</template>

<style scoped>
.timeline { list-style: none; margin: 0; padding: 0; }

.entry {
  display: grid;
  grid-template-columns: 22px 1fr;
  gap: 14px;
}
@media (min-width: 768px) {
  .entry { grid-template-columns: 28px 1fr; gap: 20px; }
}

.rail {
  position: relative;
  display: flex;
  justify-content: center;
}
.rail::before {
  content: '';
  position: absolute;
  top: 0;
  bottom: 0;
  width: 2px;
  background: linear-gradient(180deg, rgba(124, 92, 255, 0.45), rgba(34, 211, 238, 0.25));
}
.entry:last-child .rail::before { bottom: auto; height: 34px; }

.node {
  position: relative;
  margin-top: 28px;
  width: 14px;
  height: 14px;
  border-radius: 50%;
  background: var(--lg-base);
  border: 3px solid var(--vp-c-brand-2);
}
.node.first {
  background: linear-gradient(135deg, rgb(var(--lg-accent)), rgb(var(--lg-accent-2)));
  border-color: transparent;
  box-shadow: 0 0 0 5px rgba(99, 102, 241, 0.18), 0 0 18px rgba(34, 211, 238, 0.6);
}

.card {
  --lg-r: 26px;
  padding: 22px 24px 20px;
  margin-bottom: 18px;
  min-width: 0;
}

.card-head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px 12px;
  margin-bottom: 6px;
}
.card-head h2 { font-size: 26px; font-weight: 800; margin: 0; line-height: 1.2; }
.card-head h2 a { color: inherit; text-decoration: none; }
.card-head h2 a:hover { color: var(--vp-c-brand-1); }
.date { color: var(--vp-c-text-2); font-size: 14px; }
.dl { margin-left: auto; }

.group h3 {
  font-size: 13px;
  font-weight: 700;
  letter-spacing: 0.04em;
  text-transform: uppercase;
  color: var(--vp-c-brand-1);
  margin: 14px 0 6px;
}
.group ul {
  list-style: disc;
  margin: 0;
  padding-left: 20px;
  color: var(--vp-c-text-1);
  font-size: 15px;
  line-height: 1.65;
}
.group li + li { margin-top: 6px; }
.group :deep(code) {
  font-family: var(--vp-font-family-mono);
  font-size: 0.85em;
  padding: 1px 6px;
  border-radius: 6px;
  background: var(--vp-c-default-soft);
  overflow-wrap: anywhere;
}
</style>

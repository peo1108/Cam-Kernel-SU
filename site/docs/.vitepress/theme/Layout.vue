<script setup lang="ts">
// Wraps the default layout with the glass background (layer 1) and starts the glass runtime.
import DefaultTheme from 'vitepress/theme'
import { useRoute } from 'vitepress'
import { nextTick, onMounted, watch } from 'vue'

const route = useRoute()

onMounted(async () => {
  const { initGlass } = await import('./glass/runtime')
  initGlass()
})

watch(
  () => route.path,
  async () => {
    await nextTick()
    const { refreshGlass } = await import('./glass/runtime')
    refreshGlass()
  },
)
</script>

<template>
  <canvas id="lg-bg" aria-hidden="true" />
  <DefaultTheme.Layout />
</template>

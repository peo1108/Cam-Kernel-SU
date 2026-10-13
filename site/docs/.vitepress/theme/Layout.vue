<script setup lang="ts">
// Wraps the default layout with the glass background (layer 1) and starts the glass runtime.
import DefaultTheme from 'vitepress/theme'
import { useRoute } from 'vitepress'
import { nextTick, onMounted, ref, watch } from 'vue'

const route = useRoute()
const splash = ref<HTMLCanvasElement | null>(null)

onMounted(async () => {
  const { initGlass } = await import('./glass/runtime')
  initGlass()
  // Fluid ink behind the pointer. It only moves when the visitor moves, so it stays on under
  // reduced motion (Windows turns that on with "Animation effects" off). Phones get a lighter simulation.
  if (splash.value) {
    const coarse = matchMedia('(pointer: coarse)').matches
    const { startSplash } = await import('./glass/splash')
    startSplash(splash.value, {
      hues: [0.5, 0.56, 0.61, 0.7, 0.78], // cyan, sky, Miuix blue, indigo, violet
      splatRadius: 0.12,
      ...(coarse ? { simResolution: 64, dyeResolution: 512, pressureIterations: 12 } : {}),
    })
  }
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
  <canvas ref="splash" class="lg-splash" aria-hidden="true" />
</template>

<style>
.lg-splash {
  position: fixed;
  inset: 0;
  z-index: 60;
  width: 100vw;
  height: 100vh;
  pointer-events: none;
}
</style>

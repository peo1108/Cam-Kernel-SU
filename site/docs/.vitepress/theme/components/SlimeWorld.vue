<script setup lang="ts">
// The Manager's roaming slimes (ui/slime/RoamWorld.kt, RoamSocial.kt) for the web home page.
// They walk along the top edges of the page's glass ([data-ledge]), hop between them, fall,
// can be picked up and thrown, react to pokes, play together (high five, chase, prank, brawl),
// chase a laser dot (long press on empty space), follow the reader down the page and sleep at
// night. Colours, sizes and personalities come from Cast in ArenaSlime.kt.
import { onBeforeUnmount, onMounted, ref } from 'vue'

const CAST = [
  { name: 'Mochi', body: '#ff9cc8', deep: '#d6457e', glow: '#ffe6f2', size: 1, stretch: 1, speed: 60, trait: 'bossy' },
  { name: 'Bơ', body: '#a6e07a', deep: '#4c9a33', glow: '#f1ffe0', size: 1.3, stretch: 0.95, speed: 38, trait: 'gentle' },
  { name: 'Soda', body: '#8fd3ff', deep: '#2b7fc4', glow: '#e8f7ff', size: 1, stretch: 1.16, speed: 62, trait: 'showoff' },
  { name: 'Chanh', body: '#ffe27a', deep: '#cc9a12', glow: '#fff9de', size: 0.66, stretch: 1, speed: 95, trait: 'trouble' },
] as const

const layer = ref<HTMLElement | null>(null)

type Mode = 'wait' | 'idle' | 'walk' | 'hop' | 'air' | 'held' | 'react' | 'sleep' | 'gone'
type Mood = 'normal' | 'happy' | 'angry' | 'dizzy' | 'wow' | 'sleep' | 'sneak'

interface Ledge { el: Element; l: number; r: number; y: number }
interface Hop { fx: number; fy: number; tx: number; ty: number; dur: number; H: number; to: Ledge | null; spin: number; crouch: number }
interface Slime {
  k: number
  el: HTMLElement
  rig: HTMLElement
  face: SVGGElement
  bubble: HTMLElement
  w: number
  h: number
  x: number
  y: number
  vx: number
  vy: number
  mode: Mode
  t: number
  dir: number
  on: Ledge | null
  sq: number
  sqv: number
  tilt: number
  spin: number
  hop: Hop | null
  think: number
  walkTo: number
  speed: number
  mood: Mood
  moodUntil: number
  sayUntil: number
  blinkAt: number
  blinkUntil: number
  taps: number[]
  react: string
  reactDur: number
  dizzyAfter: boolean
  duo: Duo | null
  pounceAt: number
  laserHopAt: number
  zAt: number
  hidden: boolean
}
interface Duo { kind: 'five' | 'chase' | 'prank' | 'brawl'; a: Slime; b: Slime; phase: number; t0: number; tick: number }

let stop = () => {}

onMounted(() => {
  const root = layer.value
  if (!root) return
  const host = root.parentElement as HTMLElement
  const els = [...root.querySelectorAll<HTMLElement>('.sl')]
  const G = 2400
  const mobile = innerWidth < 640
  const BASE = mobile ? 40 : 52
  const rnd = (a: number, b: number) => a + Math.random() * (b - a)
  const pick = <T,>(xs: readonly T[]) => xs[Math.floor(Math.random() * xs.length)]
  let now = 0

  const S: Slime[] = els.map((el, k) => {
    const c = CAST[k]
    const w = BASE * c.size
    const h = w * (60 / 64) * c.stretch
    el.style.width = `${w}px`
    el.style.height = `${h}px`
    return {
      k, el, w, h,
      rig: el.querySelector('.rig') as HTMLElement,
      face: el.querySelector('.face') as SVGGElement,
      bubble: el.querySelector('.say') as HTMLElement,
      x: 0, y: -999, vx: 0, vy: 0, mode: 'wait', t: 0, dir: k % 2 ? -1 : 1, on: null,
      sq: 0, sqv: 0, tilt: 0, spin: 0, hop: null, think: 0, walkTo: 0, speed: c.speed,
      mood: 'normal', moodUntil: 0, sayUntil: 0, blinkAt: rnd(1, 4), blinkUntil: 0, taps: [],
      react: '', reactDur: 0, dizzyAfter: false, duo: null, pounceAt: -9, laserHopAt: -9, zAt: 0, hidden: false,
    }
  })

  /* ---------------- the world: ledges ---------------- */

  let ledges: Ledge[] = []
  let floor: Ledge = { el: host, l: 0, r: 0, y: 0 }
  let W = 0
  let hostTop = 0
  const LEDGE_SEL = '[data-ledge], .sim .pane > .tile, .sim .pane > .sheet'

  function scan() {
    const c = host.getBoundingClientRect()
    hostTop = c.top
    W = c.width
    const next: Ledge[] = []
    host.querySelectorAll(LEDGE_SEL).forEach((el) => {
      const r = el.getBoundingClientRect()
      if (r.width < 70 || r.height < 16) return
      const radius = Math.min(parseFloat(getComputedStyle(el).borderTopLeftRadius) || 0, r.height / 2, 24)
      next.push({ el, l: r.left - c.left + radius * 0.6 + 4, r: r.right - c.left - radius * 0.6 - 4, y: r.top - c.top + 1 })
    })
    floor = { el: host, l: 24, r: W - 24, y: c.height - 8 }
    // Keep slimes on the ledge objects of the same elements.
    for (const s of S) {
      if (!s.on) continue
      if (s.on.el === host) s.on = floor
      else s.on = next.find((L) => L.el === s.on!.el) ?? null
      if (!s.on && (s.mode === 'idle' || s.mode === 'walk' || s.mode === 'sleep' || s.mode === 'react')) fall(s)
    }
    ledges = next
  }

  const view = () => {
    const top = -hostTop
    return { top, bottom: top + innerHeight }
  }
  const visible = (y: number, pad = 0) => {
    const v = view()
    return y > v.top + pad && y < v.bottom - 10
  }
  const visibleLedges = (except?: Ledge | null) =>
    ledges.filter((L) => L !== except && visible(L.y, 90) && L.r - L.l > 50)

  /* ---------------- helpers ---------------- */

  function say(s: Slime, text: string, dur = 1.3) {
    s.bubble.textContent = text
    s.bubble.classList.add('on')
    s.sayUntil = now + dur
  }
  function mood(s: Slime, m: Mood, dur = 1.2) {
    s.mood = m
    s.moodUntil = now + dur
  }
  function fx(kind: string, x: number, y: number, text = '') {
    const e = document.createElement('span')
    e.className = `fx fx-${kind}`
    e.textContent = text
    e.style.left = `${x}px`
    e.style.top = `${y}px`
    root!.appendChild(e)
    setTimeout(() => e.remove(), 1600)
  }
  const free = (s: Slime) => !s.duo && (s.mode === 'idle' || s.mode === 'walk')
  const clampOn = (s: Slime, L: Ledge, x: number) => Math.min(Math.max(x, L.l + s.w * 0.35), L.r - s.w * 0.35)

  function fall(s: Slime, vx = s.vx, vy = s.vy) {
    s.on = null
    s.mode = 'air'
    s.t = 0
    s.vx = vx
    s.vy = vy
  }

  function hopTo(s: Slime, L: Ledge | null, x: number, opts: { H?: number; spin?: number; dur?: number } = {}) {
    const ty = L ? L.y : s.y
    const tx = L ? clampOn(s, L, x) : x
    const dist = Math.hypot(tx - s.x, ty - s.y)
    const up = Math.max(0, s.y - ty)
    s.hop = {
      fx: s.x, fy: s.y, tx, ty,
      dur: opts.dur ?? Math.min(1.25, 0.42 + dist / 900),
      H: opts.H ?? 46 + up * 0.55 + dist * 0.08,
      to: L, spin: opts.spin ?? 0, crouch: 0.13,
    }
    s.dir = tx >= s.x ? 1 : -1
    s.on = null
    s.mode = 'hop'
    s.t = 0
  }

  function land(s: Slime, L: Ledge, impact: number) {
    s.on = L
    s.y = L.y
    s.vx = 0
    s.vy = 0
    s.spin = 0
    s.sqv += Math.min(14, 3 + impact / 160)
    if (s.dizzyAfter) {
      s.dizzyAfter = false
      mood(s, 'dizzy', 2.4)
      say(s, '@_@', 1.6)
      startReact(s, 'dizzy', 2.4)
      return
    }
    if (impact > 1500) {
      startReact(s, 'splat', 0.9)
      if (Math.random() < 0.6) say(s, pick(['ui da!', 'bẹp…', 'oái!']))
      return
    }
    s.mode = 'idle'
    s.t = 0
    s.think = now + rnd(0.3, 1.1)
  }

  function startReact(s: Slime, kind: string, dur: number) {
    s.mode = 'react'
    s.react = kind
    s.reactDur = dur
    s.t = 0
  }

  function walkTo(s: Slime, x: number, speed = CAST[s.k].speed * rnd(0.8, 1.25)) {
    if (!s.on) return
    s.walkTo = clampOn(s, s.on, x)
    s.speed = speed
    s.dir = s.walkTo >= s.x ? 1 : -1
    s.mode = 'walk'
    s.t = 0
  }

  /* ---------------- brains ---------------- */

  const night = () => {
    const h = new Date().getHours()
    return h >= 23 || h < 6
  }

  function decide(s: Slime) {
    if (!s.on) return
    const trait = CAST[s.k].trait
    if (night() && Math.random() < 0.35) {
      s.mode = 'sleep'
      s.t = 0
      mood(s, 'sleep', 9999)
      return
    }
    const r = Math.random()
    if (r < 0.42) {
      walkTo(s, rnd(s.on.l, s.on.r))
    } else if (r < 0.66) {
      const others = visibleLedges(s.on)
      if (others.length) {
        const near = others.sort((a, b) => Math.abs(a.y - s.y) + Math.abs((a.l + a.r) / 2 - s.x) * 0.5 - (Math.abs(b.y - s.y) + Math.abs((b.l + b.r) / 2 - s.x) * 0.5))
        const L = near[Math.floor(Math.random() * Math.min(3, near.length))]
        hopTo(s, L, rnd(L.l, L.r))
      } else walkTo(s, rnd(s.on.l, s.on.r))
    } else if (r < 0.8) {
      // A happy jump; Soda shows off with a flip.
      const flip = trait === 'showoff' ? (Math.random() < 0.7 ? 360 : 0) : Math.random() < 0.2 ? 360 : 0
      hopTo(s, s.on, s.x + rnd(-30, 30), { H: flip ? 80 : 34, spin: flip * (Math.random() < 0.5 ? 1 : -1), dur: flip ? 0.7 : 0.42 })
      if (flip && Math.random() < 0.5) say(s, pick(['yee~', 'xem nè!', 'hí']))
    } else if (r < 0.9) {
      s.dir *= -1
      s.think = now + rnd(0.6, 1.4)
    } else {
      s.think = now + rnd(1, 2.6)
    }
    if (s.mode === 'idle') s.think = Math.max(s.think, now + rnd(0.8, 2.2))
  }

  function poke(s: Slime, px: number) {
    s.taps = [...s.taps.filter((t) => now - t < 2.5), now]
    if (s.mode === 'sleep') {
      s.mode = 'idle'
      mood(s, 'normal', 0)
      say(s, 'ưm…')
      s.think = now + 1.2
      return
    }
    if (s.duo) endDuo(s.duo)
    if (s.taps.length >= 5) {
      s.taps = []
      startReact(s, 'melt', 2)
      say(s, '…', 1.6)
      return
    }
    const trait = CAST[s.k].trait
    const r = Math.random()
    const kind =
      trait === 'bossy' ? (r < 0.55 ? 'huff' : r < 0.8 ? 'wiggle' : 'flip') :
      trait === 'gentle' ? (r < 0.5 ? 'wiggle' : r < 0.8 ? 'huff' : 'flip') :
      trait === 'showoff' ? (r < 0.55 ? 'flip' : r < 0.8 ? 'wiggle' : 'flee') :
      r < 0.45 ? 'flee' : r < 0.75 ? 'flip' : 'huff'
    if (kind === 'huff') {
      mood(s, 'angry', 1.2)
      say(s, 'hứ!')
      startReact(s, 'huff', 1.1)
    } else if (kind === 'wiggle') {
      say(s, pick(['ơ?', 'hì', 'ưm?']))
      startReact(s, 'wiggle', 0.75)
    } else if (kind === 'flip') {
      mood(s, 'happy', 1)
      say(s, pick(['yee~', 'hí hí', 'wheee']))
      hopTo(s, s.on, s.x, { H: 90, spin: 360 * (Math.random() < 0.5 ? 1 : -1), dur: 0.72 })
    } else {
      mood(s, 'wow', 1.4)
      say(s, 'á!')
      if (s.on) walkTo(s, px < s.x ? s.on.r : s.on.l, 230)
    }
  }

  /* ---------------- social (RoamSocial) ---------------- */

  const duos: Duo[] = []
  let nextSocial = 6

  function startSocial() {
    const ready = S.filter((s) => free(s) && s.on && visible(s.y))
    if (ready.length < 2) return
    const a = pick(ready)
    const rest = ready.filter((s) => s !== a)
    const b = rest.find((s) => s.on === a.on) ?? pick(rest)
    const trait = CAST[a.k].trait
    const kind: Duo['kind'] = trait === 'trouble' && Math.random() < 0.6 ? 'prank' : trait === 'bossy' && Math.random() < 0.4 ? 'chase' : pick(['five', 'five', 'chase', 'prank', 'brawl'] as const)
    const d: Duo = { kind, a, b, phase: 0, t0: now, tick: 0 }
    a.duo = b.duo = d
    duos.push(d)
    // Meet on the same ledge first.
    if (kind !== 'chase' && a.on !== b.on && a.on) hopTo(b, a.on, a.x + (Math.random() < 0.5 ? -1 : 1) * a.w * 2)
    if (kind === 'chase') {
      say(a, 'đứng lại!')
      say(b, b.k === 3 ? 'hí hí' : 'yee~')
      mood(b, 'happy', 6)
    }
  }

  function endDuo(d: Duo) {
    d.a.duo = d.b.duo = null
    duos.splice(duos.indexOf(d), 1)
    for (const s of [d.a, d.b]) {
      if (s.mode === 'walk') s.mode = 'idle'
      s.think = now + rnd(0.6, 1.6)
    }
  }

  function stepDuo(d: Duo) {
    const { a, b } = d
    const since = now - d.t0
    if (a.mode === 'held' || b.mode === 'held' || since > 14) return endDuo(d)
    const same = a.on && a.on === b.on
    const gap = (a.w + b.w) / 2
    switch (d.kind) {
      case 'five': {
        if (d.phase === 0) {
          if (!same) return
          if (Math.abs(a.x - b.x) > gap * 1.05) {
            if (a.mode !== 'hop' && a.mode !== 'walk') walkTo(a, b.x - Math.sign(b.x - a.x) * gap, 90)
            if (b.mode !== 'hop' && b.mode !== 'walk') walkTo(b, a.x - Math.sign(a.x - b.x) * gap, 90)
          } else if (a.mode !== 'hop' && b.mode !== 'hop') {
            a.dir = Math.sign(b.x - a.x) || 1
            b.dir = -a.dir
            hopTo(a, a.on, a.x, { H: 44, dur: 0.46 })
            hopTo(b, b.on, b.x, { H: 44, dur: 0.46 })
            d.phase = 1
            d.tick = now
          }
        } else if (d.phase === 1 && now - d.tick > 0.23) {
          fx('clap', (a.x + b.x) / 2, Math.min(a.y, b.y) - Math.max(a.h, b.h) - 40, 'bốp!')
          d.phase = 2
        } else if (d.phase === 2 && a.mode !== 'hop' && b.mode !== 'hop') {
          mood(a, 'happy', 1.2)
          mood(b, 'happy', 1.2)
          endDuo(d)
        }
        break
      }
      case 'chase': {
        if (b.mode === 'idle' || (b.mode === 'walk' && b.on && Math.abs(b.x - b.walkTo) < 4)) {
          const L = b.on
          if (L) {
            const atEnd = b.x - L.l < b.w || L.r - b.x < b.w
            const others = visibleLedges(L)
            if (atEnd && others.length && Math.random() < 0.7) {
              const T = pick(others)
              hopTo(b, T, rnd(T.l, T.r))
            } else walkTo(b, a.x < b.x ? L.r : L.l, 175)
          }
        }
        if (a.mode === 'idle' || a.mode === 'walk') {
          if (same) walkTo(a, b.x, 150)
          else if (b.on && b.mode !== 'hop') hopTo(a, b.on, b.x - Math.sign(b.x - a.x) * gap)
        }
        if (same && Math.abs(a.x - b.x) < gap * 0.8 && b.mode !== 'hop') {
          say(a, 'bắt được!')
          mood(a, 'happy', 1.5)
          mood(b, 'dizzy', 1.4)
          startReact(b, 'wiggle', 0.8)
          endDuo(d)
        } else if (since > 7) {
          say(a, 'hộc hộc…')
          endDuo(d)
        }
        break
      }
      case 'prank': {
        if (!same) return
        if (d.phase === 0) {
          // The victim looks away; the prankster sneaks up behind.
          if (b.mode === 'walk') b.mode = 'idle'
          b.think = now + 5
          const behind = b.x - b.dir * gap * 0.95
          mood(a, 'sneak', 4)
          if (Math.abs(a.x - behind) > 6) {
            if (a.mode !== 'walk' && a.mode !== 'hop') walkTo(a, behind, 32)
          } else {
            say(a, 'hù!', 1)
            mood(b, 'wow', 1.4)
            say(b, 'ê!')
            hopTo(b, b.on, b.x + b.dir * 20, { H: 100, dur: 0.6 })
            d.phase = 1
            d.tick = now
          }
        } else if (d.phase === 1 && now - d.tick > 0.7) {
          say(a, 'hehe')
          mood(a, 'happy', 1.4)
          startReact(a, 'wiggle', 0.8)
          d.phase = 2
          d.tick = now
        } else if (d.phase === 2 && now - d.tick > 0.9) {
          endDuo(d)
          if (Math.random() < 0.5) {
            // Payback: the victim chases the prankster.
            const c: Duo = { kind: 'chase', a: b, b: a, phase: 0, t0: now, tick: 0 }
            a.duo = b.duo = c
            duos.push(c)
            say(b, 'đứng lại!')
          }
        }
        break
      }
      case 'brawl': {
        if (!same) return
        if (d.phase === 0) {
          if (Math.abs(a.x - b.x) > gap * 0.7) {
            if (a.mode !== 'walk' && a.mode !== 'hop') walkTo(a, b.x, 110)
            if (b.mode !== 'walk' && b.mode !== 'hop') walkTo(b, a.x, 110)
          } else {
            mood(a, 'angry', 3)
            mood(b, 'angry', 3)
            a.hidden = b.hidden = true
            a.mode = b.mode = 'idle'
            a.think = b.think = now + 9
            fx('cloud', (a.x + b.x) / 2, a.y - a.h * 0.6)
            d.phase = 1
            d.tick = now
          }
        } else if (d.phase === 1 && now - d.tick > 1.3) {
          a.hidden = b.hidden = false
          const dirA = Math.sign(a.x - b.x) || -1
          fall(a, dirA * 260, -520)
          fall(b, -dirA * 260, -520)
          say(a, 'hứ!')
          say(b, 'hứ!')
          d.phase = 2
          d.tick = now
        } else if (d.phase === 2 && now - d.tick > 0.9) {
          a.dir = Math.sign(a.x - b.x) || 1
          b.dir = -a.dir
          endDuo(d)
        }
        break
      }
    }
  }

  /* ---------------- laser (hold still on empty space) ---------------- */

  const dot = root.querySelector('.laser') as HTMLElement
  let laser: { x: number; y: number } | null = null
  let pressTimer = 0
  let pressAt = { x: 0, y: 0 }

  function laserStep(s: Slime) {
    if (!laser || !s.on || s.mode === 'hop' || s.mode === 'air' || s.mode === 'held') return
    const L = s.on
    const onThis = laser.x > L.l - 10 && laser.x < L.r + 10 && laser.y > L.y - 170 && laser.y < L.y + 20
    if (onThis) {
      const dx = laser.x - s.x
      if (Math.abs(dx) < 18 && now - s.pounceAt > 0.8) {
        s.pounceAt = now
        hopTo(s, L, laser.x, { H: 30 + Math.max(0, L.y - laser.y) * 0.6, dur: 0.38 })
      } else if (Math.abs(dx) >= 18) walkTo(s, laser.x, s.k === 3 ? 260 : 210)
      return
    }
    const under = ledges
      // Only a ledge right under the dot; otherwise they run along their own.
      .filter((T) => laser!.x > T.l && laser!.x < T.r && T.y >= laser!.y - 20 && T.y - laser!.y < 220)
      .sort((p, q) => p.y - q.y)[0]
    if (under && now - s.laserHopAt > 1 + s.k * 0.15) {
      s.laserHopAt = now
      hopTo(s, under, laser.x + rnd(-24, 24))
    } else if (s.mode !== 'walk') walkTo(s, laser.x, 200)
  }

  /* ---------------- pointer: pick up, throw, poke ---------------- */

  let grab: { s: Slime; dx: number; dy: number; down: number; moved: number; lifted: boolean; samples: { x: number; y: number; t: number }[] } | null = null
  const local = (e: PointerEvent) => {
    const c = host.getBoundingClientRect()
    return { x: e.clientX - c.left, y: e.clientY - c.top }
  }
  let pointer = { x: -9999, y: -9999 }

  function onDown(e: PointerEvent) {
    const target = e.target as Element
    const slimeEl = target.closest('.sl') as HTMLElement | null
    const p = local(e)
    if (slimeEl && root!.contains(slimeEl)) {
      const s = S[Number(slimeEl.dataset.k)]
      slimeEl.setPointerCapture(e.pointerId)
      grab = { s, dx: p.x - s.x, dy: p.y - s.y, down: now, moved: 0, lifted: false, samples: [{ ...p, t: now }] }
      e.preventDefault()
      return
    }
    // Long press on empty space: the laser.
    if (!host.contains(target) || target.closest('a, button, input, textarea, label, select, .orb-wrap, .show-list, pre')) return
    pressAt = p
    clearTimeout(pressTimer)
    pressTimer = window.setTimeout(() => {
      laser = { ...pressAt }
      dot.classList.add('on')
      document.documentElement.classList.add('lg-lasering')
    }, 450)
  }

  function onMove(e: PointerEvent) {
    const p = local(e)
    pointer = p
    if (grab) {
      const g = grab
      g.moved += Math.hypot(p.x - g.samples[g.samples.length - 1].x, p.y - g.samples[g.samples.length - 1].y)
      g.samples.push({ ...p, t: now })
      if (g.samples.length > 6) g.samples.shift()
      if (!g.lifted && g.moved > 6) {
        g.lifted = true
        if (g.s.duo) endDuo(g.s.duo)
        g.s.mode = 'held'
        g.s.on = null
        mood(g.s, 'wow', 999)
        if (Math.random() < 0.5) say(g.s, pick(['ơ ơ!', 'thả ra!', 'wheee']))
      }
      if (g.lifted) {
        g.s.x = p.x - g.dx
        g.s.y = p.y - g.dy + g.s.h * 0.15
      }
      return
    }
    if (laser) {
      laser = p
      return
    }
    if (pressTimer && Math.hypot(p.x - pressAt.x, p.y - pressAt.y) > 10) {
      clearTimeout(pressTimer)
      pressTimer = 0
    }
  }

  function onUp() {
    clearTimeout(pressTimer)
    pressTimer = 0
    if (grab) {
      const g = grab
      grab = null
      if (!g.lifted) return poke(g.s, pointer.x)
      const a = g.samples[0]
      const b = g.samples[g.samples.length - 1]
      const dt = Math.max(0.016, b.t - a.t)
      const vx = Math.max(-2600, Math.min(2600, (b.x - a.x) / dt))
      const vy = Math.max(-2600, Math.min(2600, (b.y - a.y) / dt))
      if (g.moved > 2200) g.s.dizzyAfter = true
      mood(g.s, 'wow', 1)
      fall(g.s, vx, vy)
      return
    }
    if (laser) {
      laser = null
      dot.classList.remove('on')
      document.documentElement.classList.remove('lg-lasering')
      const watchers = S.filter((s) => s.on && visible(s.y))
      watchers.slice(0, 2).forEach((s) => say(s, 'ơ…?'))
      S.forEach((s) => s.mode === 'walk' && (s.mode = 'idle'))
    }
  }
  const noMenu = (e: Event) => laser && e.preventDefault()

  /* ---------------- follow the reader ---------------- */

  let nextFollow = 3
  function follow() {
    const lost = S.filter((s) => !visible(s.y, 40) && (s.mode === 'idle' || s.mode === 'walk' || s.mode === 'sleep') && !s.duo)
    if (!lost.length) return
    const targets = visibleLedges()
    if (!targets.length) return
    const s = pick(lost)
    const L = pick(targets)
    s.mode = 'idle'
    mood(s, 'normal', 0)
    hopTo(s, L, rnd(L.l, L.r), { dur: 1.05, H: 140 })
    window.setTimeout(() => say(s, pick(['đợi tớ!', 'tớ đây!', 'hế lô~'])), 900)
  }

  /* ---------------- party (the 7-tap secret) ---------------- */

  function party() {
    S.forEach((s, i) =>
      window.setTimeout(() => {
        if (s.mode === 'held') return
        if (s.duo) endDuo(s.duo)
        if (!s.on) return
        mood(s, 'happy', 2)
        say(s, pick(['yay!', 'yee~', 'hí hí', 'woo!']))
        hopTo(s, s.on, s.x, { H: 110, spin: 360, dur: 0.8 })
      }, i * 140),
    )
  }

  /* ---------------- the loop ---------------- */

  let raf = 0
  let last = performance.now()
  let scanAt = 0

  function frame(t: number) {
    const dt = Math.min(0.05, (t - last) / 1000)
    last = t
    now += dt
    if (now - scanAt > 0.25) {
      scanAt = now
      scan()
    }
    if (now > nextSocial) {
      nextSocial = now + rnd(7, 13)
      if (!laser) startSocial()
    }
    if (now > nextFollow) {
      nextFollow = now + rnd(2.2, 3.6)
      if (!laser) follow()
    }
    duos.slice().forEach(stepDuo)
    if (laser) {
      dot.style.left = `${laser.x}px`
      dot.style.top = `${laser.y}px`
    }

    for (const s of S) {
      s.t += dt
      if (laser && !s.duo) laserStep(s)
      switch (s.mode) {
        case 'wait':
          break
        case 'idle':
          if (!s.duo && !laser && now > s.think) decide(s)
          break
        case 'sleep':
          if (now > s.zAt) {
            s.zAt = now + 1.2
            fx('z', s.x + s.w * 0.3, s.y - s.h, 'z')
          }
          if (!night() || s.t > 25) {
            s.mode = 'idle'
            mood(s, 'normal', 0)
          }
          break
        case 'walk': {
          const L = s.on
          if (!L) break
          const step = s.speed * dt
          const dx = s.walkTo - s.x
          if (Math.abs(dx) <= step) {
            s.x = s.walkTo
            s.mode = 'idle'
            s.think = now + rnd(0.5, 1.8)
          } else {
            s.dir = Math.sign(dx)
            s.x += s.dir * step
          }
          break
        }
        case 'hop': {
          const h = s.hop!
          if (s.t < h.crouch) {
            s.sq += (0.28 - s.sq) * Math.min(1, dt * 30)
            break
          }
          const u = Math.min(1, (s.t - h.crouch) / h.dur)
          const px = s.x
          const py = s.y
          s.x = h.fx + (h.tx - h.fx) * u
          s.y = h.fy + (h.ty - h.fy) * u - h.H * 4 * u * (1 - u)
          s.vx = (s.x - px) / Math.max(dt, 1e-3)
          s.vy = (s.y - py) / Math.max(dt, 1e-3)
          s.spin = h.spin * u
          if (u >= 1) {
            const L = h.to
            s.hop = null
            const valid = L && (L === floor || ledges.includes(L)) && s.x >= L.l - 8 && s.x <= L.r + 8
            if (valid) land(s, L!, Math.abs(s.vy) * 0.6)
            else fall(s, s.vx * 0.3, Math.max(0, s.vy))
          }
          break
        }
        case 'air': {
          const py = s.y
          s.vy += G * dt
          s.x += s.vx * dt
          s.y += s.vy * dt
          s.spin += s.vx * dt * 0.6
          if (s.x < s.w / 2) {
            s.x = s.w / 2
            s.vx = Math.abs(s.vx) * 0.55
          } else if (s.x > W - s.w / 2) {
            s.x = W - s.w / 2
            s.vx = -Math.abs(s.vx) * 0.55
          }
          if (s.vy > 0) {
            const hit = [...ledges, floor].find((L) => py <= L.y + 1 && s.y >= L.y && s.x >= L.l && s.x <= L.r)
            if (hit) {
              s.y = hit.y
              if (s.vy > 650 && s.vy < 1500 && !s.dizzyAfter) {
                // A bounce or two before settling.
                s.vy = -s.vy * 0.32
                s.vx *= 0.6
                s.sqv += 9
              } else {
                s.spin = 0
                land(s, hit, s.vy)
              }
            }
          }
          break
        }
        case 'held':
          s.vx = 0
          break
        case 'react': {
          const p = s.t / s.reactDur
          if (s.react === 'wiggle') s.tilt = Math.sin(s.t * 34) * 13 * (1 - p)
          else if (s.react === 'huff') s.sq = -0.1 + Math.sin(s.t * 22) * 0.06
          else if (s.react === 'splat') s.sq = 0.5 * (1 - p) ** 2
          else if (s.react === 'dizzy') s.tilt = Math.sin(s.t * 9) * 10
          else if (s.react === 'melt') s.sq = p < 0.6 ? Math.min(0.7, p * 2.4) : 0.7 * (1 - (p - 0.6) / 0.4)
          if (s.t >= s.reactDur) {
            s.tilt = 0
            s.mode = 'idle'
            s.think = now + rnd(0.4, 1.2)
            if (s.react === 'melt') {
              s.sqv -= 8
              say(s, 'pop!', 0.8)
            }
          }
          break
        }
      }
      // Standing on a ledge: follow it, or fall when it slides away.
      if (s.on && (s.mode === 'idle' || s.mode === 'walk' || s.mode === 'sleep' || s.mode === 'react')) {
        s.y = s.on.y
        if (s.x < s.on.l - 10 || s.x > s.on.r + 10) fall(s, s.dir * 60, -80)
      }
      // Jelly: a spring pulls the squash back to round.
      if (s.mode !== 'react' && !(s.mode === 'hop' && s.t < (s.hop?.crouch ?? 0))) {
        s.sqv += (-320 * s.sq - 12 * s.sqv) * dt
        s.sq += s.sqv * dt
      }
      if (s.mode !== 'react' && s.mode !== 'air' && s.mode !== 'hop') s.tilt *= 1 - Math.min(1, dt * 10)
      render(s)
    }
    raf = requestAnimationFrame(frame)
  }

  function render(s: Slime) {
    if (now > s.sayUntil && s.bubble.classList.contains('on')) s.bubble.classList.remove('on')
    if (s.mood !== 'normal' && now > s.moodUntil) s.mood = 'normal'
    let m: string = s.mood
    if (m === 'normal' && now > s.blinkAt) {
      s.blinkUntil = now + 0.13
      s.blinkAt = now + rnd(2, 5.5)
    }
    if (m === 'normal' && now < s.blinkUntil) m = 'blink'
    if (s.mode === 'held' || ((s.mode === 'air' || s.mode === 'hop') && s.vy > 500)) m = m === 'happy' ? m : 'wow'
    const cls = `sl k${s.k} m-${m}${s.hidden ? ' hide' : ''}`
    if (s.el.className !== cls) s.el.className = cls

    const air = s.mode === 'air' || s.mode === 'hop' || s.mode === 'held'
    const bob = s.mode === 'walk' ? -Math.abs(Math.sin(s.t * (s.speed > 140 ? 22 : 13))) * 4 : 0
    let sx = 1 + s.sq
    let sy = 1 - s.sq
    if (air && s.mode !== 'held') {
      const st = Math.min(0.22, Math.abs(s.vy) / 2600)
      sx -= st * 0.55
      sy += st
    }
    if (s.mode === 'held') {
      sx = 0.88
      sy = 1.18
    }
    const tilt = s.mode === 'held' ? Math.max(-28, Math.min(28, (pointer.x - s.x - (grab?.dx ?? 0)) * 0.3)) : s.tilt
    s.el.style.transform = `translate3d(${(s.x - s.w / 2).toFixed(1)}px, ${(s.y - s.h + bob).toFixed(1)}px, 0)`
    s.rig.style.transform = `rotate(${(tilt + s.spin).toFixed(1)}deg) scale(${sx.toFixed(3)}, ${sy.toFixed(3)})`

    // Eyes: the laser, else the pointer when near, else where it is going.
    const target = laser ?? (Math.hypot(pointer.x - s.x, pointer.y - s.y) < 420 ? pointer : null)
    let lx = s.dir * 1.6
    let ly = 0
    if (target) {
      const dx = target.x - s.x
      const dy = target.y - (s.y - s.h * 0.55)
      const d = Math.hypot(dx, dy) || 1
      lx = (dx / d) * 2.4
      ly = (dy / d) * 1.8
    }
    s.face.style.transform = `translate(${lx.toFixed(2)}px, ${ly.toFixed(2)}px)`
  }

  /* ---------------- start: they drop onto the emblem box ---------------- */

  scan()
  const stage = ledges.find((L) => (L.el as HTMLElement).classList?.contains('stage')) ?? ledges[0] ?? floor
  S.forEach((s, i) => {
    const span = stage.r - stage.l
    s.x = stage.l + span * ((i + 0.5) / S.length)
    s.y = stage.y - 260 - i * 70
    s.mode = 'wait'
    window.setTimeout(() => {
      fall(s, 0, 0)
      s.dizzyAfter = false
    }, 500 + i * 220)
  })
  window.setTimeout(() => say(S[3], 'hế lô~'), 1900)

  addEventListener('pointerdown', onDown, true)
  addEventListener('pointermove', onMove, { passive: true })
  addEventListener('pointerup', onUp)
  addEventListener('pointercancel', onUp)
  addEventListener('contextmenu', noMenu)
  addEventListener('lg-party', party)
  const onVis = () => {
    if (document.hidden) cancelAnimationFrame(raf)
    else {
      last = performance.now()
      raf = requestAnimationFrame(frame)
    }
  }
  document.addEventListener('visibilitychange', onVis)
  raf = requestAnimationFrame(frame)

  stop = () => {
    cancelAnimationFrame(raf)
    clearTimeout(pressTimer)
    removeEventListener('pointerdown', onDown, true)
    removeEventListener('pointermove', onMove)
    removeEventListener('pointerup', onUp)
    removeEventListener('pointercancel', onUp)
    removeEventListener('contextmenu', noMenu)
    removeEventListener('lg-party', party)
    document.removeEventListener('visibilitychange', onVis)
    document.documentElement.classList.remove('lg-lasering')
  }
})

onBeforeUnmount(() => stop())
</script>

<template>
  <div ref="layer" class="world" aria-hidden="true">
    <div
      v-for="(c, k) in CAST"
      :key="c.name"
      class="sl"
      :data-k="k"
      :title="c.name"
      :style="{ '--body': c.body, '--deep': c.deep, '--glow': c.glow }"
    >
      <span class="say" />
      <div class="rig">
        <svg viewBox="0 0 64 60" preserveAspectRatio="none">
          <defs>
            <radialGradient :id="`sw-${k}`" cx="36%" cy="26%" r="80%">
              <stop offset="0%" :stop-color="c.glow" />
              <stop offset="45%" :stop-color="c.body" />
              <stop offset="100%" :stop-color="c.deep" />
            </radialGradient>
          </defs>
          <path class="jelly" d="M5 50 C5 23 17 6 32 6 C47 6 59 23 59 50 C59 56.5 51 58.5 32 58.5 C13 58.5 5 56.5 5 50 Z" :fill="`url(#sw-${k})`" />
          <path class="tint" d="M5 50 C5 23 17 6 32 6 C47 6 59 23 59 50 C59 56.5 51 58.5 32 58.5 C13 58.5 5 56.5 5 50 Z" />
          <path d="M12 47 C15 54 49 54 52 47" fill="none" stroke="rgba(255,255,255,0.35)" stroke-width="2" stroke-linecap="round" />
          <ellipse cx="21" cy="18" rx="8" ry="4.2" transform="rotate(-28 21 18)" fill="rgba(255,255,255,0.88)" />
          <circle cx="31" cy="12.5" r="2" fill="rgba(255,255,255,0.8)" />
          <g class="face">
            <g class="e-open">
              <ellipse cx="24" cy="32" rx="3.4" ry="4.4" fill="#1d2230" />
              <ellipse cx="40" cy="32" rx="3.4" ry="4.4" fill="#1d2230" />
              <circle cx="25.2" cy="30.3" r="1.3" fill="#fff" />
              <circle cx="41.2" cy="30.3" r="1.3" fill="#fff" />
            </g>
            <g class="e-happy" fill="none" stroke="#1d2230" stroke-width="2.4" stroke-linecap="round">
              <path d="M20.5 33 Q24 28.5 27.5 33" />
              <path d="M36.5 33 Q40 28.5 43.5 33" />
            </g>
            <g class="e-shut" fill="none" stroke="#1d2230" stroke-width="2.2" stroke-linecap="round">
              <path d="M20.5 32.5 Q24 34.5 27.5 32.5" />
              <path d="M36.5 32.5 Q40 34.5 43.5 32.5" />
            </g>
            <g class="e-dizzy" fill="none" stroke="#1d2230" stroke-width="2.2" stroke-linecap="round">
              <path d="M21 29 L27 35 M27 29 L21 35" />
              <path d="M37 29 L43 35 M43 29 L37 35" />
            </g>
            <g class="brows" fill="none" stroke="#1d2230" stroke-width="2.2" stroke-linecap="round">
              <path d="M19.5 24.5 L27.5 27.5" />
              <path d="M44.5 24.5 L36.5 27.5" />
            </g>
            <ellipse cx="17" cy="39.5" rx="3.8" ry="2.3" fill="rgba(255,100,140,0.42)" />
            <ellipse cx="47" cy="39.5" rx="3.8" ry="2.3" fill="rgba(255,100,140,0.42)" />
            <path class="mo-smile" d="M28.6 38.6 Q32 42 35.4 38.6" fill="none" stroke="#1d2230" stroke-width="1.8" stroke-linecap="round" />
            <ellipse class="mo-o" cx="32" cy="40.5" rx="2.6" ry="3.2" fill="#1d2230" />
            <path class="mo-flat" d="M29 40 L35 39" fill="none" stroke="#1d2230" stroke-width="1.8" stroke-linecap="round" />
            <path class="mo-grin" d="M27.5 38 Q32 44 36.5 38 Z" fill="#1d2230" />
          </g>
        </svg>
      </div>
    </div>
    <span class="laser" />
  </div>
</template>

<style scoped>
.world {
  position: absolute;
  inset: 0;
  z-index: 6;
  pointer-events: none;
}

.sl {
  position: absolute;
  left: 0;
  top: 0;
  pointer-events: auto;
  touch-action: none;
  cursor: grab;
  will-change: transform;
  -webkit-tap-highlight-color: transparent;
  user-select: none;
}
.sl:active { cursor: grabbing; }
.sl.hide { opacity: 0; }
.rig {
  width: 100%;
  height: 100%;
  transform-origin: 50% 100%;
  filter: drop-shadow(0 6px 6px rgba(0, 0, 0, 0.16));
}
.rig svg { display: block; width: 100%; height: 100%; overflow: visible; }
.jelly { stroke: color-mix(in srgb, var(--glow) 70%, white); stroke-width: 1.2; }
.tint { fill: #ff2a2a; opacity: 0; transition: opacity 0.25s ease; }

/* Faces (ArenaSlime moods). */
.face { transition: transform 0.18s cubic-bezier(0.3, 1.4, 0.5, 1); }
.e-happy, .e-shut, .e-dizzy, .brows, .mo-o, .mo-flat, .mo-grin { display: none; }
.m-blink .e-open, .m-sleep .e-open { display: none; }
.m-blink .e-shut, .m-sleep .e-shut { display: inline; }
.m-happy .e-open { display: none; }
.m-happy .e-happy, .m-happy .mo-grin { display: inline; }
.m-happy .mo-smile { display: none; }
.m-angry .brows, .m-angry .mo-flat { display: inline; }
.m-angry .mo-smile { display: none; }
.m-angry .tint { opacity: 0.32; }
.m-dizzy .e-open, .m-dizzy .mo-smile { display: none; }
.m-dizzy .e-dizzy, .m-dizzy .mo-o { display: inline; }
.m-wow .mo-smile { display: none; }
.m-wow .mo-o { display: inline; }
.m-sneak .e-open { display: none; }
.m-sneak .e-happy, .m-sneak .mo-grin { display: inline; }
.m-sneak .mo-smile { display: none; }
.m-sleep .mo-smile { display: none; }
.m-sleep .mo-o { display: inline; transform: scale(0.6); transform-origin: 32px 40px; }

/* Speech bubble (FxKind.Say). */
.say {
  position: absolute;
  left: 50%;
  bottom: calc(100% + 8px);
  transform: translateX(-50%) scale(0.4);
  transform-origin: 50% 100%;
  padding: 4px 10px;
  border-radius: 12px;
  font-size: 12.5px;
  font-weight: 700;
  white-space: nowrap;
  color: #1d2230;
  background: rgba(255, 255, 255, 0.85);
  -webkit-backdrop-filter: blur(8px);
  backdrop-filter: blur(8px);
  box-shadow: 0 6px 16px -6px rgba(0, 0, 0, 0.3), inset 0 1px 1px #fff;
  opacity: 0;
  pointer-events: none;
  transition: opacity 0.15s ease, transform 0.35s cubic-bezier(0.3, 1.6, 0.5, 1);
}
.say::after {
  content: '';
  position: absolute;
  left: 50%;
  top: 100%;
  margin-left: -5px;
  border: 5px solid transparent;
  border-top-color: rgba(255, 255, 255, 0.85);
}
.say.on { opacity: 1; transform: translateX(-50%) scale(1); }

/* Effects: clap, brawl cloud, sleep z. */
.world :deep(.fx) { position: absolute; pointer-events: none; transform: translate(-50%, -50%); }
.world :deep(.fx-clap) {
  font-weight: 900;
  font-size: 17px;
  color: #fff;
  padding: 10px 12px;
  background: radial-gradient(circle, #ffb703 0 45%, transparent 46%), conic-gradient(from 0deg, #ffb703 0 10deg, transparent 10deg 30deg, #ffb703 30deg 40deg, transparent 40deg 60deg, #ffb703 60deg 70deg, transparent 70deg 90deg, #ffb703 90deg 100deg, transparent 100deg 120deg, #ffb703 120deg 130deg, transparent 130deg 150deg, #ffb703 150deg 160deg, transparent 160deg 180deg, #ffb703 180deg 190deg, transparent 190deg 210deg, #ffb703 210deg 220deg, transparent 220deg 240deg, #ffb703 240deg 250deg, transparent 250deg 270deg, #ffb703 270deg 280deg, transparent 280deg 300deg, #ffb703 300deg 310deg, transparent 310deg 330deg, #ffb703 330deg 340deg, transparent 340deg);
  border-radius: 50%;
  text-shadow: 0 1px 2px rgba(0, 0, 0, 0.35);
  animation: fx-pop 0.9s cubic-bezier(0.3, 1.6, 0.5, 1) both;
}
.world :deep(.fx-cloud) {
  width: 120px;
  height: 80px;
  border-radius: 50%;
  background:
    radial-gradient(circle at 30% 40%, rgba(255, 255, 255, 0.95) 0 22%, transparent 23%),
    radial-gradient(circle at 62% 35%, rgba(255, 255, 255, 0.95) 0 26%, transparent 27%),
    radial-gradient(circle at 48% 64%, rgba(255, 255, 255, 0.95) 0 28%, transparent 29%),
    radial-gradient(circle at 78% 62%, rgba(255, 255, 255, 0.9) 0 18%, transparent 19%),
    radial-gradient(circle at 18% 66%, rgba(255, 255, 255, 0.9) 0 16%, transparent 17%);
  filter: drop-shadow(0 6px 10px rgba(0, 0, 0, 0.18));
  animation: fx-brawl 1.35s ease both;
}
.world :deep(.fx-cloud)::before,
.world :deep(.fx-cloud)::after {
  content: '★ ✦ ★';
  position: absolute;
  inset: 0;
  display: grid;
  place-items: center;
  font-size: 16px;
  color: #ffb703;
  animation: fx-spin 0.5s linear infinite;
}
.world :deep(.fx-cloud)::after { content: '💢'; font-size: 20px; animation: fx-jitter 0.18s steps(2) infinite; }
.world :deep(.fx-z) {
  font-weight: 800;
  font-size: 15px;
  color: var(--vp-c-text-2);
  animation: fx-z 1.6s ease-out both;
}
@keyframes fx-pop {
  0% { transform: translate(-50%, -50%) scale(0.2) rotate(-20deg); opacity: 0; }
  30% { transform: translate(-50%, -50%) scale(1.15) rotate(6deg); opacity: 1; }
  70% { opacity: 1; }
  100% { transform: translate(-50%, -80%) scale(0.9); opacity: 0; }
}
@keyframes fx-brawl {
  0% { transform: translate(-50%, -50%) scale(0.3); opacity: 0; }
  15% { transform: translate(-50%, -50%) scale(1.1); opacity: 1; }
  25%, 45%, 65% { transform: translate(-46%, -52%) scale(1); }
  35%, 55%, 75% { transform: translate(-54%, -48%) scale(1.05); }
  88% { opacity: 1; }
  100% { transform: translate(-50%, -50%) scale(1.3); opacity: 0; }
}
@keyframes fx-spin { to { transform: rotate(360deg); } }
@keyframes fx-jitter { 50% { transform: translate(3px, -2px); } }
@keyframes fx-z {
  from { transform: translate(-50%, -50%) scale(0.6); opacity: 0; }
  20% { opacity: 1; }
  to { transform: translate(10px, -46px) scale(1.2); opacity: 0; }
}

/* The laser dot. */
.laser {
  position: absolute;
  left: 0;
  top: 0;
  width: 14px;
  height: 14px;
  margin: -7px 0 0 -7px;
  border-radius: 50%;
  background: radial-gradient(circle, #fff 0 20%, #ff2d55 35%, rgba(255, 45, 85, 0) 72%);
  box-shadow: 0 0 18px 6px rgba(255, 45, 85, 0.55);
  opacity: 0;
  transform: scale(0);
  transition: opacity 0.15s ease, transform 0.2s cubic-bezier(0.3, 1.6, 0.5, 1);
  pointer-events: none;
}
.laser.on { opacity: 1; transform: scale(1); }
</style>

<style>
.lg-lasering,
.lg-lasering * { user-select: none !important; -webkit-user-select: none !important; cursor: crosshair !important; }
</style>

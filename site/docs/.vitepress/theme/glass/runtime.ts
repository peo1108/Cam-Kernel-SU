// Motion and light for the web glass, following the Manager:
// - the specular rim follows a light that tracks the pointer (desktop) or gravity (phone tilt),
//   quantised to 3 degrees like rememberQuantizedGravityAngle();
// - InteractiveHighlight: a glow that follows the finger, spring(0.5, 300);
// - GlassIconButton: droplets swell to 1.12 on press, spring(0.5, 600);
// - cardEntrance: cards rise 56 px with spring(0.78, MediumLow), 40 ms stagger;
// - FloatingBottomBar: a glass drop slides between nav items and stretches with its speed.
import { Spring, springEasing } from './spring'
import { startBackground } from './background'
import { scanLenses } from './lens'

const PRESS = { damping: 0.5, stiffness: 600 }
const GLOW = { damping: 0.5, stiffness: 300 }
const ENTRANCE = { damping: 0.78, stiffness: 200 }
const DROP = { damping: 0.72, stiffness: 380 }

let started = false
const root = () => document.documentElement

/* ---------------- light direction ---------------- */

function startLight() {
  const r = root()
  let current = 0
  const spring = new Spring(0, { damping: 0.85, stiffness: 90 }, (v) => {
    const q = Math.round(v / 3) * 3
    if (q !== current) {
      current = q
      r.style.setProperty('--lg-angle', `${q}deg`)
    }
  })
  const aim = (deg: number) => {
    // Shortest way round so the light never spins the long way.
    let d = deg - (spring.target % 360)
    d = ((d + 540) % 360) - 180
    spring.to(spring.target + d)
  }
  addEventListener(
    'pointermove',
    (e) => {
      if (e.pointerType !== 'mouse') return
      const dx = e.clientX - innerWidth / 2
      const dy = e.clientY - innerHeight * 0.35
      aim((Math.atan2(dx, -dy) * 180) / Math.PI)
      r.style.setProperty('--lg-mx', `${((e.clientX / innerWidth) * 100).toFixed(1)}%`)
      r.style.setProperty('--lg-my', `${((e.clientY / innerHeight) * 100).toFixed(1)}%`)
    },
    { passive: true },
  )
  // Android gives orientation without a prompt; iOS needs a permission dialog, so it keeps the top light.
  addEventListener(
    'deviceorientation',
    (e) => {
      if (e.beta == null || e.gamma == null) return
      const b = (e.beta * Math.PI) / 180
      const g = (e.gamma * Math.PI) / 180
      const gx = Math.sin(g)
      const gy = Math.sin(b)
      if (gx * gx + gy * gy < 0.01) return
      aim((Math.atan2(gx, gy) * 180) / Math.PI)
    },
    { passive: true },
  )
}

/* ---------------- press: droplet swell + finger glow ---------------- */

interface PressState {
  scale: Spring
  glow: Spring
  px: Spring
  py: Spring
  sx: number
  sy: number
}

const pressStates = new WeakMap<HTMLElement, PressState>()

function pressState(el: HTMLElement): PressState {
  let s = pressStates.get(el)
  if (s) return s
  const set = (k: string, v: string) => el.style.setProperty(k, v)
  s = {
    scale: new Spring(1, PRESS, (v) => set('--lg-ps', v.toFixed(4))),
    glow: new Spring(0, GLOW, (v) => set('--lg-gp', Math.max(0, v).toFixed(3))),
    px: new Spring(0, GLOW, (v) => set('--lg-gx', `${v.toFixed(1)}px`), 0.5),
    py: new Spring(0, GLOW, (v) => set('--lg-gy', `${v.toFixed(1)}px`), 0.5),
    sx: 0,
    sy: 0,
  }
  pressStates.set(el, s)
  return s
}

function startPress() {
  let active: HTMLElement | null = null
  const local = (el: HTMLElement, e: PointerEvent) => {
    const r = el.getBoundingClientRect()
    // Undo the current swell so the glow sits under the finger.
    const k = parseFloat(el.style.getPropertyValue('--lg-ps') || '1')
    return [(e.clientX - r.left) / k, (e.clientY - r.top) / k]
  }
  addEventListener(
    'pointerdown',
    (e) => {
      const el = (e.target as Element).closest<HTMLElement>('.lg-press')
      if (!el) return
      active = el
      const s = pressState(el)
      const [x, y] = local(el, e)
      s.sx = x
      s.sy = y
      s.px.snap(x)
      s.py.snap(y)
      const big = el.offsetWidth > 120
      s.scale.to(parseFloat(el.dataset.press || '') || (big ? 1.05 : 1.12))
      s.glow.to(1)
    },
    { passive: true },
  )
  addEventListener(
    'pointermove',
    (e) => {
      if (active) {
        const s = pressState(active)
        const [x, y] = local(active, e)
        s.px.snap(x)
        s.py.snap(y)
        return
      }
      // Hover: the same glow, dimmer, for mice.
      if (e.pointerType !== 'mouse') return
      const el = (e.target as Element).closest<HTMLElement>('.lg-press, .lg-hover')
      if (!el) return
      const r = el.getBoundingClientRect()
      el.style.setProperty('--lg-hx', `${(e.clientX - r.left).toFixed(0)}px`)
      el.style.setProperty('--lg-hy', `${(e.clientY - r.top).toFixed(0)}px`)
    },
    { passive: true },
  )
  const release = () => {
    if (!active) return
    const s = pressState(active)
    s.scale.to(1)
    s.glow.to(0)
    s.px.to(s.sx)
    s.py.to(s.sy)
    active = null
  }
  addEventListener('pointerup', release, { passive: true })
  addEventListener('pointercancel', release, { passive: true })
}

/* ---------------- 3D tilt with moving reflection ---------------- */

function startTilt() {
  const springs = new WeakMap<HTMLElement, [Spring, Spring]>()
  const get = (el: HTMLElement) => {
    let s = springs.get(el)
    if (!s) {
      const spec = { damping: 0.6, stiffness: 120 }
      s = [
        new Spring(0, spec, (v) => el.style.setProperty('--lg-rx', `${v.toFixed(2)}deg`)),
        new Spring(0, spec, (v) => el.style.setProperty('--lg-ry', `${v.toFixed(2)}deg`)),
      ]
      springs.set(el, s)
    }
    return s
  }
  let last: HTMLElement | null = null
  addEventListener(
    'pointermove',
    (e) => {
      if (e.pointerType !== 'mouse') return
      const el = (e.target as Element).closest<HTMLElement>('[data-tilt]')
      if (last && last !== el) {
        const [a, b] = get(last)
        a.to(0)
        b.to(0)
      }
      last = el
      if (!el) return
      const r = el.getBoundingClientRect()
      const nx = (e.clientX - r.left) / r.width - 0.5
      const ny = (e.clientY - r.top) / r.height - 0.5
      const max = parseFloat(el.dataset.tilt || '') || 10
      const [a, b] = get(el)
      a.to(-ny * max)
      b.to(nx * max)
      el.style.setProperty('--lg-sx', `${((nx + 0.5) * 100).toFixed(1)}%`)
      el.style.setProperty('--lg-sy', `${((ny + 0.5) * 100).toFixed(1)}%`)
    },
    { passive: true },
  )
  document.addEventListener('pointerleave', () => {
    if (!last) return
    const [a, b] = get(last)
    a.to(0)
    b.to(0)
    last = null
  })
}

/* ---------------- entrance ---------------- */

let io: IntersectionObserver | null = null

function scanEntrance() {
  const items = document.querySelectorAll<HTMLElement>('.lg-in:not(.lg-in-seen)')
  if (!('IntersectionObserver' in window)) {
    items.forEach((n) => n.classList.add('lg-in-seen', 'lg-in-done'))
    return
  }
  io ??= new IntersectionObserver(
    (entries) => {
      // Cards that arrive together are staggered by 40 ms, top to bottom.
      const shown = entries.filter((e) => e.isIntersecting).sort((a, b) => a.boundingClientRect.top - b.boundingClientRect.top)
      shown.forEach((e, i) => {
        const n = e.target as HTMLElement
        n.style.setProperty('--lg-delay', `${Math.min(i, 8) * 40}ms`)
        n.classList.add('lg-in-done')
        io!.unobserve(n)
      })
    },
    { rootMargin: '0px 0px -6% 0px' },
  )
  items.forEach((n) => {
    n.classList.add('lg-in-seen')
    io!.observe(n)
  })
}

/* ---------------- nav drop ---------------- */

function scanNavDrop() {
  const menu = document.querySelector<HTMLElement>('.VPNavBarMenu')
  if (!menu || menu.querySelector('.lg-nav-drop')) {
    if (menu) placeDrop(menu, null, true)
    return
  }
  const drop = document.createElement('span')
  drop.className = 'lg-nav-drop'
  drop.setAttribute('aria-hidden', 'true')
  menu.prepend(drop)
  let vx = 0
  const state = {
    x: new Spring(0, DROP, (v, vel) => {
      vx = vel
      drop.style.setProperty('--x', `${v.toFixed(1)}px`)
      // Liquid stretch: faster drop, longer and thinner.
      const st = Math.min(Math.abs(vx) / 2500, 0.28)
      drop.style.setProperty('--sx', (1 + st).toFixed(3))
      drop.style.setProperty('--sy', (1 - st * 0.45).toFixed(3))
    }),
    w: new Spring(0, DROP, (v) => drop.style.setProperty('--w', `${v.toFixed(1)}px`)),
    o: new Spring(0, { damping: 1, stiffness: 300 }, (v) => drop.style.setProperty('--o', v.toFixed(3))),
  }
  ;(menu as any).__lgDrop = state
  menu.addEventListener('pointerover', (e) => {
    const link = (e.target as Element).closest<HTMLElement>('.VPNavBarMenuLink, .VPNavBarMenuGroup')
    if (link) placeDrop(menu, link, false)
  })
  menu.addEventListener('pointerleave', () => placeDrop(menu, null, false))
  placeDrop(menu, null, true)
}

function placeDrop(menu: HTMLElement, target: HTMLElement | null, snap: boolean) {
  const st = (menu as any).__lgDrop
  if (!st) return
  const el = target ?? menu.querySelector<HTMLElement>('.VPNavBarMenuLink.active')
  if (!el) {
    st.o.to(0)
    return
  }
  const m = menu.getBoundingClientRect()
  const r = el.getBoundingClientRect()
  const x = r.left - m.left + 2
  const w = r.width - 4
  if (snap || st.o.value < 0.05) {
    st.x.snap(x)
    st.w.snap(w)
  } else {
    st.x.to(x)
    st.w.to(w)
  }
  st.o.to(1)
}

/* ---------------- scroll edge ---------------- */

function startScrollEdge() {
  let on = false
  const update = () => {
    const next = scrollY > 6
    if (next !== on) {
      on = next
      root().classList.toggle('lg-scrolled', on)
    }
  }
  addEventListener('scroll', update, { passive: true })
  update()
}

/* ---------------- entry points ---------------- */

export function initGlass() {
  if (started) return
  started = true
  const r = root()
  r.classList.add('lg-js')
  const e = springEasing(ENTRANCE)
  r.style.setProperty('--lg-in-ease', e.easing)
  r.style.setProperty('--lg-in-dur', `${e.duration}ms`)
  const p = springEasing(PRESS)
  r.style.setProperty('--lg-press-ease', p.easing)
  r.style.setProperty('--lg-press-dur', `${p.duration}ms`)

  const canvas = document.querySelector<HTMLCanvasElement>('#lg-bg')
  if (canvas) startBackground(canvas)
  startLight()
  startPress()
  startTilt()
  startScrollEdge()
  refreshGlass()
  // Never leave content hidden if an observer misses something.
  setTimeout(() => {
    document.querySelectorAll<HTMLElement>('.lg-in:not(.lg-in-done)').forEach((n) => {
      if (n.getBoundingClientRect().top < innerHeight) n.classList.add('lg-in-done')
    })
  }, 3000)
}

/** Re-scan after a route change: new cards to reveal, new lenses, the nav drop's target. */
export function refreshGlass() {
  requestAnimationFrame(() => {
    scanEntrance()
    scanLenses()
    scanNavDrop()
  })
}

// Edge refraction of the Manager's liquid glass (ui/component/liquid/Lens.kt, from
// Kyant0/AndroidLiquidGlass) for the web. The AGSL shader bends what is behind a rounded rect
// near its edge; here the same math fills a displacement map, and an SVG filter applied through
// `backdrop-filter: url(#…)` does the bending. Only Chromium runs SVG filters in backdrop-filter,
// so other browsers keep the plain blur from the stylesheet.

interface LensSpec {
  height: number // refractionHeight: band along the edge that bends, px
  amount: number // refractionAmount: how far it bends, px
  depth: boolean // depthEffect: bend toward the centre too
  ca: number // chromaticAberration
}

// Same numbers as GlassDefaults (dp == CSS px).
const SPECS: Record<string, LensSpec> = {
  card: { height: 12, amount: 16, depth: false, ca: 0 },
  bar: { height: 8, amount: 12, depth: false, ca: 0 },
  droplet: { height: 12, amount: 20, depth: true, ca: 0.5 },
  dialog: { height: 16, amount: 24, depth: false, ca: 0 },
  // The hero's free-floating drop: a thick lens so the bending is easy to see.
  orb: { height: 44, amount: 40, depth: true, ca: 0.8 },
}

const supported = () =>
  typeof navigator !== 'undefined' &&
  !!(navigator as any).userAgentData?.brands?.some((b: { brand: string }) => /Chromium/.test(b.brand))

function sdRoundedRect(px: number, py: number, hx: number, hy: number, r: number) {
  const cx = Math.abs(px) - (hx - r)
  const cy = Math.abs(py) - (hy - r)
  const outside = Math.hypot(Math.max(cx, 0), Math.max(cy, 0)) - r
  const inside = Math.min(Math.max(cx, cy), 0)
  return outside + inside
}

function gradSdRoundedRect(px: number, py: number, hx: number, hy: number, r: number): [number, number] {
  const cx = Math.abs(px) - (hx - r)
  const cy = Math.abs(py) - (hy - r)
  const sx = Math.sign(px) || 1
  const sy = Math.sign(py) || 1
  if (cx >= 0 || cy >= 0) {
    const mx = Math.max(cx, 0)
    const my = Math.max(cy, 0)
    const l = Math.hypot(mx, my) || 1
    return [(sx * mx) / l, (sy * my) / l]
  }
  const gx = cy <= cx ? 1 : 0
  return [sx * gx, sy * (1 - gx)]
}

/** Displacement map (R = x, G = y, 128 = none) for a w x h rounded rect, as a PNG blob URL. */
async function buildMap(w: number, h: number, radius: number, s: LensSpec): Promise<string> {
  const canvas = document.createElement('canvas')
  canvas.width = w
  canvas.height = h
  const ctx = canvas.getContext('2d')!
  const img = ctx.createImageData(w, h)
  const hx = w / 2
  const hy = h / 2
  const r = Math.min(radius, hx, hy)
  const gradR = Math.min(r * 1.5, hx, hy)
  for (let y = 0; y < h; y++) {
    for (let x = 0; x < w; x++) {
      const i = (y * w + x) * 4
      const px = x + 0.5 - hx
      const py = y + 0.5 - hy
      let dx = 0
      let dy = 0
      let sd = sdRoundedRect(px, py, hx, hy, r)
      if (-sd < s.height) {
        sd = Math.min(sd, 0)
        const t = 1 - -sd / s.height
        const d = (1 - Math.sqrt(Math.max(0, 1 - t * t))) * s.amount
        let [gx, gy] = gradSdRoundedRect(px, py, hx, hy, gradR)
        if (s.depth) {
          const l = Math.hypot(px, py) || 1
          gx += px / l
          gy += py / l
        }
        const gl = Math.hypot(gx, gy) || 1
        // refractionAmount is passed negated in Lens.kt: samples move inward.
        dx = (-d * gx) / gl
        dy = (-d * gy) / gl
      }
      img.data[i] = 128 + Math.round((dx / s.amount) * 127)
      img.data[i + 1] = 128 + Math.round((dy / s.amount) * 127)
      img.data[i + 2] = 128
      img.data[i + 3] = 255
    }
  }
  ctx.putImageData(img, 0, 0)
  const blob = await new Promise<Blob | null>((res) => canvas.toBlob(res, 'image/png'))
  return blob ? URL.createObjectURL(blob) : canvas.toDataURL()
}

const SVGNS = 'http://www.w3.org/2000/svg'
let defs: SVGDefsElement | null = null
const filters = new Map<string, Promise<string>>()

function ensureDefs() {
  if (defs?.isConnected) return defs
  const svg = document.createElementNS(SVGNS, 'svg')
  svg.setAttribute('aria-hidden', 'true')
  svg.setAttribute('width', '0')
  svg.setAttribute('height', '0')
  svg.style.position = 'absolute'
  svg.style.pointerEvents = 'none'
  defs = document.createElementNS(SVGNS, 'defs')
  svg.appendChild(defs)
  document.body.appendChild(svg)
  filters.clear()
  return defs
}

function el<K extends string>(name: K, attrs: Record<string, string | number>) {
  const e = document.createElementNS(SVGNS, name)
  for (const [k, v] of Object.entries(attrs)) e.setAttribute(k, String(v))
  return e
}

function filterFor(w: number, h: number, radius: number, kind: string): Promise<string> {
  const s = SPECS[kind] ?? SPECS.card
  const key = `lg-${kind}-${w}x${h}-${Math.round(radius)}`
  let f = filters.get(key)
  if (f) return f
  f = buildMap(w, h, radius, s).then((url) => {
    const d = ensureDefs()
    const filter = el('filter', {
      id: key,
      x: 0,
      y: 0,
      width: w,
      height: h,
      filterUnits: 'userSpaceOnUse',
      primitiveUnits: 'userSpaceOnUse',
      'color-interpolation-filters': 'sRGB',
    })
    filter.appendChild(el('feImage', { href: url, x: 0, y: 0, width: w, height: h, result: 'map', preserveAspectRatio: 'none' }))
    // 128 +- 127 encodes +-amount; feDisplacementMap moves by scale * (C - 0.5).
    const scale = (s.amount * 255) / 127
    if (s.ca > 0) {
      // Dispersion: each channel bends a little more (red) or less (blue) than green.
      const ch: [string, number, string][] = [
        ['r', 1 + s.ca * 0.16, '1 0 0 0 0  0 0 0 0 0  0 0 0 0 0  0 0 0 1 0'],
        ['g', 1, '0 0 0 0 0  0 1 0 0 0  0 0 0 0 0  0 0 0 1 0'],
        ['b', 1 - s.ca * 0.16, '0 0 0 0 0  0 0 0 0 0  0 0 1 0 0  0 0 0 1 0'],
      ]
      for (const [n, m, matrix] of ch) {
        filter.appendChild(el('feDisplacementMap', { in: 'SourceGraphic', in2: 'map', scale: scale * m, xChannelSelector: 'R', yChannelSelector: 'G', result: `d${n}` }))
        filter.appendChild(el('feColorMatrix', { in: `d${n}`, type: 'matrix', values: matrix, result: n }))
      }
      filter.appendChild(el('feBlend', { in: 'r', in2: 'g', mode: 'screen', result: 'rg' }))
      filter.appendChild(el('feBlend', { in: 'rg', in2: 'b', mode: 'screen' }))
    } else {
      filter.appendChild(el('feDisplacementMap', { in: 'SourceGraphic', in2: 'map', scale, xChannelSelector: 'R', yChannelSelector: 'G' }))
    }
    d.appendChild(filter)
    return key
  })
  filters.set(key, f)
  return f
}

const sizes = new WeakMap<Element, string>()
let ro: ResizeObserver | null = null

async function apply(node: HTMLElement) {
  const w = Math.round(node.offsetWidth)
  const h = Math.round(node.offsetHeight)
  if (w < 8 || h < 8 || w * h > 1_600_000) return
  const cs = getComputedStyle(node)
  const radius = Math.min(parseFloat(cs.borderTopLeftRadius) || 0, w / 2, h / 2)
  const kind = node.dataset.lens || 'card'
  const key = `${w}x${h}-${radius}-${kind}`
  if (sizes.get(node) === key) return
  sizes.set(node, key)
  const id = await filterFor(w, h, radius, kind)
  if (sizes.get(node) === key) node.style.setProperty('--lg-lens', `url(#${id})`)
}

/** Gives every `[data-lens]` element under [root] its refraction filter, kept in sync with its size. */
export function scanLenses(root: ParentNode = document) {
  if (!supported()) return
  document.documentElement.classList.add('lg-lens-on')
  ro ??= new ResizeObserver((entries) => {
    for (const e of entries) apply(e.target as HTMLElement)
  })
  root.querySelectorAll<HTMLElement>('[data-lens]').forEach((n) => {
    // Glass inside glass samples nothing (see .lg .lg in style.css), so it needs no lens.
    if (n.parentElement?.closest('.lg')) return
    if (!sizes.has(n)) ro!.observe(n)
    apply(n)
  })
}

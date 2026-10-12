// Damped springs, parameterised like Compose's spring(dampingRatio, stiffness) so values can be
// copied from the Manager (GlassDefaults, InteractiveHighlight, CardEntrance) unchanged.

export interface SpringSpec {
  damping: number
  stiffness: number
}

/** A 1-D spring value stepped by requestAnimationFrame (semi-implicit Euler, unit mass). */
export class Spring {
  value: number
  target: number
  velocity = 0
  private raf = 0
  private last = 0

  constructor(
    value: number,
    private spec: SpringSpec,
    private onUpdate: (v: number, velocity: number) => void,
    private threshold = 0.001,
  ) {
    this.value = value
    this.target = value
  }

  to(target: number, spec?: SpringSpec) {
    this.target = target
    if (spec) this.spec = spec
    if (!this.raf) {
      this.last = performance.now()
      this.raf = requestAnimationFrame(this.tick)
    }
  }

  snap(value: number) {
    this.value = this.target = value
    this.velocity = 0
    this.onUpdate(value, 0)
  }

  stop() {
    cancelAnimationFrame(this.raf)
    this.raf = 0
  }

  private tick = (now: number) => {
    // Sub-steps keep stiff springs (stiffness 600) stable at 60 Hz.
    let dt = Math.min((now - this.last) / 1000, 1 / 20)
    this.last = now
    const k = this.spec.stiffness
    const c = 2 * this.spec.damping * Math.sqrt(k)
    while (dt > 0) {
      const h = Math.min(dt, 1 / 240)
      const force = -k * (this.value - this.target) - c * this.velocity
      this.velocity += force * h
      this.value += this.velocity * h
      dt -= h
    }
    const settled = Math.abs(this.velocity) < this.threshold * 10 && Math.abs(this.value - this.target) < this.threshold
    if (settled) {
      this.value = this.target
      this.velocity = 0
    }
    this.onUpdate(this.value, this.velocity)
    this.raf = settled ? 0 : requestAnimationFrame(this.tick)
  }
}

/** Closed-form step response, for baking a spring into a CSS linear() easing. */
function response(t: number, { damping: z, stiffness: k }: SpringSpec): number {
  const w0 = Math.sqrt(k)
  if (z < 1) {
    const wd = w0 * Math.sqrt(1 - z * z)
    return 1 - Math.exp(-z * w0 * t) * (Math.cos(wd * t) + ((z * w0) / wd) * Math.sin(wd * t))
  }
  return 1 - Math.exp(-w0 * t) * (1 + w0 * t)
}

/** CSS `linear()` easing plus duration (ms) for a spring, so plain transitions overshoot like Compose. */
export function springEasing(spec: SpringSpec): { easing: string; duration: number } {
  let end = 0.05
  while (end < 4) {
    let settled = true
    for (let t = end; t < end + 0.25; t += 0.01) {
      if (Math.abs(1 - response(t, spec)) > 0.002) settled = false
    }
    if (settled) break
    end += 0.05
  }
  const n = 48
  const pts: string[] = []
  for (let i = 0; i <= n; i++) pts.push(response((end * i) / n, spec).toFixed(4))
  pts[n] = '1'
  return { easing: `linear(${pts.join(', ')})`, duration: Math.round(end * 1000) }
}

// The Manager's animated background (ui/component/miuix/effect: OS3_BG_FRAG, BgEffectPainter,
// BgEffectConfig) ported to WebGL. Same shader, same presets, same timing: points drift with
// sin/cos(time), and every colorInterpPeriod / 2 seconds the palette springs (0.9, 35) to the next
// stage. Rendered at 1/4 resolution and stretched, like GlassDefaults.gradientDownscale.

interface Preset {
  points: number[]
  colors1: number[]
  colors2: number[]
  colors3: number[]
  colorInterpPeriod: number
  lightOffset: number
  saturateOffset: number
  pointOffset: number
}

const POINTS = [0.8, 0.2, 1.0, 0.8, 0.9, 1.0, 0.2, 0.9, 1.0, 0.2, 0.2, 1.0]

const PHONE_LIGHT: Preset = {
  points: POINTS,
  colors1: [1.0, 0.9, 0.94, 1.0, 1.0, 0.84, 0.89, 1.0, 0.97, 0.73, 0.82, 1.0, 0.64, 0.65, 0.98, 1.0],
  colors2: [0.58, 0.74, 1.0, 1.0, 1.0, 0.9, 0.93, 1.0, 0.74, 0.76, 1.0, 1.0, 0.97, 0.77, 0.84, 1.0],
  colors3: [0.98, 0.86, 0.9, 1.0, 0.6, 0.73, 0.98, 1.0, 0.92, 0.93, 1.0, 1.0, 0.56, 0.69, 1.0, 1.0],
  colorInterpPeriod: 5.0,
  lightOffset: 0.1,
  saturateOffset: 0.2,
  pointOffset: 0.2,
}

const PHONE_DARK: Preset = {
  points: POINTS,
  colors1: [0.2, 0.06, 0.88, 0.4, 0.3, 0.14, 0.55, 0.5, 0.0, 0.64, 0.96, 0.5, 0.11, 0.16, 0.83, 0.4],
  colors2: [0.07, 0.15, 0.79, 0.5, 0.62, 0.21, 0.67, 0.5, 0.06, 0.25, 0.84, 0.5, 0.0, 0.2, 0.78, 0.5],
  colors3: [0.58, 0.3, 0.74, 0.4, 0.27, 0.18, 0.6, 0.5, 0.66, 0.26, 0.62, 0.5, 0.12, 0.16, 0.7, 0.6],
  colorInterpPeriod: 8.0,
  lightOffset: 0.0,
  saturateOffset: 0.17,
  pointOffset: 0.4,
}

const PAD_LIGHT: Preset = {
  points: POINTS,
  colors1: [0.99, 0.77, 0.86, 1.0, 0.74, 0.76, 1.0, 1.0, 0.72, 0.74, 1.0, 1.0, 0.98, 0.76, 0.8, 1.0],
  colors2: [0.66, 0.75, 1.0, 1.0, 1.0, 0.86, 0.91, 1.0, 0.74, 0.76, 1.0, 1.0, 0.97, 0.77, 0.84, 1.0],
  colors3: [0.97, 0.79, 0.85, 1.0, 0.65, 0.68, 0.98, 1.0, 0.66, 0.77, 1.0, 1.0, 0.72, 0.73, 0.98, 1.0],
  colorInterpPeriod: 7.0,
  lightOffset: 0.1,
  saturateOffset: 0.2,
  pointOffset: 0.2,
}

const PAD_DARK: Preset = {
  points: POINTS,
  colors1: [0.66, 0.26, 0.62, 0.4, 0.06, 0.25, 0.84, 0.5, 0.0, 0.64, 0.96, 0.5, 0.14, 0.18, 0.55, 0.5],
  colors2: [0.07, 0.15, 0.79, 0.5, 0.11, 0.16, 0.83, 0.5, 0.06, 0.25, 0.84, 0.5, 0.66, 0.26, 0.62, 0.5],
  colors3: [0.58, 0.3, 0.74, 0.5, 0.11, 0.16, 0.83, 0.5, 0.66, 0.26, 0.62, 0.5, 0.27, 0.18, 0.6, 0.6],
  colorInterpPeriod: 7.0,
  lightOffset: 0.0,
  saturateOffset: 0.0,
  pointOffset: 0.2,
}

const VERT = `attribute vec2 p; void main() { gl_Position = vec4(p, 0.0, 1.0); }`

// OS3_BG_FRAG with AGSL's top-left fragCoord rebuilt from gl_FragCoord.
const FRAG = `
precision highp float;
uniform vec2 uResolution;
uniform float uAnimTime;
uniform vec4 uBound;
uniform float uTranslateY;
uniform vec3 uPoints[4];
uniform vec2 uPointsAnim[4];
uniform vec4 uColors[4];
uniform float uAlphaMulti;
uniform float uNoiseScale;
uniform float uPointRadiusMulti;
uniform float uSaturateOffset;
uniform float uLightOffset;

vec3 rgb2hsv(vec3 c) {
  vec4 K = vec4(0.0, -1.0 / 3.0, 2.0 / 3.0, -1.0);
  vec4 p = mix(vec4(c.bg, K.wz), vec4(c.gb, K.xy), step(c.b, c.g));
  vec4 q = mix(vec4(p.xyw, c.r), vec4(c.r, p.yzx), step(p.x, c.r));
  float d = q.x - min(q.w, q.y);
  float e = 1.0e-10;
  return vec3(abs(q.z + (q.w - q.y) / (6.0 * d + e)), d / (q.x + e), q.x);
}
vec3 hsv2rgb(vec3 c) {
  vec4 K = vec4(1.0, 2.0 / 3.0, 1.0 / 3.0, 3.0);
  vec3 p = abs(fract(c.xxx + K.xyz) * 6.0 - K.www);
  return c.z * mix(K.xxx, clamp(p - K.xxx, 0.0, 1.0), c.y);
}
float hash(vec2 p) {
  vec3 p3 = fract(vec3(p.xyx) * 0.13);
  p3 += dot(p3, p3.yzx + 3.333);
  return fract((p3.x + p3.y) * p3.z);
}
float perlin(vec2 x) {
  vec2 i = floor(x); vec2 f = fract(x);
  float a = hash(i); float b = hash(i + vec2(1.0, 0.0));
  float c = hash(i + vec2(0.0, 1.0)); float d = hash(i + vec2(1.0, 1.0));
  vec2 u = f * f * (3.0 - 2.0 * f);
  return mix(a, b, u.x) + (c - a) * u.y * (1.0 - u.x) + (d - b) * u.x * u.y;
}
float gradientNoise(in vec2 uv) {
  return fract(52.9829189 * fract(dot(uv, vec2(0.06711056, 0.00583715))));
}
void main() {
  vec2 fragCoord = vec2(gl_FragCoord.x, uResolution.y - gl_FragCoord.y);
  vec2 vUv = fragCoord / uResolution;
  vUv.y = 1.0 - vUv.y;
  vec2 uv = vUv;
  uv -= vec2(0., uTranslateY);
  uv.xy -= uBound.xy;
  uv.xy /= uBound.zw;
  vec4 color = vec4(0.0);
  float noiseValue = perlin(vUv * uNoiseScale + vec2(-uAnimTime, -uAnimTime));
  for (int i = 0; i < 4; i++) {
    vec4 pointColor = uColors[i];
    pointColor.rgb *= pointColor.a;
    vec2 point = uPointsAnim[i];
    float rad = uPoints[i].z * uPointRadiusMulti;
    float d = distance(uv, point);
    float pct = smoothstep(rad, 0., d);
    color.rgb = mix(color.rgb, pointColor.rgb, pct);
    color.a = mix(color.a, pointColor.a, pct);
  }
  float oppositeNoise = smoothstep(0., 1., noiseValue);
  color.rgb /= color.a;
  vec3 hsv = rgb2hsv(color.rgb);
  hsv.y = mix(hsv.y, 0.0, oppositeNoise * uSaturateOffset);
  color.rgb = hsv2rgb(hsv);
  color.rgb += oppositeNoise * uLightOffset;
  color.a = clamp(color.a, 0., 1.);
  color.a *= uAlphaMulti;
  color += (10.0 / 255.0) * gradientNoise(fragCoord.xy) - (5.0 / 255.0);
  gl_FragColor = vec4(color.rgb * color.a, color.a);
}`

const DOWNSCALE = 4

export function startBackground(canvas: HTMLCanvasElement): () => void {
  const gl = canvas.getContext('webgl', { premultipliedAlpha: true, antialias: false, alpha: true })
  if (!gl) {
    canvas.dataset.fallback = '1'
    return () => {}
  }

  const compile = (type: number, src: string) => {
    const s = gl.createShader(type)!
    gl.shaderSource(s, src)
    gl.compileShader(s)
    if (!gl.getShaderParameter(s, gl.COMPILE_STATUS)) throw new Error(gl.getShaderInfoLog(s) ?? 'shader')
    return s
  }
  const prog = gl.createProgram()!
  try {
    gl.attachShader(prog, compile(gl.VERTEX_SHADER, VERT))
    gl.attachShader(prog, compile(gl.FRAGMENT_SHADER, FRAG))
    gl.linkProgram(prog)
  } catch (e) {
    console.warn('[glass] background shader failed', e)
    canvas.dataset.fallback = '1'
    return () => {}
  }
  gl.useProgram(prog)

  const buf = gl.createBuffer()
  gl.bindBuffer(gl.ARRAY_BUFFER, buf)
  gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1, -1, 3, -1, -1, 3]), gl.STATIC_DRAW)
  const loc = gl.getAttribLocation(prog, 'p')
  gl.enableVertexAttribArray(loc)
  gl.vertexAttribPointer(loc, 2, gl.FLOAT, false, 0, 0)

  const u = (n: string) => gl.getUniformLocation(prog, n)
  const U = {
    res: u('uResolution'),
    time: u('uAnimTime'),
    bound: u('uBound'),
    points: u('uPoints'),
    pointsAnim: u('uPointsAnim'),
    colors: u('uColors'),
    light: u('uLightOffset'),
    sat: u('uSaturateOffset'),
  }
  // BgEffectPainter's static uniforms.
  gl.uniform1f(u('uTranslateY'), 0)
  gl.uniform1f(u('uNoiseScale'), 1.5)
  gl.uniform1f(u('uPointRadiusMulti'), 1)
  gl.uniform1f(u('uAlphaMulti'), 1)

  let preset = PHONE_LIGHT
  const pickPreset = () => {
    const dark = document.documentElement.classList.contains('dark')
    const pad = innerWidth >= 900
    preset = pad ? (dark ? PAD_DARK : PAD_LIGHT) : dark ? PHONE_DARK : PHONE_LIGHT
    gl.uniform3fv(U.points, preset.points)
    gl.uniform1f(U.light, preset.lightOffset)
    gl.uniform1f(U.sat, preset.saturateOffset)
  }

  const resize = () => {
    const w = Math.max(1, Math.round(innerWidth / DOWNSCALE))
    const h = Math.max(1, Math.round(innerHeight / DOWNSCALE))
    canvas.width = w
    canvas.height = h
    gl.viewport(0, 0, w, h)
    gl.uniform2f(U.res, w, h)
    // updateBound() with the effect filling the whole surface (logoHeight == totalHeight).
    if (w <= h) gl.uniform4f(U.bound, 0, 0, 1, 1)
    else {
      const a = w / h
      gl.uniform4f(U.bound, 0, 0.5 - a / 2, 1, a)
    }
    pickPreset()
  }

  const cycle = (i: number) => (((i % 4) + 4) % 4 === 1 ? preset.colors1 : ((i % 4) + 4) % 4 === 3 ? preset.colors3 : preset.colors2)
  const colors = new Float32Array(16)
  const anim = new Float32Array(8)

  // Colour stage: wait colorInterpPeriod * 500 ms, then spring(0.9, 35) to the next integer.
  let stage = 0
  let stageV = 0
  let stageTarget = 0
  let nextStageAt = performance.now() + preset.colorInterpPeriod * 500

  const t0 = performance.now()
  let last = t0
  let raf = 0
  let visible = !document.hidden

  const draw = (now: number) => {
    // Slow ambient drift, kept even under prefers-reduced-motion.
    const time = (now - t0) / 1000
    const dt = Math.min((now - last) / 1000, 0.05)
    last = now
    {
      if (now >= nextStageAt && Math.abs(stage - stageTarget) < 0.01) {
        stageTarget += 1
        nextStageAt = now + preset.colorInterpPeriod * 500 + 1200
      }
      const k = 35
      const c = 2 * 0.9 * Math.sqrt(k)
      stageV += (-k * (stage - stageTarget) - c * stageV) * dt
      stage += stageV * dt
    }
    const base = Math.floor(stage)
    const f = stage - base
    const a = cycle(base)
    const b = cycle(base + 1)
    for (let i = 0; i < 16; i++) colors[i] = a[i] + (b[i] - a[i]) * f
    for (let i = 0; i < 4; i++) {
      const sx = preset.points[i * 3]
      const sy = preset.points[i * 3 + 1]
      const ax = sx + Math.sin(time + sy) * preset.pointOffset
      const ay = sy + Math.cos(time + ax) * preset.pointOffset
      anim[i * 2] = ax
      anim[i * 2 + 1] = ay
    }
    gl.uniform1f(U.time, time)
    gl.uniform4fv(U.colors, colors)
    gl.uniform2fv(U.pointsAnim, anim)
    gl.drawArrays(gl.TRIANGLES, 0, 3)
    raf = visible ? requestAnimationFrame(draw) : 0
  }

  resize()
  raf = requestAnimationFrame(draw)

  const onVis = () => {
    visible = !document.hidden
    if (visible && !raf) {
      last = performance.now()
      raf = requestAnimationFrame(draw)
    }
  }
  const onResize = () => {
    resize()
    if (!raf) raf = requestAnimationFrame(draw)
  }
  const mo = new MutationObserver(() => {
    pickPreset()
    if (!raf) raf = requestAnimationFrame(draw)
  })
  mo.observe(document.documentElement, { attributes: true, attributeFilter: ['class'] })
  addEventListener('resize', onResize)
  document.addEventListener('visibilitychange', onVis)

  return () => {
    cancelAnimationFrame(raf)
    mo.disconnect()
    removeEventListener('resize', onResize)
    document.removeEventListener('visibilitychange', onVis)
  }
}

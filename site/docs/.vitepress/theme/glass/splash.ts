// Fluid ink that trails the pointer, drawn over the page.
// Ported from React Bits' SplashCursor (https://reactbits.dev/animations/splash-cursor),
// Copyright (c) 2026 David Haz, MIT + Commons Clause, itself based on Pavel Dobryakov's
// WebGL Fluid Simulation (MIT). Changes: the loop sleeps once the ink has faded and wakes on the
// next move, every listener is removed on stop, and colours come from a palette (the logo's
// blue/cyan/violet) instead of the full rainbow.

export interface SplashOptions {
  simResolution?: number
  dyeResolution?: number
  densityDissipation?: number
  velocityDissipation?: number
  pressure?: number
  pressureIterations?: number
  curl?: number
  splatRadius?: number
  splatForce?: number
  shading?: boolean
  colorUpdateSpeed?: number
  /** Hues in [0, 1) to pick from; empty means the full rainbow. */
  hues?: number[]
}

interface Rgb {
  r: number
  g: number
  b: number
}

interface Fbo {
  texture: WebGLTexture
  fbo: WebGLFramebuffer
  width: number
  height: number
  texelSizeX: number
  texelSizeY: number
  attach: (id: number) => number
}

interface DoubleFbo {
  width: number
  height: number
  texelSizeX: number
  texelSizeY: number
  read: Fbo
  write: Fbo
  swap: () => void
}

interface Format {
  internalFormat: number
  format: number
}

// Ink fades with densityDissipation 3.5 to under 1 % in about 1.5 s; sleep a little after that.
const IDLE_MS = 2500

const baseVertex = `
  precision highp float;
  attribute vec2 aPosition;
  varying vec2 vUv;
  varying vec2 vL;
  varying vec2 vR;
  varying vec2 vT;
  varying vec2 vB;
  uniform vec2 texelSize;

  void main () {
    vUv = aPosition * 0.5 + 0.5;
    vL = vUv - vec2(texelSize.x, 0.0);
    vR = vUv + vec2(texelSize.x, 0.0);
    vT = vUv + vec2(0.0, texelSize.y);
    vB = vUv - vec2(0.0, texelSize.y);
    gl_Position = vec4(aPosition, 0.0, 1.0);
  }
`

const copySource = `
  precision mediump float;
  precision mediump sampler2D;
  varying highp vec2 vUv;
  uniform sampler2D uTexture;
  void main () { gl_FragColor = texture2D(uTexture, vUv); }
`

const clearSource = `
  precision mediump float;
  precision mediump sampler2D;
  varying highp vec2 vUv;
  uniform sampler2D uTexture;
  uniform float value;
  void main () { gl_FragColor = value * texture2D(uTexture, vUv); }
`

const displaySource = `
  precision highp float;
  precision highp sampler2D;
  varying vec2 vUv;
  varying vec2 vL;
  varying vec2 vR;
  varying vec2 vT;
  varying vec2 vB;
  uniform sampler2D uTexture;
  uniform vec2 texelSize;

  void main () {
    vec3 c = texture2D(uTexture, vUv).rgb;
    #ifdef SHADING
      vec3 lc = texture2D(uTexture, vL).rgb;
      vec3 rc = texture2D(uTexture, vR).rgb;
      vec3 tc = texture2D(uTexture, vT).rgb;
      vec3 bc = texture2D(uTexture, vB).rgb;
      float dx = length(rc) - length(lc);
      float dy = length(tc) - length(bc);
      vec3 n = normalize(vec3(dx, dy, length(texelSize)));
      vec3 l = vec3(0.0, 0.0, 1.0);
      float diffuse = clamp(dot(n, l) + 0.7, 0.7, 1.0);
      c *= diffuse;
    #endif
    float a = max(c.r, max(c.g, c.b));
    gl_FragColor = vec4(c, a);
  }
`

const splatSource = `
  precision highp float;
  precision highp sampler2D;
  varying vec2 vUv;
  uniform sampler2D uTarget;
  uniform float aspectRatio;
  uniform vec3 color;
  uniform vec2 point;
  uniform float radius;

  void main () {
    vec2 p = vUv - point.xy;
    p.x *= aspectRatio;
    vec3 splat = exp(-dot(p, p) / radius) * color;
    vec3 base = texture2D(uTarget, vUv).xyz;
    gl_FragColor = vec4(base + splat, 1.0);
  }
`

const advectionSource = `
  precision highp float;
  precision highp sampler2D;
  varying vec2 vUv;
  uniform sampler2D uVelocity;
  uniform sampler2D uSource;
  uniform vec2 texelSize;
  uniform vec2 dyeTexelSize;
  uniform float dt;
  uniform float dissipation;

  vec4 bilerp (sampler2D sam, vec2 uv, vec2 tsize) {
    vec2 st = uv / tsize - 0.5;
    vec2 iuv = floor(st);
    vec2 fuv = fract(st);
    vec4 a = texture2D(sam, (iuv + vec2(0.5, 0.5)) * tsize);
    vec4 b = texture2D(sam, (iuv + vec2(1.5, 0.5)) * tsize);
    vec4 c = texture2D(sam, (iuv + vec2(0.5, 1.5)) * tsize);
    vec4 d = texture2D(sam, (iuv + vec2(1.5, 1.5)) * tsize);
    return mix(mix(a, b, fuv.x), mix(c, d, fuv.x), fuv.y);
  }

  void main () {
    #ifdef MANUAL_FILTERING
      vec2 coord = vUv - dt * bilerp(uVelocity, vUv, texelSize).xy * texelSize;
      vec4 result = bilerp(uSource, coord, dyeTexelSize);
    #else
      vec2 coord = vUv - dt * texture2D(uVelocity, vUv).xy * texelSize;
      vec4 result = texture2D(uSource, coord);
    #endif
    float decay = 1.0 + dissipation * dt;
    gl_FragColor = result / decay;
  }
`

const divergenceSource = `
  precision mediump float;
  precision mediump sampler2D;
  varying highp vec2 vUv;
  varying highp vec2 vL;
  varying highp vec2 vR;
  varying highp vec2 vT;
  varying highp vec2 vB;
  uniform sampler2D uVelocity;

  void main () {
    float L = texture2D(uVelocity, vL).x;
    float R = texture2D(uVelocity, vR).x;
    float T = texture2D(uVelocity, vT).y;
    float B = texture2D(uVelocity, vB).y;
    vec2 C = texture2D(uVelocity, vUv).xy;
    if (vL.x < 0.0) { L = -C.x; }
    if (vR.x > 1.0) { R = -C.x; }
    if (vT.y > 1.0) { T = -C.y; }
    if (vB.y < 0.0) { B = -C.y; }
    float div = 0.5 * (R - L + T - B);
    gl_FragColor = vec4(div, 0.0, 0.0, 1.0);
  }
`

const curlSource = `
  precision mediump float;
  precision mediump sampler2D;
  varying highp vec2 vUv;
  varying highp vec2 vL;
  varying highp vec2 vR;
  varying highp vec2 vT;
  varying highp vec2 vB;
  uniform sampler2D uVelocity;

  void main () {
    float L = texture2D(uVelocity, vL).y;
    float R = texture2D(uVelocity, vR).y;
    float T = texture2D(uVelocity, vT).x;
    float B = texture2D(uVelocity, vB).x;
    float vorticity = R - L - T + B;
    gl_FragColor = vec4(0.5 * vorticity, 0.0, 0.0, 1.0);
  }
`

const vorticitySource = `
  precision highp float;
  precision highp sampler2D;
  varying vec2 vUv;
  varying vec2 vL;
  varying vec2 vR;
  varying vec2 vT;
  varying vec2 vB;
  uniform sampler2D uVelocity;
  uniform sampler2D uCurl;
  uniform float curl;
  uniform float dt;

  void main () {
    float L = texture2D(uCurl, vL).x;
    float R = texture2D(uCurl, vR).x;
    float T = texture2D(uCurl, vT).x;
    float B = texture2D(uCurl, vB).x;
    float C = texture2D(uCurl, vUv).x;
    vec2 force = 0.5 * vec2(abs(T) - abs(B), abs(R) - abs(L));
    force /= length(force) + 0.0001;
    force *= curl * C;
    force.y *= -1.0;
    vec2 velocity = texture2D(uVelocity, vUv).xy;
    velocity += force * dt;
    velocity = min(max(velocity, -1000.0), 1000.0);
    gl_FragColor = vec4(velocity, 0.0, 1.0);
  }
`

const pressureSource = `
  precision mediump float;
  precision mediump sampler2D;
  varying highp vec2 vUv;
  varying highp vec2 vL;
  varying highp vec2 vR;
  varying highp vec2 vT;
  varying highp vec2 vB;
  uniform sampler2D uPressure;
  uniform sampler2D uDivergence;

  void main () {
    float L = texture2D(uPressure, vL).x;
    float R = texture2D(uPressure, vR).x;
    float T = texture2D(uPressure, vT).x;
    float B = texture2D(uPressure, vB).x;
    float divergence = texture2D(uDivergence, vUv).x;
    float pressure = (L + R + B + T - divergence) * 0.25;
    gl_FragColor = vec4(pressure, 0.0, 0.0, 1.0);
  }
`

const gradientSubtractSource = `
  precision mediump float;
  precision mediump sampler2D;
  varying highp vec2 vUv;
  varying highp vec2 vL;
  varying highp vec2 vR;
  varying highp vec2 vT;
  varying highp vec2 vB;
  uniform sampler2D uPressure;
  uniform sampler2D uVelocity;

  void main () {
    float L = texture2D(uPressure, vL).x;
    float R = texture2D(uPressure, vR).x;
    float T = texture2D(uPressure, vT).x;
    float B = texture2D(uPressure, vB).x;
    vec2 velocity = texture2D(uVelocity, vUv).xy;
    velocity.xy -= vec2(R - L, T - B);
    gl_FragColor = vec4(velocity, 0.0, 1.0);
  }
`

function hsvToRgb(h: number, s: number, v: number): Rgb {
  const i = Math.floor(h * 6)
  const f = h * 6 - i
  const p = v * (1 - s)
  const q = v * (1 - f * s)
  const t = v * (1 - (1 - f) * s)
  const table: [number, number, number][] = [
    [v, t, p],
    [q, v, p],
    [p, v, t],
    [p, q, v],
    [t, p, v],
    [v, p, q],
  ]
  const [r, g, b] = table[((i % 6) + 6) % 6]
  return { r, g, b }
}

/** Starts the fluid on `canvas` (sized by CSS to the viewport). Returns a stop function. */
export function startSplash(canvas: HTMLCanvasElement, options: SplashOptions = {}): () => void {
  const config = {
    simResolution: 128,
    dyeResolution: 1440,
    densityDissipation: 3.5,
    velocityDissipation: 2,
    pressure: 0.1,
    pressureIterations: 20,
    curl: 3,
    splatRadius: 0.2,
    splatForce: 6000,
    shading: true,
    colorUpdateSpeed: 10,
    hues: [] as number[],
    ...options,
  }

  const params = { alpha: true, depth: false, stencil: false, antialias: false, preserveDrawingBuffer: false }
  const gl2 = canvas.getContext('webgl2', params)
  const gl = (gl2 ?? canvas.getContext('webgl', params)) as WebGL2RenderingContext | null
  if (!gl) return () => {}
  const isWebGL2 = !!gl2

  /* ---------- formats ---------- */

  let supportLinearFiltering: boolean
  let halfFloatTexType: number
  if (isWebGL2) {
    gl.getExtension('EXT_color_buffer_float')
    supportLinearFiltering = !!gl.getExtension('OES_texture_float_linear')
    halfFloatTexType = gl.HALF_FLOAT
  } else {
    const halfFloat = gl.getExtension('OES_texture_half_float')
    supportLinearFiltering = !!gl.getExtension('OES_texture_half_float_linear')
    halfFloatTexType = halfFloat?.HALF_FLOAT_OES ?? 0
  }

  function supportRenderTextureFormat(internalFormat: number, format: number, type: number) {
    const texture = gl!.createTexture()
    gl!.bindTexture(gl!.TEXTURE_2D, texture)
    gl!.texParameteri(gl!.TEXTURE_2D, gl!.TEXTURE_MIN_FILTER, gl!.NEAREST)
    gl!.texParameteri(gl!.TEXTURE_2D, gl!.TEXTURE_MAG_FILTER, gl!.NEAREST)
    gl!.texParameteri(gl!.TEXTURE_2D, gl!.TEXTURE_WRAP_S, gl!.CLAMP_TO_EDGE)
    gl!.texParameteri(gl!.TEXTURE_2D, gl!.TEXTURE_WRAP_T, gl!.CLAMP_TO_EDGE)
    gl!.texImage2D(gl!.TEXTURE_2D, 0, internalFormat, 4, 4, 0, format, type, null)
    const fbo = gl!.createFramebuffer()
    gl!.bindFramebuffer(gl!.FRAMEBUFFER, fbo)
    gl!.framebufferTexture2D(gl!.FRAMEBUFFER, gl!.COLOR_ATTACHMENT0, gl!.TEXTURE_2D, texture, 0)
    const ok = gl!.checkFramebufferStatus(gl!.FRAMEBUFFER) === gl!.FRAMEBUFFER_COMPLETE
    gl!.bindFramebuffer(gl!.FRAMEBUFFER, null)
    gl!.deleteFramebuffer(fbo)
    gl!.deleteTexture(texture)
    return ok
  }

  function getSupportedFormat(internalFormat: number, format: number, type: number): Format | null {
    if (supportRenderTextureFormat(internalFormat, format, type)) return { internalFormat, format }
    if (!isWebGL2) return null
    switch (internalFormat) {
      case gl!.R16F:
        return getSupportedFormat(gl!.RG16F, gl!.RG, type)
      case gl!.RG16F:
        return getSupportedFormat(gl!.RGBA16F, gl!.RGBA, type)
      default:
        return null
    }
  }

  const formatRGBA = isWebGL2 ? getSupportedFormat(gl.RGBA16F, gl.RGBA, halfFloatTexType) : getSupportedFormat(gl.RGBA, gl.RGBA, halfFloatTexType)
  const formatRG = isWebGL2 ? getSupportedFormat(gl.RG16F, gl.RG, halfFloatTexType) : formatRGBA
  const formatR = isWebGL2 ? getSupportedFormat(gl.R16F, gl.RED, halfFloatTexType) : formatRGBA
  if (!formatRGBA || !formatRG || !formatR) return () => {}

  if (!supportLinearFiltering) {
    config.dyeResolution = 256
    config.shading = false
  }

  /* ---------- programs ---------- */

  function compileShader(type: number, source: string, keywords: string[] = []) {
    const shader = gl!.createShader(type)!
    gl!.shaderSource(shader, keywords.map((k) => `#define ${k}\n`).join('') + source)
    gl!.compileShader(shader)
    if (!gl!.getShaderParameter(shader, gl!.COMPILE_STATUS)) console.warn('splash:', gl!.getShaderInfoLog(shader))
    return shader
  }

  const vertexShader = compileShader(gl.VERTEX_SHADER, baseVertex)

  class Program {
    program: WebGLProgram
    uniforms: Record<string, WebGLUniformLocation | null> = {}
    constructor(fragment: string, keywords: string[] = []) {
      this.program = gl!.createProgram()!
      gl!.attachShader(this.program, vertexShader)
      gl!.attachShader(this.program, compileShader(gl!.FRAGMENT_SHADER, fragment, keywords))
      gl!.bindAttribLocation(this.program, 0, 'aPosition')
      gl!.linkProgram(this.program)
      if (!gl!.getProgramParameter(this.program, gl!.LINK_STATUS)) console.warn('splash:', gl!.getProgramInfoLog(this.program))
      const count = gl!.getProgramParameter(this.program, gl!.ACTIVE_UNIFORMS) as number
      for (let i = 0; i < count; i++) {
        const name = gl!.getActiveUniform(this.program, i)!.name
        this.uniforms[name] = gl!.getUniformLocation(this.program, name)
      }
    }
    bind() {
      gl!.useProgram(this.program)
    }
  }

  const copyProgram = new Program(copySource)
  const clearProgram = new Program(clearSource)
  const splatProgram = new Program(splatSource)
  const advectionProgram = new Program(advectionSource, supportLinearFiltering ? [] : ['MANUAL_FILTERING'])
  const divergenceProgram = new Program(divergenceSource)
  const curlProgram = new Program(curlSource)
  const vorticityProgram = new Program(vorticitySource)
  const pressureProgram = new Program(pressureSource)
  const gradientSubtractProgram = new Program(gradientSubtractSource)
  const displayProgram = new Program(displaySource, config.shading ? ['SHADING'] : [])

  const blit = (() => {
    gl.bindBuffer(gl.ARRAY_BUFFER, gl.createBuffer())
    gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1, -1, -1, 1, 1, 1, 1, -1]), gl.STATIC_DRAW)
    gl.bindBuffer(gl.ELEMENT_ARRAY_BUFFER, gl.createBuffer())
    gl.bufferData(gl.ELEMENT_ARRAY_BUFFER, new Uint16Array([0, 1, 2, 0, 2, 3]), gl.STATIC_DRAW)
    gl.vertexAttribPointer(0, 2, gl.FLOAT, false, 0, 0)
    gl.enableVertexAttribArray(0)
    return (target: Fbo | null, clear = false) => {
      if (target) {
        gl.viewport(0, 0, target.width, target.height)
        gl.bindFramebuffer(gl.FRAMEBUFFER, target.fbo)
      } else {
        gl.viewport(0, 0, gl.drawingBufferWidth, gl.drawingBufferHeight)
        gl.bindFramebuffer(gl.FRAMEBUFFER, null)
      }
      if (clear) {
        gl.clearColor(0, 0, 0, 0)
        gl.clear(gl.COLOR_BUFFER_BIT)
      }
      gl.drawElements(gl.TRIANGLES, 6, gl.UNSIGNED_SHORT, 0)
    }
  })()

  /* ---------- framebuffers ---------- */

  function createFBO(w: number, h: number, internalFormat: number, format: number, type: number, param: number): Fbo {
    gl!.activeTexture(gl!.TEXTURE0)
    const texture = gl!.createTexture()!
    gl!.bindTexture(gl!.TEXTURE_2D, texture)
    gl!.texParameteri(gl!.TEXTURE_2D, gl!.TEXTURE_MIN_FILTER, param)
    gl!.texParameteri(gl!.TEXTURE_2D, gl!.TEXTURE_MAG_FILTER, param)
    gl!.texParameteri(gl!.TEXTURE_2D, gl!.TEXTURE_WRAP_S, gl!.CLAMP_TO_EDGE)
    gl!.texParameteri(gl!.TEXTURE_2D, gl!.TEXTURE_WRAP_T, gl!.CLAMP_TO_EDGE)
    gl!.texImage2D(gl!.TEXTURE_2D, 0, internalFormat, w, h, 0, format, type, null)
    const fbo = gl!.createFramebuffer()!
    gl!.bindFramebuffer(gl!.FRAMEBUFFER, fbo)
    gl!.framebufferTexture2D(gl!.FRAMEBUFFER, gl!.COLOR_ATTACHMENT0, gl!.TEXTURE_2D, texture, 0)
    gl!.viewport(0, 0, w, h)
    gl!.clearColor(0, 0, 0, 0)
    gl!.clear(gl!.COLOR_BUFFER_BIT)
    return {
      texture,
      fbo,
      width: w,
      height: h,
      texelSizeX: 1 / w,
      texelSizeY: 1 / h,
      attach(id: number) {
        gl!.activeTexture(gl!.TEXTURE0 + id)
        gl!.bindTexture(gl!.TEXTURE_2D, texture)
        return id
      },
    }
  }

  function createDoubleFBO(w: number, h: number, internalFormat: number, format: number, type: number, param: number): DoubleFbo {
    return {
      width: w,
      height: h,
      texelSizeX: 1 / w,
      texelSizeY: 1 / h,
      read: createFBO(w, h, internalFormat, format, type, param),
      write: createFBO(w, h, internalFormat, format, type, param),
      swap() {
        const tmp = this.read
        this.read = this.write
        this.write = tmp
      },
    }
  }

  function dropFBO(target: Fbo) {
    gl!.deleteFramebuffer(target.fbo)
    gl!.deleteTexture(target.texture)
  }

  function resizeDoubleFBO(target: DoubleFbo, w: number, h: number, internalFormat: number, format: number, type: number, param: number) {
    if (target.width === w && target.height === h) return target
    const read = createFBO(w, h, internalFormat, format, type, param)
    copyProgram.bind()
    gl!.uniform1i(copyProgram.uniforms.uTexture, target.read.attach(0))
    blit(read)
    dropFBO(target.read)
    dropFBO(target.write)
    target.read = read
    target.write = createFBO(w, h, internalFormat, format, type, param)
    target.width = w
    target.height = h
    target.texelSizeX = 1 / w
    target.texelSizeY = 1 / h
    return target
  }

  function getResolution(resolution: number) {
    const w = gl!.drawingBufferWidth
    const h = gl!.drawingBufferHeight
    const aspect = w / h < 1 ? h / w : w / h
    const min = Math.round(resolution)
    const max = Math.round(resolution * aspect)
    return w > h ? { width: max, height: min } : { width: min, height: max }
  }

  let dye: DoubleFbo | null = null
  let velocity: DoubleFbo | null = null
  let divergence: Fbo | null = null
  let curl: Fbo | null = null
  let pressure: DoubleFbo | null = null

  function initFramebuffers() {
    const simRes = getResolution(config.simResolution)
    const dyeRes = getResolution(config.dyeResolution)
    const type = halfFloatTexType
    const filtering = supportLinearFiltering ? gl!.LINEAR : gl!.NEAREST
    gl!.disable(gl!.BLEND)
    dye = dye
      ? resizeDoubleFBO(dye, dyeRes.width, dyeRes.height, formatRGBA!.internalFormat, formatRGBA!.format, type, filtering)
      : createDoubleFBO(dyeRes.width, dyeRes.height, formatRGBA!.internalFormat, formatRGBA!.format, type, filtering)
    velocity = velocity
      ? resizeDoubleFBO(velocity, simRes.width, simRes.height, formatRG!.internalFormat, formatRG!.format, type, filtering)
      : createDoubleFBO(simRes.width, simRes.height, formatRG!.internalFormat, formatRG!.format, type, filtering)
    for (const old of [divergence, curl, pressure?.read, pressure?.write]) if (old) dropFBO(old)
    divergence = createFBO(simRes.width, simRes.height, formatR!.internalFormat, formatR!.format, type, gl!.NEAREST)
    curl = createFBO(simRes.width, simRes.height, formatR!.internalFormat, formatR!.format, type, gl!.NEAREST)
    pressure = createDoubleFBO(simRes.width, simRes.height, formatR!.internalFormat, formatR!.format, type, gl!.NEAREST)
  }

  /* ---------- simulation ---------- */

  function step(dt: number) {
    const v = velocity!
    const d = dye!
    const p = pressure!
    gl!.disable(gl!.BLEND)

    curlProgram.bind()
    gl!.uniform2f(curlProgram.uniforms.texelSize, v.texelSizeX, v.texelSizeY)
    gl!.uniform1i(curlProgram.uniforms.uVelocity, v.read.attach(0))
    blit(curl)

    vorticityProgram.bind()
    gl!.uniform2f(vorticityProgram.uniforms.texelSize, v.texelSizeX, v.texelSizeY)
    gl!.uniform1i(vorticityProgram.uniforms.uVelocity, v.read.attach(0))
    gl!.uniform1i(vorticityProgram.uniforms.uCurl, curl!.attach(1))
    gl!.uniform1f(vorticityProgram.uniforms.curl, config.curl)
    gl!.uniform1f(vorticityProgram.uniforms.dt, dt)
    blit(v.write)
    v.swap()

    divergenceProgram.bind()
    gl!.uniform2f(divergenceProgram.uniforms.texelSize, v.texelSizeX, v.texelSizeY)
    gl!.uniform1i(divergenceProgram.uniforms.uVelocity, v.read.attach(0))
    blit(divergence)

    clearProgram.bind()
    gl!.uniform1i(clearProgram.uniforms.uTexture, p.read.attach(0))
    gl!.uniform1f(clearProgram.uniforms.value, config.pressure)
    blit(p.write)
    p.swap()

    pressureProgram.bind()
    gl!.uniform2f(pressureProgram.uniforms.texelSize, v.texelSizeX, v.texelSizeY)
    gl!.uniform1i(pressureProgram.uniforms.uDivergence, divergence!.attach(0))
    for (let i = 0; i < config.pressureIterations; i++) {
      gl!.uniform1i(pressureProgram.uniforms.uPressure, p.read.attach(1))
      blit(p.write)
      p.swap()
    }

    gradientSubtractProgram.bind()
    gl!.uniform2f(gradientSubtractProgram.uniforms.texelSize, v.texelSizeX, v.texelSizeY)
    gl!.uniform1i(gradientSubtractProgram.uniforms.uPressure, p.read.attach(0))
    gl!.uniform1i(gradientSubtractProgram.uniforms.uVelocity, v.read.attach(1))
    blit(v.write)
    v.swap()

    advectionProgram.bind()
    gl!.uniform2f(advectionProgram.uniforms.texelSize, v.texelSizeX, v.texelSizeY)
    if (!supportLinearFiltering) gl!.uniform2f(advectionProgram.uniforms.dyeTexelSize, v.texelSizeX, v.texelSizeY)
    const velocityId = v.read.attach(0)
    gl!.uniform1i(advectionProgram.uniforms.uVelocity, velocityId)
    gl!.uniform1i(advectionProgram.uniforms.uSource, velocityId)
    gl!.uniform1f(advectionProgram.uniforms.dt, dt)
    gl!.uniform1f(advectionProgram.uniforms.dissipation, config.velocityDissipation)
    blit(v.write)
    v.swap()

    if (!supportLinearFiltering) gl!.uniform2f(advectionProgram.uniforms.dyeTexelSize, d.texelSizeX, d.texelSizeY)
    gl!.uniform1i(advectionProgram.uniforms.uVelocity, v.read.attach(0))
    gl!.uniform1i(advectionProgram.uniforms.uSource, d.read.attach(1))
    gl!.uniform1f(advectionProgram.uniforms.dissipation, config.densityDissipation)
    blit(d.write)
    d.swap()
  }

  function render() {
    gl!.blendFunc(gl!.ONE, gl!.ONE_MINUS_SRC_ALPHA)
    gl!.enable(gl!.BLEND)
    displayProgram.bind()
    if (config.shading) gl!.uniform2f(displayProgram.uniforms.texelSize, 1 / gl!.drawingBufferWidth, 1 / gl!.drawingBufferHeight)
    gl!.uniform1i(displayProgram.uniforms.uTexture, dye!.read.attach(0))
    blit(null, true)
  }

  function correctRadius(radius: number) {
    const aspect = canvas.width / canvas.height
    return aspect > 1 ? radius * aspect : radius
  }

  function splat(x: number, y: number, dx: number, dy: number, color: Rgb) {
    const v = velocity!
    const d = dye!
    splatProgram.bind()
    gl!.uniform1i(splatProgram.uniforms.uTarget, v.read.attach(0))
    gl!.uniform1f(splatProgram.uniforms.aspectRatio, canvas.width / canvas.height)
    gl!.uniform2f(splatProgram.uniforms.point, x, y)
    gl!.uniform3f(splatProgram.uniforms.color, dx, dy, 0)
    gl!.uniform1f(splatProgram.uniforms.radius, correctRadius(config.splatRadius / 100))
    blit(v.write)
    v.swap()

    gl!.uniform1i(splatProgram.uniforms.uTarget, d.read.attach(0))
    gl!.uniform3f(splatProgram.uniforms.color, color.r, color.g, color.b)
    blit(d.write)
    d.swap()
  }

  function generateColor(): Rgb {
    const hue = config.hues.length ? config.hues[Math.floor(Math.random() * config.hues.length)] : Math.random()
    const c = hsvToRgb(hue, 1, 1)
    return { r: c.r * 0.15, g: c.g * 0.15, b: c.b * 0.15 }
  }

  /* ---------- input and loop ---------- */

  const pointer = { x: 0, y: 0, prevX: 0, prevY: 0, dx: 0, dy: 0, moved: false, color: generateColor() }
  let raf = 0
  let lastTime = 0
  let lastInput = 0
  let colorTimer = 0

  const scale = (v: number) => Math.floor(v * (devicePixelRatio || 1))

  function resizeCanvas() {
    const w = scale(canvas.clientWidth)
    const h = scale(canvas.clientHeight)
    if (canvas.width === w && canvas.height === h) return false
    canvas.width = w
    canvas.height = h
    return true
  }

  function frame(now: number) {
    raf = 0
    const dt = Math.min((now - lastTime) / 1000, 0.016666)
    lastTime = now
    if (resizeCanvas()) initFramebuffers()
    colorTimer += dt * config.colorUpdateSpeed
    if (colorTimer >= 1) {
      colorTimer %= 1
      pointer.color = generateColor()
    }
    if (pointer.moved) {
      pointer.moved = false
      splat(pointer.x, pointer.y, pointer.dx * config.splatForce, pointer.dy * config.splatForce, pointer.color)
    }
    step(dt)
    render()
    if (now - lastInput < IDLE_MS) {
      raf = requestAnimationFrame(frame)
    } else {
      // The ink has faded: clear the last faint frame and sleep until the next move.
      gl!.bindFramebuffer(gl!.FRAMEBUFFER, null)
      gl!.clearColor(0, 0, 0, 0)
      gl!.clear(gl!.COLOR_BUFFER_BIT)
    }
  }

  function wake() {
    lastInput = performance.now()
    if (raf) return
    lastTime = lastInput
    raf = requestAnimationFrame(frame)
  }

  function moveTo(clientX: number, clientY: number) {
    pointer.prevX = pointer.x
    pointer.prevY = pointer.y
    pointer.x = scale(clientX) / canvas.width
    pointer.y = 1 - scale(clientY) / canvas.height
    const aspect = canvas.width / canvas.height
    let dx = pointer.x - pointer.prevX
    let dy = pointer.y - pointer.prevY
    if (aspect < 1) dx *= aspect
    if (aspect > 1) dy /= aspect
    pointer.dx = dx
    pointer.dy = dy
    pointer.moved = dx !== 0 || dy !== 0
  }

  function placeAt(clientX: number, clientY: number) {
    pointer.x = pointer.prevX = scale(clientX) / canvas.width
    pointer.y = pointer.prevY = 1 - scale(clientY) / canvas.height
    pointer.dx = pointer.dy = 0
    pointer.moved = false
  }

  let placed = false
  const onMove = (e: PointerEvent) => {
    if (!dye) return
    // The first event only places the pointer, so the ink does not shoot in from the corner.
    if (!placed) {
      placed = true
      placeAt(e.clientX, e.clientY)
    } else {
      moveTo(e.clientX, e.clientY)
    }
    wake()
  }
  const onDown = (e: PointerEvent) => {
    if (!dye) return
    placed = true
    placeAt(e.clientX, e.clientY)
    pointer.color = generateColor()
    if (e.pointerType === 'mouse') {
      // A click throws a bright burst, as in the original.
      const c = generateColor()
      splat(pointer.x, pointer.y, 10 * (Math.random() - 0.5), 30 * (Math.random() - 0.5), { r: c.r * 10, g: c.g * 10, b: c.b * 10 })
    }
    wake()
  }
  const onLeave = () => {
    placed = false
  }

  resizeCanvas()
  initFramebuffers()
  addEventListener('pointermove', onMove, { passive: true })
  addEventListener('pointerdown', onDown, { passive: true })
  document.documentElement.addEventListener('pointerleave', onLeave)

  return () => {
    cancelAnimationFrame(raf)
    removeEventListener('pointermove', onMove)
    removeEventListener('pointerdown', onDown)
    document.documentElement.removeEventListener('pointerleave', onLeave)
    gl.getExtension('WEBGL_lose_context')?.loseContext()
  }
}

// Behaviour for the landing page: tonight strip, seat map, gig guide, venue
// editor and the WebGL layers. Ported from frontend_design/landing.html and
// driven imperatively against the markup rendered by Landing.tsx.
// Returns a cleanup that stops every listener, observer and frame loop.

type Seat = {
  r: number; a: number; block: string
  x?: number; y?: number; n?: number; tier?: number; open?: boolean; i?: number
  el?: SVGGElement
}
type Layer = ReturnType<typeof glLayer>

const ASSETS = '/assets-v9/'

const GLSL = `#version 300 es
precision highp float;
in vec2 v; out vec4 o;
float h(vec2 p){ return fract(sin(dot(p, vec2(127.1, 311.7)))*43758.5453); }
float n(vec2 p){ vec2 i = floor(p), f = fract(p); f = f*f*(3. - 2.*f); return mix(mix(h(i), h(i + vec2(1, 0)), f.x), mix(h(i + vec2(0, 1)), h(i + 1.), f.x), f.y); }
// image uv for a screen uv, with the image covering the canvas
vec2 cover(vec2 uv, vec2 res, float ia, float zoom, vec2 focus){ float ca = res.x/res.y; vec2 s = ca > ia ? vec2(1., ia/ca) : vec2(ca/ia, 1.); return focus + (uv - .5)*s/zoom; }
// lights pulse on the beat
vec3 pulse(vec3 c, float t, float amt){ float beat = pow(.5 + .5*sin(t*2.6), 8.); return c + c*smoothstep(.3, .85, dot(c, vec3(.3, .59, .11)))*(amt*.35 + amt*beat); }
// lift blacks to the page's night colour, then film grain
vec3 finish(vec3 c, vec2 uv, vec2 px, float t){ c *= 1. - .35*pow(length(uv - .5)*1.2, 2.); c = mix(vec3(.051, .059, .027), vec3(1.), c); return c + (h(px + fract(t*7.)*113.) - .5)*.07; }
`

const HERO_FS = `
uniform sampler2D uBg, uFg; uniform vec2 uRes, uPtr; uniform float uT, uScroll, uBgA, uFgA;
void main(){
  vec2 uv = v, px = uv*uRes; float asp = uRes.x/uRes.y;
  // the stage sits in the top half, drifts against the pointer and lags the scroll
  vec2 bu = cover(uv, uRes, uBgA, 1.1, vec2(.5, .27) - uPtr*vec2(.014, .01) + vec2(0., uScroll*.1));
  bu += (vec2(n(bu*7. + uT*.18), n(bu*7. - uT*.15 + 3.)) - .5)*.006*smoothstep(.3, .9, uv.y);
  vec2 ca = (uv - .5)*.005;
  vec3 col = vec3(texture(uBg, bu + ca).r, texture(uBg, bu).g, texture(uBg, bu - ca).b);
  col = pulse(col, uT, .5);
  // moving heads: slow cones sweeping over the photo
  for (int i = 0; i < 5; i++){
    float fi = float(i);
    vec2 d = uv - vec2(.1 + fi*.2, 1.05); d.x *= asp;
    float a = atan(d.x, -d.y) - sin(uT*.33 + fi*1.9)*.55;
    float cone = exp(-a*a*140.)*smoothstep(1.4, 0., length(d));
    vec3 c = mod(fi, 2.) < 1. ? vec3(.8, .92, .23) : vec3(.55, .45, 1.);
    col += c*cone*.2*(.55 + .45*n(vec2(length(d)*4. - uT*.6, fi*7.)));
  }
  // the crowd: nearer than the stage, so it moves more and rises as you scroll
  float sw = max(1.1, uRes.y*(asp < 1. ? 1.2 : .84)*uFgA/uRes.x);
  vec2 fu = vec2((uv.x - .5 - uPtr.x*.03)/sw + .5, (uv.y + .02 - uScroll*.18 + uPtr.y*.012)/(sw*asp/uFgA));
  vec4 f = texture(uFg, fu);
  col = mix(col, f.rgb*mix(vec3(.9, 1.1, .45), vec3(.8, .7, 1.2), uv.x)*1.5, f.a);
  // room for the type: darker nav band and lower left
  col *= 1. - .7*smoothstep(.8, 1., uv.y);
  col *= 1. - .4*smoothstep(.9, 0., length((uv - vec2(0., .1))*vec2(asp*.55, 1.)));
  o = vec4(finish(col, uv, px, uT), 1.);
}`

const POSTER_FS = `
uniform sampler2D uA, uB; uniform vec2 uRes, uVel; uniform float uT, uMix;
vec3 split(sampler2D s, vec2 uv, vec2 d){ return vec3(texture(s, uv + d).r, texture(s, uv).g, texture(s, uv - d).b); }
void main(){
  vec2 uv = v, vel = uVel; float sp = length(vel);
  uv.x += sin(uv.y*8. + uT*7.)*vel.x*.02;
  uv.y += sin(uv.x*6. + uT*6.)*vel.y*.02;
  vec2 c = uv - .5; uv = .5 + c*(1. - sp*.06*(1. - dot(c, c)*2.));
  vec3 a = split(uA, uv, vel*.006), b = split(uB, uv, vel*.006);
  float r = length(fract(uv*vec2(22., 33.)) - .5);
  float rad = clamp(uMix*1.7 - (1. - uv.y)*.7, 0., .75);
  o = vec4(mix(a, b, 1. - smoothstep(rad - .05, rad + .05, r)), 1.);
}`

const TONIGHT: Record<string, [string, string][]> = {
  Mumbai: [['Kiln Yard Jazz Sessions', '8:30 pm · 41 left'], ['Tanvi Rao: Work in Progress', '9:00 pm · 12 left'], ['Monsoon Frequencies', 'Sat · 212 left']],
  Bengaluru: [['Open Mic at Tin Roof', '9:00 pm · 30 left'], ['Carnatic Nights', '7:00 pm · 64 left']],
  Delhi: [['Tughlaq', '7:00 pm · 88 left'], ['Qawwali at the Fort', '8:00 pm · 19 left']],
  Pune: [['Kabaddi: Pune vs Jaipur', '6:30 pm · 540 left']],
}

const GIGS = [
  { cat: 'MUSIC', title: 'Monsoon Frequencies', poster: 'p-monsoon', venue: 'Harbourline Arena', city: 'Mumbai', when: '2026-10-17T14:00:00Z', from: 2500, left: 212, cap: 1800 },
  { cat: 'COMEDY', title: 'Tanvi Rao: Work in Progress', poster: 'p-tanvi', venue: 'Chalk Room', city: 'Mumbai', when: '2026-10-09T15:30:00Z', from: 799, left: 12, cap: 212 },
  { cat: 'THEATRE', title: 'Tughlaq', poster: 'p-tughlaq', venue: 'Rangmanch Hall', city: 'Delhi', when: '2026-10-24T13:30:00Z', from: 1200, left: 88, cap: 420 },
  { cat: 'SPORTS', title: 'Kabaddi: Pune vs Jaipur', poster: 'p-kabaddi', venue: 'Riverside Dome', city: 'Pune', when: '2026-11-01T13:00:00Z', from: 450, left: 540, cap: 4000 },
  { cat: 'MUSIC', title: 'Kiln Yard Jazz Sessions', poster: 'p-jazz', venue: 'Kiln Yard', city: 'Mumbai', when: '2026-10-30T15:00:00Z', from: 999, left: 41, cap: 260 },
  { cat: 'COMEDY', title: 'Open Mic at Tin Roof', poster: 'p-openmic', venue: 'Tin Roof Club', city: 'Bengaluru', when: '2026-11-07T14:30:00Z', from: 599, left: 30, cap: 140 },
]
const catName: Record<string, string> = { MUSIC: 'Music', COMEDY: 'Comedy', THEATRE: 'Theatre', SPORTS: 'Sport' }

const LIME = '#CDEB3A'
const inr = (p: number) => new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR', maximumFractionDigits: 0 }).format(p)
const $ = <T extends HTMLElement = HTMLElement>(id: string) => document.getElementById(id) as T
const part = (iso: string, o: Intl.DateTimeFormatOptions) => new Intl.DateTimeFormat('en-IN', { timeZone: 'Asia/Kolkata', ...o }).format(new Date(iso))
const load = (src: string) => new Promise<HTMLImageElement>((ok, no) => { const i = new Image(); i.onload = () => ok(i); i.onerror = no; i.src = src })

function fit(c: HTMLCanvasElement): [CanvasRenderingContext2D, number, number] {
  const r = c.getBoundingClientRect(), d = devicePixelRatio || 1
  c.width = r.width * d; c.height = r.height * d
  const g = c.getContext('2d')!
  g.setTransform(d, 0, 0, d, 0, 0)
  return [g, r.width, r.height]
}
function rng(s: number) { return () => (s = (s * 16807) % 2147483647) / 2147483647 }
function seatShape(g: CanvasRenderingContext2D, x: number, y: number, w: number, h: number, rot: number) {
  g.save(); g.translate(x, y); g.rotate(rot); g.beginPath()
  const r = w * .32; g.moveTo(-w / 2, h / 2); g.lineTo(-w / 2, -h / 2 + r); g.quadraticCurveTo(-w / 2, -h / 2, -w / 2 + r, -h / 2); g.lineTo(w / 2 - r, -h / 2); g.quadraticCurveTo(w / 2, -h / 2, w / 2, -h / 2 + r); g.lineTo(w / 2, h / 2); g.closePath(); g.restore()
}

function glLayer(canvas: HTMLCanvasElement, frag: string) {
  const gl = canvas.getContext('webgl2', { alpha: false, antialias: false })
  if (!gl) throw new Error('no webgl2')
  const sh = (type: number, src: string) => { const s = gl.createShader(type)!; gl.shaderSource(s, src); gl.compileShader(s); if (!gl.getShaderParameter(s, gl.COMPILE_STATUS)) throw new Error(gl.getShaderInfoLog(s) ?? ''); return s }
  const pr = gl.createProgram()
  gl.attachShader(pr, sh(gl.VERTEX_SHADER, '#version 300 es\nin vec2 p; out vec2 v; void main(){ v = p*.5 + .5; gl_Position = vec4(p, 0, 1); }'))
  gl.attachShader(pr, sh(gl.FRAGMENT_SHADER, GLSL + frag))
  gl.bindAttribLocation(pr, 0, 'p'); gl.linkProgram(pr); gl.useProgram(pr)
  gl.bindBuffer(gl.ARRAY_BUFFER, gl.createBuffer())
  gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1, -1, 1, -1, -1, 1, 1, 1]), gl.STATIC_DRAW)
  gl.enableVertexAttribArray(0); gl.vertexAttribPointer(0, 2, gl.FLOAT, false, 0, 0)
  const locs: Record<string, WebGLUniformLocation | null> = {}
  const u = (k: string) => k in locs ? locs[k] : (locs[k] = gl.getUniformLocation(pr, k))
  const layer = {
    tex(img: HTMLImageElement, unit: number, name?: string, mip?: boolean) {
      gl.activeTexture(gl.TEXTURE0 + unit); gl.bindTexture(gl.TEXTURE_2D, gl.createTexture())
      gl.pixelStorei(gl.UNPACK_FLIP_Y_WEBGL, true)
      gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, gl.RGBA, gl.UNSIGNED_BYTE, img)
      if (mip) gl.generateMipmap(gl.TEXTURE_2D)
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, mip ? gl.LINEAR_MIPMAP_LINEAR : gl.LINEAR)
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE); gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE)
      if (name) layer.int(name, unit)
      layer.set(name + 'A', img.width / img.height)
    },
    int(k: string, x: number) { gl.uniform1i(u(k), x) },
    set(k: string, ...x: number[]) {
      const loc = u(k)
      if (x.length === 1) gl.uniform1f(loc, x[0])
      else gl.uniform2f(loc, x[0], x[1])
    },
    size() {
      const d = Math.min(devicePixelRatio || 1, 2), r = canvas.getBoundingClientRect()
      canvas.width = Math.max(1, Math.round(r.width * d)); canvas.height = Math.max(1, Math.round(r.height * d))
      gl.viewport(0, 0, canvas.width, canvas.height); layer.set('uRes', canvas.width, canvas.height)
    },
    draw() { gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4) },
  }
  return layer
}

export function mountLanding(): () => void {
  const reduce = matchMedia('(prefers-reduced-motion: reduce)').matches
  const ac = new AbortController(), signal = ac.signal
  const observers: IntersectionObserver[] = []
  const timers = new Set<number>()
  let alive = true
  const frame = (cb: FrameRequestCallback) => requestAnimationFrame(t => { if (alive) cb(t) })

  /* ---------- Tonight strip ---------- */
  function tonight(city: string) {
    $('tonightLabel').textContent = 'Tonight in ' + city
    $('tonightList').innerHTML = TONIGHT[city].map(([a, b]) => `<li><b>${a}</b> <span>${b}</span></li>`).join('')
  }
  $('q-city').addEventListener('change', e => tonight((e.target as HTMLSelectElement).value), { signal })
  $('search').addEventListener('submit', e => { e.preventDefault(); $('guide').scrollIntoView({ behavior: reduce ? 'auto' : 'smooth' }) }, { signal })
  tonight('Mumbai')

  /* ---------- Seat map: a curved house around a lit stage ---------- */
  const NS = 'http://www.w3.org/2000/svg'
  const mk = <T extends SVGElement = SVGElement>(tag: string, at: Record<string, string | number>, parent?: Element) => {
    const e = document.createElementNS(NS, tag) as T
    for (const k in at) e.setAttribute(k, String(at[k]))
    if (parent) parent.appendChild(e)
    return e
  }
  const ROWS = 'ABCDEFGH', RAD = [440, 470, 530, 560, 590, 650, 680, 710]
  // name, price, first row, row after last, radius of the ring label
  const TIERS: [string, number, number, number, number][] = [['Front', 6500, 0, 2, 424], ['Middle', 4500, 2, 5, 507], ['Back', 2500, 5, 8, 627]]
  const tierOf = (r: number) => TIERS.findIndex(t => r >= t[2] && r < t[3])
  // rows are gentle arcs around a point far above the stage
  const SX = 500, SY = -330, SPAN = 36 * Math.PI / 180, AISLE = 12 * Math.PI / 180, PITCH = 26, FRONT = 395, STAGE = 22 * Math.PI / 180
  const pt = (r: number, a: number): [number, number] => [SX + r * Math.sin(a), SY + r * Math.cos(a)]
  const f1 = (n: number) => +n.toFixed(1)
  const arc = (r: number, a0: number, a1: number) => { const [x0, y0] = pt(r, a0), [x1, y1] = pt(r, a1); return `M${f1(x0)} ${f1(y0)}A${r} ${r} 0 0 0 ${f1(x1)} ${f1(y1)}` }
  const rr = (x: number, y: number, w: number, h: number, r: number) => `M${x + r} ${y}h${w - 2 * r}a${r} ${r} 0 0 1 ${r} ${r}v${h - 2 * r}a${r} ${r} 0 0 1 ${-r} ${r}h${2 * r - w}a${r} ${r} 0 0 1 ${-r} ${-r}v${2 * r - h}a${r} ${r} 0 0 1 ${r} ${-r}z`
  const SEAT = rr(-10, -9, 20, 12, 3.5) + rr(-11, 4.5, 22, 4.5, 2)   // cushion toward the stage, backrest behind

  // seats: three blocks per row split by two aisles, some already gone
  const rand = rng(20261017), seats: Seat[] = [], rows: Seat[][] = []
  RAD.forEach((r, ri) => {
    const g = 13 / r, row: Seat[] = []
    ;([[-SPAN, -AISLE - g, 'Left'], [-AISLE + g, AISLE - g, 'Centre'], [AISLE + g, SPAN, 'Right']] as [number, number, string][]).forEach(([a0, a1, block]) => {
      const n = Math.floor((a1 - a0) * r / PITCH) + 1, step = PITCH / r, mid = (a0 + a1) / 2
      for (let k = 0; k < n; k++) row.push({ r: ri, a: mid + (k - (n - 1) / 2) * step, block })
    })
    let prev = false
    row.forEach((s, k) => {
      const p = [.5, .48, .46, .44, .42, .38, .34, .3][ri] + .12 * (1 - Math.abs(s.a) / SPAN) - .1 + (prev ? .2 : 0);
      [s.x, s.y] = pt(r, s.a); s.n = k + 1; s.tier = tierOf(ri); s.open = rand() > p; prev = !s.open
      s.i = seats.length; seats.push(s)
    })
    rows.push(row)
  })

  const plan = $('plan')
  const svg = mk<SVGSVGElement>('svg', { viewBox: '56 -6 888 410', tabindex: 0, role: 'group', 'aria-label': 'Seat map for Monsoon Frequencies. Arrow keys move between open seats, Enter picks.' }, plan)
  const defs = mk('defs', {}, svg)
  defs.innerHTML = `
    <radialGradient id="stageSpill" gradientUnits="userSpaceOnUse" cx="500" cy="70" r="240"><stop offset="0" stop-color="#CDEB3A" stop-opacity=".26"/><stop offset="1" stop-color="#CDEB3A" stop-opacity="0"/></radialGradient>
    <linearGradient id="beamL" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#CDEB3A" stop-opacity=".22"/><stop offset=".85" stop-color="#CDEB3A" stop-opacity="0"/></linearGradient>
    <linearGradient id="beamV" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#A490FF" stop-opacity=".26"/><stop offset=".85" stop-color="#A490FF" stop-opacity="0"/></linearGradient>
    <filter id="seatGlow" x="-80%" y="-80%" width="260%" height="260%"><feGaussianBlur stdDeviation="4" result="b"/><feMerge><feMergeNode in="b"/><feMergeNode in="SourceGraphic"/></feMerge></filter>`
  mk('rect', { x: 0, y: 0, width: 1000, height: 410, fill: 'url(#stageSpill)' }, svg)
  const beams = mk('g', { transform: `translate(${SX} ${SY + FRONT - 4})`, 'aria-hidden': 'true' }, svg)
  mk('polygon', { class: 'beam', points: '0,0 -44,340 44,340', fill: 'url(#beamL)' }, beams)
  mk('polygon', { class: 'beam b2', points: '0,0 -58,340 58,340', fill: 'url(#beamV)' }, beams)

  // the stage, its lit front edge, and the ring labels that carry the prices
  const [slx, sly] = pt(FRONT, -STAGE), [srx, sry] = pt(FRONT, STAGE)
  mk('path', { d: `M${f1(slx)} 12H${f1(srx)}V${f1(sry)}A${FRONT} ${FRONT} 0 0 1 ${f1(slx)} ${f1(sly)}Z`, fill: '#EEF1DC' }, svg)
  mk('path', { d: arc(FRONT, -STAGE, STAGE), fill: 'none', stroke: LIME, 'stroke-width': 3 }, svg)
  mk('text', { class: 'stage-t', x: SX, y: SY + FRONT - 26 }, svg).textContent = 'STAGE'
  const ringLines: [SVGTextElement, number, number, SVGPathElement][] = []
  TIERS.forEach(([name, price, r0, r1, rad], i) => {
    const a = SPAN + .06
    mk('path', { id: 'ring' + i, d: arc(rad, -a, a), fill: 'none' }, defs)
    const t = mk<SVGTextElement>('text', { class: 'tierl' }, svg), tp = mk('textPath', { href: '#ring' + i, startOffset: '50%', 'text-anchor': 'middle' }, t)
    tp.textContent = `${name.toUpperCase()} ${ROWS[r0]}–${ROWS[r1 - 1]}  `
    mk('tspan', {}, tp).textContent = inr(price)
    ringLines.push([t, rad, a, mk<SVGPathElement>('path', { class: 'tierline' }, svg)])
  })
  function ringGaps() {
    ringLines.forEach(([t, rad, a, line]) => {
      const h = (t.getComputedTextLength() / 2 + 14) / rad
      line.setAttribute('d', arc(rad - 4, -a, -h) + arc(rad - 4, h, a))
    })
  }
  RAD.forEach((r, ri) => [-1, 1].forEach(side => {
    const [x, y] = pt(r, side * (SPAN + 20 / r))
    mk('text', { class: 'rowl', x: f1(x), y: f1(y), 'aria-hidden': 'true' }, svg).textContent = ROWS[ri]
  }))

  const deg = (a: number) => a * 180 / Math.PI
  seats.forEach(s => {
    if (!s.open) { mk('circle', { class: 'gone', cx: f1(s.x!), cy: f1(s.y!), r: 2.6 }, svg); return }
    const g = mk<SVGGElement>('g', {
      class: `seat t${s.tier}`, transform: `translate(${f1(s.x!)} ${f1(s.y!)}) rotate(${f1(-deg(s.a))})`, role: 'button',
      'aria-label': `Row ${ROWS[s.r]} seat ${s.n}, ${s.block.toLowerCase()} block, ${inr(TIERS[s.tier!][1])}`,
    }, svg)
    const pop = mk('g', { class: 'pop' }, g)
    mk('path', { class: 'body', d: SEAT }, pop)
    mk('circle', { r: 15, fill: 'transparent' }, g)
    g.addEventListener('click', () => { kf = s; choose(s) })
    g.addEventListener('pointerenter', () => peek(s))
    g.addEventListener('pointerleave', () => peek(null))
    s.el = g
  })

  // overlay: the sightline to the stage and a chip that names the seat under the pointer
  const top = mk('g', { 'aria-hidden': 'true', 'pointer-events': 'none' }, svg)
  const ghost = mk('path', { class: 'sight ghost', display: 'none' }, top)
  const sight = mk('path', { class: 'sight' }, top)
  const dist = mk('text', { class: 'dist' }, top)
  const chip = mk('g', { class: 'chip', display: 'none' }, top)
  const chipR = mk('rect', { height: 22, rx: 11, y: -11 }, chip), chipT = mk<SVGTextElement>('text', {}, chip)
  const eye = [SX, SY + FRONT]
  const metres = (x: number, y: number) => Math.round(Math.hypot(x - eye[0], y - eye[1]) * .075 + 2)

  let groupN = 2, mine: Seat[] = [], kf: Seat | null = null
  const say = (t: string, warn?: boolean) => { $('msg').textContent = t; $('msg').classList.toggle('warn', !!warn) }
  const words = ['', '', 'Both of you', 'All three of you', 'All four of you', 'All five of you']
  function runIn(s: Seat) {
    const row = rows[s.r]; let a = row.indexOf(s), b = a
    while (a > 0 && row[a - 1].open && row[a - 1].block === s.block) a--
    while (b < row.length - 1 && row[b + 1].open && row[b + 1].block === s.block) b++
    return row.slice(a, b + 1)
  }
  function choose(s: Seat) {
    const run = runIn(s), i = run.indexOf(s)
    if (run.length < groupN) {
      set(run)
      return say(`Only ${run.length} together here. Try another row to keep all ${groupN} side by side.`, true)
    }
    const st = Math.min(Math.max(0, i - Math.floor((groupN - 1) / 2)), run.length - groupN)
    set(run.slice(st, st + groupN))
    say(groupN > 1 ? `${words[groupN]}, side by side in row ${ROWS[s.r]}.` : `Row ${ROWS[s.r]}, seat ${s.n}. Good choice.`)
  }
  function best() {
    let pick: Seat[] | null = null, score = Infinity
    rows.forEach(row => {
      for (let i = 0; i + groupN <= row.length; i++) {
        const w = row.slice(i, i + groupN)
        if (!w.every(s => s.open && s.block === w[0].block)) continue
        const sc = w[0].r * .55 + Math.abs(w.reduce((t, s) => t + s.a, 0) / groupN) * 4
        if (sc < score) { score = sc; pick = w }
      }
    })
    if (!pick) return say(`No ${groupN} seats left together. Try a smaller group.`, true)
    const p: Seat[] = pick
    kf = p[Math.floor(groupN / 2)]; set(p)
    say(`Our pick: the closest ${groupN > 1 ? groupN + ' together' : 'seat'} to the centre line, row ${ROWS[p[0].r]}.`)
  }
  const tick = (id: string, text: string) => { const e = $(id); if (e.textContent === text) return; e.innerHTML = `<span class="tick">${text}</span>` }
  function set(pick: Seat[]) {
    mine.forEach(s => s.el!.classList.remove('mine'))
    pick.forEach(s => { s.el!.classList.add('mine'); s.el!.parentNode!.appendChild(s.el!) })
    svg.appendChild(top)
    mine = pick; if (!kf || !pick.includes(kf)) kf = pick[Math.floor(pick.length / 2)]; focusMark()
    const n = pick.length, s0 = pick[0], [name, price] = TIERS[s0.tier!]
    const ma = pick.reduce((t, s) => t + s.a, 0) / n, mx = pick.reduce((t, s) => t + s.x!, 0) / n, my = pick.reduce((t, s) => t + s.y!, 0) / n
    const d = Math.abs(deg(ma)), side = ma < 0 ? 'left' : 'right', m = metres(mx, my)
    tick('tkAdmit', `Admit ${n} · Sat 17 Oct · 7:30 pm`)
    tick('tkRow', ROWS[s0.r])
    tick('tkSeats', n > 1 ? `${s0.n}–${pick[n - 1].n}` : String(s0.n))
    tick('tkBlock', s0.block)
    tick('tkView', d < 9 ? 'Dead centre' : d < 26 ? `Slightly ${side}` : `From the ${side}`)
    tick('tkDist', `${m} m`)
    tick('tkEach', `${n} × ${inr(price)} · ${name}`)
    tick('tkTotal', inr(price * n))
    $('gaugeN').style.transform = `rotate(${f1(ma / SPAN * 72)}deg)`
    sight.setAttribute('d', `M${f1(mx)} ${f1(my)}L${eye[0]} ${eye[1]}`)
    dist.setAttribute('x', String(f1((mx + eye[0]) / 2 + (mx < SX ? -18 : 18)))); dist.setAttribute('y', String(f1((my + eye[1]) / 2)))
    dist.textContent = `${m} M`
  }
  function peek(s: Seat | null) {
    seats.forEach(x => x.el?.classList.toggle('hv', x === s))
    if (!s) { chip.setAttribute('display', 'none'); ghost.setAttribute('display', 'none'); return }
    chipT.textContent = `${ROWS[s.r]}${s.n} · ${inr(TIERS[s.tier!][1])}`
    const w = chipT.getComputedTextLength() + 20
    chipR.setAttribute('x', String(-w / 2)); chipR.setAttribute('width', String(w))
    chip.setAttribute('transform', `translate(${f1(s.x!)} ${f1(s.y! - 26)})`); chip.removeAttribute('display')
    if (!mine.includes(s)) { ghost.setAttribute('d', `M${f1(s.x!)} ${f1(s.y!)}L${eye[0]} ${eye[1]}`); ghost.removeAttribute('display') }
    else ghost.setAttribute('display', 'none')
  }

  // keyboard: one tab stop, arrows walk the open seats, Enter picks
  function focusMark() { seats.forEach(s => s.el?.classList.toggle('kf', s === kf)) }
  function walk(dx: number, dy: number) {
    const cur = kf!
    if (dx) {
      const row = rows[cur.r]; let i = row.indexOf(cur) + dx
      while (row[i] && !row[i].open) i += dx
      if (row[i]) kf = row[i]
    } else {
      for (let r = cur.r + dy; r >= 0 && r < rows.length; r += dy) {
        const open = rows[r].filter(s => s.open); if (!open.length) continue
        kf = open.reduce((b, s) => Math.abs(s.a - cur.a) < Math.abs(b.a - cur.a) ? s : b); break
      }
    }
    focusMark(); peek(kf)
  }
  svg.addEventListener('keydown', e => {
    const k = ({ ArrowLeft: [-1, 0], ArrowRight: [1, 0], ArrowUp: [0, -1], ArrowDown: [0, 1] } as Record<string, [number, number]>)[e.key]
    if (k) { e.preventDefault(); walk(...k) }
    else if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); choose(kf!) }
  })
  svg.addEventListener('focus', () => { if (svg.matches(':focus-visible')) peek(kf) })
  svg.addEventListener('blur', () => peek(null))

  // party size: seat pips that fill up to the pointer
  const pips = $('pips')
  const paint = (n: number) => [...pips.children].forEach((b, i) => b.classList.toggle('on', i < n))
  pips.innerHTML = [1, 2, 3, 4, 5].map(n => `<button type="button" data-n="${n}" aria-pressed="${n === 2}" aria-label="${n} ${n > 1 ? 'people' : 'person'}"><svg viewBox="-12 -11 24 22" aria-hidden="true"><path d="${SEAT}" fill="currentColor"/></svg></button>`).join('')
  pips.querySelectorAll<HTMLButtonElement>('button').forEach(b => {
    const n = +b.dataset.n!
    b.addEventListener('pointerenter', () => paint(n))
    b.addEventListener('pointerleave', () => paint(groupN))
    b.addEventListener('click', () => {
      groupN = n; paint(n)
      pips.querySelectorAll('button').forEach(x => x.setAttribute('aria-pressed', String(x === b)))
      $('pn').textContent = String(n)
      $('bestTxt').textContent = n > 1 ? `Best ${n} together` : 'Best single seat'
      choose(kf || mine[0])
    })
  })
  paint(groupN)
  $('best').addEventListener('click', best, { signal })
  $('tkLeft').textContent = `${seats.filter(s => s.open).length} of ${seats.length} left`
  const centrePlan = () => { plan.scrollLeft = (plan.scrollWidth - plan.clientWidth) / 2 }
  ringGaps(); best(); centrePlan()
  document.fonts.ready.then(() => { if (alive) ringGaps() })

  // run a frame loop only while `el` is on screen; returns a one-off redraw
  function loop(el: Element, draw: (now: number) => void) {
    let on = false, raf = 0
    const step = (now: number) => { draw(now); raf = on && !reduce ? frame(step) : 0 }
    const io = new IntersectionObserver(([e]) => { on = e.isIntersecting; if (on && !raf) raf = frame(step) })
    io.observe(el); observers.push(io)
    return () => { if (!raf) frame(draw) }
  }

  /* ---------- Gig guide ---------- */
  function renderGigs(cat: string) {
    const list = GIGS.filter(e => cat === 'ALL' || e.cat === cat).sort((a, b) => a.when.localeCompare(b.when))
    $('gigs').innerHTML = list.length ? list.map(e => {
      const sold = 1 - e.left / e.cap, hot = e.left < 50
      return `<a class="gig${hot ? ' hot' : ''}" href="#view" data-poster="${e.poster}">
        <div class="date"><b class="num">${part(e.when, { day: 'numeric' })}</b><span class="label">${part(e.when, { month: 'short' })}<br>${part(e.when, { weekday: 'short' })}</span></div>
        <div class="t"><img class="thumb" src="${ASSETS}${e.poster}.webp" alt="" loading="lazy"><div><h3>${e.title}</h3><div class="sub">${e.venue}, ${e.city} · ${catName[e.cat]} · ${part(e.when, { hour: 'numeric', minute: '2-digit' })}</div></div></div>
        <div class="avail"><span class="left label">${hot ? `Only ${e.left} left` : `${e.left} seats left`}</span><div class="bar"><i style="width:${Math.round(sold * 100)}%"></i></div></div>
        <div class="from num"><small>From</small>${inr(e.from)}</div>
        <span class="go">Seats <i aria-hidden="true">→</i></span>
      </a>`
    }).join('') : '<p class="empty">Nothing here yet. Try another type.</p>'
  }
  const tabs = document.querySelectorAll<HTMLButtonElement>('.tabs button')
  tabs.forEach(b => b.addEventListener('click', () => {
    tabs.forEach(x => x.setAttribute('aria-pressed', String(x === b))); renderGigs(b.dataset.cat!)
  }, { signal }))
  renderGigs('ALL')

  /* ---------- Venue editor ---------- */
  const ed = $<HTMLCanvasElement>('editor')
  let E: { g: CanvasRenderingContext2D, W: number, H: number }
  function buildEditor() { const [g, W, H] = fit(ed); E = { g, W, H } }
  function drawEditor(now: number) {
    const { g, W, H } = E, t = reduce ? 9 : (now / 1000) % 9
    g.fillStyle = '#0D0F07'; g.fillRect(0, 0, W, H)
    g.strokeStyle = 'rgba(238,241,220,.05)'; g.lineWidth = 1
    for (let x = 0; x < W; x += 20) { g.beginPath(); g.moveTo(x + .5, 0); g.lineTo(x + .5, H); g.stroke() }
    for (let y = 0; y < H; y += 20) { g.beginPath(); g.moveTo(0, y + .5); g.lineTo(W, y + .5); g.stroke() }
    const stW = W * .5, sy0 = Math.max(24, (H - 9 * Math.min(18, (W - 120) / 18) * 1.3) / 2 - 40); g.fillStyle = '#23281A'; g.fillRect((W - stW) / 2, sy0, stW, 34)
    g.fillStyle = 'rgba(238,241,220,.6)'; g.font = '500 11px "IBM Plex Mono", monospace'; g.textAlign = 'center'; g.fillText('S T A G E', W / 2, sy0 + 21)
    const rows = 9, perRow = 16, sz = Math.min(18, (W - 120) / (perRow + 2)), gx = (W - (perRow * sz * 1.25 + sz)) / 2, gy = Math.max(84, (H - rows * sz * 1.3) / 2 + 20)
    const phase = t < 4 ? 0 : t < 6.5 ? 1 : 2
    document.querySelectorAll('.ed-bar .tools span').forEach((s, i) => s.classList.toggle('on', i === phase))
    const drawn = phase === 0 ? t / 4 * rows * perRow : rows * perRow
    const tint = ['rgba(205,235,58,.9)', 'rgba(140,116,255,.85)', 'rgba(238,241,220,.55)']
    let cursor: [number, number] | null = null
    for (let i = 0; i < rows; i++) for (let k = 0; k < perRow; k++) {
      const idx = i * perRow + k; if (idx > drawn) continue
      const x = gx + k * sz * 1.25 + (k >= perRow / 2 ? sz : 0), y = gy + i * sz * 1.3
      const band = i < 3 ? 0 : i < 6 ? 1 : 2
      seatShape(g, x + sz / 2, y + sz / 2, sz * .9, sz * .8, 0)
      if (phase === 0) { g.strokeStyle = 'rgba(238,241,220,.7)'; g.lineWidth = 1.2; g.stroke() }
      else { g.fillStyle = phase === 2 && ((idx * 37) % 11 < 3) ? '#2A2F1F' : tint[band]; g.fill() }
      if (idx === Math.floor(drawn)) cursor = [x + sz, y + sz]
    }
    if (phase >= 1) {
      ['Front ₹6,500', 'Middle ₹4,500', 'Back ₹2,500'].forEach((s, i) => {
        const y = gy + i * 3 * sz * 1.3 + sz * 1.9, x = W - 36
        g.textAlign = 'right'; g.fillStyle = tint[i]; g.font = '500 11px "IBM Plex Mono", monospace'; g.fillText(s.toUpperCase(), x, y)
      })
    }
    if (phase === 2) {
      const lx = 20, ly = H - 44; g.fillStyle = LIME; g.beginPath(); g.roundRect(lx, ly, 150, 28, 4); g.fill()
      g.fillStyle = '#161A0C'; g.textAlign = 'left'; g.fillText('● ON SALE · 9 OCT', lx + 12, ly + 18)
    }
    if (cursor && phase === 0) {
      const [x, y] = cursor; g.fillStyle = '#fff'; g.beginPath(); g.moveTo(x, y); g.lineTo(x, y + 16); g.lineTo(x + 4, y + 12); g.lineTo(x + 8, y + 19); g.lineTo(x + 10, y + 18); g.lineTo(x + 7, y + 11); g.lineTo(x + 12, y + 11); g.closePath(); g.fill()
    }
    if (!reduce) frame(drawEditor)
  }

  /* ---------- Hero scene: stage photo + crowd layer ---------- */
  const scene = $('scene')
  const ptr = { x: 0, y: 0 }, ptrT = { x: 0, y: 0 }
  addEventListener('pointermove', e => { ptrT.x = e.clientX / innerWidth * 2 - 1; ptrT.y = e.clientY / innerHeight * 2 - 1 }, { passive: true, signal })
  let heroL: Layer | null = null, heroKick = () => {}
  async function heroGL() {
    const [bg, fg] = await Promise.all([load(ASSETS + 'hero-stage.webp'), load(ASSETS + 'crowd.webp')])
    if (!alive) return
    const L = glLayer($<HTMLCanvasElement>('heroGL'), HERO_FS)
    L.tex(bg, 0, 'uBg'); L.tex(fg, 1, 'uFg'); L.size(); heroL = L
    heroKick = loop(scene, now => {
      ptr.x += (ptrT.x - ptr.x) * .05; ptr.y += (ptrT.y - ptr.y) * .05
      L.set('uT', reduce ? 2 : now / 1000); L.set('uPtr', ptr.x, -ptr.y)
      L.set('uScroll', Math.min(1, scrollY / scene.offsetHeight)); L.draw()
    })
  }

  /* ---------- Gig posters: follow the cursor, ripple with its speed, halftone into the next ---------- */
  async function posterGL() {
    if (reduce || !matchMedia('(hover:hover) and (pointer:fine)').matches) return
    const cv = $<HTMLCanvasElement>('posterGL'), names = [...new Set(GIGS.map(g => g.poster))]
    const imgs = await Promise.all(names.map(p => load(`${ASSETS}${p}.webp`)))
    if (!alive) return
    const L = glLayer(cv, POSTER_FS), unit: Record<string, number> = {}
    names.forEach((p, i) => { unit[p] = i; L.tex(imgs[i], i) })
    L.size()
    let show = 0, want = 0, x = 0, y = 0, tx = 0, ty = 0, a: string | null = null, b: string | null = null, mix = 1, last = 0
    addEventListener('pointermove', e => { tx = e.clientX; ty = e.clientY }, { passive: true, signal })
    const list = $('gigs')
    list.addEventListener('pointerover', e => {
      const g = (e.target as Element).closest<HTMLElement>('.gig'); if (!g) return
      const p = g.dataset.poster!
      if (p !== b) { a = show > .05 ? b : p; b = p; mix = a === b ? 1 : 0; L.int('uA', unit[a!]); L.int('uB', unit[b]) }
      want = 1
    }, { signal })
    list.addEventListener('pointerleave', () => want = 0, { signal })
    addEventListener('scroll', () => { if (!list.matches(':hover')) { want = show = 0; cv.style.opacity = '0' } }, { passive: true, signal })
    loop($('guide'), now => {
      const dt = Math.min(.05, (now - last) / 1000); last = now
      if (show < .01 && want) { x = tx; y = ty }
      x += (tx - x) * .16; y += (ty - y) * .16
      show += (want - show) * .18; mix = Math.min(1, mix + dt * 1.8)
      const vx = Math.max(-1, Math.min(1, (tx - x) / 60)), vy = Math.max(-1, Math.min(1, (ty - y) / 60))
      cv.style.opacity = show.toFixed(3)
      cv.style.transform = `translate3d(${x + 36}px,${y - 158}px,0) rotate(${vx * 9}deg) scale(${.82 + .18 * show})`
      if (show < .01) return
      L.set('uVel', vx, -vy); L.set('uMix', mix); L.set('uT', now / 1000); L.draw()
    })
  }

  /* ---------- Final ticket: tilts toward the pointer, sways when left alone ---------- */
  const tk = $('tk'), fin = tk.closest('.final')!
  const tilt = { x: 0, y: 0 }
  let aim: { x: number, y: number } | null = null
  fin.addEventListener('pointermove', e => { const r = tk.getBoundingClientRect(); aim = { x: ((e as PointerEvent).clientX - r.left) / r.width - .5, y: ((e as PointerEvent).clientY - r.top) / r.height - .5 } }, { signal })
  fin.addEventListener('pointerleave', () => aim = null, { signal })
  if (!reduce) loop(fin, now => {
    const t = now / 1000, g = aim ?? { x: Math.sin(t * .7) * .3, y: Math.cos(t * .9) * .22 }
    const cl = (q: number) => Math.max(-.8, Math.min(.8, q))
    tilt.x += (cl(g.x) - tilt.x) * .08; tilt.y += (cl(g.y) - tilt.y) * .08
    tk.style.setProperty('--ry', (tilt.x * 26).toFixed(2) + 'deg'); tk.style.setProperty('--rx', (-tilt.y * 20).toFixed(2) + 'deg')
    tk.style.setProperty('--mx', (50 + tilt.x * 90).toFixed(1) + '%'); tk.style.setProperty('--my', (50 + tilt.y * 90).toFixed(1) + '%')
  })

  function boot() {
    if (!alive) return
    buildEditor()
    drawEditor(performance.now())
    // a shader that fails (no WebGL2, or images blocked) leaves the CSS photo underneath
    heroGL().catch(e => console.warn('[landing] hero', e))
    posterGL().catch(e => console.warn('[landing] poster', e))
  }
  document.fonts.ready.then(boot)
  let rt = 0
  addEventListener('resize', () => {
    clearTimeout(rt); timers.delete(rt)
    rt = window.setTimeout(() => {
      buildEditor(); heroL?.size(); heroKick(); centrePlan()
      if (reduce) drawEditor(performance.now())
    }, 120)
    timers.add(rt)
  }, { signal })

  return () => {
    alive = false
    ac.abort()
    observers.forEach(io => io.disconnect())
    timers.forEach(t => clearTimeout(t))
    plan.replaceChildren()
  }
}

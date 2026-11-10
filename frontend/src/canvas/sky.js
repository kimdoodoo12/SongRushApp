// #sky, the per-mood background scene: the mockup's part, initSky, firework
// and drawSky (docs/design/108-ui-design-reference.html, D-124), with the
// same numbers, colours and counts. The mockup's globals are parameters:
// the state object (state.js), the 2d context, mood, the frame time t (its
// performance.now()), and opts.playing (its phase==='play'), opts.reduce
// (its reduce) and opts.focus (the deck's box, its deck.getBoundingClientRect()).
import { rng } from './state.js'

const DENSITY = { rain: 1.1, sakura: 0.3, sunset: 0.3, neon: 0.15, chalk: 0, pixel: 0.35, matsuri: 0, manga: 0 }
const FIREWORK_COLOURS = ['#ff5a4e', '#ffc94d', '#6fd3ff', '#ff9ec4', '#a8f07a']
const MOON = [
  [1, 0], [2, 0], [3, 0], [0, 1], [1, 1], [2, 1], [3, 1], [4, 1], [0, 2], [1, 2], [2, 2],
  [3, 2], [4, 2], [0, 3], [1, 3], [2, 3], [3, 3], [4, 3], [1, 4], [2, 4], [3, 4],
]

const pick = (a) => a[Math.floor(Math.random() * a.length)]

// One particle; W and H are the sky canvas's size.
function part(mood, dpr, W, H, any) {
  const R = Math.random
  const p = { x: R() * W, y: any ? R() * H : -30 * dpr, ph: R() * 6.28 }
  if (mood === 'rain') Object.assign(p, { len: (12 + R() * 16) * dpr, vy: (9 + R() * 7) * dpr })
  else if (mood === 'sakura') Object.assign(p, { s: (4 + R() * 6) * dpr, vy: (0.3 + R() * 0.6) * dpr, rot: R() * 6, vr: (R() - 0.5) * 0.03 })
  else if (mood === 'sunset') Object.assign(p, { y: any ? R() * H : H + 10 * dpr, s: (1.2 + R() * 2) * dpr, vy: -(0.15 + R() * 0.35) * dpr })
  else if (mood === 'chalk') Object.assign(p, { s: (1 + R() * 1.6) * dpr, vy: -(0.05 + R() * 0.15) * dpr, vx: (R() - 0.5) * 0.25 * dpr })
  else if (mood === 'pixel') Object.assign(p, { x: R() * W, y: R() * H * 0.75, s: (R() < 0.3 ? 3 : 2) * 1.5 * dpr, vx: -(0.3 + R() * 1.2) * dpr })
  else Object.assign(p, { s: (1 + R() * 1.5) * dpr, vy: (0.2 + R() * 0.4) * dpr })
  return p
}

// The mockup's initSky, without its reduced-motion still frame (the caller
// draws it).
export function initSky(state, canvas, mood, dpr) {
  state.mood = mood
  state.dpr = dpr
  const W = innerWidth * dpr
  const H = innerHeight * dpr
  canvas.width = W
  canvas.height = H
  const area = (innerWidth * innerHeight) / 1e4
  const r = rng(5)
  const cw = canvas.width
  const ch = canvas.height
  state.parts = Array.from({ length: Math.min(220, Math.round(area * DENSITY[mood])) }, () => part(mood, dpr, cw, ch, true))
  const scene = {}
  state.scene = scene
  if (mood === 'sunset') {
    const b = []
    let x = 0
    while (x < W) {
      const w = (30 + r() * 70) * dpr
      const h = (40 + r() * 150) * dpr
      const win = []
      for (let k = 0; k < 6; k++) if (r() < 0.5) win.push([x + r() * (w - 8 * dpr), H - h + r() * (h - 10 * dpr)])
      b.push({ x, w, h, win })
      x += w + 2 * dpr
    }
    scene.b = b
  }
  if (mood === 'rain') {
    scene.bk = Array.from({ length: 28 }, () => ({
      x: r() * W,
      y: H * (0.6 + r() * 0.4),
      s: (20 + r() * 60) * dpr,
      c: r() < 0.5 ? '255,190,120' : '140,200,255',
      a: 0.06 + r() * 0.1,
    }))
  }
  if (mood === 'chalk') {
    scene.sm = Array.from({ length: 16 }, () => ({
      x: r() * W,
      y: r() * H,
      rx: (80 + r() * 220) * dpr,
      ry: (30 + r() * 80) * dpr,
      a: 0.025 + r() * 0.04,
      rot: r() * 0.6 - 0.3,
    }))
    scene.dd = [
      ['♪ 오늘의 곡은?', 0.36, 0.17, -0.06],
      ['정답은 채팅으로!', 0.52, 0.955, 0.03],
      ['숙제: OP 10곡 듣기', 0.02, 0.6, 0.04],
      ['☆', 0.6, 0.1, 0],
      ['♩♪ ♫', 0.28, 0.965, -0.05],
    ]
  }
  if (mood === 'pixel') {
    const mt = []
    let h = 0.12
    for (let x = 0; x < W; x += 8 * dpr) {
      h = Math.max(0.05, Math.min(0.3, h + (r() - 0.5) * 0.04))
      mt.push(h)
    }
    scene.mt = mt
  }
  if (mood === 'matsuri') {
    scene.fw = []
    scene.next = 0
  }
  if (mood === 'manga') {
    const o = document.createElement('canvas')
    o.width = W
    o.height = H
    const x = o.getContext('2d')
    // Without a 2d context (jsdom) there is no tone.
    if (x !== null && x !== undefined) {
      const st = 11 * dpr
      x.fillStyle = '#111'
      for (let yy = 0; yy < H; yy += st) {
        for (let xx = 0; xx < W; xx += st) {
          const d1 = Math.hypot(xx, H - yy) / (Math.min(W, H) * 0.75)
          const d2 = Math.hypot(W - xx, yy) / (Math.min(W, H) * 0.6)
          const k = Math.max(0, 1 - d1, 1 - d2)
          if (k > 0) {
            x.beginPath()
            x.arc(xx + ((yy / st) % 2 ? st / 2 : 0), yy, st * 0.42 * k, 0, 7)
            x.fill()
          }
        }
      }
      scene.tone = o
    }
  }
}

// The mockup's firework: sparks into the matsuri scene at (x, y), in sky
// canvas pixels; nothing in any other mood.
export function fireworkAt(state, x, y, big) {
  if (state.mood !== 'matsuri' || !Array.isArray(state.scene.fw)) return
  const dpr = state.dpr
  const col = pick(FIREWORK_COLOURS)
  const n = big ? 90 : 46
  for (let i = 0; i < n; i++) {
    const a = Math.random() * 6.283
    const v = (big ? 3.2 : 2.2) * (0.4 + Math.random() * 0.6) * dpr
    state.scene.fw.push({ x, y, vx: Math.cos(a) * v, vy: Math.sin(a) * v, life: 1, col })
  }
}

// The mockup's drawSky(move).
export function drawSky(state, sc, mood, t, move, opts = {}) {
  if (sc === null || sc === undefined) return
  const { playing = false, reduce = false, focus = null } = opts
  const dpr = state.dpr
  const scene = state.scene
  const W = sc.canvas.width
  const H = sc.canvas.height
  const sp = playing ? 1.6 : 1
  if (mood === 'sunset') {
    const g = sc.createLinearGradient(0, 0, 0, H)
    g.addColorStop(0, '#1c0f1a')
    g.addColorStop(0.55, '#4a1d33')
    g.addColorStop(1, '#b8513a')
    sc.fillStyle = g
    sc.fillRect(0, 0, W, H)
    const sx = W * 0.2
    const sy = H * 0.9
    const sr = Math.min(W, H) * 0.14
    const gl = sc.createRadialGradient(sx, sy, sr * 0.5, sx, sy, sr * 3)
    gl.addColorStop(0, 'rgba(255,170,100,.45)')
    gl.addColorStop(1, 'rgba(255,170,100,0)')
    sc.fillStyle = gl
    sc.fillRect(0, 0, W, H)
    sc.fillStyle = '#ffb070'
    sc.beginPath()
    sc.arc(sx, sy, sr, 0, 7)
    sc.fill()
    for (const b of scene.b || []) {
      sc.fillStyle = '#150a12'
      sc.fillRect(b.x, H - b.h, b.w, b.h)
      sc.fillStyle = 'rgba(255,207,112,.6)'
      for (const [wx, wy] of b.win) sc.fillRect(wx, wy, 4 * dpr, 5 * dpr)
    }
  } else if (mood === 'rain') {
    const g = sc.createLinearGradient(0, 0, 0, H)
    g.addColorStop(0, '#0b131d')
    g.addColorStop(1, '#16263a')
    sc.fillStyle = g
    sc.fillRect(0, 0, W, H)
    for (const b of scene.bk || []) {
      const rg = sc.createRadialGradient(b.x, b.y, 0, b.x, b.y, b.s)
      rg.addColorStop(0, `rgba(${b.c},${b.a})`)
      rg.addColorStop(1, `rgba(${b.c},0)`)
      sc.fillStyle = rg
      sc.fillRect(b.x - b.s, b.y - b.s, b.s * 2, b.s * 2)
    }
  } else if (mood === 'sakura') {
    const g = sc.createLinearGradient(0, 0, 0, H)
    g.addColorStop(0, '#161430')
    g.addColorStop(1, '#2a2052')
    sc.fillStyle = g
    sc.fillRect(0, 0, W, H)
    const mx = W * 0.86
    const my = H * 0.14
    const mr = Math.min(W, H) * 0.06
    const mg = sc.createRadialGradient(mx, my, mr, mx, my, mr * 4)
    mg.addColorStop(0, 'rgba(255,240,220,.25)')
    mg.addColorStop(1, 'rgba(255,240,220,0)')
    sc.fillStyle = mg
    sc.fillRect(0, 0, W, H)
    sc.fillStyle = '#fff5e0'
    sc.beginPath()
    sc.arc(mx, my, mr, 0, 7)
    sc.fill()
  } else if (mood === 'chalk') {
    sc.fillStyle = '#203b31'
    sc.fillRect(0, 0, W, H)
    for (const m of scene.sm || []) {
      sc.save()
      sc.translate(m.x, m.y)
      sc.rotate(m.rot)
      sc.fillStyle = `rgba(255,255,255,${m.a})`
      sc.beginPath()
      sc.ellipse(0, 0, m.rx, m.ry, 0, 0, 7)
      sc.fill()
      sc.restore()
    }
    sc.fillStyle = 'rgba(243,241,231,.1)'
    sc.font = `${34 * dpr}px "Nanum Pen Script",cursive`
    for (const [text, x, y, rot] of scene.dd || []) {
      sc.save()
      sc.translate(W * x, H * y)
      sc.rotate(rot)
      sc.fillText(text, 0, 0)
      sc.restore()
    }
    sc.fillStyle = '#6b4a2f'
    sc.fillRect(0, H - 16 * dpr, W, 16 * dpr)
    sc.fillStyle = '#4e3420'
    sc.fillRect(0, H - 16 * dpr, W, 3 * dpr)
    sc.fillStyle = '#f3f1e7'
    sc.fillRect(W * 0.7, H - 24 * dpr, 30 * dpr, 8 * dpr)
    sc.fillStyle = '#ffb3c1'
    sc.fillRect(W * 0.74, H - 23 * dpr, 22 * dpr, 7 * dpr)
  } else if (mood === 'pixel') {
    sc.fillStyle = '#0f0a24'
    sc.fillRect(0, 0, W, H)
    const u = 8 * dpr
    sc.fillStyle = '#fff1a8'
    const mx = Math.round((W * 0.8) / u) * u
    const my = Math.round((H * 0.16) / u) * u
    MOON.forEach(([i, j]) => sc.fillRect(mx + i * u, my + j * u, u, u))
    ;(scene.mt || []).forEach((h, i) => {
      const hh = Math.round((h * H) / u) * u
      sc.fillStyle = '#251a4d'
      sc.fillRect(i * u, H - hh, u, hh)
      sc.fillStyle = '#1a1238'
      const h2 = Math.round((h * H * 0.55) / u) * u
      sc.fillRect(i * u, H - h2, u, h2)
    })
  } else if (mood === 'matsuri') {
    const g = sc.createLinearGradient(0, 0, 0, H)
    g.addColorStop(0, '#0b1028')
    g.addColorStop(1, '#1d2550')
    sc.fillStyle = g
    sc.fillRect(0, 0, W, H)
    if (move && t > scene.next) {
      fireworkAt(state, W * (0.1 + Math.random() * 0.8), H * (0.15 + Math.random() * 0.3), false)
      scene.next = t + (playing ? 900 : 1800) + Math.random() * 1500
    }
    scene.fw = (scene.fw || []).filter((f) => f.life > 0)
    for (const f of scene.fw) {
      if (move) {
        f.x += f.vx
        f.y += f.vy
        f.vy += 0.035 * dpr
        f.vx *= 0.985
        f.life -= 0.012
      }
      sc.globalAlpha = Math.max(0, f.life)
      sc.fillStyle = f.col
      sc.fillRect(f.x, f.y, 2.2 * dpr, 2.2 * dpr)
    }
    sc.globalAlpha = 1
    const n = Math.ceil(W / (120 * dpr))
    const tt = t / 1000
    const ly = (i) => H * 0.03 + Math.sin(((i + 0.5) / n) * Math.PI) * 28 * dpr
    sc.strokeStyle = 'rgba(255,246,234,.35)'
    sc.lineWidth = 1.5 * dpr
    sc.beginPath()
    for (let i = 0; i <= n; i++) {
      const x = (i / n) * W
      const y = H * 0.03 + Math.sin((i / n) * Math.PI) * 28 * dpr
      if (i) sc.lineTo(x, y)
      else sc.moveTo(x, y)
    }
    sc.stroke()
    for (let i = 0; i < n; i++) {
      const x = ((i + 0.5) / n) * W
      const y = ly(i) + 6 * dpr
      const sw = reduce ? 0 : Math.sin(tt * 1.4 + i) * 0.08
      sc.save()
      sc.translate(x, y)
      sc.rotate(sw)
      const gl = sc.createRadialGradient(0, 26 * dpr, 4 * dpr, 0, 26 * dpr, 60 * dpr)
      gl.addColorStop(0, 'rgba(255,190,90,.35)')
      gl.addColorStop(1, 'rgba(255,190,90,0)')
      sc.fillStyle = gl
      sc.fillRect(-60 * dpr, -34 * dpr, 120 * dpr, 120 * dpr)
      sc.fillStyle = '#2a1a10'
      sc.fillRect(-7 * dpr, 0, 14 * dpr, 5 * dpr)
      sc.fillRect(-7 * dpr, 47 * dpr, 14 * dpr, 5 * dpr)
      sc.fillStyle = i % 3 === 1 ? '#fff1d6' : '#e0452f'
      sc.beginPath()
      sc.ellipse(0, 26 * dpr, 17 * dpr, 22 * dpr, 0, 0, 7)
      sc.fill()
      // The mockup's outline loop breaks after its first pass: one outline.
      sc.strokeStyle = 'rgba(0,0,0,.2)'
      sc.lineWidth = 1 * dpr
      sc.beginPath()
      sc.ellipse(0, 26 * dpr, 17 * dpr, 22 * dpr, 0, 0, 7)
      sc.stroke()
      for (let k = 1; k < 5; k++) {
        sc.beginPath()
        sc.moveTo(-16 * dpr, 26 * dpr - 22 * dpr + k * 8.8 * dpr)
        sc.lineTo(16 * dpr, 26 * dpr - 22 * dpr + k * 8.8 * dpr)
        sc.stroke()
      }
      sc.restore()
    }
  } else if (mood === 'manga') {
    sc.fillStyle = '#fbfbf8'
    sc.fillRect(0, 0, W, H)
    if (scene.tone) sc.drawImage(scene.tone, 0, 0)
    if (playing && !reduce && focus !== null) {
      const b = focus
      const cx = (b.left + b.width / 2) * dpr
      const cy = (b.top + b.height / 2) * dpr
      const r0 = b.width * 0.62 * dpr
      const L = Math.hypot(W, H)
      sc.strokeStyle = 'rgba(17,17,17,.12)'
      for (let i = 0; i < 70; i++) {
        const a = Math.random() * 6.283
        sc.lineWidth = (0.5 + Math.random() * 2.5) * dpr
        sc.beginPath()
        sc.moveTo(cx + Math.cos(a) * r0 * (1 + Math.random() * 0.4), cy + Math.sin(a) * r0 * (1 + Math.random() * 0.4))
        sc.lineTo(cx + Math.cos(a) * L, cy + Math.sin(a) * L)
        sc.stroke()
      }
    }
  } else {
    sc.fillStyle = '#07060f'
    sc.fillRect(0, 0, W, H)
    const hz = H * 0.6
    const hg = sc.createLinearGradient(0, hz - H * 0.15, 0, hz)
    hg.addColorStop(0, 'rgba(255,63,210,0)')
    hg.addColorStop(1, 'rgba(255,63,210,.25)')
    sc.fillStyle = hg
    sc.fillRect(0, hz - H * 0.15, W, H * 0.15)
    sc.strokeStyle = 'rgba(255,63,210,.35)'
    sc.lineWidth = 1 * dpr
    if (move) state.gridOff = (state.gridOff + (playing ? 0.012 : 0.005)) % 1
    for (let i = 0; i < 14; i++) {
      const f = (i + state.gridOff) / 14
      const y = hz + (H - hz) * f * f
      sc.beginPath()
      sc.moveTo(0, y)
      sc.lineTo(W, y)
      sc.stroke()
    }
    for (let i = -12; i <= 12; i++) {
      sc.beginPath()
      sc.moveTo(W / 2 + i * W * 0.02, hz)
      sc.lineTo(W / 2 + i * W * 0.14, H)
      sc.stroke()
    }
  }
  for (const p of state.parts) {
    p.ph += 0.02
    if (mood === 'rain') {
      if (move) {
        p.y += p.vy * sp
        p.x -= p.vy * 0.18
        if (p.y > H) {
          Object.assign(p, part(mood, dpr, W, H, false))
          p.x = Math.random() * W * 1.2
        }
      }
      sc.strokeStyle = 'rgba(214,236,255,.28)'
      sc.lineWidth = 1.2 * dpr
      sc.beginPath()
      sc.moveTo(p.x, p.y)
      sc.lineTo(p.x + p.len * 0.18, p.y - p.len)
      sc.stroke()
    } else if (mood === 'sakura') {
      if (move) {
        p.y += p.vy * sp
        p.x += Math.sin(p.ph * 0.6) * 0.5 * dpr
        p.rot += p.vr
        if (p.y > H + 20 * dpr) Object.assign(p, part(mood, dpr, W, H, false))
      }
      sc.save()
      sc.translate(p.x, p.y)
      sc.rotate(p.rot)
      sc.fillStyle = 'rgba(255,170,205,.6)'
      sc.beginPath()
      sc.ellipse(0, 0, p.s, p.s * 0.55, 0, 0, 6.283)
      sc.fill()
      sc.restore()
    } else if (mood === 'sunset') {
      if (move) {
        p.y += p.vy * sp
        p.x += Math.sin(p.ph * 0.5) * 0.4 * dpr
        if (p.y < -10 * dpr) Object.assign(p, part(mood, dpr, W, H, false))
      }
      const al = 0.35 + 0.45 * Math.abs(Math.sin(p.ph))
      sc.fillStyle = `rgba(255,207,112,${al * 0.25})`
      sc.beginPath()
      sc.arc(p.x, p.y, p.s * 4, 0, 6.283)
      sc.fill()
      sc.fillStyle = `rgba(255,220,150,${al})`
      sc.beginPath()
      sc.arc(p.x, p.y, p.s, 0, 6.283)
      sc.fill()
    } else if (mood === 'chalk') {
      if (move) {
        p.y += p.vy * sp
        p.x += p.vx
        if (p.y < -10) Object.assign(p, part(mood, dpr, W, H, false), { y: H })
      }
      sc.fillStyle = 'rgba(243,241,231,.3)'
      sc.fillRect(p.x, p.y, p.s, p.s)
    } else if (mood === 'pixel') {
      if (move) {
        p.x += p.vx * sp
        if (p.x < -10) p.x = W + 10
      }
      sc.fillStyle = p.s > 4 * dpr ? '#ffd23f' : '#a49ad6'
      sc.fillRect(Math.round(p.x / (2 * dpr)) * 2 * dpr, Math.round(p.y / (2 * dpr)) * 2 * dpr, p.s, p.s)
    } else {
      if (move) {
        p.y += p.vy * sp
        if (p.y > H * 0.6) Object.assign(p, part(mood, dpr, W, H, false), { y: 0 })
      }
      sc.fillStyle = `rgba(51,243,255,${0.3 + 0.3 * Math.sin(p.ph)})`
      sc.fillRect(p.x, p.y, p.s, p.s)
    }
  }
}

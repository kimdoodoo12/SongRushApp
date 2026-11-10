// #viz, the visualizer around the disc: the mockup's sizeViz, drawViz,
// chalkStaff with DOODLES and chalkPoly, and senbon, sakura's petal storm
// (docs/design/108-ui-design-reference.html, D-124), with the same numbers,
// colours, counts and timings. The mockup's globals are parameters: the
// state object (state.js), the 2d context, mood, the frame time t, and
// opts.live (its analyser&&phase==='play'; here a song is audible),
// opts.levels (its fd, the analyser's byte frequency data) and opts.reduce.
// Its song.bpm is a fixed 120: the real songs have no BPM data (spec item 1).
// Not ported: sakuraWreath, sakSites and sakFlower, dead code in the mockup.

const BPM = 120
// The mockup's MOODS[mood].viz.
const KIND = { sunset: 'bars', rain: 'wave', sakura: 'dots', neon: 'neon', chalk: 'chalk', pixel: 'pixel', matsuri: 'spark', manga: 'ink' }
// fd while nothing is live (never read then, as in the mockup).
const SILENT = new Uint8Array(128)

const pick = (a) => a[Math.floor(Math.random() * a.length)]
const css = (n) => getComputedStyle(document.documentElement).getPropertyValue(n).trim()

// Chalk doodles: lines in normalised coordinates (-1..1), drawn from the
// start up to a length, so they look written.
const DOODLES = (() => {
  const star = []
  for (let i = 0; i <= 10; i++) {
    const a = -Math.PI / 2 + (i * Math.PI) / 5
    const r = i % 2 ? 0.42 : 1
    star.push([Math.cos(a) * r, Math.sin(a) * r])
  }
  const heart = []
  for (let i = 0; i <= 40; i++) {
    const t = (i / 40) * 6.2832
    heart.push([
      (16 * Math.pow(Math.sin(t), 3)) / 17,
      -(13 * Math.cos(t) - 5 * Math.cos(2 * t) - 2 * Math.cos(3 * t) - Math.cos(4 * t)) / 17,
    ])
  }
  const spiral = []
  for (let i = 0; i <= 50; i++) {
    const t = (i / 50) * 12
    const r = t / 12
    spiral.push([Math.cos(t) * r, Math.sin(t) * r])
  }
  const note = [[[-0.35, 0.55], [-0.35, -0.8], [0.4, -0.55], [0.4, 0.35]]]
  return [
    [star],
    [heart],
    [spiral],
    [[[0, -1], [0, 0.35]], [[0, 0.75], [0, 0.85]]],
    [[[-1, 0], [1, 0]], [[0, -1], [0, 1]], [[-0.6, -0.6], [0.6, 0.6]], [[-0.6, 0.6], [0.6, -0.6]]],
    note,
    [[[-1, 0.2], [-0.4, -0.6], [0.2, 0.2], [0.8, -0.6], [1, -0.2]]],
  ]
})()

const CORNERS = [[0.11, 0.11], [0.89, 0.11], [0.11, 0.89], [0.89, 0.89], [0.2, 0.06], [0.8, 0.94], [0.06, 0.8], [0.94, 0.2]]

// The mockup's sizeViz.
export function sizeViz(canvas, dpr) {
  const b = canvas.getBoundingClientRect()
  canvas.width = b.width * dpr
  canvas.height = b.height * dpr
}

function chalkPoly(vc, polys, x, y, size, rot, frac, col, alpha, W) {
  let total = 0
  const segs = []
  for (const pl of polys) {
    for (let i = 1; i < pl.length; i++) {
      const l = Math.hypot(pl[i][0] - pl[i - 1][0], pl[i][1] - pl[i - 1][1])
      segs.push([pl[i - 1], pl[i], l])
      total += l
    }
  }
  let left = total * frac
  vc.save()
  vc.translate(x, y)
  vc.rotate(rot)
  vc.strokeStyle = col
  vc.globalAlpha = alpha
  vc.lineWidth = W * 0.006
  vc.lineCap = 'round'
  vc.lineJoin = 'round'
  for (const [a, b, l] of segs) {
    if (left <= 0) break
    const f = Math.min(1, left / l)
    vc.beginPath()
    vc.moveTo(a[0] * size, a[1] * size)
    vc.lineTo((a[0] + (b[0] - a[0]) * f) * size, (a[1] + (b[1] - a[1]) * f) * size)
    vc.stroke()
    left -= l
  }
  vc.restore()
  vc.globalAlpha = 1
}

// The chalk visualizer: a round chalk staff around the record. While live it
// turns, and the chalk at the top writes a note on each eighth note; notes
// are erased after about half a turn.
function chalkStaff(s, vc, fd, W, cx, cy, live, t, a1, a2, reduce) {
  const R0 = W * 0.385
  const gap = W * 0.018
  const ink = '243,241,231'
  const dt = s.chalkLast ? Math.min(0.05, (t - s.chalkLast) / 1000) : 0
  s.chalkLast = t
  let e = 0
  if (live) {
    for (let b = 2; b < 70; b++) e += fd[b]
    e = e / 68 / 255
  }
  s.chalkEnergy += (e - s.chalkEnergy) * 0.25
  let b = 0
  if (live) {
    for (let i = 1; i < 7; i++) b += fd[i]
    b /= 6 * 255
  }
  const kick = b - s.chalkB > 0.05 && b > 0.45
  s.chalkB += (b - s.chalkB) * 0.35
  s.chalkKick = Math.max(s.chalkKick * 0.88, kick ? 1 : 0)
  if (live && kick && t > s.chalkCool && !reduce && s.chalkDood.length < 2) {
    s.chalkCool = t + 1600
    const corner = CORNERS[Math.floor(Math.random() * 8)]
    s.chalkDood.push({
      x: W * corner[0],
      y: W * corner[1],
      d: DOODLES[Math.floor(Math.random() * DOODLES.length)],
      born: t,
      rot: (Math.random() - 0.5) * 0.8,
      size: W * (0.045 + Math.random() * 0.03),
      col: pick([a1, a2, '#9ad7ff', '#f3f1e7']),
    })
  }
  if (live) s.chalkBeat = (t - s.chalkSpawn) / (60000 / BPM / 2)
  if (!reduce) s.chalkRot += dt * (live ? 0.45 : 0.06)
  const TOP = -Math.PI / 2
  vc.lineCap = 'round'
  for (let k = 0; k < 5; k++) {
    const rr = (R0 + k * gap) * (1 + s.chalkKick * 0.006)
    const amp = W * (0.001 + s.chalkEnergy * 0.005)
    vc.strokeStyle = `rgba(${ink},${0.38 + s.chalkEnergy * 0.3})`
    vc.lineWidth = W * 0.0032
    vc.beginPath()
    for (let i = 0; i <= 140; i++) {
      const a = (i / 140) * 6.2832
      const w = Math.sin(i * 1.7 + k * 3.1 + (reduce ? 0 : t * 0.009)) * amp
      const x = cx + Math.cos(a) * (rr + w)
      const y = cy + Math.sin(a) * (rr + w)
      if (i) vc.lineTo(x, y)
      else vc.moveTo(x, y)
    }
    vc.stroke()
  }
  // The bar lines turn with the staff.
  vc.strokeStyle = `rgba(${ink},.55)`
  vc.lineWidth = W * 0.004
  for (let m = 0; m < 4; m++) {
    const a = TOP + s.chalkRot + (m * Math.PI) / 2
    vc.beginPath()
    vc.moveTo(cx + Math.cos(a) * (R0 - W * 0.004), cy + Math.sin(a) * (R0 - W * 0.004))
    vc.lineTo(cx + Math.cos(a) * (R0 + 4 * gap + W * 0.004), cy + Math.sin(a) * (R0 + 4 * gap + W * 0.004))
    vc.stroke()
  }
  if (live) {
    const eighth = 60000 / BPM / 2
    if (t - s.chalkSpawn >= eighth) {
      s.chalkSpawn = t
      let sum = 0
      let wsum = 0
      for (let bin = 2; bin < 70; bin++) {
        sum += fd[bin]
        wsum += fd[bin] * bin
      }
      if (sum / 68 > 30) {
        const c = wsum / sum
        s.cLo = Math.min(s.cLo + 0.15, c)
        s.cHi = Math.max(s.cHi - 0.15, c)
        const p = Math.max(0, Math.min(8, Math.round(((c - s.cLo) / Math.max(2, s.cHi - s.cLo)) * 8)))
        const col = s.chalkNotes.length % 3 === 2 ? a2 : a1
        s.chalkNotes.push({ a: -s.chalkRot, p, born: t, col, hop: s.chalkNotes.length % 2 })
        if (s.chalkEnergy > 0.42 && p <= 5) {
          s.chalkNotes.push({ a: -s.chalkRot, p: p + 2, born: t, col, hop: s.chalkNotes.length % 2, chord: 1 })
          if (s.chalkEnergy > 0.55 && p <= 3) s.chalkNotes.push({ a: -s.chalkRot, p: p + 4, born: t, col, hop: 0, chord: 1 })
        }
      }
    }
  }
  s.chalkNotes = s.chalkNotes.filter((n) => s.chalkRot + n.a < Math.PI * 1.25)
  for (const n of s.chalkNotes) {
    const trav = s.chalkRot + n.a
    const al = trav < Math.PI * 0.8 ? 0.95 : 0.95 * (1 - (trav - Math.PI * 0.8) / (Math.PI * 0.45))
    const age = (t - n.born) / 1000
    const a = TOP + trav
    const pop = age < 0.25 ? 1 + (1 - age / 0.25) * 0.8 : 1
    const beat = 1 + s.chalkEnergy * 0.35
    const hop =
      reduce || !live
        ? 0
        : Math.max(0, Math.sin((t / (60000 / BPM) + n.hop * 0.5) * Math.PI * 2)) * W * 0.003 * (0.4 + s.chalkEnergy * 1.2)
    const rr = R0 + (n.p * gap) / 2 + hop
    const hx = cx + Math.cos(a) * rr
    const hy = cy + Math.sin(a) * rr
    const sc = pop * beat
    vc.globalAlpha = Math.max(0, al)
    vc.fillStyle = n.col
    vc.strokeStyle = n.col
    vc.save()
    vc.translate(hx, hy)
    vc.rotate(a + Math.PI / 2 - 0.35)
    vc.beginPath()
    vc.ellipse(0, 0, W * 0.0105 * sc, W * 0.0075 * sc, 0, 0, 6.2832)
    vc.fill()
    vc.restore()
    const ox = Math.cos(a)
    const oy = Math.sin(a)
    const tx = -oy
    const ty = ox
    const sx = hx + tx * W * 0.009 * sc
    const sy = hy + ty * W * 0.009 * sc
    const len = W * 0.038 * Math.min(1, age / 0.18 + 0.2)
    vc.lineWidth = W * 0.0035
    vc.beginPath()
    vc.moveTo(sx, sy)
    vc.lineTo(sx + ox * len, sy + oy * len)
    vc.stroke()
    if (age > 0.15) {
      vc.beginPath()
      vc.moveTo(sx + ox * len, sy + oy * len)
      vc.quadraticCurveTo(
        sx + ox * len * 0.85 + tx * W * 0.02,
        sy + oy * len * 0.85 + ty * W * 0.02,
        sx + ox * len * 0.55 + tx * W * 0.014,
        sy + oy * len * 0.55 + ty * W * 0.014,
      )
      vc.stroke()
    }
  }
  vc.globalAlpha = 1
  s.chalkDood = s.chalkDood.filter((d) => t - d.born < 1900)
  for (const d of s.chalkDood) {
    const age = (t - d.born) / 1000
    const frac = Math.min(1, age / 0.6)
    const al = age < 1.3 ? 0.85 : 0.85 * (1 - (age - 1.3) / 0.6)
    chalkPoly(vc, d.d, d.x, d.y, d.size, d.rot, frac, d.col, Math.max(0, al) * 0.8, W)
  }
  // The chalk at the top that writes the notes (taps on the beat).
  const bob = live && !reduce ? Math.max(0, 1 - (t - s.chalkSpawn) / 140) * W * 0.012 : 0
  const rr = R0 + 4 * gap + W * 0.032 - bob
  vc.save()
  vc.translate(cx, cy - rr)
  vc.rotate(-0.5)
  vc.fillStyle = '#f3f1e7'
  vc.fillRect(-W * 0.02, -W * 0.007, W * 0.04, W * 0.014)
  vc.fillStyle = 'rgba(0,0,0,.14)'
  vc.fillRect(W * 0.009, -W * 0.007, W * 0.011, W * 0.014)
  vc.restore()
  if (bob > W * 0.006) {
    vc.fillStyle = `rgba(${ink},.5)`
    for (let k = 0; k < 4; k++) {
      vc.fillRect(cx + (Math.random() - 0.5) * W * 0.05, cy - R0 - 4 * gap - W * 0.005 + (Math.random() - 0.5) * W * 0.02, W * 0.004, W * 0.004)
    }
  }
}

// The sakura visualizer: petals swirling around the record, leaving trails;
// faster when louder, flung outwards on a kick.
function senbon(s, vc, fd, W, cx, cy, live, t, reduce) {
  const dt = s.senLast ? Math.min(0.05, (t - s.senLast) / 1000) : 0
  s.senLast = t
  let e = 0
  let b = 0
  if (live) {
    for (let i = 2; i < 70; i++) e += fd[i]
    e /= 68 * 255
    for (let i = 1; i < 7; i++) b += fd[i]
    b /= 6 * 255
  }
  s.senE += (e - s.senE) * 0.15
  const kick = Math.max(0, b - s.senB - 0.04)
  s.senB += (b - s.senB) * 0.3
  s.senW = Math.max(s.senW * 0.94, kick * 4)
  // The trail: the last frame is only partly erased.
  vc.globalCompositeOperation = 'destination-out'
  vc.fillStyle = `rgba(0,0,0,${reduce ? 1 : 0.55})`
  vc.fillRect(0, 0, W, W)
  vc.globalCompositeOperation = 'source-over'
  const spd = reduce ? 0 : live ? 0.42 + s.senE * 1.3 : 0.15
  const tt = t / 1000
  vc.globalCompositeOperation = 'lighter'
  for (const p of s.SENB) {
    p.a += dt * p.sp * spd
    if (p.out) {
      p.rb += dt * 0.025 * spd
      if (p.rb > 0.52) p.rb = 0.37
    }
    const arm = Math.sin(p.a * 2 - tt * 0.8 + p.rb * 30) * 0.012
    const r = W * (p.rb + arm + s.senW * 0.025 + s.senE * 0.012)
    const x = cx + Math.cos(p.a) * r
    const y = cy + Math.sin(p.a) * r
    const flip = Math.abs(Math.sin(tt * p.fl + p.ph))
    const sz = W * p.s * (1 + s.senE * 0.8)
    vc.setTransform(1, 0, 0, 1, x, y)
    vc.rotate(p.a + Math.PI / 2 + p.ph * 0.3)
    vc.fillStyle = `rgba(${p.c},${p.al * (live ? 1 : 0.7)})`
    vc.beginPath()
    vc.ellipse(0, 0, sz, sz * (0.18 + 0.42 * flip), 0, 0, 6.2832)
    vc.fill()
  }
  vc.setTransform(1, 0, 0, 1, 0, 0)
  vc.globalCompositeOperation = 'source-over'
}

// The mockup's drawViz(t).
export function drawViz(state, vc, mood, t, opts = {}) {
  if (vc === null || vc === undefined) return
  const { live = false, levels = null, reduce = false } = opts
  const fd = live && levels !== null ? levels : SILENT
  const W = vc.canvas.width
  const H = vc.canvas.height
  const cx = W / 2
  const cy = H / 2
  const R = W * 0.37
  const N = 72
  const kind = KIND[mood]
  if (kind !== 'dots') vc.clearRect(0, 0, W, H)
  const val = (i) => {
    const k = i < N / 2 ? i : N - 1 - i
    return live ? fd[2 + Math.floor(k * 1.6)] / 255 : reduce ? 0.08 : 0.08 + 0.05 * Math.sin(t / 600 + i * 0.5)
  }
  const a1 = css('--a1')
  const a2 = css('--a2')
  if (kind === 'chalk') {
    chalkStaff(state, vc, fd, W, cx, cy, live, t, a1, a2, reduce)
    return
  }
  if (kind === 'dots') {
    senbon(state, vc, fd, W, cx, cy, live, t, reduce)
    return
  }
  if (kind === 'wave') {
    vc.strokeStyle = a2
    vc.lineWidth = W * 0.004
    vc.globalAlpha = 0.8
    vc.beginPath()
    for (let i = 0; i <= N; i++) {
      const a = (i / N) * Math.PI * 2 - Math.PI / 2
      const rr = R + W * 0.01 + val(i % N) * W * 0.08
      const x = cx + Math.cos(a) * rr
      const y = cy + Math.sin(a) * rr
      if (i) vc.lineTo(x, y)
      else vc.moveTo(x, y)
    }
    vc.closePath()
    vc.stroke()
    vc.globalAlpha = 0.35
    vc.beginPath()
    vc.arc(cx, cy, R + W * 0.01, 0, 7)
    vc.stroke()
    vc.globalAlpha = 1
    return
  }
  if (kind === 'pixel') {
    const M = 36
    const u = W * 0.022
    for (let i = 0; i < M; i++) {
      const v = val(Math.floor((i * N) / M))
      const a = (i / M) * Math.PI * 2 - Math.PI / 2
      const steps = 1 + Math.floor(v * 6)
      for (let st = 0; st < steps; st++) {
        const rr = R + u * 0.6 + st * u * 1.15
        vc.fillStyle = st >= 4 ? css('--a1') : st >= 2 ? a2 : css('--a3')
        vc.fillRect(Math.round(cx + Math.cos(a) * rr - u / 2), Math.round(cy + Math.sin(a) * rr - u / 2), u, u)
      }
    }
    return
  }
  // bars (sunset), neon, spark (matsuri) and ink (manga).
  for (let i = 0; i < N; i++) {
    const v = val(i)
    const a = (i / N) * Math.PI * 2 - Math.PI / 2
    const f = (i < N / 2 ? i : N - 1 - i) / (N / 2)
    if (kind === 'spark') {
      const rr = R + W * 0.02 + v * W * 0.1
      vc.fillStyle = i % 3 ? a2 : a1
      vc.globalAlpha = 0.4 + v * 0.6
      vc.beginPath()
      vc.arc(cx + Math.cos(a) * rr, cy + Math.sin(a) * rr, W * (0.004 + v * 0.008), 0, 7)
      vc.fill()
      vc.globalAlpha = 0.25
      vc.strokeStyle = a2
      vc.lineWidth = W * 0.003
      vc.beginPath()
      vc.moveTo(cx + Math.cos(a) * (R + W * 0.01), cy + Math.sin(a) * (R + W * 0.01))
      vc.lineTo(cx + Math.cos(a) * rr, cy + Math.sin(a) * rr)
      vc.stroke()
      vc.globalAlpha = 1
      continue
    }
    if (kind === 'ink') {
      const len = W * 0.02 + v * W * 0.13
      vc.strokeStyle = '#111'
      vc.lineWidth = W * (0.003 + v * 0.006)
      vc.lineCap = 'round'
      vc.beginPath()
      vc.moveTo(cx + Math.cos(a) * (R + W * 0.03), cy + Math.sin(a) * (R + W * 0.03))
      vc.lineTo(cx + Math.cos(a) * (R + W * 0.03 + len), cy + Math.sin(a) * (R + W * 0.03 + len))
      vc.stroke()
      continue
    }
    const len = W * 0.015 + v * W * 0.1
    vc.lineCap = kind === 'neon' ? 'butt' : 'round'
    vc.lineWidth = W * (kind === 'neon' ? 0.008 : 0.011)
    vc.strokeStyle = kind === 'neon' ? (f < 0.5 ? a1 : a2) : `hsl(${20 + f * 30} 100% ${62 + f * 8}%)`
    if (kind === 'neon') {
      vc.shadowColor = vc.strokeStyle
      vc.shadowBlur = W * 0.015
    }
    vc.beginPath()
    vc.moveTo(cx + Math.cos(a) * R, cy + Math.sin(a) * R)
    vc.lineTo(cx + Math.cos(a) * (R + len), cy + Math.sin(a) * (R + len))
    vc.stroke()
  }
  vc.shadowBlur = 0
}

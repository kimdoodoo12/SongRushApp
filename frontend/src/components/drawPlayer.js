// The disc of the deck, per mood: a port of the mockup's drawPlayer() and
// the helpers it uses, rng() and rough() (docs/design/108-ui-design-reference.html,
// D-124). It draws what the mockup draws, on the canvas it is given, sized by
// canvas.width and canvas.height (520 x 520, no DPR scaling). Without a 2d
// context (jsdom) it draws nothing.

// The mockup's seeded random generator (mulberry32).
function rng(seed) {
  let a = seed >>> 0
  return () => {
    a = (a + 0x6d2b79f5) | 0
    let t = Math.imul(a ^ (a >>> 15), 1 | a)
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296
  }
}

// A hand-drawn circle: n wobbly strokes of radius r.
function rough(c, cx, cy, r, col, w, n) {
  c.strokeStyle = col
  c.lineWidth = w
  c.lineCap = 'round'
  c.globalAlpha = 0.6
  for (let k = 0; k < n; k++) {
    c.beginPath()
    for (let a = 0; a <= 6.4; a += 0.12) {
      const rr = r + (Math.random() - 0.5) * w * 1.6
      const x = cx + Math.cos(a) * rr
      const y = cy + Math.sin(a) * rr
      if (a) c.lineTo(x, y)
      else c.moveTo(x, y)
    }
    c.stroke()
  }
  c.globalAlpha = 1
}

export function drawPlayer(canvas, mood) {
  const c = canvas.getContext('2d')
  if (c === null || c === undefined) return
  const W = canvas.width
  const H = canvas.height
  const cx = W / 2
  const cy = H / 2
  c.clearRect(0, 0, W, H)
  if (mood === 'sunset') {
    // A black LP with an orange label.
    c.fillStyle = '#121012'
    c.beginPath()
    c.arc(cx, cy, W / 2, 0, 7)
    c.fill()
    c.strokeStyle = 'rgba(255,255,255,.06)'
    c.lineWidth = 1.5
    for (let r = W * 0.2; r < W * 0.49; r += W * 0.012) {
      c.beginPath()
      c.arc(cx, cy, r, 0, 7)
      c.stroke()
    }
    c.strokeStyle = 'rgba(255,220,180,.18)'
    c.lineWidth = W * 0.03
    c.beginPath()
    c.arc(cx, cy, W * 0.38, -0.9, -0.4)
    c.stroke()
    const lg = c.createRadialGradient(cx, cy, 0, cx, cy, W * 0.18)
    lg.addColorStop(0, '#ffcf70')
    lg.addColorStop(1, '#ff8a4c')
    c.fillStyle = lg
    c.beginPath()
    c.arc(cx, cy, W * 0.18, 0, 7)
    c.fill()
    c.fillStyle = '#121012'
    c.beginPath()
    c.arc(cx, cy, W * 0.015, 0, 7)
    c.fill()
  } else if (mood === 'rain') {
    // An iridescent CD.
    c.fillStyle = '#c9d3de'
    c.beginPath()
    c.arc(cx, cy, W / 2, 0, 7)
    c.fill()
    if (c.createConicGradient) {
      const cg = c.createConicGradient(0, cx, cy)
      const stops = [
        'rgba(255,170,200,.45)',
        'rgba(170,220,255,.5)',
        'rgba(200,255,220,.45)',
        'rgba(255,240,170,.45)',
        'rgba(200,180,255,.5)',
        'rgba(255,170,200,.45)',
      ]
      stops.forEach((s, i, a) => cg.addColorStop(i / (a.length - 1), s))
      c.fillStyle = cg
      c.beginPath()
      c.arc(cx, cy, W / 2, 0, 7)
      c.fill()
    }
    c.fillStyle = 'rgba(255,255,255,.35)'
    c.beginPath()
    c.arc(cx, cy, W * 0.17, 0, 7)
    c.fill()
    c.globalCompositeOperation = 'destination-out'
    c.beginPath()
    c.arc(cx, cy, W * 0.07, 0, 7)
    c.fill()
    c.globalCompositeOperation = 'source-over'
    c.strokeStyle = 'rgba(255,255,255,.6)'
    c.lineWidth = 2
    c.beginPath()
    c.arc(cx, cy, W * 0.07, 0, 7)
    c.stroke()
  } else if (mood === 'sakura') {
    // A pastel picture disc with blossoms.
    const g = c.createRadialGradient(cx, cy, W * 0.1, cx, cy, W / 2)
    g.addColorStop(0, '#ffd6e7')
    g.addColorStop(1, '#c7b5ff')
    c.fillStyle = g
    c.beginPath()
    c.arc(cx, cy, W / 2, 0, 7)
    c.fill()
    const r = rng(9)
    for (let i = 0; i < 14; i++) {
      const a = r() * 6.28
      const d = W * (0.22 + r() * 0.24)
      const fx = cx + Math.cos(a) * d
      const fy = cy + Math.sin(a) * d
      const s = W * (0.025 + r() * 0.02)
      c.fillStyle = r() < 0.5 ? '#fff' : '#ff9ec4'
      for (let k = 0; k < 5; k++) {
        const pa = (k / 5) * 6.28 + a
        c.beginPath()
        c.ellipse(fx + Math.cos(pa) * s, fy + Math.sin(pa) * s, s, s * 0.6, pa, 0, 7)
        c.fill()
      }
      c.fillStyle = '#ffe08a'
      c.beginPath()
      c.arc(fx, fy, s * 0.45, 0, 7)
      c.fill()
    }
    c.fillStyle = '#ff9ec4'
    c.beginPath()
    c.arc(cx, cy, W * 0.17, 0, 7)
    c.fill()
    c.fillStyle = '#161430'
    c.beginPath()
    c.arc(cx, cy, W * 0.02, 0, 7)
    c.fill()
  } else if (mood === 'chalk') {
    // A record drawn in chalk.
    c.fillStyle = 'rgba(255,255,255,.05)'
    c.beginPath()
    c.arc(cx, cy, W * 0.48, 0, 7)
    c.fill()
    rough(c, cx, cy, W * 0.47, '#f3f1e7', W * 0.012, 3)
    rough(c, cx, cy, W * 0.36, '#f3f1e7', W * 0.006, 1)
    rough(c, cx, cy, W * 0.28, '#f3f1e7', W * 0.006, 1)
    rough(c, cx, cy, W * 0.17, '#ffe27a', W * 0.012, 3)
    rough(c, cx, cy, W * 0.02, '#ffe27a', W * 0.01, 2)
    c.strokeStyle = 'rgba(255,179,193,.7)'
    c.lineWidth = W * 0.008
    c.beginPath()
    c.arc(cx, cy, W * 0.42, -1, -0.4)
    c.stroke()
  } else if (mood === 'pixel') {
    // A pixel disc: 36 x 36 cells, scaled up unsmoothed.
    const n = 36
    const o = document.createElement('canvas')
    o.width = n
    o.height = n
    const x = o.getContext('2d')
    if (x !== null && x !== undefined) {
      for (let i = 0; i < n; i++) {
        for (let j = 0; j < n; j++) {
          const dx = i - n / 2 + 0.5
          const dy = j - n / 2 + 0.5
          const d = Math.hypot(dx, dy)
          let col = null
          if (d < n / 2) col = '#2a1f55'
          if (d < n / 2 && d > n / 2 - 1.6) col = '#3fe0ff'
          if (d < n * 0.37 && d > n * 0.33) col = '#3e2f7a'
          if (d < n * 0.42 && d > n * 0.24 && dx < -2 && dy < -2 && (i + j) % 4 === 0) col = '#8a7fd6'
          if (d < n * 0.2) col = '#ff5c8a'
          if (d < n * 0.06) col = '#0f0a24'
          if (col) {
            x.fillStyle = col
            x.fillRect(i, j, 1, 1)
          }
        }
      }
    }
    c.imageSmoothingEnabled = false
    c.drawImage(o, 0, 0, W, H)
    c.imageSmoothingEnabled = true
  } else if (mood === 'matsuri') {
    // A festival drum, seen from above.
    c.fillStyle = '#5a2a14'
    c.beginPath()
    c.arc(cx, cy, W / 2, 0, 7)
    c.fill()
    c.fillStyle = '#e9c46a'
    for (let k = 0; k < 28; k++) {
      const a = (k / 28) * 6.283
      c.beginPath()
      c.arc(cx + Math.cos(a) * W * 0.455, cy + Math.sin(a) * W * 0.455, W * 0.012, 0, 7)
      c.fill()
    }
    const hg = c.createRadialGradient(cx - W * 0.08, cy - W * 0.08, W * 0.05, cx, cy, W * 0.42)
    hg.addColorStop(0, '#f8ecd0')
    hg.addColorStop(1, '#d6b483')
    c.fillStyle = hg
    c.beginPath()
    c.arc(cx, cy, W * 0.42, 0, 7)
    c.fill()
    c.fillStyle = '#c8372d'
    c.strokeStyle = '#c8372d'
    c.lineCap = 'round'
    for (let k = 0; k < 3; k++) {
      const a = k * 2.094 - 1.2
      const hx = cx + Math.cos(a) * W * 0.27
      const hy = cy + Math.sin(a) * W * 0.27
      c.beginPath()
      c.arc(hx, hy, W * 0.055, 0, 7)
      c.fill()
      c.lineWidth = W * 0.035
      c.beginPath()
      c.arc(cx, cy, W * 0.3, a + 0.15, a + 1.15)
      c.stroke()
    }
  } else if (mood === 'manga') {
    // A record drawn in ink.
    c.fillStyle = '#fff'
    c.beginPath()
    c.arc(cx, cy, W * 0.48, 0, 7)
    c.fill()
    c.strokeStyle = '#111'
    c.lineWidth = W * 0.025
    c.beginPath()
    c.arc(cx, cy, W * 0.475, 0, 7)
    c.stroke()
    c.lineWidth = W * 0.004
    for (let rr = W * 0.22; rr < W * 0.45; rr += W * 0.018) {
      c.beginPath()
      c.arc(cx, cy, rr, 0, 7)
      c.stroke()
    }
    c.strokeStyle = '#fff'
    c.lineWidth = W * 0.05
    c.beginPath()
    c.arc(cx, cy, W * 0.36, -1.1, -0.6)
    c.stroke()
    c.save()
    c.beginPath()
    c.arc(cx, cy, W * 0.18, 0, 7)
    c.clip()
    c.fillStyle = '#fff'
    c.fillRect(0, 0, W, H)
    c.fillStyle = '#111'
    for (let y = cy - W * 0.2; y < cy + W * 0.2; y += W * 0.022) {
      for (let x = cx - W * 0.2; x < cx + W * 0.2; x += W * 0.022) {
        const k = (y - cy + W * 0.2) / (W * 0.4)
        c.beginPath()
        c.arc(x + ((y / (W * 0.022)) % 2 ? W * 0.011 : 0), y, W * 0.009 * k, 0, 7)
        c.fill()
      }
    }
    c.restore()
    c.strokeStyle = '#111'
    c.lineWidth = W * 0.012
    c.beginPath()
    c.arc(cx, cy, W * 0.18, 0, 7)
    c.stroke()
  } else {
    // Neon wire rings.
    c.lineWidth = W * 0.008
    const rings = [
      [0.49, '#33f3ff'],
      [0.4, '#ff3fd2'],
      [0.3, '#33f3ff'],
      [0.18, '#ff3fd2'],
    ]
    rings.forEach(([r, col], i) => {
      c.strokeStyle = col
      c.shadowColor = col
      c.shadowBlur = W * 0.03
      c.beginPath()
      if (i === 1) c.setLineDash([W * 0.04, W * 0.02])
      else c.setLineDash([])
      c.arc(cx, cy, W * r, 0, 7)
      c.stroke()
    })
    c.setLineDash([])
    c.shadowBlur = 0
    c.strokeStyle = 'rgba(51,243,255,.5)'
    c.beginPath()
    c.moveTo(cx - W * 0.49, cy)
    c.lineTo(cx + W * 0.49, cy)
    c.moveTo(cx, cy - W * 0.49)
    c.lineTo(cx, cy + W * 0.49)
    c.stroke()
  }
}

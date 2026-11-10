// The drawing state of the mockup's sky and viz script
// (docs/design/108-ui-design-reference.html), which keeps it in module-level
// variables. Here it lives in one object per mount (cycle 115, spec M5).

// The mockup's seeded random (rng).
export function rng(seed) {
  let a = seed >>> 0
  return () => {
    a = (a + 0x6d2b79f5) | 0
    let t = Math.imul(a ^ (a >>> 15), 1 | a)
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296
  }
}

// The mockup's SENB: sakura's 170 petals.
function senbonPetals() {
  const r = rng(77)
  const cols = ['255,170,205', '255,214,231', '255,255,255', '255,120,170', '255,190,215']
  return Array.from({ length: 170 }, () => ({
    a: r() * 6.2832,
    rb: 0.395 + (r() + r() + r() - 1.5) * 0.035,
    sp: 0.8 + r() * 0.4,
    s: 0.006 + r() * 0.006,
    ph: r() * 6.2832,
    fl: 1 + r() * 1.5,
    c: cols[Math.floor(r() * cols.length)],
    al: 0.45 + r() * 0.4,
    out: r() < 0.12,
  }))
}

// The visualizer's per-mood state, as the mockup declares it.
function vizFields() {
  return {
    chalkNotes: [],
    chalkRot: 0,
    chalkLast: 0,
    chalkSpawn: 0,
    cLo: 99,
    cHi: 0,
    chalkEnergy: 0,
    chalkB: 0,
    chalkKick: 0,
    chalkCool: 0,
    chalkDood: [],
    chalkBeat: 0,
    SENB: senbonPetals(),
    senLast: 0,
    senE: 0,
    senB: 0,
    senW: 0,
  }
}

export function createCanvasState() {
  return {
    // The sky (part, initSky, drawSky, firework); mood and dpr are the last
    // initSky's.
    parts: [],
    scene: {},
    gridOff: 0,
    mood: null,
    dpr: 1,
    ...vizFields(),
  }
}

// Back to the visualizer's starting state (a mood change, spec item 1).
export function resetViz(state) {
  Object.assign(state, vizFields())
}

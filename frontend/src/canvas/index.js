// The mockup's two canvases (cycle 115, D-124): #sky (sky.js) and #viz
// (viz.js), with their drawing state (state.js). The app imports the canvas
// code only through this file.
export { createCanvasState, resetViz } from './state.js'
export { initSky, drawSky, fireworkAt } from './sky.js'
export { sizeViz, drawViz } from './viz.js'

// The mockup's dpr: the device pixel ratio, at most 2.
export function canvasDpr() {
  return Math.min(2, window.devicePixelRatio || 1)
}

// The mockup's reduce: prefers-reduced-motion. A missing matchMedia (jsdom)
// counts as motion allowed.
export function prefersReducedMotion() {
  return typeof window.matchMedia === 'function' && window.matchMedia('(prefers-reduced-motion: reduce)').matches
}

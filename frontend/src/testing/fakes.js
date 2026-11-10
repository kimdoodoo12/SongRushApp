// Test doubles for the browser objects App and playback.js use: WebSocket,
// AudioContext and fetch. Not a test file itself (no .test. in the name).
import { vi } from 'vitest'

// A WebSocket-like object. The app sets the on* properties and calls
// send(string) and close(); tests drive it with open(), receive() and
// serverClose().
export class FakeSocket {
  constructor(url) {
    this.url = url
    this.sent = []
    this.closed = false
    this.readyState = 0
    this.onopen = null
    this.onmessage = null
    this.onclose = null
    this.onerror = null
  }

  send(text) {
    this.sent.push(JSON.parse(text))
  }

  close() {
    this.closed = true
    this.readyState = 3
  }

  // Sent messages of one type, e.g. sentOfType('ready').
  sentOfType(type) {
    return this.sent.filter((m) => m.type === type)
  }

  open() {
    this.readyState = 1
    if (this.onopen) this.onopen({ type: 'open' })
  }

  receive(obj) {
    if (this.onmessage) this.onmessage({ data: JSON.stringify(obj) })
  }

  serverClose(code = 1006) {
    this.readyState = 3
    if (this.onclose) this.onclose({ type: 'close', code, wasClean: false })
  }
}

// A socketFactory that records every socket it creates.
export function createSocketFactory() {
  const sockets = []
  const factory = vi.fn((url) => {
    const socket = new FakeSocket(url)
    sockets.push(socket)
    return socket
  })
  return {
    factory,
    sockets,
    get last() {
      return sockets[sockets.length - 1]
    },
  }
}

export class FakeSource {
  constructor() {
    this.buffer = null
    this.connect = vi.fn()
    this.start = vi.fn()
    this.stop = vi.fn()
  }
}

// A GainNode-like object: gain.value is a plain settable field.
export class FakeGain {
  constructor() {
    this.gain = { value: 1 }
    this.connect = vi.fn()
  }
}

// An AnalyserNode-like object (cycle 115). getByteFrequencyData fills the
// array with `level` (default 0); tests set it to feed the visualizer.
export class FakeAnalyser {
  constructor() {
    this.fftSize = 2048
    this.smoothingTimeConstant = 0.8
    this.level = 0
    this.connect = vi.fn()
    this.disconnect = vi.fn()
    this.getByteFrequencyData = vi.fn((array) => {
      array.fill(this.level)
    })
  }

  get frequencyBinCount() {
    return this.fftSize / 2
  }
}

// A DynamicsCompressorNode-like object (cycle 115): AudioParam-like fields
// with a plain settable value.
export class FakeCompressor {
  constructor() {
    this.threshold = { value: -24 }
    this.knee = { value: 30 }
    this.ratio = { value: 12 }
    this.attack = { value: 0.003 }
    this.release = { value: 0.25 }
    this.connect = vi.fn()
    this.disconnect = vi.fn()
  }
}

export class FakeAudioBuffer {
  constructor(id) {
    this.id = id
    this.duration = 90
  }
}

// An AudioContext-like object. currentTime and state are plain settable
// fields. decodeAudioData resolves at once by default; with
// { deferDecode: true } each call waits until resolveDecode() or
// rejectDecode() is called (oldest first). resume() sets state to
// 'running' and resolves at once by default; with { deferResume: true }
// the state stays as it is and every pending resume() waits for
// resolveResume().
export class FakeAudioContext {
  constructor({ state = 'running', currentTime = 0, deferDecode = false, deferResume = false } = {}) {
    this.state = state
    this.currentTime = currentTime
    this.deferDecode = deferDecode
    this.deferResume = deferResume
    this.destination = { kind: 'destination' }
    this.sources = []
    this.gains = []
    this.analysers = []
    this.compressors = []
    this.decodeCalls = []
    this.decoded = []
    this.pendingDecodes = []
    this.pendingResumes = []
    this.resume = vi.fn(() => {
      if (!this.deferResume) {
        this.state = 'running'
        return Promise.resolve()
      }
      return new Promise((resolve) => this.pendingResumes.push(resolve))
    })
  }

  resolveResume() {
    this.state = 'running'
    const pending = this.pendingResumes.splice(0)
    for (const resolve of pending) resolve()
  }

  decodeAudioData(arrayBuffer) {
    this.decodeCalls.push(arrayBuffer)
    const buffer = new FakeAudioBuffer(this.decodeCalls.length)
    if (!this.deferDecode) {
      this.decoded.push(buffer)
      return Promise.resolve(buffer)
    }
    return new Promise((resolve, reject) => {
      this.pendingDecodes.push({
        resolve: () => {
          this.decoded.push(buffer)
          resolve(buffer)
        },
        reject: () => reject(new Error('decode failed')),
      })
    })
  }

  resolveDecode() {
    const pending = this.pendingDecodes.shift()
    if (!pending) throw new Error('no pending decode')
    pending.resolve()
  }

  rejectDecode() {
    const pending = this.pendingDecodes.shift()
    if (!pending) throw new Error('no pending decode')
    pending.reject()
  }

  createBufferSource() {
    const source = new FakeSource()
    this.sources.push(source)
    return source
  }

  createGain() {
    const gain = new FakeGain()
    this.gains.push(gain)
    return gain
  }

  createAnalyser() {
    const analyser = new FakeAnalyser()
    this.analysers.push(analyser)
    return analyser
  }

  createDynamicsCompressor() {
    const compressor = new FakeCompressor()
    this.compressors.push(compressor)
    return compressor
  }

  // Sources on which start() was called.
  startedSources() {
    return this.sources.filter((s) => s.start.mock.calls.length > 0)
  }
}

// An audioContextFactory that records every context it creates.
export function createAudioContextFactory(options) {
  const contexts = []
  const factory = vi.fn(() => {
    const ctx = new FakeAudioContext(options)
    contexts.push(ctx)
    return ctx
  })
  return {
    factory,
    contexts,
    get last() {
      return contexts[contexts.length - 1]
    },
  }
}

// A fetch stand-in. The first `failures` calls fail: by rejecting
// (failWith 'reject') or with an ok:false response (failWith 'status').
// Later calls succeed with a fresh ArrayBuffer each time.
export function createFakeFetch({ failures = 0, failWith = 'reject' } = {}) {
  const urls = []
  const buffers = []
  let remaining = failures
  const fetchFn = vi.fn(async (url) => {
    urls.push(url)
    if (remaining > 0) {
      remaining -= 1
      if (failWith === 'reject') throw new Error('network down')
      return { ok: false, status: 500, arrayBuffer: async () => new ArrayBuffer(0) }
    }
    const buffer = new ArrayBuffer(8)
    buffers.push(buffer)
    return { ok: true, status: 200, arrayBuffer: async () => buffer }
  })
  return { fetchFn, urls, buffers }
}

const CONTEXT_METHODS = new Set([
  'arc', 'arcTo', 'beginPath', 'bezierCurveTo', 'clearRect', 'clip', 'closePath', 'createConicGradient',
  'createImageData', 'createLinearGradient', 'createPattern', 'createRadialGradient', 'drawFocusIfNeeded',
  'drawImage', 'ellipse', 'fill', 'fillRect', 'fillText', 'getImageData', 'getLineDash', 'getTransform',
  'isPointInPath', 'isPointInStroke', 'lineTo', 'measureText', 'moveTo', 'putImageData', 'quadraticCurveTo',
  'rect', 'reset', 'resetTransform', 'restore', 'rotate', 'roundRect', 'save', 'scale', 'setLineDash',
  'setTransform', 'stroke', 'strokeRect', 'strokeText', 'transform', 'translate',
])

// A recording CanvasRenderingContext2D stand-in (cycle 115). Every method
// call is pushed to ctx.calls as [name, args] and every property set to
// ctx.sets as [name, value]; reads return the last value set. Every 2d
// method is recorded; most return undefined, the ones whose result drawing
// code uses (gradients, patterns, measureText, getImageData) return a
// stand-in. Other names read as undefined. ctx.canvas is the canvas passed in.
export function createRecordingContext(canvas = { width: 300, height: 150 }) {
  const calls = []
  const sets = []
  const values = {
    canvas,
    calls,
    sets,
    fillStyle: '#000000',
    strokeStyle: '#000000',
    globalAlpha: 1,
    globalCompositeOperation: 'source-over',
    lineWidth: 1,
    font: '10px sans-serif',
  }
  const gradient = () => ({ addColorStop: () => {} })
  const results = {
    createLinearGradient: gradient,
    createRadialGradient: gradient,
    createConicGradient: gradient,
    createPattern: () => ({}),
    measureText: (text) => ({ width: String(text).length * 8 }),
    getImageData: (x, y, w, h) => ({ width: w, height: h, data: new Uint8ClampedArray(Math.max(0, w * h * 4)) }),
    createImageData: (w, h) => ({ width: w, height: h, data: new Uint8ClampedArray(Math.max(0, w * h * 4)) }),
    isPointInPath: () => false,
    getTransform: () => ({ a: 1, b: 0, c: 0, d: 1, e: 0, f: 0 }),
  }
  const methods = {}
  const proxy = new Proxy(values, {
    get(target, prop) {
      if (prop in target) return target[prop]
      if (typeof prop !== 'string' || !CONTEXT_METHODS.has(prop)) return undefined
      if (!methods[prop]) {
        methods[prop] = (...args) => {
          calls.push([prop, args])
          return results[prop] ? results[prop](...args) : undefined
        }
      }
      return methods[prop]
    },
    set(target, prop, value) {
      sets.push([prop, value])
      target[prop] = value
      return true
    },
  })
  return proxy
}

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

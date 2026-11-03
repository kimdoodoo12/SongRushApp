// NTP-style estimate of the server clock (cycle 047, F-004). estimate() is
// pure; createClockSync() gets its clock, its send and its timers injected,
// so it starts no timer of its own.

const KEEP = 3
const WINDOW = 8
const SPACING_MS = 150

function median(values) {
  const sorted = [...values].sort((a, b) => a - b)
  const mid = Math.floor(sorted.length / 2)
  if (sorted.length % 2 === 1) return sorted[mid]
  return (sorted[mid - 1] + sorted[mid]) / 2
}

// samples: [{ t0, t1, serverTime }]. Keeps the 3 lowest-rtt samples; the
// offset is the median of theirs and rttMs the lowest rtt. A negative rtt
// (the client clock stepped backwards) is ignored. null for none.
export function estimate(samples) {
  const measured = samples
    .map(({ t0, t1, serverTime }) => {
      const rtt = t1 - t0
      return { rtt, offset: serverTime - (t0 + rtt / 2) }
    })
    .filter((m) => m.rtt >= 0)
  if (measured.length === 0) return null
  measured.sort((a, b) => a.rtt - b.rtt)
  const kept = measured.slice(0, KEEP)
  return { offsetMs: median(kept.map((m) => m.offset)), rttMs: kept[0].rtt }
}

// send(msg) sends one ping{id}; schedule(fn, ms) sets one timer and returns
// its cancel function. current() is the estimate over the last 8 samples;
// clear() drops the stored samples.
export function createClockSync({ now, send, schedule }) {
  let nextId = 1
  // id -> t0 for the pings still waiting for their pong.
  const pending = new Map()
  let samples = []
  let cancels = new Set()

  function ping() {
    const id = nextId++
    pending.set(id, now())
    send({ type: 'ping', id })
  }

  return {
    burst(count) {
      if (count <= 0) return
      ping()
      for (let i = 1; i < count; i++) {
        const cancel = schedule(() => {
          cancels.delete(cancel)
          ping()
        }, SPACING_MS * i)
        cancels.add(cancel)
      }
    },
    onPong(msg) {
      if (!pending.has(msg.id)) return
      const t0 = pending.get(msg.id)
      pending.delete(msg.id)
      const t1 = now()
      // A clock stepped backwards: the sample would only take a window slot.
      if (t1 < t0) return
      samples = [...samples, { t0, t1, serverTime: msg.serverTime }].slice(-WINDOW)
    },
    current() {
      return estimate(samples)
    },
    clear() {
      samples = []
    },
    stop() {
      for (const cancel of cancels) cancel()
      cancels = new Set()
    },
  }
}

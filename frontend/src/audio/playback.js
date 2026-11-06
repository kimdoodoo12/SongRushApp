// Audio helpers for the play screen: scheduling against the server clock,
// the Ogg Opus support check (D-053) and fetching + decoding one track.

// Converts the server's startAt/deadlineAt (epoch ms) into AudioContext
// seconds. offsetMs is server time minus client time; nowMs and ctxTime are
// read at the same moment. outputLatency (seconds, ctx.outputLatency) is
// subtracted so the sound leaves the speaker at startAt. Returns null when
// the song is already over.
export function planPlayback({ startAt, deadlineAt, offsetMs, nowMs, ctxTime, outputLatency = 0 }) {
  const delaySec = (startAt - offsetMs - nowMs) / 1000 - outputLatency
  const when = ctxTime + Math.max(0, delaySec)
  // A late start plays from where the song is now.
  const offset = Math.max(0, -delaySec)
  const stopAt = ctxTime + (deadlineAt - offsetMs - nowMs) / 1000 - outputLatency
  if (stopAt <= when) return null
  return { when, offset, stopAt }
}

export function canPlayOggOpus(audioEl = new Audio()) {
  const answer = audioEl.canPlayType('audio/ogg; codecs="opus"')
  return answer === 'probably' || answer === 'maybe'
}

async function fetchAndDecode(ctx, url, fetchFn) {
  const response = await fetchFn(url)
  if (!response.ok) throw new Error(`audio fetch failed: ${response.status}`)
  const body = await response.arrayBuffer()
  return ctx.decodeAudioData(body)
}

// One retry after a failed fetch or decode; the second failure is thrown.
export async function loadTrack(ctx, url, fetchFn) {
  try {
    return await fetchAndDecode(ctx, url, fetchFn)
  } catch {
    return fetchAndDecode(ctx, url, fetchFn)
  }
}

# 047 clock-sync (F-004)

## Goal
In a room, every device starts a song at the same real moment. To do that, the client:
- estimates its offset to the server clock with NTP-style pings;
- schedules on the `AudioContext` clock with the output latency subtracted;
- re-syncs on drift;
- keeps iOS audio unlocked with a resume on every click and a "탭하여 소리 켜기" prompt.

This covers design 029 F5, design 034 F2/M6, and the cycle 041 review.

## Interface (test-facing)
- **`src/net/clockSync.js`** (pure, no timers):
  - `estimate(samples)` takes `samples: [{t0, t1, serverTime}]` and returns `{ offsetMs, rttMs }`, or `null` when `samples` is empty.
    - Per sample: `rtt = t1 - t0` and `offset = serverTime - (t0 + rtt / 2)`.
    - The function keeps the 3 samples with the lowest `rtt` (all samples if there are fewer than 3). `offsetMs` is the median of their offsets (the middle value; the mean of the two middle values when 2 are kept). `rttMs` is the lowest `rtt`.
  - `createClockSync({ now, send, schedule })` returns `{ burst(count), onPong(msg), current(), stop() }`.
    - `send(msg)` sends `ping{id}`.
    - `schedule(fn, ms)` sets one timer and returns a cancel function. In tests it wraps fake timers.
    - `burst(count)` sends `count` pings 150 ms apart. The first goes at once.
    - `onPong({id, serverTime})` records `{t0, t1: now(), serverTime}` for a known id and ignores unknown ids.
    - `current()` returns `estimate` over the most recent 8 samples.
    - `stop()` cancels the pending pings.
- **`src/audio/playback.js`** — `planPlayback` gains an optional `outputLatency` (seconds, default 0). The locked cycle 039 tests keep passing with the default.
  - `delaySec = (startAt - offsetMs - nowMs) / 1000 - outputLatency`
  - `when = ctxTime + max(0, delaySec)`
  - `offset = max(0, -delaySec)`
  - `stopAt = ctxTime + (deadlineAt - offsetMs - nowMs) / 1000 - outputLatency`
  - The result is `null` if `stopAt <= when`.

## Behaviour (room sessions only)
Solo keeps the cycle 039 receipt-time offset (`serverTime - now()` at `start`). It has a single listener and needs no sync traffic.

1. **Sync.**
   - On `joined`: `burst(8)`.
   - After each song's file has loaded (right when `ready` is sent): `burst(3)`; not at `prepare`, where the pings would queue behind the download (spec review M2).
   - Every 30 s while the session is open: `burst(3)`. This uses the session's own interval and stops when the session closes.
   - Pings start only after `joined`, so the locked cycle 041 tests that check the exact first frames (`create`, `join`) still hold.
   - `close()` and the socket's `onclose` call `stop()`; no ping is sent after the session ends (spec review N4).
   - The clock is the injected `now()` (`Date.now` by default), the same clock `planPlayback` uses, so the arithmetic is consistent; design 034 F2's `performance.now()` is not used, and a wall-clock step is corrected by the next bursts (spec review N3, accepted).
2. **Offset used at `start`.**
   - If `current()` is not null, `offsetMs` comes from it. Otherwise it falls back to `serverTime - now()`.
   - **Drift.** Let `receipt = serverTime - now()` at arrival. If `|receipt - current().offsetMs| > current().rttMs + 50`, the session sends `burst(3)`. The song still uses the current estimate.
3. **Output latency.** Scheduling passes `ctx.outputLatency` (0 when undefined) to `planPlayback`. The remaining-time bar keeps its cycle 039 formula, with `offsetMs` from step 2.
4. **Audio unlock (cycle 041 review).**
   - The clicks on `게임 시작` and `대기실로` call `ctx.resume()` synchronously in the handler when `ctx.state !== 'running'`.
   - On `prepare`, if `ctx.state !== 'running'`, the game screen shows a button `탭하여 소리 켜기`. Its click calls `ctx.resume()` synchronously. The button disappears after the click's `resume` resolves with the state `running`, and is checked again at `start` (it shows if the state is not running then).
   - When the context is created (first click), `navigator.audioSession.type = 'playback'` is set if `navigator.audioSession` exists (iOS 17+), so the iOS silent switch does not mute the game (spec review H1).
   - This also applies to solo.

## Acceptance criteria (from `frontend/`)
1. **`src/net/clockSync.test.js`** (pure)
   - `estimate` picks the 3 lowest-RTT samples. A sample with a large RTT and a wild offset does not move the result.
   - The median is computed for 1, 2 and 3 kept samples.
   - `null` is returned for no samples.
   - `createClockSync` (fake `schedule` and `now`):
     - `burst(8)` sends 8 pings with distinct ids, 150 ms apart.
     - `onPong` for an unknown id is ignored.
     - `current()` uses only the last 8 samples.
     - `stop()` cancels the remaining pings.
2. **`src/audio/playback.test.js`** stays locked and unchanged.
   - **`src/audio/playbackLatency.test.js`** (new): with `outputLatency: 0.05` both `when` and `stopAt` move 50 ms earlier. When the delay is smaller than the latency, `when` is `ctxTime` and `offset` covers the difference.
3. **`src/App.sync.test.jsx`** (fakes from cycle 039, unchanged; fake timers only where pings are spaced, per the cycle 039 timer rule)
   - **Burst on joined.** After `joined` in a room, 8 `ping` messages go out, 150 ms apart.
   - **Synced offset.**
     - Answering them with `pong{id, serverTime}`, the next `start` schedules `source.start` at the `when` computed from the synced offset, not the receipt offset. The test makes the two differ by 2 s.
     - A drift beyond `rtt + 50` sends 3 more pings.
     - No drift sends none.
   - **Re-sync.** The song's load (the `ready` send) triggers 3 pings; `prepare` alone does not. 30 s of time triggers 3 pings. After `나가기` or a server close no further ping is sent.
   - **Audio session.** With a stub `navigator.audioSession = {}` before the first click, its `type` becomes `'playback'`; without it nothing breaks.
   - **Solo.** A solo session sends no `ping`.
   - **Output latency.** `outputLatency` on the fake context shifts the scheduled `when`.
   - **Sound prompt.**
     - With the context `suspended` at `prepare`, the button `탭하여 소리 켜기` appears. Clicking it calls `resume()` synchronously, and the button disappears once the resume resolves with the state `running`. A context that becomes `suspended` before `start` shows the button at `start`.
     - With a running context, no button.
   - **Resume on click.** `게임 시작` and `대기실로` call `resume()` synchronously when the context is not running.
4. The cycle 038, 039 and 041 tests and `src/testing/fakes.js` are locked again for this cycle (spec review M3) and pass unchanged. `npm test`, `npm run lint` and `npm run build` exit 0.

## Review changes (spec round 1)
- **High.** The iOS silent switch mutes Web Audio even with a running context; `navigator.audioSession.type = 'playback'` is set (iOS 17+, within D-053's 18.4+).
- **Medium.** The I-003 owner check now covers two devices in one room (backlog text updated); the per-song burst moves from `prepare` to after the load; the earlier tests and fakes are locked.
- **Nits.** `처음으로` is dropped from the resume list; the prompt is checked at `prepare`, after its click and at `start`; the clock choice is recorded; sync stops on close.

## Test list
- `frontend/src/net/clockSync.test.js`
- `frontend/src/audio/playbackLatency.test.js`
- `frontend/src/App.sync.test.jsx`

## File ownership
- **Test writer:** the three new test files.
- **Implementer:** `src/net/clockSync.js`, `src/audio/playback.js` (additive change only), `src/game/*`, `src/components/*` and `src/App.jsx`.

## Out of scope
- **A separate I-003 test page.** The solo screen already serves it; see the BACKLOG I-003 text.
- **The multiplayer game screen** (F-005).

## Result
- Tests `28cd4ae` (locked, after one pre-lock fix of a vacuous latency test found by the verifier); implementation `bca8b52` (attempt 1).
- Red phase (verifier): `clockSync.test.js` failed only on the missing module; 5 of 6 latency tests failed on the unsupported option; 24 of 28 App.sync tests failed on missing pings, prompt, audio session, latency or resume; the passing ones are deliberate negative guards; every earlier test passed.
- Verifier: `npm test` twice (8 files, 116 tests), `npm run lint`, `npm run build` exit 0; no test or fake file changed after `28cd4ae`.

## Code review (critic)
No Blocker or High. Confirmed: offset sign and maths match `planPlayback`; pings are per session and stop on close; no StrictMode double effects; the audio session and resumes happen inside the click; no `eslint-disable`.
- **M1 (to F-005, fairness).** On a detected drift the song still uses the old estimate; a client whose clock stepped forward would start early. The fix changes the spec and the locked drift test.

# 039 solo-screen (F-002)

## Goal
A player can open the site, type a nickname, pick a song count, and play a solo game against the cycle 037 server: they hear each song from the announced moment, guess by chat, and see the reveal, the "정답!" announcement and the final score, all in Korean (D-015, D-023, D-053, D-060; design 034).

## Interface (test-facing)
- **`src/audio/playback.js`**
  - `planPlayback({ startAt, deadlineAt, offsetMs, nowMs, ctxTime })` returns `{ when, offset, stopAt }` in AudioContext seconds, or `null` when the song is already over. The inputs:
    - `startAt` and `deadlineAt` are server epoch ms.
    - `offsetMs` is server time minus client time.
    - `nowMs` is the client's `Date.now()`.
    - `ctxTime` is `ctx.currentTime` read at the same moment.
  - The calculation:
    - `delay = startAt - offsetMs - nowMs`.
    - `when = ctxTime + max(0, delay) / 1000`.
    - `offset = max(0, -delay) / 1000`, so a late start plays from where the song is now.
    - `stopAt = ctxTime + (deadlineAt - offsetMs - nowMs) / 1000`.
    - The result is `null` if `stopAt <= when`.
  - `canPlayOggOpus(audioEl)` is true when `audioEl.canPlayType('audio/ogg; codecs="opus"')` returns `"probably"` or `"maybe"`. `audioEl` defaults to `new Audio()`.
  - `async loadTrack(ctx, url, fetchFn)` does `fetchFn(url)`, requires `ok`, then calls `arrayBuffer()` and `ctx.decodeAudioData(buffer)`. After one failure it retries once; after the second failure it throws.
- **`App` props.** All props are optional, and the defaults are the real browser objects, so `<App />` still renders as in cycle 038.
  - `socketFactory(url)` returns a WebSocket-like object. The app sets its `onopen`, `onmessage`, `onclose` and `onerror` properties and calls `send(string)` and `close()`.
  - `audioContextFactory()` returns an AudioContext-like object with `state`, `currentTime`, `resume()`, `decodeAudioData(arrayBuffer)` returning a Promise, `destination`, and `createBufferSource()`. The buffer source has `buffer`, `connect(node)`, `start(when, offset)` and `stop(when)`. The app creates one context on the first "시작" click and reuses it for later games (only `resume()` again).
  - `fetchFn` defaults to `fetch`.
  - `canPlayOpus` is a boolean. It defaults to `canPlayOggOpus()`.
  - `now()` returns ms and defaults to `Date.now`.
  - The WebSocket URL is `ws(s)://<location.host>/ws`.

## Behaviour
1. **Start screen.**
   - It shows `h1` "SongRush", a "닉네임" text input (maximum 16 characters), a "곡 수" select (5-30, default 10) and a "시작" button.
   - The footer always shows the credit line `데이터 출처: AnimeThemes, AniList, anime-offline-database, AniDB, Wikidata` (D-023).
   - If `canPlayOpus` is false, the screen shows `이 브라우저에서는 음원을 재생할 수 없습니다. 브라우저 또는 iOS를 최신 버전으로 업데이트해 주세요.` and "시작" is disabled (D-053).
2. **Start.**
   - Clicking "시작" creates the AudioContext (first time only) and calls `resume()` synchronously inside the click handler, before any `await`. This is the audio-unlock click (design 029; iOS needs it in the gesture).
   - It then opens the socket. On open it sends `create{nickname, songCount}`, where `songCount` is a number.
   - **One socket at a time.** Before closing a socket on purpose ("다시 하기", a start-screen error), the app clears its handlers; events from any socket that is not the current one are ignored.
3. **prepare{index, total, audioUrl}.**
   - The screen shows `<index+1> / <total>` and `음원을 불러오는 중...`.
   - The app calls `loadTrack`, then sends `ready{index}`.
   - If loading fails twice, it shows `이번 곡을 재생하지 못했습니다.` and still sends `ready{index}`, so the game is not held up.
4. **start{index, startAt, deadlineAt, serverTime}.**
   - `offsetMs = serverTime - now()` when the message arrives.
   - If `ctx.state !== 'running'`, the app calls `resume()` and waits for it before reading `now()` and `ctx.currentTime` (design 034 M6).
   - The app schedules the decoded buffer with `planPlayback`: it calls `source.start(when, offset)` and `source.stop(stopAt)`.
   - If decoding finishes after `start`, it schedules then, so a late start plays from the current point (design 034 H2), but only if that song's index is still the current one and it has not been revealed; a decode result for an older index or after the reveal is dropped.
   - A remaining-time bar (`role="progressbar"`) has `aria-valuemin=0`, `aria-valuemax = deadlineAt - startAt` and `aria-valuenow` = remaining ms (`deadlineAt - offsetMs - now()`, clamped to 0..max; the max before `startAt`). It updates every 250 ms from an interval callback (D-060).
5. **Chat.**
   - A form with an input labelled "정답 입력" sends `chat{text}` on submit and clears the input. An empty input sends nothing.
   - Received `chat{name, text}` lines appear in a chat log as `<name>: <text>`.
6. **correct{name}** shows `<name> 정답!`.
7. **reveal.**
   - The current song stops at once: `stop()` is called on its source, if one was started.
   - A card shows the `reveal` title, `themeSlug` as the server sends it (already like `OP1`, `ED2-EN`; `themeType` is not prepended), the `song` (omitted when null), the `artists` joined by `, ` (the line is omitted when the list is empty), and either `정답: <winner>` or `정답자 없음`.
   - **scores** updates the shown score as `점수: <n>`, read from `scores[0].score` (solo has one row; the server does not echo the trimmed nickname).
8. **gameOver** shows `최종 점수: <score>점` (from `ranking[0].score`) and a "다시 하기" button. The button closes the socket and returns to the start screen.
9. **error{code}** shows a Korean message:
   - `bad-nickname`: `닉네임은 1~16자로 입력해 주세요.`
   - `bad-song-count`: `곡 수는 5~30곡입니다.`
   - `not-enough-songs`: `출제할 곡이 부족합니다.`
   - `too-long`: `채팅은 200자까지 입력할 수 있습니다.`
   - any other code: `오류가 발생했습니다 (<code>)`.
   - A start-screen error, which arrives before any `prepare`, closes that socket (handlers cleared first) and returns the player to the start screen with the message shown.
10. **Socket closed** unexpectedly during a game shows `서버와 연결이 끊어졌습니다.` and a "처음으로" button. There is no automatic reconnect in M-1.

## Acceptance criteria (from `frontend/`)
1. `src/audio/playback.test.js`:
   - `planPlayback` on time: delay 3000 gives `when = ctxTime + 3` and `offset = 0`.
   - Late by 2500 ms: `when = ctxTime` and `offset = 2.5`.
   - `stopAt` follows `deadlineAt`, and `offsetMs` shifts all three values.
   - The result is `null` after the deadline.
   - `canPlayOggOpus` with fake elements answering `"probably"`, `"maybe"` and `""`.
   - `loadTrack` succeeds, retries once after one failure (for example a rejected fetch or `ok: false`), and throws after two failures.
2. `src/App.flow.test.jsx` uses a fake socket, a fake AudioContext and a fake fetch, renders `<App canPlayOpus={true} ...>` (jsdom's `canPlayType` always returns `""`), and reads the screen with Testing Library (`fireEvent`; user-event is not installed) by visible text and roles. Timer rule: real timers with `await waitFor(...)` for async flows; fake timers only in the progressbar test, advancing with `await act(async () => { await vi.advanceTimersByTimeAsync(250) })` and an injected `now` that advances with them (Testing Library's `waitFor` does not drive Vitest fake timers):
   - The start screen shows the inputs, the default song count 10 and the credit line.
   - Clicking "시작" calls `resume()` and sends `create` with the nickname and a number.
   - `prepare` leads to a fetch of `audioUrl`, then `ready{index}`, and shows `1 / 5`.
   - `start` leads to `source.start` with the planned `when` and `offset` (both on-time and late cases); the progressbar has the specified `aria-valuemax`, and its `aria-valuenow` decreases after 250 ms of advanced time.
   - Chat submit sends `chat{text}` and clears the input; incoming chat lines show.
   - `correct` shows `<name> 정답!`.
   - `reveal` stops the source and shows the card with `정답자 없음` for a null winner and the server's `themeSlug` (for example `OP1`) as given.
   - A decode that resolves after the song's `reveal` does not call `source.start`.
   - `gameOver` shows the final score, and "다시 하기" returns to the start screen; a later `onclose` from the old fake socket does not show the disconnect message.
   - Two failed loads show the failure text and still send `ready`.
   - `canPlayOpus={false}` shows the notice and disables "시작".
   - `error{code:"not-enough-songs"}` shows its message on the start screen.
   - An unexpected close shows `서버와 연결이 끊어졌습니다.`
3. The cycle 038 tests still pass unchanged.
4. `package.json` lint script is `eslint . --max-warnings 0` (cycle 038 review). `npm test`, `npm run lint` and `npm run build` each exit 0.

## Implementer notes
- `react-hooks` recommended rules include `purity`, `refs`, `set-state-in-effect` and `immutability` as errors, and lint now fails on warnings: create sockets and audio in event handlers, keep `Date.now()`/`now()` out of render (store the remaining time from the interval callback), and avoid direct `setState` in effect bodies.

## Test list
- `frontend/src/audio/playback.test.js` covers criterion 1.
- `frontend/src/App.flow.test.jsx` covers criterion 2.
- Fake helpers may live in `frontend/src/testing/fakes.js`, which is not a test file name.

## File ownership
- **Test writer:** the two test files and `src/testing/fakes.js`.
- **Implementer:** `src/audio/playback.js`, `src/App.jsx`, new components and modules under `src/` (for example `src/game/`, `src/components/`), plain CSS (`src/App.css` imported from `App.jsx` or `main.jsx`), and the `lint` script in `package.json`.
- **Unchanged:** the cycle 038 tests and `vite.config.js`.

## Review changes (spec round 1)
- **High.** The server's `themeSlug` already holds `OP1`/`ED2-EN`; the card shows it as given (the planned `type + slug` would show `OPOP1`). Testing Library's `waitFor` does not drive Vitest fake timers; the timer rule is fixed.
- **Medium.** Late decodes after the reveal or for an old index are dropped; old sockets' events are ignored and handlers are cleared before an intentional close; one AudioContext is reused; a suspended context is resumed before scheduling; solo scores read row 0; the progressbar's aria values are defined and tested; lint-rule notes for the implementer.
- **Nits.** Empty artists and null songs are omitted; flow tests pass `canPlayOpus={true}`.

## Out of scope
- NTP-style clock sync (F-004). For solo play on one machine the `serverTime`-at-receipt offset is enough.
- Rooms and lobby (F-003).
- Reconnect (F-005, B-005b).
- Skip (B-006b, F-005).
- Visual polish beyond a clean, readable layout.

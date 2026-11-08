# 087 disc-before-start (D-101 clarified)

## Decision (owner, 2026-11-08)
"여전히 음원 불러오는중일때 움직임이 멈춘게 아니라 아예 막대랑 cd가 안보이는데"

Cycle 086 misread D-101. The owner's "loading" is the prepare phase before a song starts, when the stage shows "음원을 불러오는 중...". In that phase the record and equalizer were not drawn at all, because they were drawn only between the start and the reveal. The intent is that they are always drawn while a round runs, standing still until the song actually plays.

## Behaviour
1. **When the disc is drawn.** The disc (`.disc-area` with the record and equalizer) is drawn whenever the game stage has a current round and no reveal (`screen === 'game' && round !== null && reveal === null`). That covers prepare, loading and playing.
2. **When it is paused.** `data-paused="true"` is set unless the song is actually playing, meaning `started` is true and `load` is neither `loading` nor `failed`. So it is paused in prepare, before the start, while loading and after a failed load, and it moves only while the song plays.
3. **Unchanged.** The hint and the skip row still show only from the start, and the reveal still replaces the disc. The CSS pause rule from 086 is unchanged.
4. **Layout.** No theme rule changes. Theme box equality must stay 0. The stage may grow at prepare, because the disc now appears earlier; this is the same in every theme.

## Acceptance
**Tests.** Declared rewrite or addition in `frontend/src/App.loadingdisc.test.jsx`:
- **Prepare phase.** After `prepare` and before `start`, while the audio is loaded or still loading, `.disc-area` is rendered with `data-paused="true"`, and the record and equalizer are present.
- **After start and the audio loaded.** The attribute is absent.
- **The 086 cases still hold:** loading after the start, and a failed load.
- **Reveal.** After the reveal, `.disc-area` is absent.

**Commands.** `npm test`, `npm run lint` and `npm run build` exit 0.

**Lead's browser check.**
- Audio is held with a route, and the round has not started. The disc is visible in all six themes, with a pixel change under 1 %.
- After release and the start, it spins (at least 5 %).
- Theme box differences stay 0 at 1280 and 390.

## File ownership
- **Test writer:** `frontend/src/App.loadingdisc.test.jsx`.
- **Implementer:** `frontend/src/components/GameScreen.jsx`.

## Result
- **Commits.**
  - Tests: `d0b3987`, then a declared revision at `6395829`.
  - Implementation: `2eeda79`.
  - Attempt 2: `299c32b`.
- **Out of scope, riding along.** Commit `a5af7b3` (`docs/design/087-songs-3000-covers.md` and BACKLOG lines D-102 to D-107, S-007, C-018 to F-022) was made by another owner session in the shared checkout. It is owner-approved design work, not part of this cycle. The spec commit `293314b` also carried that session's uncommitted D-102 to D-105 BACKLOG lines.
- **Review (attempt 1).** No Blocker.
  - **H-1:** the disc spun during the roughly 3 s silent lead after `start`.
  - **M-1:** the disc jumped when the loading line disappeared.
  - **M-2:** it spun while the sound was blocked.
  - **M-3:** there were no tests for late join, rejoin or waiting for the next song.
  - All four were fixed in attempt 2. The disc is paused unless `started && reached && load ok && !soundBlocked`, where `reached` is recomputed on every 250 ms tick. The `load-line` keeps its box while the disc is shown.
- **Verifier at `299c32b`.**
  - `npm test`: 30 files, 407 tests passed; lint exit 0. The targeted run (loadingdisc, themeLayout, flow, reconnect) passed 82 tests.
  - The locked-test diff is empty.
  - The `session.js` diff only adds the display-only `reached` helper and dispatch fields; playback timing is untouched.
- **Lead's browser check.** Local jar and built client, Playwright on Edge.
  - **Audio held before the start, all six themes.** The disc is drawn before the start (no hint yet) and changes by 0.0 % while held. After release it spins: 12.5 % to 42.8 %.
  - **Normal load.** Right after the start, in the silent lead, it changes 0.0 %. 3.5 s later, while audible, it changes 15.7 %.
  - **Theme box differences** are 0 at 1280 in ko, en and ja, and 0 at 390 once measured in page coordinates. The earlier "2 differences" at 390 came from viewport scroll in the measurement script, not from layout.
- **Not handled, Follow-up.** While reconnecting, `started` and `reached` stay set, so the disc may keep spinning until the snapshot restores.

# 131 window-restore (F-037, from S-008 design 125, D-129, issue #44 request 1)

## Goal
A player who reloads, reconnects or joins late while the music plays on after the reveal sees the answer card and hears the rest of the music, in time with everyone else.

## Background
- Cycle 128 (B-036): with the switch on, a snapshot taken in a reveal window has `song` with the playing fields (`index`, `total`, `audioUrl`, `startAt`, `deadlineAt`, per-mode keys) plus `reveal` (the broadcast `reveal` frame without `type`, `playUntil` included) and `skipState{votes, needed}`. Outside a window nothing changed (a snapshot in `REVEAL` has `song: null`).
- Today the client builds a played round from such a song and starts it late (`onRejoinSnapshot`, session.js:633-671; `onSnapshot` for a watcher or late joiner, 675-704; `lateStart` 606-611 waits for the first clock estimate), and ignores `reveal`, so a reload in the window plays the song again with no card and a live answer box.
- Cycle 129 (F-035): `onReveal` (session.js:751-793) opens a window only for a round that has a `start`; a reveal before the start ends the round (`over`).

## Design
When the snapshot's `song.reveal` is an object:

- **At once, from the snapshot:** the session builds the round as today and marks it `revealed`, and the screen shows the answer card from `song.reveal` (the same card fields and `correctChoice` handling as a live `reveal`), with no time bar and no `playing` yet. The snapshot's `skipState` sets the count at once, so a newer `skipState` that arrives later (the server sends one right after the snapshot when it removes a replaced player's vote) overrides it. The own vote starts as not voted. The `restore`/`watching` action marks the song as announced, so no "n번째 곡 시작" line appears for a song whose answer is already out.
- **At the first clock estimate (`lateStart`):** if the window's local end (`min(playUntil, deadlineAt)` with the estimate's offset) is later than now, the round begins late as today (the source from the time since `startAt`, the tick) and the window opens through a new reducer action `windowOpen {index}` (sets `windowOpen`; leaves the card, the count and the own vote alone), with the same window-end timer as cycle 129. The skip button then shows for a player per cycle 130. Otherwise the round ends (`over`) without starting: the card stays, nothing plays.
- Chat is not touched: after the reveal it is ordinary chat (D-129), and the server never checks it. A pick is refused as today (no `start` yet, then `revealed`).
- If the round is dropped first (another snapshot, a `prepare`, `gameOver`), nothing of it remains.

A snapshot without `reveal` behaves exactly as today.

## Acceptance criteria (Vitest unless said otherwise)
1. Rejoin (`restore`) with a song that has `reveal` (`playUntil` 50 s ahead) and `skipState{votes: 1, needed: 2}`: the answer card with the snapshot's titles shows at once, before the clock estimate, with no source started and no time bar. After the clock estimate the source starts late with an offset equal to the time since `startAt`, the time bar counts down, `body` has `playing`, and the skip button shows not voted with the count `1/2`.
2. The same through a `watching` snapshot (late joiner): card at once, late audio and time bar after the estimate; no skip button (watcher).
3. At `playUntil` the source stops and the time bar goes; the card stays.
4. A snapshot `reveal` whose `playUntil` is already past when the clock estimate arrives: the card shows, no source is started, no time bar, no `playing`.
5. A `skipState{votes: 0, needed: 2}` for that index arriving between the snapshot and the clock estimate is the count shown after the estimate (`0/2`), not the snapshot's.
6. No "n번째 곡 시작" chat line appears for the snapshot's song, in the window or in case 4.
7. A choice-room snapshot with `reveal.correctChoice` marks the correct tile as a live reveal does, and a tile click sends no `pick`.
8. A snapshot without `reveal` behaves as today; every existing test passes unchanged.
9. `npm test` and `npm run lint` pass from `frontend/`.
10. Browser check (lead), with the local server started with `SONGRUSH_GAME_PLAYTOLIMIT=true` (the default is still off until B-037): two players, a song answered early; one player reloads in the window, sees the card, hears the music on, and its clock agrees with the other player's within a second.

## Test list
- New `frontend/src/App.windowRestore.test.jsx`: criteria 1 to 7. Models: `App.reconnect.test.jsx` / `App.reload.test.jsx` (rejoin snapshot, ping/pong clock estimate), `App.window.test.jsx` and `App.windowSkip.test.jsx` (window helpers, fake timers and clock), the watcher test in `App.windowSkip.test.jsx`.

## File ownership
- Test writer: `frontend/src/App.windowRestore.test.jsx`.
- Implementer: `frontend/src/game/session.js`, `frontend/src/game/state.js`, `frontend/src/components/GameScreen.jsx` if needed. No CSS.

## Out of scope
- Server changes, turning the switch on (B-037), the follow-ups of cycles 128 to 130.

## Spec review
One round. One High, three Medium and a Nit, all taken; the design was rewritten.
- High: the draft refused chat until the clock estimate. After the reveal chat is ordinary chat (D-129, design 125) and the server never checks it, and the client sends `chat`, not `guess`, so the criterion tested nothing. Chat is left alone; picks are already refused.
- Medium: re-applying the snapshot's `skipState` after the reveal would overwrite the newer `skipState` the server sends when it removes a replaced player's vote right after the snapshot. The count is applied when the snapshot arrives and never re-applied (criterion 5).
- Medium: `lateStart` dispatches `start`, and the reducer would post "n번째 곡 시작" for a song whose answer is out (even one whose music is over). `restore`/`watching` mark the song announced (criterion 6).
- Medium: the card needs no clock, so it shows from the snapshot at once; only the window waits for the estimate, through a `windowOpen` action that keeps the card and the count.
- Nit: criterion 10 names the switch.
- Checked by the critic: `beginStart` sets `r.start` before the window is computed; a window keeps `timer` and `started`; the snapshot's `reveal` keys match what a live `reveal` carries; the own vote is right as not voted (the server clears it on every rejoin path); playback starts from the time since `startAt` and ends at `min(playUntil, deadlineAt)` as a live window does.

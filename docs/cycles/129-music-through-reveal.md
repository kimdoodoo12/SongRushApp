# 129 music-through-reveal (F-035, from S-008 design 125, D-129, issue #44 request 1)

## Goal
When the server opens a reveal window (`reveal` with `playUntil` later than now), the web client keeps the music, the clock and the spinning disc going until `playUntil`, with the reveal card shown.

## Background
- Cycles 126 to 128 built the server side behind `songrush.game.play-to-limit` (default off). This is step 4 of 7; the client must handle the window before B-037 turns the switch on.
- Today `onReveal` (frontend/src/game/session.js:668-693) stops the 250 ms tick and the audio source, sets `round.revealed`, and the reducer (state.js:457-466) clears `timer` and `started`. `round.revealed` also gates late audio, the clock correction and the late start (session.js:282, 291, 388, 411, 521, 535) and pick/skip (988, 996).
- `onPrepare` (session.js:459-497) never stops the old round's source or tick, and `gameOver` (723-735) does not stop the source; today the reveal already did both. With a window they are still live.
- Design 125 client section (docs/design/125-play-to-limit.md:48-53).

## Design
`playUntil` is server epoch ms. It is "later than now" when `playUntil - offsetMs > now()`, with `offsetMs` the round's `start.offsetMs`. A `reveal` opens a **window** only when the round has a `start` (the song began for this client) and `playUntil` is later than now; any other `reveal` (no `playUntil`, at or before now, or no `start` yet) behaves exactly as today, and then a late start can no longer begin the song (`over` is set). The window ends at `min(playUntil, deadlineAt)` local time.

**State (reducer).** The `reveal` action gains `window: true` when a window opens; the reducer then keeps `timer` and `started`, and sets a new `windowOpen: true`. A new action `windowEnd {index}` clears `timer`, `started` and `windowOpen`, and is ignored when its `index` is not the current round's (a late timer must never touch the next song). `prepare` and `gameOver` clear `windowOpen`. `isAudible` and the `playing` body class accept a revealed round while `windowOpen`. The skip button (`canSkip`, GameScreen.jsx:428) is not shown while `reveal !== null` (F-036 brings it back for the window).

**The previous round.** When a `prepare` replaces `round`, the old round's source and its window-end timer move to a separate reference (`prev`). `endHold`, `beginStart`, a `prepare` without hold, `gameOver`, `dropRound` and the window-end timer all stop `prev`'s source and clear it. A decode of the old round that resolves after its `prepare` is dropped, as today.

**Round flags.** `revealed` (the answer is out: no pick, no skip from this item's point of view, the hold logic at :463) and `over` (the music and clock are finished). Late audio, the decode result, choice preload, the clock correction and the late start (the lines listed above) check `over`. `dropRound` sets both. A reveal without a window sets both, as today.

**In a window.** `onReveal` sets `revealed` only, keeps the audio source and the tick, and the screen shows the card while the time bar keeps counting down to `playUntil` and the disc keeps spinning (`isAudible` and the `playing` body class allow a revealed round while the window is open). A buffer that finishes decoding during the window starts late, at the right offset, as during the song. At `playUntil` the client stops the tick and the source and sets `over`; the time bar and the spin stop, the card stays.

**Leaving a window early.**
- A `prepare` without a positive `holdMs` stops the previous round's source and tick at once.
- A held `prepare` (positive `holdMs`) leaves them running; they stop when the hold ends or when the new song starts, whichever is first, and at `playUntil` if that comes before.
- `gameOver` stops the source and the tick.
These stops apply in all cases (they are no-ops when the reveal already stopped everything).

Pick and skip still refuse once `revealed` (the skip button in the window is F-036).

## Acceptance criteria (Vitest, unless said otherwise)
1. A reveal whose `playUntil` is 60 s ahead, during a playing song: `source.stop` is not called by the reveal (its only call is the scheduled `stop(stopAt)` from playback, if any); the time bar (`role="progressbar"`) still renders and keeps decreasing over ticks; the reveal card shows; `body` keeps the `playing` class.
2. A decode that resolves after such a reveal (before `playUntil`) calls `source.start` with a late offset.
3. At `playUntil` (clock and timers advanced past it): the tick stops (the time bar stops changing or is gone), `body` loses `playing`, the source has been stopped, the card is still shown.
4. A `prepare` for the next index without `holdMs` during the window calls `stop` on the old source at once and the old time bar no longer counts.
5. A `prepare` with `holdMs` during the window does not stop the old source; it is stopped when the hold ends (`holdMs` later), or earlier when the next `start` arrives.
6. `gameOver` during the window stops the source.
7. A reveal without `playUntil`, and one with `playUntil` at or before now, behave as today: every existing test passes unchanged, and in a new test such a reveal stops the source at once and clears the time bar.
   - A rejoin snapshot (`restore`) during a held window stops the old source.
8. A pick or a skip vote after a window reveal sends nothing, and no skip button is shown after any reveal.
8a. A reveal with a future `playUntil` that arrives before this client's `start` (a round still in `prepare`) opens no window: no time bar, no `playing` class, and a `start` for that index arriving afterwards does not start the audio.
8b. A `windowEnd` timer that fires after the next song's `start` leaves the new song's time bar and `playing` class alone.
9. `npm test` and `npm run lint` pass from `frontend/`.
10. Browser check (lead, recorded below): local backend with `SONGRUSH_GAME_PLAYTOLIMIT=true`, two players, a song answered early keeps playing with the card shown and the clock counting, and the next song starts when the music ends.

## Test list
- New `frontend/src/App.window.test.jsx`: criteria 1 to 8. Models: `App.flow.test.jsx` (`startMsg`, `revealMsg`, deferred decode at :299, stop calls at :246), `App.prefetch.test.jsx` (held prepare, `advance`, :318-:375, :444).

## File ownership
- Test writer: `frontend/src/App.window.test.jsx`.
- Implementer: `frontend/src/game/session.js`, `frontend/src/game/state.js`, `frontend/src/components/GameScreen.jsx` (only where the playing/audible state is read). Never `room.css` (D-124).

## Out of scope
- The skip button in the window (F-036), a reload or late join in the window (F-037), turning the switch on (B-037), any server change.

## Spec review
One round. No Blocker; two High and two Medium, all taken.
- High: after a held `prepare` the old round's source had no reference left (`round = r`, session.js:466), so nothing could stop the old song. The design now keeps it in `prev`, stopped by every exit, and criterion 5 adds a rejoin during a held window.
- High: keeping `started` true for the spinning disc would bring back the skip button (GameScreen.jsx:428), which is F-036's. The window gets its own `windowOpen` flag and the skip button stays hidden after a reveal (criterion 8).
- Medium: the reducer API was unnamed; it is now `reveal {window: true}`, `windowOpen`, and `windowEnd {index}` that ignores another index (criterion 8b).
- Medium: a reveal before this client's `start` had no time basis; it opens no window (criterion 8a).
- Checked by the critic: no overlap with the next song beyond clock skew (the new song needs its `start`; the hold ends at the window's end); the time bar keeps its meaning since `playUntil` equals `deadlineAt` when decided; the fakes can drive every criterion.

# 130 window-skip-button (F-036, from S-008 design 125, D-129, issue #44 request 1)

## Goal
While the music plays on after the reveal (a reveal window, cycle 129), the skip button and its count stay on screen, so players can vote to move on; the server already accepts these votes (cycle 127).

## Background
- Cycle 127 (B-035): with the switch on, when a window opens the server sends `skipState{index, votes: 0, needed}` right after `reveal`, accepts `skipVote` for that index while `now < playUntil` and the next `prepare` has not gone out, and a passed vote moves on at once (the next `prepare` without hold, or `gameOver`).
- Cycle 129 (F-035): the client keeps the music, clock and disc in a window (`windowOpen` in the reducer), and hides the skip button after any reveal (`canSkip`, GameScreen.jsx:430, requires `reveal === null`). `skipVote()` (session.js:1083) refuses once `round.revealed`.
- The own vote is counted from clicks and tagged refusals (`skipsSent`, `skipsRefused`, session.js:170-174, 355-365) and reset with the song.

## Design
All of this only in a window (a `reveal` that opened one, cycle 129). Any other reveal: as today (no button).

- **Shown:** the skip button and the count are shown for a player (not a watcher) from the window reveal until the window ends (`playUntil`, or earlier when the round is over), and not while a held `prepare` is pending. A held `prepare` (positive `holdMs`) hides them at once, although the old music plays on until the hold ends; the server ignores votes after that `prepare`.
- **The own vote is reset at the window reveal:** a vote cast before the reveal does not count after it (the server cleared it), so the button shows as not voted. The count shows the `skipState` that follows the reveal.
- **A click** sends `skipVote{index}` for the revealed song's index and toggles the own vote as during the song (same rate-limit handling).
- **`skipState`** for that index updates the count in the window as during the song.
- A `prepare` without hold, `gameOver` and the window's end remove the button (they end the window).

**Where.**
- Session: `skipVote()` refuses on `round.over` instead of `round.revealed` (with the existing watcher and no-`start` checks). `over` is set by a reveal without a window, by the window's end and by `dropRound`, so no second flag is needed there. During a hold `round` is already the next round with no `start`, so `skipVote()` sends nothing.
- Session: `onReveal`, when it opens a window, calls `resetSkips()` and dispatches `ownSkip` (not voted). A late `rate-limited` refusal after the reset is already ignored (`skipsRefused < skipsSent`, session.js:389).
- Reducer: a new action `held {index}`, dispatched by `onPrepare` when it holds, sets `skipClosed: true`; `prepare`, `restore`, `watching` and `gameOver` clear it. It does not touch `timer`, `started` or `windowOpen` (the clock and disc go on through the hold, cycle 129).
- Screen: `canSkip` = a room, the game screen, `started`, not watching, and (`reveal === null` or (`windowOpen` and not `skipClosed`)).

## Acceptance criteria (Vitest unless said otherwise)
1. After a window reveal, the skip button is shown; clicking it sends `skipVote` with the revealed song's index, and the button shows as voted.
2. A `skipState{index, votes: 1, needed: 2}` in the window shows that count; a `skipState` for another index does not change it.
3. A vote made before the reveal shows as not voted right after the window reveal, and the next click sends one `skipVote` and shows as voted.
4. No skip button: after `playUntil` (the window ended); after a held `prepare` arrives in the window (while the old music still plays); for a watcher in a window; after a reveal without a window (no `playUntil`, or one at or before now).
5. A `prepare` without hold in the window removes the button.
6. Every existing test passes unchanged, except `App.window.test.jsx:453-464` (cycle 129, criterion 8), which pinned that no skip button shows in a window: its skip assertions are removed (the pick assertions stay) and the window case moves to the new file.
7. `npm test` and `npm run lint` pass from `frontend/`.
8. Browser check (lead): two players, a song answered early, both vote skip in the window; the next song starts at once (well before the old song's end).

## Test list
- New `frontend/src/App.windowSkip.test.jsx`: criteria 1 to 5.
- `frontend/src/App.window.test.jsx`: only the skip assertions of the test at 453-464 are removed, as criterion 6 says. Models: `App.window.test.jsx` (window reveal, held prepare, fake timers and clock), `App.skipVote.test.jsx` (the button, `skipVote`, `skipState`, voted look).

## File ownership
- Test writer: `frontend/src/App.windowSkip.test.jsx`, and the declared change in `App.window.test.jsx`.
- Implementer: `frontend/src/game/session.js`, `frontend/src/game/state.js`, `frontend/src/components/GameScreen.jsx`. No CSS (the voted look exists).

## Out of scope
- A reload or late join in the window (F-037), turning the switch on (B-037), any server change, wording of the reveal card.

## Spec review
One round. One Blocker, one High, two Medium and a Nit, all taken.
- Blocker: cycle 129's `App.window.test.jsx:453-464` pins that no skip button shows in a window (a choice room keeps the skip row), so this item turns it red. Its skip assertions are removed in the test step and criterion 6 names the exception.
- High: during a held `prepare` the reducer never sees the `prepare` (it is dispatched when the hold ends), so nothing could hide the button "at once". A `held {index}` action sets `skipClosed`; it leaves the clock and disc alone.
- Medium: the session gate (`over` instead of `revealed`) and the owner of the vote reset (`onReveal` calls `resetSkips()`) are now named.
- Nit: the rejoin line (F-037's) and the repeat of the next song's button were dropped.
- Checked by the critic: during a hold `skipVote()` cannot send for the wrong index (the new round has no `start`); a late refusal after the reset is ignored; `skipState` arrives after `reveal`; the fakes can drive every criterion.

## Result
- Tests first: commit `9b6ab0f`. `App.windowSkip.test.jsx` (new, 13 cases) and the declared change in `App.window.test.jsx` (three skip assertions removed from the cycle 129 pick test, its title and the file's header line updated). Red, run by the lead: 853 tests, 9 failed, all in the new file, each an assertion that the skip button is missing after a window reveal; the 4 "no button" cases pass on `dev`.
- Implementation: commit `9dc53ca`, first attempt (+29, -5): `skipVote()` refuses on `over`; a window reveal resets the own vote; a held `prepare` dispatches `held`, which sets `skipClosed`; `canSkip` allows an open window that is not closed.
- Green, run by the verifier: `npm test` 55 files, 853 tests passed; lint exit 0; build exit 0. The locked tests are unchanged since `9b6ab0f`; against `dev`, `App.window.test.jsx` differs only as criterion 6 allows. Criteria 1 to 7 confirmed.
- Review of the diff: no Blocker or High. Checked: no dead button (every path that ends a window also tells the reducer); `skipClosed` is cleared by every route back to a playing song; the switch-off path is the old one. One Medium went to Follow-ups: a vote sent within about one round trip before the deciding answer reaches the server after its reset, is counted in the window, and the client (which reset the own vote at the reveal) then shows the opposite of the server's record for that player. Fixing it needs the vote to say which phase it was meant for (a server change).
- Criterion 8, by the lead in Chromium against the local server (switch on, placeholder songs), two players: after the early answer both screens showed the skip button and the count; the host's click showed `스킵 취소` and `스킵 1/2`; the guest's click passed the vote and the next song played 2.8 s later (8.2 s after the reveal, instead of about 70 s). Console: only the placeholder covers' 404s.
- The switch is still off by default.

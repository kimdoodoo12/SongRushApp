# 079 reload-rejoin (bug, owner 2026-11-07: "새로고침을 했을 때 웹소캣이 끊겨서 방에서 나가지는 것 같아")

## Problem
A page reload drops the player out of the room.
- The client stores `{token, code}` in `sessionStorage['songrush.rejoin']` on every `token` frame (cycle 049).
- Nothing reads that entry after a reload, so the app always starts on the start screen. Cycle 049 had deferred this (`docs/cycles/049-auto-reconnect.md:19`; BACKLOG follow-up, line 435).
- Reproduced on the live site with Playwright: the entry was set, the page was reloaded, no WebSocket opened, and the start screen showed.

The server keeps a dropped seat for 30 s (`Lobby.GRACE`), so a reload inside that window can rejoin.

## Behaviour
1. **On mount, read the entry.** App reads `sessionStorage['songrush.rejoin']`, wrapped in try/catch. A missing entry, unparsable JSON, or a token or code that is not a string means none.
2. **Only a real reload rejoins.** The page's navigation type must be `reload`, from `performance.getEntriesByType('navigation')[0]?.type`.
   - A duplicated tab copies `sessionStorage` but does not count as a reload, so it does not steal the original tab's seat (BACKLOG 435).
   - For any other navigation type, the entry is cleared and the start screen shows.
   - **Test seam:** App takes an optional prop `navigationType`. When it is undefined, the value comes from the Performance API, falling back to `'navigate'`.
3. **Rejoin path.**
   - The app opens a session in a new mode `rejoin` with that token and code. The screen shows the connecting/reconnecting state, using the same `RECONNECTING_TEXT` banner.
   - On open, the first frame sent is `{type:"rejoin", token}`.
   - From there it is exactly the cycle 049 rejoin socket: frames before the snapshot are filtered, then `joined` gives `rejoined`, the `snapshot` gives `restore` (lobby or game), the roster follows, and a `token` frame refreshes the stored entry.
   - The clock sync burst runs after `joined`, as today.
   - The audio context is created at mount time without a user gesture, so it may be suspended. The existing "탭하여 소리 켜기" prompt (soundBlocked) covers this.
4. **Failure.**
   - If the server answers `error{code:"rejoin-failed"}`, or the socket closes before `joined` (no retry ladder on this first attempt):
     - the entry is cleared;
     - the session closes;
     - the start screen shows the existing `rejoin-failed` text ('다시 연결하지 못했습니다. 방에 다시 참가해 주세요.') as its error.
   - A 4001 close (replaced) ends the session the same way, but shows the existing replaced text.
5. **Leaving is unchanged.** `나가기` and every intentional close still clear the entry (cycle 049).

## Acceptance (Vitest, `frontend/src/App.reload.test.jsx`, new)
Each test seeds `sessionStorage` in `beforeEach` and renders App with fakes and `navigationType`.
1. **Lobby rejoin.** An entry and `'reload'`: exactly one socket opens, and its first sent frame is `{type:'rejoin', token:'T1'}`. The banner shows. After `joined`, `snapshot` (lobby) and `roster`, the lobby shows the room code and the seats, and the banner is gone.
2. **Game rejoin.** An entry, `'reload'` and a playing snapshot: the game stage shows (the round text and the time bar).
3. **`rejoin-failed`.** The start screen shows the rejoin-failed text, the entry is removed, and the socket is closed.
4. **Close before `joined`.** The same outcome as test 3.
5. **Not a reload.** An entry with `'navigate'`, and separately with `'back_forward'`: no socket opens, the entry is removed, and the start screen shows with no error.
6. **No entry.** `'reload'` with no entry: no socket and the start screen. Malformed JSON behaves the same and removes the entry.
7. **Blocked storage.** When `sessionStorage` throws, the app still renders the start screen.
8. **Token refresh.** After a successful rejoin, a new `token` frame updates the entry; `나가기` clears it.

All earlier tests pass unchanged. `npm test`, `npm run lint` and `npm run build` exit 0.

## Live check (lead, after deploy)
On the live site, Playwright creates a room, reloads the page, and lands in the same room. The same check is run during a game.

## File ownership
- **Test writer:** `frontend/src/App.reload.test.jsx`.
- **Implementer:** `frontend/src/App.jsx`, `frontend/src/game/session.js`, `frontend/src/game/state.js` (if needed).
EOF
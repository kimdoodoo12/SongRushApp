# 107 prefetch-on-reveal (D-119)

## Decision (owner, 2026-11-10)
"그러면 이제 성능 개선을 위해 이번에는 후보 미리 받기, 정답공개 때 미리 받기를 하는 것으로 성능 개선하자. 이것도 다 측정하고 형식지켜서 해라"

- **Step 2 of the performance report.** The next song's `prepare` goes out when the 5 s reveal **starts**, not when it ends. Clients download during the reveal.
- **Fixed times stay.** The reveal stays 5 s for everyone, and the song still starts only after the reveal ends, all connected players are ready (or the timeout fires), and the 3 s LEAD has passed.
- **Switch:** `songrush.game.prefetch-on-reveal` (env `SONGRUSH_GAME_PREFETCHONREVEAL`). Absent or false means today's behaviour, exactly. This lets the report measure off and on the same day.
- **Fairness:** a modified client can hear the next song up to the reveal plus the lead (about 5–8 s) early. This is inside D-025 and D-067.
- **Not in scope:** prefetching during the previous song, a shorter LEAD, and a "곧 시작" label. Those are candidates for later steps.

## Backend (`Room`, `Lobby`, `GameSocketHandler`, config)
1. **Config.**
   - A boolean property `songrush.game.prefetch-on-reveal`, default false, passed GameSocketHandler → Lobby → Room through a new constructor parameter or settings object.
   - The existing constructors keep the old behaviour, so existing tests compile unchanged.
2. **Early mode, a song that is not the last.** Inside `endSong`, after `reveal` and `scores` are sent:
   - Clear `picks` and `rankedIds` as `afterReveal` does today. Then do everything `prepare(songIndex+1)` does today: promote watchers, clear `ready` and `votes`, issue the token, set `audioUrl`, `phase=PREPARE`, `songIndex=next`, send `prepare`, including `choices` in a choice room.
   - The `prepare` message gains one key, `holdMs` = `REVEAL_PAUSE` in ms, **only in early mode**. Default-mode messages keep exactly today's keys.
   - Mark `revealing = true`, then schedule `REVEAL_PAUSE` → `revealOver()`.
   - `revealOver()`:
     - set `revealing = false`;
     - if every connected player is ready, call `startSong()` (`startAt = now + LEAD`);
     - otherwise schedule `PREPARE_TIMEOUT` → `startSong`. The maximum wait after the reveal is therefore the same 25 s as today.
   - `ready` for the new index is accepted while `revealing`. `startIfAllReady()` does nothing while `revealing`.
   - **The last song** behaves as today: reveal, then after `REVEAL_PAUSE` `gameOver`. No early prepare.
3. **Chat while `revealing`** is treated as in REVEAL today: broadcast to everyone, never answer-checked. It must not fall into the PREPARE "too early" path.
4. **Skip votes** are ignored while `revealing`, as in REVEAL and PREPARE today.
5. **Snapshot and reconnect while `revealing`.**
   - `currentSong` returns the prepared next song, as in PREPARE: `{index,total,audioUrl,choices…}`.
   - The snapshot also carries `holdMs` = the remaining reveal time, so the client keeps waiting and then shows the prepared round.
6. **Pause and resume.**
   - A pause while `revealing` cancels the reveal timer.
   - Resume treats the reveal as over: re-send `prepare` for the same index without `holdMs` and run the normal PREPARE flow. This mirrors today's "resume from REVEAL prepares at once".
7. **Audio token TTL** covers the extra reveal time: `TOKEN_TTL` gains `REVEAL_PAUSE`.
8. **Secrecy is unchanged.**
   - `prepare` still carries nothing that identifies the song or marks a correct choice.
   - The reveal for song N is sent before song N+1's prepare.

## Frontend (`game/state.js`, `game/session.js`, components)
9. **A `prepare` with `holdMs > 0` while a reveal is shown** starts loading at once (download, decode, images, `ready` when done), but the screen keeps the reveal card, its `correctChoice` marks and the revealed song's choice grid for `holdMs`. After `holdMs` (local timer from receipt) the prepared round is shown exactly as a normal `prepare` shows it today:
   - the round label;
   - the loading line or loaded state;
   - the disc;
   - the new choices;
   - the reveal cleared.

   If `start` for the new index arrives earlier, it ends the hold immediately.
10. **A `prepare` without `holdMs`** (default mode) behaves exactly as today.
11. **Snapshot with `holdMs`** (reconnect during the reveal): there is no reveal card to keep. Load at once and show the loading state.

## Perf tooling
12. **`perf/run.py` records `startAt` and `serverTime` from `start`.**
    - Each song row gains `betweenSongs_ms` for song n ≥ 2: the previous song's `reveal` receipt → this song's audible moment.
    - The audible moment is the `start` receipt + (`startAt` − `serverTime`).
    - Song 1 has `None`.
    - It also gains `prepareAfterReveal_ms` = `prepare_t` − the previous song's `reveal_t`, for songs 2..N. This shows whether the early mode is on: about 0 when on, about 5000 when off.
13. **`perflib.summarize`** adds per format and `all`:
    - the median of `betweenSongs_ms`;
    - a count of songs whose audio was ready before the reveal ended, `readyBeforeRevealEnd`: `ready_t − prev reveal_t ≤ 5000`, songs 2..N.
14. **`perf/compare.py` and `comparelib.py`** gain a panel or option for "곡 사이 대기 (초)": the median of `betweenSongs_ms`, before vs after.
15. **`perf/devtools_shot.py`** gains a view `between`:
    - In a chat game, after song 1 is audible, clear the Network log and click `스킵`.
    - Take a DevTools shot about 2 s into the reveal, while recording, so song 2's download is visible during the reveal. Then take a game shot of the reveal.
    - Wait until song 2 is audible and take a final DevTools shot.
    - Keys `between-reveal`, `between-done`, `game-between-reveal`.
    - The existing secret and bucket checks apply.

## Acceptance (tests; all offline)
- **Backend:**
  - **Default mode** is unchanged. All existing tests pass unedited.
  - **Early mode** (new test classes):
    - `prepare(N+1)` is sent right after `reveal`/`scores` of N, with `holdMs=5000`.
    - No `start` before `REVEAL_PAUSE` even if everyone is ready.
    - With everyone ready during the reveal, `start` comes exactly at reveal end, with `startAt = revealEnd + LEAD`.
    - With someone not ready, `start` comes at the earlier of all-ready or reveal end + `PREPARE_TIMEOUT`.
    - Chat during the reveal is broadcast and never scores.
    - Skip is ignored during the reveal.
    - The last song ends with `gameOver` and no prepare.
    - A reconnect during the reveal gets the next song and `holdMs`.
    - Pause and resume during the reveal re-prepare without `holdMs`.
    - Choice room: the next `prepare` has `choices` with no correct mark, and the reveal of N still carries `correctChoice`.
    - Ranked: the ranking of N is in N's reveal, and the tags are cleared for N+1.
    - Watchers are promoted at the early prepare.
    - Nothing before song N's reveal identifies song N.
  - The property binds from `songrush.game.prefetch-on-reveal=true`.
- **Frontend:**
  - `prepare` with `holdMs` keeps the reveal card and the revealed grid for `holdMs` while loading starts, so `ready` is sent before the hold ends when loading finishes.
  - After `holdMs` the new round shows.
  - A `start` during the hold ends it.
  - Without `holdMs` the old behaviour holds. Existing tests pass unedited.
- **Perf:**
  - The new metrics are computed from hand-made recorder data in tests.
  - The `between` view keys are added, as a declared change to `test_devtools_shot.py`'s key set.
- **Commands:** `.\gradlew.bat test`, `npm test`, `npm run lint`, `npm run build`, and `data/.venv/Scripts/python.exe -m pytest perf -q`.

## Measurement after deploy (lead and owner)
- The owner sets `SONGRUSH_GAME_PREFETCHONREVEAL` false, then true, restarting each time, the same day.
- Three `perf/run.py` runs each.
- `devtools_shot --views between` for both.
- Step 2 in `docs/performance.md` uses the same format as step 1. The main row is "곡 사이 대기", from the direct metric.

## File ownership
- **Backend test writer:** new files under `backend/src/test/java/com/songrush/game/` and `.../ws/`.
- **Frontend test writer:** new `frontend/src/App.prefetch.test.jsx`.
- **Perf test writer:** `perf/test_between.py` (new) and the declared key-set change in `perf/test_devtools_shot.py`.
- **Implementers:**
  - backend: `backend/src/main/java/com/songrush/game/**`, `.../ws/GameSocketHandler.java` and a new properties record;
  - frontend: `frontend/src/game/*.js` and `frontend/src/components/*.jsx`;
  - perf: `perf/run.py`, `perf/perflib.py`, `perf/compare.py`, `perf/comparelib.py`, `perf/devtools_shot.py` and `perf/README.md`.
- **Lead:** docs.

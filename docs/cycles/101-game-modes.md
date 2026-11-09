# 101 game-modes (D-112)

## Decision (owner, 2026-11-09)
"게임 모드도 늘려. ... 하나는 1등만 맞추면 끝나는 것과 다른 하나는 등수마다 점수를 다르게 주는 것"

- **Two modes,** picked by the host in the lobby next to the answer format (D-111):
  - **선착순** (First Come / 早い者勝ち) is today's rule. It is the default.
  - **순위전** (Ranked / 順位戦): every player can answer once per song, scored by server arrival order. 1st gets 3 points, 2nd gets 2, and any later correct answer gets 1.
- **A ranked song ends** when every connected room player has answered correctly (chat format) or has picked (choice format, right or wrong), at the deadline, or by a passed skip.
- **Ranked visibility.** A correct chat is not echoed to others. Everyone sees only the name line in real time. Later chat, spoilers included, is the players' business and is not filtered.
- Modes combine freely with both answer formats.

## Backend
1. **Setting.** `gameMode` is either `first` or `ranked`. It travels exactly like `answerMode` (cycle 100, A8):
   - it is an optional field of the `start` request (absent means `first`);
   - an invalid value gets `error{code:"bad-game-mode"}`, checked in the handler right after `bad-answer-mode`;
   - it is fixed in the Room for the game and echoed in `settings` as `gameMode:"ranked"` (absent for `first`);
   - `settings` is sent when the filter is non-empty, the answer mode is `choice`, or the game mode is `ranked`.
2. **First come.** Unchanged in every way, including D-094: the winner's chat is echoed to everyone after `correct`, and in a choice room a correct pick ends the song.
3. **Ranked, chat format.** A chat after `startAt`, from a connected room player (not a watcher) who has not yet answered this song correctly, that matches an accepted title:
   - The player gets rank `r` = 1 + the number of correct answers already accepted this song, and `points(r)` = 3, 2 or 1 (r ≥ 3). The points are added to their score.
   - Everyone gets `correct{index, name, rank}`. The chat text goes **only to the sender** as `chat{name, text}`, so they see their own line.
   - If every connected room player has now answered correctly, the song ends (`endSong`, with winner = the rank-1 player). Otherwise it continues.
   - A chat from a player who already answered correctly this song is never answer-checked. It is broadcast as an ordinary chat line under today's visibility rules (the players' business).
   - Wrong chats, chats before `startAt` and watcher chats behave as today.
4. **Ranked, choice format.** One pick per player per song (cycle 100).
   - A correct pick follows the same ranking and points as item 3, and everyone gets `correct{index, name, rank}`.
   - A wrong pick sends `pick-result` to the sender only and locks them out (as today).
   - When every connected room player has picked, right or wrong, the song ends with winner = the rank-1 player, or null.
   - Chat never scores (cycle 100, O4).
5. **Reveal.** In ranked mode the reveal also carries `ranking: [{name, rank, points}]` in rank order, empty when nobody answered. `winner` is the rank-1 name or null. Scores follow as today.
6. **Connection changes.** "Every connected player answered" is re-checked when a player disconnects or leaves during a song, so the song does not wait for someone who left. A reconnecting player during the song keeps their answered state (`currentSong(playerId)` adds `answered: true|false` in ranked mode).
7. **Skips** work as today. Points already given in a song stay when it is skipped.
8. **Secrecy.** Nothing before the reveal identifies the song. `correct{rank}` carries only the name and rank. In ranked chat the correct text never reaches anyone but its sender before the reveal.

## Frontend
9. **Lobby.** The host sees a game-mode picker: `role=group`, label 게임 모드 / Game mode / ゲームモード, buttons 선착순 / 순위전 (First Come / Ranked, 早い者勝ち / 順位戦). It defaults to 선착순 and is kept like the filters. The `start` request includes `gameMode:"ranked"` only for ranked. The settings line appends ` · 순위전`.
10. **Ranked in game.**
    - On each `correct{name, rank}` the chat log adds a system line `✔ <name> 정답! (<rank>등)` (en `✔ <name> got it! (#<rank>)`, ja `✔ <name> 正解！（<rank>位）`).
    - The seats mark every correct player with the 정답! tag, not only one.
    - The stage does not show the first-come "<name> 정답!" banner that ends the song. The song keeps playing until the server ends it.
    - A player who answered correctly keeps a usable chat input. In choice format, their tiles are disabled (picked).
11. **Reveal in ranked.** The reveal card lists the ranking (rank, name, +points) in place of the single winner line. When the ranking is empty it says 정답자 없음 as today.
12. **i18n** keys go in ko, en and ja (key parity).
13. **Layout.** No theme rules change. Theme box equality stays 0. A long ranking list must not change the card's box between themes; it may scroll inside a fixed box.

## Acceptance
**Backend tests** (`RoomRankedTest`, `RoomRankedChoiceTest`, `LobbyGameModeTest` and socket tests):
- **Setting.** The setting is validated, echoed in `settings`, fixed for the game, and only the host sets it.
- **Ranked chat.** Three players correct in order get ranks 1, 2 and 3 with points 3, 2 and 1. A 4th correct player gets 1 point.
  - Everyone gets `correct{index,name,rank}`, and the text goes to the sender only.
  - The song continues until all have answered, then ends, and the reveal ranking is correct.
  - A repeated correct chat from an answered player is just a broadcast chat, with no new rank or points.
  - Wrong chats are unchanged. Before `startAt`, sender only. Watchers get nothing scored.
  - A disconnect of the last unanswered player ends the song.
  - The deadline ends the song with a partial ranking. A skip keeps the points.
  - A reconnect snapshot carries `answered`.
  - Simultaneous correct answers are ranked by arrival.
- **Ranked choice.** Correct picks are ranked with the same points. The song ends when all have picked. Wrong picks lock out. Chat never scores.
- **First come** is unchanged: the existing tests pass as they are.
- **Secrecy.** No pre-reveal message other than the sender's own chat echo carries the correct text. `correct` has exactly the keys `{type,index,name,rank}` in ranked mode and `{type,index,name}` in first-come.

**Frontend tests** (`App.ranked.test.jsx`):
- The picker sends `gameMode`, and the settings line shows 순위전.
- A ranked `correct{rank}` adds the system line with the rank and does not stop the stage.
- Seats show the tag for each correct player, and chat stays enabled after you answer.
- The reveal ranking list.
- en and ja labels come from the dictionaries.

**Commands.** `.\gradlew.bat test`, `npm test`, `npm run lint` and `npm run build` exit 0.

**Lead's browser check.** Three players in ranked chat and ranked choice: ranks and points shown, the song ends when all have answered, the reveal ranking is right, and theme boxes stay equal.

## File ownership
- **Backend test writer:** new tests under `backend/src/test/java/com/songrush/game/` and `.../ws/`.
- **Backend implementer:** `backend/src/main/java/com/songrush/game/**` and `.../ws/GameSocketHandler.java`.
- **Frontend test writer:** `frontend/src/App.ranked.test.jsx`.
- **Frontend implementer:** `frontend/src/components/*.jsx`, `frontend/src/game/{state,session,filters}.js`, `frontend/src/i18n/*.json` and `frontend/src/index.css`.

## Out of scope
- Other point tables.
- Team modes.
- Per-player time bonuses.

## Amendments after spec review (round 1). These override the text above.
- **R1 (H1). Never end an empty room's song.** The all-answered check never passes with zero connected room players, the same guard as `startIfAllReady`. When the last player disconnects, the song pauses (cycle 044); it does not end.
- **R2 (H2). Answered state survives a resume.** The song's answered set and its ranking are kept like picks: cleared only when the song index changes (and at start), never by a resume's re-prepare.
- **R3 (H3). Order of frames, and placing the system line.**
  - Everyone gets `correct{...}`, and then the sender alone gets `chat{name,text}` at once, with no frame in between.
  - In ranked mode the client places the system line above only the sender's own echo. On every other client the system line is created as already placed, so that player's later chat appends below it.
- **R4 (H4). Another player's correct changes nothing on my stage.**
  - In ranked mode another player's `correct` does not show the first-come banner, does not disable my tiles or my chat, and does not stop the stage.
  - The single `correct` field that drives the banner, `canPick` and Seats is replaced in ranked mode by the list of correct names.
  - My own tiles are disabled once I have picked.
- **R5 (M1, M2). Reconnect snapshot.**
  - In ranked mode `currentSong(playerId)` adds `answered: bool` and `correct: [{name, rank}]` (names already broadcast).
  - On a rejoin snapshot the client treats a held pick as wrong only when `answered` is false, and restores the Seats tags from `correct`.
- **R6 (M3).** The all-answered check also runs when a song starts (`startSong`), so a resumed song whose connected players have all answered ends at once (still never with zero connected).
- **R7 (M4). Winner on every ending.** Every way a ranked song ends sends `winner` = the rank-1 name from the ranking (or null) and the full `ranking`, never the `endSong` argument. That covers all answered, the deadline timer, a late chat or pick at the deadline, a skip, and a disconnect.
- **R8 (M5, owner decision 2026-11-09).** Answered players may still vote to skip, the same as today.
- **R9 (Nit). Solo is first-come.** The solo `create` path is always first-come and ignores `gameMode`.
- **Added acceptance.**
  - A answered, then disconnects; B unanswered, then disconnects: no reveal is sent, and B's return replays the same song.
  - A answers, everyone leaves, A returns: A's repeated correct chat after the new `startAt` earns nothing.
  - Ranked: after `correct{B}`, a `chat{B}` on another client goes below the ✔ line.
  - After another player's ranked `correct`, my tiles are still enabled and no banner shows.
  - Deadline and skip reveals have `winner` = rank 1 and the ranking.

## Review round 2 (code review of 9e313ca). These override the text above.
- **C1 (M1).** In a ranked **choice** room the client creates its own system line as already placed: no echo follows a pick, so a later chat of mine goes below my ✔ line. The ranked chat format is unchanged; there my echo goes above my line.
- **C2 (M2).** In ranked mode, while the song's ranking is non-empty, `prepare` (including a resume's re-prepare) carries `correct: [{name, rank}]`, the names already broadcast. The client restores the Seats tags and its own answered state from it. Without this, a player who answered before everyone left would not know it after the resume, and a retyped title would be broadcast to the others.

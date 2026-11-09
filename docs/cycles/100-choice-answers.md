# 100 choice-answers (D-111)

## Decision (owner, 2026-11-09)
"객관식 모드로 표지들 여러개 주고 어떤게 맞는 애니메이션인지"

- **Answer format.** It is a room setting, not a separate game mode. The host picks **채팅** (chat, today) or **객관식** (multiple choice) in the lobby, like the theme and the filters. The default is chat.
- **Game rule unchanged.** The first correct answer by server arrival order scores 1 point and ends the song (D-011, D-016).
- **Choices are covers only,** with no titles.
- **The number of choices follows the tier:** easy 4, normal 6, hard 8 (the owner said "쉬움은 4개 이런식으로"; the lead chose 6 and 8).
- **One pick per player per song** (owner: "한번 선택하면 그만해야하는걸로"). A wrong pick locks that player out for the rest of the song.
- **Lead's choices, told to the owner:**
  - In a choice room, chat is talk only and never scores.
  - A player's pick is never shown to anyone else before the reveal.

## Backend
1. **Setting.** `answerMode`, either `chat` or `choice`. It travels like `roomTheme`: the host sends it in the lobby, it is broadcast to the room, it is included in the `settings` line and the snapshot, and the host can change it only in the lobby.
   - A non-host gets `error{code:"not-host"}`.
   - A bad value gets `error{code:"bad-answer-mode"}`.
2. **Draw.** In a choice room, only themes whose anime has a cover are drawn.
   - The `CoverMap` knows which anime have covers.
   - `not-enough-songs` and `available` count only those themes, and the `unit: "series"` rule for easy still holds.
3. **Choices per song.**
   - **Size `k`:** 4 for easy, 6 for normal or no tier, 8 for hard.
   - **Content:** the correct anime plus `k-1` distractors. The distractors come from the same tier pool, each from a different series group, none from the correct anime's group, and all with covers.
   - **Order:** shuffled. The server keeps the correct position.
   - **Fewer distractors than needed** (should not happen): use as many as exist, with a minimum of 2 choices in total. Otherwise the draw already rejected the song.
   - **When chosen:** at draw time, so a reconnect sees the same set.
4. **Wire.**
   - The `start` message to everyone carries `choices: [{coverUrl:"/cover/<id>"}, ...]` in a choice room. It carries no anime id, title or group, and nothing marks the correct one.
   - `prepare` carries nothing about the choices.
   - The reconnect snapshot of a playing song carries the same `choices` and, for that player, `picked: <position>|null` and `lockedOut: bool`.
5. **Pick.** The client sends `{type:"pick", index:<songIndex>, choice:<position>}`.
   - **Ignored (or answered with an error to the sender only), in this order:**
     - not a choice room;
     - not PLAYING;
     - before `startAt`;
     - a watcher;
     - the wrong `index`;
     - a position out of range;
     - already picked this song (`error{code:"already-picked"}`).
   - **A wrong pick** sends `pick-result{index, choice, correct:false}` to the sender only, and the player is locked out.
   - **A correct pick** is the same path as a correct chat answer: `correct{index,name}` to all, then the reveal, with no echoed text.
   - The pick consumes the chat rate-limit token.
6. **Reveal.** In a choice room the reveal also carries `correctChoice: <position>`, so clients can mark it.
7. **Chat in a choice room** never runs the answer check. Every non-blank chat line follows today's wrong-guess visibility rules (sender-only before `startAt` and for watchers, otherwise to everyone).

## Frontend
8. **Lobby.** The host sees an answer-format picker (`role=group`, label 정답 형식 / Answer format / 回答形式, buttons 채팅 / 객관식). Guests see the current value. The settings line shows it. It is translated in ko, en and ja.
9. **Stage in a choice room.**
   - **Grid.** While a song is shown, a grid of `k` cover buttons sits on the stage, below the disc.
     - 2 columns at 390 px for 4 choices, 3 for 6, 4 for 8. At 1280, 4 columns.
     - The boxes are fixed, so all choice tiles have the same size.
     - Each tile is a `<button>` whose accessible name is `보기 N` (Choice N / 選択肢N); the cover image has `alt=""`.
     - A cover that fails to load shows the empty placeholder style from cycle 098.
   - **When the buttons work.** They are disabled until the song is audible, using the cycle 087 `reached` flag, and for watchers.
   - **After a pick,** every button is disabled and the picked tile is marked. On `pick-result correct:false` it is marked wrong.
   - **At the reveal,** the `correctChoice` tile is marked correct.
   - **Chat** stays for talking.
10. **Layout.** No theme rule changes. Theme box equality stays 0 in the three languages at 1280 and 390, and there is no horizontal scroll.

## Acceptance
**Backend tests** (new `RoomChoiceTest`, `LobbyAnswerModeTest`; socket-level where needed):
- **Setting.** Only the host can set the mode, in the lobby only, and it is broadcast. Bad values are rejected.
- **Choices.**
  - `k` per tier.
  - The correct anime is always present exactly once.
  - The distractors have distinct groups, no group equals the correct one, all have covers, and all are within the tier pool.
  - The positions are shuffled: over many draws the correct one is not always at the same position.
- **Answer secrecy.** No message before the reveal carries anything that marks the correct choice: no anime id, title or group, and no `correctChoice`. `prepare` carries no choices.
- **Picks.**
  - Correct: `correct` to all, song ends, 1 point.
  - Wrong: lockout and `pick-result` to the sender only. A second pick gives `already-picked`.
  - Before `startAt`: refused.
  - Watcher: refused.
  - Wrong index: ignored.
  - Concurrent correct picks: the first by arrival wins.
- **Chat in a choice room** never scores, even with the exact title.
- **Draw.** It only uses themes with covers. `not-enough-songs` counts only those.
- **Reconnect.** The snapshot carries the same choices and the player's `picked` and `lockedOut`.

**Frontend tests** (`App.choice.test.jsx`):
- The host picker sends the mode, and guests see it.
- The grid renders `k` tiles with the cover URLs, disabled until audible.
- A pick sends `{type:"pick", index, choice}` once, then all tiles are disabled.
- A wrong result marks the tile, and the reveal marks the correct tile.
- Watchers see disabled tiles.
- en and ja labels come from the dictionaries, and key parity holds.

**Commands.** `.\gradlew.bat test`, `npm test`, `npm run lint` and `npm run build` exit 0.

**Lead's browser check.**
- Two players in a choice room. The guest picks wrong and gets locked out; the host picks correct and wins.
- Six themes × three languages at 1280 and 390: theme box differences 0, no clipping, no horizontal scroll.

## File ownership
- **Backend test writer:** new tests under `backend/src/test/java/com/songrush/game/` and `.../ws/`.
- **Backend implementer:** `backend/src/main/java/com/songrush/game/**`, `.../ws/GameSocketHandler.java` and `.../cover/CoverMap.java` (read-only helper additions only).
- **Frontend test writer:** `frontend/src/App.choice.test.jsx`.
- **Frontend implementer:**
  - `frontend/src/components/*.jsx` and a new `ChoiceGrid.jsx`;
  - `frontend/src/game/{state,session,filters}.js`;
  - `frontend/src/i18n/*.json` and `frontend/src/index.css`.

## Out of scope
- Separate scoring rules.
- Titles on choices.
- Hints.
- Solo play.

## Amendments after spec review (round 1). These override the text above.
- **A1 (H1). No already-answered series as a distractor.** Distractors never come from a series group that was the correct answer of an earlier song in this game.
- **A2 (H2). Distractors are drawn by the game's own rule.** Each distractor is the anime of a song drawn by the game's own draw rule from the same filtered pool:
  - easy picks a remaining group uniformly, then a uniform song in it (D-108);
  - normal and hard pick a uniform song among those whose group is not yet in the choice set.

  The cover shown is that song's anime's cover. This way a distractor's group frequency follows the same distribution as the answer's.
- **A3 (H3). Choices arrive in `prepare`, not `start`.**
  - `prepare` carries `choices` in a choice room. Everyone gets it at the same moment, after the reveal pause.
  - The client sends `ready` only after its audio has loaded **and** every choice image has loaded or failed. Images are preloaded with `new Image()` or similar.
  - The snapshot carries `choices` in PREPARE as well as in PLAYING.
  - The earlier lines "`prepare` carries nothing about the choices" and "`start` carries choices" are void.
- **A4 (H4). Exact refusals.** In this order:
  1. not a choice room, paused, not PLAYING, a watcher, the wrong `index` or a bad position: no frame at all;
  2. `now >= deadlineAt`: end the song with no winner first, as chat does (M2), and send no frame;
  3. before `startAt`: `error{code:"too-early", for:"pick"}`;
  4. already picked this song: `error{code:"already-picked", for:"pick"}`.

  A pick costs no chat token. The client re-enables the tiles on any `error` with `for:"pick"`. Picks and lockouts reset only when the song index changes, not on a resume's re-prepare.
- **A5 (M1). The pool and `k`.**
  - Distractors come from the filtered drawable pool (tier, years, types), restricted to anime with covers.
  - At start, a choice room needs at least 4 covered series groups in that pool, else `not-enough-songs{available:<groups>, unit:"series"}`.
  - The per-song `k` is `min(tier k, eligible groups)`, and never below 2. With the 4-group minimum and A1, a 30-song game in a small pool can shrink `k` late in the game; that is accepted.
- **A6 (M3). Everyone wrong ends the song.** When every connected Room player (watchers excluded) has picked wrong, the song ends with no winner. This is the lead's choice, told to the owner.
- **A7 (M4). The grid during the reveal.** The grid stays through the reveal, under the reveal card. `correctChoice` marks the right tile, and the player's own wrong pick stays marked. The grid box keeps one height from `prepare` to the next `prepare`.
- **A8 (M5). `answerMode` travels as a filter, not like `roomTheme`.**
  - `answerMode` is an optional field of the `start` request, validated like the filters (`bad-answer-mode`). It is fixed in the Room for the game and echoed in `settings`.
  - `settings` is sent when the filter is non-empty **or** the mode is `choice`.
  - The host picks it in the lobby next to the filters. Guests see it from `settings` when the game starts.
  - The lobby UI keeps the host's choice the way the filters are kept.
- **A9 (M6). Secrecy exception.** In a choice room, the `k` cover ids of a song's choices leave the server before the reveal, and nothing marks the correct one. CLAUDE.md's rule and the `Room.java` comment are updated to state this.
  - **Tests, allow-list:**
    - `prepare` keys are exactly today's keys plus `choices`;
    - each choice is exactly `{coverUrl}` matching `^/cover/[A-Za-z0-9_-]{1,64}$`;
    - no `correctChoice` before the reveal.
  - **Shuffle test:** with a seeded random over at least 4000 songs and k=4, each position holds the correct choice 25 ± 3 % of the time.
- **A10 (N1). Layout at 390 px.** Every `k` uses 4 columns with 3:4 tiles (`object-contain`). The browser check adds: the whole grid is visible without vertical scroll at 390×844 while a song plays, with the stage and chat input layout as is (scroll of the chat area allowed).
- **A11 (N2). No redundant fields.** `lockedOut` is dropped, because it equals `picked !== null` (a correct pick ends the song). `pick-result` is `{index, choice}` and means wrong.

## Amendments from the owner (2026-11-09). These override everything above.
"채팅에서 제목쳐도 상관없을것 같은데 ... 모두 틀리면? 그게 아니라 먼저 맞춘사람이면 뜨는거지"
- **O1.** In a choice room, chat answers work exactly as in a chat room: the answer check runs, and the first correct chat wins. Item 7 of the Backend section ("Chat in a choice room never runs the answer check") is void. A player locked out of picks can still answer by chat.
- **O2.** The everyone-wrong rule (A6) is void. A song ends only as today: by the first correct answer (a pick or a chat), a passed skip, or the deadline.

## Lead rulings after the test writers
- **L1 (A1 against A5).** When excluding already-answered groups would leave no distractor for a song, that song may use already-answered groups. This keeps at least 2 choices; A1 holds whenever it can. The draw of all songs' choices at start must never throw.
- **L2. Randomness in chat rooms.** A chat room consumes no extra randomness, so the seeded draws of existing tests keep their order.
- **L3. Contract.**
  - Positions are 0-based.
  - The reconnect snapshot carries `song.choices` and `song.picked` (`Room.currentSong(playerId)`); watchers get `choices` with `picked` null.
  - `answerMode` is omitted for chat.
  - The API is `AnswerMode {CHAT, CHOICE}`, `Room.start(int, DrawFilter, AnswerMode)`, `Room.pick(String,int,int)` and `Lobby.start(..., AnswerMode)` / `Lobby.pick(...)`, as listed in the backend tests' header comments.

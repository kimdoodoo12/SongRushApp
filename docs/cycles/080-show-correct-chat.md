# 080 show-correct-chat (D-094)

## Decision (owner, 2026-11-07)
"어차피 이거 한명이 맞추면 바로 음악이 끝나는데 정답을 숨길 이유가 없지. 그러니까 채팅도 보이게 해도 돼. 그리고 채팅에 알림줄도 추가해야지."

The winning guess is no longer hidden. The first correct answer ends the song at once and the reveal follows, so showing the text gives nobody an advantage. D-015's "a correct message is hidden" is replaced.

These stay as they are:
- Chat before `startAt` or during prepare goes only to the sender.
- A watcher's guesses go only to the sender until the reveal. This hides whether a guess matched before the round.
- Only the first correct answer scores, by server arrival order.

## Server (`game/Room.java`)
On the first correct answer in PLAYING (`now >= startAt`, the sender not a watcher), the order is:
1. `correct{index, name}` to all. The round is decided.
2. Then `chat{name, text}` to all, carrying the winner's original text.
3. Then `endSong` as today: reveal, scores and so on.

The answer text therefore never reaches anyone before the round is over: `correct` comes first. Nothing else changes.

## Client
- The `correct` frame adds a **system line** to the chat log: `✔ <name> 정답!`.
  - It is a log entry with a `kind: 'system'` field. It is styled with the "good" colour and has no name colour marker.
  - Its text must not match the stage banner's text node, because locked tests use `getByText('<name> 정답!')` on the banner. Use the visible text `✔ <name> 정답!` as one text node, or keep the check mark in a separate `aria-hidden` span. Either way the banner's `getByText` must still find exactly one element.
- The winner's `chat` frame, which arrives right after `correct`, is shown as a normal chat line **above** the system line. The log reads `민수: 진격의 거인` and then `✔ 민수 정답!`.
  - Rule: a `chat` from the same name as the last `correct`, arriving while that system line is the newest entry, is inserted just before it.
  - In every other case lines append in arrival order.
- The winner's bubble on their seat shows their text like any chat line.

## Acceptance
**Backend.** These are declared rewrites of locked tests that pinned "the correct guess is never echoed".
1. **Order and content.** A correct guess during PLAYING sends, to every connected member and in this order, `correct`, then `chat{name, text}` with the original text, then the reveal frames. Each test that asserted no echo is changed to assert this order; the test writer lists them.
2. **Unchanged.** A correct guess before `startAt`, and a watcher's correct guess, still go to the sender only. A second correct guess after the song ended is a normal chat line to all.

**Frontend** (`App.chatcorrect.test.jsx`, new, plus any declared rewrite):
3. **Order in the log.** After `correct{name:'민수'}` and then `chat{name:'민수', text:'진격의 거인'}`, the chat log lines in order end with `민수: 진격의 거인`, then `✔ 민수 정답!`.
4. **Banner.** The stage banner `민수 정답!` is still found by `getByText` exactly once.
5. **Wrong guesses.** They append normally, and a system line is never added for a chat.
6. **Other name.** A `chat` from another name after `correct` is appended after the system line.

`.\gradlew.bat test`, `npm test`, `npm run lint` and `npm run build` exit 0.

## File ownership
- **Test writers:** backend tests under `game/` and `ws/` (declared rewrite), and `frontend/src/App.chatcorrect.test.jsx` plus any declared frontend rewrite.
- **Implementers:** `backend/.../game/Room.java`; `frontend/src/game/state.js`, `frontend/src/game/session.js`, `frontend/src/components/GameScreen.jsx`, `frontend/src/index.css`.

## Clarifications after the test writers
- Only the **first** chat from the winner after `correct` goes above the system line. Every later line appends.
- System entries carry no `name` that `Seats` could pick up, or `Seats` skips `kind: 'system'`, so a bubble never shows the system text. `frontend/src/components/Seats.jsx` is added to the frontend implementer's files.
- `RoomTest.nothingSentBeforeTheRevealIdentifiesTheSong` is kept unchanged. The verifier must confirm it still passes once the winner's text is echoed.

## Result
- **Commits.**
  - Tests: `e79271b`, a declared backend rewrite plus a new frontend test.
  - Implementation: `f53af94`.
  - Contrast fix: attempt 2.
- **Verifier at `f53af94`.**
  - Backend: 441 tests, 0 failures, including `RoomCorrectChatTest` (7) and `RoomTest.nothingSentBeforeTheRevealIdentifiesTheSong`.
  - Frontend: 310 tests passed; lint and build exit 0.
  - The locked-test diff is empty.
  - After attempt 2, the lead re-ran `npm test`, and it passed.
- **Declared backend rewrites.** The frame order becomes `correct, chat, reveal, scores` in:
  - `RoomTest.correctAnswerAfterStartAtEndsTheSongAtOnceAndScores`;
  - the two winner scenes in `RoomLateJoinTest`;
  - `RoomRoundsTest.aCorrectAnswerWithoutATokenDoesNotScoreAndTheSongKeepsPlaying`;
  - `SoloSocketTest.soloGameOfFiveSongs`, where the leak window now ends before `correct`.
- **Review.**
  - **High H1, fixed in attempt 2.** The system line text failed WCAG AA on light themes. A new colour token, `--good-text`, now reaches at least 4.5:1 on every theme: play 5.39, sakura 4.95–5.01, neon 14.2, chalk 7.27, star 11.1–13.0, arcade 13.2.
  - **Medium M2, fixed.** The CLAUDE.md product rule now states D-094. Only that line was committed; an unrelated uncommitted edit to CLAUDE.md by someone else was left as it was.
  - **Mediums M1 and M3** go to Follow-ups.
- **Process note.** During this cycle, Stop hooks started backend Gradle runs at the same time as the implementer's run and caused `EOFException`. The implementer re-ran alone after polling until no other Gradle process was left.

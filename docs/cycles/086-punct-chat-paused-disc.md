# 086 punct-chat-paused-disc (D-100, D-101)

## Decisions (owner, 2026-11-08)
- **D-100.** "`, . / ?` 이런것들만 쳤을 때 채팅에 보이지 않는 현상". A chat whose text is only punctuation or symbols (for example `?`, `...`, `!!`, or an emoji) is shown as normal chat. It never scores. An empty chat or one of only spaces is still ignored.
- **D-101.** "음원 불러오는중일 때 ... 그림들은 계속 유지하되, 애니메이션이 멈췄으면". While the song's audio is loading (and when loading failed), the record and the equalizer stay visible but stand still. They move again once the audio plays.

## Backend (`game/Room.java`)
1. **Ignored:** `text.strip().isEmpty()` (empty, or spaces only). This replaces "normalizes to empty".
2. **Normalizes to empty but not blank:** the text is a chat line that never matches an answer. It follows every existing chat rule:
   - the rate limit and `too-long`;
   - sender-only before `startAt` or in prepare, and sender-only for watchers;
   - otherwise to everyone;
   - after the reveal or in the lobby, to everyone.
3. The answer check runs only when the normalized text is not empty. Nothing else changes.

## Frontend
4. **Pausing.** The disc area (record plus equalizer) gets a pause flag while `load` is `loading` or `failed`: `data-paused="true"` on `.disc-area`, otherwise the attribute is absent.
   - CSS stops every animation in it with `animation-play-state: paused`. That covers the record, `.record::before`/`::after` and `.eq span`. The rule must win over the theme rules: arcade sets the record's timing function, and the base `animation` shorthand resets the play state, so the pause rule needs equal or higher specificity placed after them, or `!important`.
   - Boxes do not change: the themeLayout test stays green, and theme box equality stays 0.

## Acceptance
**Backend.**
- **Declared rewrite.** `RoomTest.chatThatNormalizesToEmptyIsIgnored` becomes two tests:
  - `blankChatIsIgnored`: `""` and `"   "` send nothing.
  - `punctuationOnlyChatIsShownAndNeverScores`: `"!? - ..."` after `startAt` goes to everyone as `chat{name, text}` with the original text. It sends no `correct`, and the phase stays PLAYING.
- **Visibility.** A punctuation-only chat before `startAt` goes to the sender only. A watcher's goes to the watcher only.

**Frontend** (`App.loadingdisc.test.jsx`, new):
- After `start`, while the audio fetch or decode is pending (`load` is `loading`), `.disc-area` has `data-paused="true"`.
- After the decode resolves and the source starts, the attribute is absent.
- A failed load keeps `data-paused="true"`.

**Commands.** `.\gradlew.bat test`, `npm test`, `npm run lint` and `npm run build` exit 0.

**Lead's browser check.**
- Audio responses are delayed with a Playwright route. During the delay, the record's pixel change over 1 s is below 1 %, in all six themes.
- After the audio arrives, it is at least 5 %, using the `spincheck` method.
- A chat of `?` from one player appears on the other player's screen.

## File ownership
- **Backend test writer:** `backend/src/test/java/com/songrush/game/RoomTest.java` (the declared test) and `RoomPunctChatTest.java` (new).
- **Backend implementer:** `backend/src/main/java/com/songrush/game/Room.java`.
- **Frontend test writer:** `frontend/src/App.loadingdisc.test.jsx`.
- **Frontend implementer:** `frontend/src/components/GameScreen.jsx` and `frontend/src/index.css`.

## Result
- **Commits.**
  - Tests: `67ca6e5`. Review addition: `45d192f`.
  - Implementation: `604fee1`.
  - Attempt 2: `66bcee5`.
- **Verifier at `66bcee5`.**
  - Backend: 600 tests, 0 failures, 1 skipped (the POSIX-only store test). `RoomPunctChatTest`: 9.
  - Frontend: 395 tests; lint and build exit 0 at `604fee1`. Attempt 2 changed only the backend.
  - The locked-test diff is empty.
- **Declared rewrite.** `RoomTest.chatThatNormalizesToEmptyIsIgnored` was split into `blankChatIsIgnored` and `punctuationOnlyChatIsShownAndNeverScores`.
- **Review.** No Blocker or High.
  - **M1:** `strip()` let invisible-only chats through (U+3164 Hangul filler, ZWSP, NBSP, BOM), and they were broadcast as empty lines. Attempt 2 fixed this with `isInvisible` (whitespace, space chars, format and control characters, Hangul fillers, variation selectors), backed by a locked test.
  - The critic confirmed that `!normalized.isEmpty()` guards the match even if a catalog accepted string were empty, and that the CSS pause rule wins over every theme rule because it sits outside the components layer at specificity 0,3,0.
- **Lead's browser check.** Local jar and built client, Playwright on Edge.
  - Two players. After `startAt`, the guest sent `?`, `...`, `,./?` and U+3164. The host's chat log shows `철수: ?`, `철수: ...` and `철수: ,./?`, and nothing for U+3164.
  - Audio requests were held with a Playwright route while `.disc-area[data-paused=true]`:

    | Theme | Record pixel change over 700 ms while held | After release |
    |---|---|---|
    | 놀이터 | 0.0 % | 12.7 % |
    | 무대 | 0.0 % | 18.1 % |
    | 벚꽃 | 0.0 % | 15.2 % |
    | 칠판 | 0.0 % | 13.4 % |
    | 별밤 | 0.0 % | 19.1 % |
    | 오락실 | 0.0 % | 87.9 % |
- **Process note.** The backend implementer's full run hit the overlapping-Gradle `EOFException` / `NoSuchFileException` twice, with no `GradleWrapperMain` visible beforehand. The third run passed. This is the known Follow-up about the Stop hook running Gradle.

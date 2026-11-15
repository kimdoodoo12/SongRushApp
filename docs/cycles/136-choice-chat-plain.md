# 136 choice-chat-plain (from D-135)

## Goal
In a choice room, chat is ordinary chat at every moment: no line is hidden from the others because it matches a title.

## Plan
- Found by the release check before the PR to `main` (critic, High): with D-132, in a choice room before the start, a line matching the answer reached its sender only while the others' titles reached everyone. With a second tab, typing the 4 to 8 choices' titles shows which one is the answer (the one the other tab never sees); picking it at `startAt` wins every song. The same held during a reveal with prefetch (D-120, for the next song's titles, now known from `nextChoices`).
- D-135 (owner, 2026-11-15): in a choice room the answer-matching chat rules do not apply at all, since chat never scores there. Nothing hidden means nothing to read off.
- `backend/src/main/java/com/songrush/game/Room.java` `chat` (~719-791): when `answerMode == AnswerMode.CHOICE`, every line that passes the flood limit goes to everyone (`toAll`): in the `revealing` branch (no D-120 spoiler check), in `PREPARE` and before `startAt` (no D-132 check), during the song (already so), and from watchers (their sender-only rule exists for guesses that could score; in a choice room nothing in chat scores). Chat rooms are unchanged.
- CLAUDE.md "Product decisions", the answer format bullet (chat never scores in a choice room): add that in a choice room chat is always shown to everyone (D-135).

## Acceptance criteria
1. Choice room, before the start (`PREPARE` and before `startAt`): a line equal to the answer's title, one equal to another choice's title, and a miss all reach everyone.
2. Choice room with prefetch, during a reveal: a line equal to the next song's title reaches everyone.
3. Choice room, a watcher's line before the reveal reaches everyone.
4. Chat rooms: unchanged (D-132 before the start, D-120 during a reveal, watchers sender-only); the existing tests of those still pass.
5. `./gradlew test` passes from `backend/`; tests that pinned the old choice-room behaviour are updated to the new rule and listed in the Result.

## Out of scope
- Chat rooms; the client (it shows whatever chat arrives).

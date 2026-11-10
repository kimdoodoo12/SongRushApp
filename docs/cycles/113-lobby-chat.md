# 113 lobby-chat (B-032; D-123)

## Decision (owner, 2026-11-10)
The owner chose 대기실 채팅 among the mockup's new features (D-123). The mockup's lobby has a chat log beside the settings; cycle 114 builds that UI.

## Goal
While a lobby has no running room (before the first game, and after a game has returned to the lobby), a `chat` from a connected member is broadcast to the lobby's connected members, under the same rules as in-game chat. It is never answer-checked.

## Behaviour
1. **Routing** (`Lobby.chat`):
   - **A room is running:** forward to `room.chat` as today. That path is unchanged.
   - **No room, member connected, lobby not closed:** apply the shared chat rules below, then send `{type:"chat", name, text}` to every connected member, including the sender.
     - The lobby's invariant holds: the lobby never uses `toAll` (`Lobby.java:21-22`). The line goes through the lobby's existing per-member send (`toConnected`, one `toPlayer` per connected member, in join order).
   - **Otherwise** (an unknown or disconnected member, or a closed lobby): nothing is sent.
2. **Shared rules.** The rules come from one place that `Room.chat` also uses, not a copy (for example a small `ChatRules` class in `com.songrush.game`):
   - **Flood limit:** a per-player token bucket with burst 5, refilling 2 per second. When it is empty, `{type:"error", code:"rate-limited"}` goes to the sender only.
   - **Length:** above `MAX_CHAT` (200), `{type:"error", code:"too-long"}` goes to the sender only.
   - **Invisible:** a text that `isInvisible` flags (only whitespace, format or control characters, Hangul fillers or variation selectors) is dropped silently.
   - **Null text** is dropped.
   - **Lobby buckets.** Each `Member` holds its own bucket as a field, so it goes away with the member and the lobby holds no separate map that could grow (see `LobbyJoinChurnTest`).
   - **Room buckets.** `Room` keeps its own buckets exactly as today, created full in the constructor and in `addPlayer`, and still shared by `chat` and `skip` (a skip vote costs a token).
   - **Game start.** A player can therefore send up to 5 lobby lines and then 5 room lines around a game start. This is accepted.
3. **No answer check, no spoiler path.** No song exists in the lobby phase, and the line is never normalized or matched.
4. **`Room` is unchanged.** `Room.chat`'s and `Room.skip`'s observable behaviour stays the same, and all existing `Room` and socket chat tests pass unmodified.
   - **One declared exception (critic Blocker).** `LobbyAbandonTest.java:249-253` sends `lobby.chat(P3, "hello")` after an abandon, with the room null and P3 connected, and asserts that nothing is sent. Its intent is that the dropped `Room` stays silent. Under this cycle P3's own lobby line is broadcast to P3.
     - The test writer changes that assertion: the only frames since the mark are P3's own `chat` line(s). No frame comes from the dropped room.

## Acceptance
1. **`LobbyChatTest.java`** (new, game package, with the same fakes as the existing Lobby tests):
   - before any game, a member's line reaches all connected members (sender included) as `chat` with `name` and `text`;
   - a disconnected member's line sends nothing;
   - a closed lobby sends nothing;
   - no `Sent` is `toAll`, and each line is one `toPlayer` per connected member;
   - six lines at the same instant give five broadcasts and one `rate-limited` to the sender;
   - after 500 ms of fake clock, one more line passes;
   - 201 characters gives `too-long` to the sender only;
   - `"ㅤ"` and `"   "` send nothing;
   - after a game has ended and the lobby is open again, chat is broadcast again;
   - while a game runs, a line goes through `Room` (a correct title still scores). That case is covered by the existing tests running unchanged.
2. **`LobbyChatSocketTest.java`** (new, ws package): two real WebSocket clients in a fresh lobby. A `chat` from one arrives at both.
3. **Commands:** `.\gradlew.bat test` passes from `backend/`, and the existing tests are unchanged.

## File ownership
- **Test writer:** the two new test files, and the declared change in `LobbyAbandonTest.java`.
- **Implementer:** `backend/src/main/java/com/songrush/game/Lobby.java`, `Room.java` (only to move its rules into the shared class), and the new shared class.

## Spec review (critic, round 1)
- **Blocker:** the `LobbyAbandonTest` exception is declared.
- **Medium:** no `toAll`; buckets are a `Member` field; the shared `Room` bucket for `skip` is kept.
- **Nit:** the burst at game start is accepted.
- **Checked and fine:** the thread (the per-lobby executor), the clock (injected), the name (`freeName`), late joiners (they go to `Room`).

## Out of scope
- The frontend (cycle 114).
- Chat history for late joiners.
- Moderation.

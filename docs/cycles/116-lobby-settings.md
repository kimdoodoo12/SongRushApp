# 116 lobby-settings (D-124; critic H1 of cycle 114)

## Why
The mockup's lobby shows guests the host's settings, locked ("방장이 정한 설정이에요"). The server never sends lobby settings to guests: today `settings` goes out only when a game starts (`Room.java:392-395`). After cycle 114, a guest's locked card therefore shows the guest's own local defaults as if the host had chosen them. This cycle makes the card true.

## Goal
The host's lobby selection (song count, filter, answer mode, game mode) is shared with every member while the lobby is open. Guests render the locked card and the header `.settings` line from it.

## Protocol
1. **Client to server** `{type:"lobbySettings", songCount, <filter fields as in start>, answerMode, gameMode}`. The host sends it on every change of a lobby control, and once on entering the lobby as host.
2. **Server.**
   - The message is parsed and validated exactly like `start`, with **start's exact error order** (critic H1):
     1. `bad-message` (non-integer `songCount`)
     2. `bad-filter`
     3. `bad-answer-mode`
     4. `bad-game-mode`
     5. `no-room`
     6. `in-game`
     7. `not-host`
     8. `bad-song-count` (outside 5–30)
   - The first four come from the socket handler's existing parsing helpers. The lobby-side checks (`in-game`, `not-host`, `bad-song-count`) use one helper shared with `Lobby.start`, not a copy.
   - The lobby stores the latest valid selection. It sends `{type:"lobbySettings", ...the same fields}` to every connected member through the per-member send (never `toAll`).
   - **Change only.** A message equal to the stored selection is accepted silently, with no broadcast. This bounds the fan-out (critic M2), as with `roomTheme`, which has no bucket either.
   - **When a selection exists, it is sent to:**
     - a member who joins or rejoins in the lobby phase, right after `sendRoomThemeTo` (that is, after joined → roster → roomTheme);
     - a member whose socket is replaced in the lobby phase, at the same point of that path;
     - nobody during a game;
     - **every connected member, when the room returns to the lobby** (`returnToLobby`), so that a late joiner who arrived during the game gets it even if the host changes nothing (round 2: the change-only rule would otherwise starve them).
   - The stored selection survives a game. After returning to the lobby it is the same until the host changes it.
   - A host transfer keeps it.
   - **The new host's client sends its own selection the moment its roster shows it became host** (false→true), so the guests' card always matches the selection the next `start` uses (critic H2).
3. **No game effect.** `start` keeps its own parameters as today. The lobby selection is display-only for guests.

## Frontend
- `session.js` and `state.js` keep `lobbySettings` (null until received) and clear it when the player leaves the room.
- **Host:** sends `lobbySettings` on each control change, on entering the lobby as host, and when the roster makes it host. The host's controls keep reading its own `selection`.
- **Guest:** the disabled controls, the year range, the stepper `output`, the `.oh` hints and the header `.settings` line all read from `lobbySettings`. Until it arrives, the guest card shows the controls disabled with no pressed segment, `—` in the stepper output, and `.opts-who` "방장이 설정을 고르는 중이에요".
- Secrecy is not affected: the selection holds no song data.

## Acceptance
1. **Backend tests**, new: `LobbySettingsTest.java` in the game package and `LobbySettingsSocketTest.java` in the ws package:
   - a host's valid message reaches every connected member as `lobbySettings` with the same fields;
   - a guest gets `not-host`;
   - each bad field gives its error, in the order above;
   - a joiner and a rejoiner get the stored selection after joined → roster → roomTheme; a replaced socket gets it too; a late joiner during a game does not;
   - an unchanged message is not broadcast;
   - nothing is sent with `toAll`;
   - during a game the message gives `in-game`;
   - after the game the stored selection is still sent to a new joiner.
2. **Frontend tests**, new: `App.lobbysettings.test.jsx`:
   - the host sends `lobbySettings` after each control change, on entering the lobby, and when a roster makes it host;
   - a guest renders the received values (song count, type, difficulty, years, answer mode, game mode, the `.settings` line and the hints);
   - before any message, a guest sees `—` and no pressed segment;
   - the existing locked tests pass unchanged.
3. **Commands:** `.\gradlew.bat test` (from `backend/`) and `npm test`, `npm run lint`, `npm run build` (from `frontend/`) all pass.

## Spec review (critic, round 1)
- **H1:** start's actual error order, with a shared helper.
- **H2:** the new host sends on becoming host.
- **M1:** the send point is fixed, and the replaced-socket and late-joiner cases are covered.
- **M2:** an unchanged selection is not broadcast.

## Test-writer findings (round 2)
- **Late joiner and the change-only rule.** These conflicted, so the server now sends the stored selection on `returnToLobby`.
- **`App.lobbyport.test.jsx:580`** expected a guest's `.opts-who` to read '방장이 정한 설정이에요' before any selection exists. That is now a declared test change: the test delivers a `lobbySettings` first, or expects '방장이 설정을 고르는 중이에요' before one.

## File ownership
- **Test writer:** the three new test files, and the declared change in `App.lobbyport.test.jsx`.
- **Implementer:**
  - backend: `ws/GameSocketHandler.java` (routing and shared parsing), `game/Lobby.java`;
  - frontend: `src/game/session.js`, `src/game/state.js`, `src/App.jsx`, `src/components/LobbyScreen.jsx`, `FilterControls.jsx`, `src/i18n/{ko,en,ja}.json`.

## Out of scope
- Guests changing settings.
- Persisting the selection.

## Result
- **Commits.**
  - Spec: `6d22c59`, round 2 `8cfae41`.
  - Tests: `68e1d3a` and `6ac0b38`, locked.
  - Implementation: `9cc1300`.
  - Declared test fix: `d31a8a7`. The `skipSong` helper did not ready late joiners, so the round-2 tests stuck in PREPARE. The implementer reported this without editing the test.
  - Critic round fix: tests `d748488`, fix `2c3ff6f`.
- **Verifier** (on `d31a8a7`):
  - `gradlew cleanTest test`: BUILD SUCCESSFUL, 926 tests, 0 failures, 1 skipped. `LobbySettingsTest` 23/23, `LobbySettingsSocketTest` 12/12.
  - `npm test` 674/674. Lint is clean and the build passes.
  - `9cc1300` touched no test.
  - `lobbySettings` is sent only per member (no `toAll`).
- **Critic code review.**
  - High: after the host's socket drops and the host rejoins, it never resends its selection. Fixed in `2c3ff6f`: a `lobbyRestores` counter, bumped on each restore into the lobby, is now a dependency of the send effect.
  - Checked and fine: `start`'s behaviour and error order, the change-only map equality, the send points, and secrecy.
- **Lead, after the fix:** `npm test` 678/678, lint clean. The backend is unchanged by the fix.

# 137 own-mood (F-039, from D-136, issue #64)

## Goal
Each player picks the room's mood for their own screen; the host no longer sets everyone's look.

## Plan
- Today (D-090, D-122): the host's picker sends `roomTheme`, the server keeps it and sends it to everyone (Lobby.java, GameSocketHandler.java), and in a room the screen uses `state.roomTheme` (frontend/src/App.jsx:479-485). Guests see a disabled picker labelled "분위기 (방장이 고름)" (GameScreen.jsx:331-334, 526; i18n `theme.labelGuest`). The start screen already uses the player's own stored pick (`loadOwnTheme`/`saveOwnTheme`, frontend/src/game/themes.js).
- Change (frontend only): in a room, `theme` is the own stored pick (the same `ownTheme` state the start screen uses); the room picker is enabled for everyone and calls the own-pick handler (`saveOwnTheme`, no `setRoomTheme`); a `roomTheme` from the server no longer affects the look. The picker label is `theme.label` for everyone (`theme.labelGuest` is no longer used). The server is left as is: it still accepts and relays `roomTheme`, which the new client neither sends nor uses; removing it is a follow-up.

## Acceptance criteria (Vitest)
1. A guest in the lobby picks a mood: `html[data-mood]` changes at once; nothing is sent.
2. A host picks a mood: the look changes; no `roomTheme` message is sent.
3. A `roomTheme` frame from the server does not change the look.
4. The pick is stored: after a reload (or on the start screen) the same mood applies.
5. The picker is enabled and labelled the same for host and guest, in the lobby and during a game.
6. `npm test`, `npm run lint`, `npm run build` pass; existing tests that pinned the host pick are updated and listed in the Result.

## Out of scope
- Removing `roomTheme` from the server; any change to the eight moods themselves.

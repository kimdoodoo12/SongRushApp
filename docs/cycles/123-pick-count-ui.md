# 123 pick-count-ui (F-033, from D-130, issue #44 request 2)

## Goal
In a choice room, players see how many have picked the current song and a chat line naming whoever picked wrong.

## Background
- Cycle 122 made the server send `pickState{index, picked, total}` and `wrong{index, name}` to everyone in choice games, and put `pickState: {picked, total}` in the snapshot's song. Today the client drops both frames in `onMessage`'s `default` branch with a console warning (`session.js:780`).
- D-130: the count is `picked / connected players`; a wrong pick is announced in chat with the name only; choice rooms only.

## Design
State (`game/state.js`)
- New field `pickCount`: `null` or `{picked, total}`.
- `null` at the start and after `prepare`, `watching`, `reveal` and `abandoned` (the same places that clear `skipCount`).
- New action `pickState{picked, total}` sets it. The reducer returns the same state object when the numbers did not change.
- `restore` and `watching` take it from the snapshot's `song.pickState` when that is valid, else `null`.
- New action `pickWrongNotice{name}` appends a notice line `{kind: 'notice', key: 'chat.wrongLine', values: {name}}` through `withNotice`, so `ChatLog` renders it as `div.msg.sys` with no new markup. It is not a `kind: 'system'` line, so the winner-chat ordering does not see it.

Session (`game/session.js`)
- `case 'pickState'`: like `onSkipState`, only for the current round (`round !== null && msg.index === round.index`), and only when `picked` and `total` are integers with `0 <= picked <= total` and `total >= 1`. Otherwise ignored without a warning.
- `case 'wrong'`: only for the current round and a non-empty string `name`.
- Neither type reaches `console.warn('unknown message', ...)` any more, in any room.
- `onRejoinSnapshot` and the watching snapshot read `song.pickState` with the same validation.

Screen (`components/GameScreen.jsx`)
- The count is a `span` with classes `skipcount pickcount` in the panel's `div.row`, after the skip button and its vote count. Text: `stage.pickCount` with `{picked, total}`.
- It shows only when the choice grid is shown, the song has started, no reveal is up, and `pickCount !== null`. The row renders when the skip button or the count shows (a late joiner who is watching has no skip button but sees the count).
- `.skipcount` is already styled by `room.css`; `pickcount` is a hook for tests only. No CSS file changes.

Texts (`i18n/ko.json`, `en.json`, `ja.json`)
- `stage.pickCount`: "선택 {{picked}}/{{total}}", "Picked {{picked}}/{{total}}", "選択 {{picked}}/{{total}}".
- `chat.wrongLine`: "✘ {{name}} 오답", "✘ {{name}} picked wrong", "✘ {{name}} 不正解". The mark is U+2718, typed as the character in the JSON files.

Why the count is hidden outside a playing song: the snapshot of a rejoining player does not count that player yet (cycle 122 follow-up). During a song the broadcast that follows corrects it at once, and after a `start` the server sends a fresh `pickState`.

## Acceptance criteria
1. Choice room, song audible: `pickState{index: 0, picked: 1, total: 3}` shows "선택 1/3" in `.panel .pickcount`; a later `{picked: 2, total: 3}` updates it. In en and ja the text follows the language.
2. Not shown before the song starts (a `pickState` during prepare leaves no visible count), not shown once the reveal is up, and the next song does not show the previous song's count before its own `pickState` arrives.
3. A `pickState` for another index, or with non-integer numbers, `picked > total` or `total < 1`, changes nothing.
4. `wrong{index: 0, name}` adds exactly one `aside.chat .msg.sys` line (not `.correct`) with the ko text; en and ja follow the language. Another index or a missing name adds nothing.
5. The player's own wrong pick still marks the tile and shows the existing `pickmsg`; the `wrong` frame that follows adds the chat line too.
6. A chat room that receives `pickState` or `wrong` shows no count and no line. In no room do these two types call `console.warn`.
7. Reload during a choice song: a snapshot whose song has `pickState: {picked: 1, total: 2}` shows "선택 1/2" once the song is running again; a snapshot without it shows no count. A late joiner's watching snapshot is treated the same.
8. `i18n/keys.test.js` passes with the two new keys in all three languages.
9. `git diff dev...HEAD --stat` has no `.css` file.
10. From `frontend/`: `npm test`, `npm run lint` and `npm run build` pass.
11. Seen in a browser by the lead against a real local server: the count changes when another player picks, and the wrong line appears for the other players. Captures go in `docs/issues/044/`.

## Test list
- New `frontend/src/App.pickcount.test.jsx`: criteria 1 to 7, driving `<App>` with the fake socket as `App.choice.test.jsx`, `App.skipVote.test.jsx` and `App.reload.test.jsx` do.
- `frontend/src/i18n/keys.test.js` is not edited; it covers criterion 8 as it stands.
- Existing tests that assert an exact chat log or panel content in choice rooms are updated only if the new behaviour changes them; each one is reported.

## File ownership
- Test writer: `frontend/src/App.pickcount.test.jsx` and any existing test that needs the update above.
- Implementer: `frontend/src/game/state.js`, `frontend/src/game/session.js`, `frontend/src/components/GameScreen.jsx`, `frontend/src/i18n/ko.json`, `en.json`, `ja.json`. `App.jsx` only if a prop must be passed.

## Out of scope
- Any CSS change, any new layout. `room.css` stays as it is (D-124).
- Songs playing to the time limit (D-129, S-008).
- Changing the snapshot count on the server (cycle 122 follow-up).

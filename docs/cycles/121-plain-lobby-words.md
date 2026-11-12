# 121 plain-lobby-words (D-128)

## Decision (owner, 2026-11-12)
"현재 각 테마에 레코드 올리기, 카드 뽑기 시작 등등 이런거 하지말고 Play Song같은걸로 통일해. 그리고 옥상 레코드바, 새벽창가 등등 이것도 그냥 간단한 테마별로 어울리는걸 해. 이상한 수식어 붙여서 그럴싸한 말 하지말고"

## Goal
The lobby texts per mood (`skin.<mood>.*` and `mood.tag.<mood>` in ko/en/ja) become plain: one start label, a plain title per mood, and the same plain words for the rest.

## Values
- `start`: `Play Song` in every mood and language.
- `title` (ko / en / ja):

  | mood | ko | en | ja |
  |---|---|---|---|
  | sunset | 노을 | Sunset | 夕焼け |
  | rain | 비 오는 밤 | Rainy Night | 雨の夜 |
  | sakura | 벚꽃 | Cherry Blossoms | 桜 |
  | neon | 네온 | Neon | ネオン |
  | chalk | 교실 | Classroom | 教室 |
  | pixel | 오락실 | Arcade | ゲーセン |
  | matsuri | 여름 축제 | Summer Festival | 夏祭り |
  | manga | 만화 | Manga | 漫画 |

- Same in every mood (ko / en / ja):
  - `label`: 방 코드 / Room code / ルームコード
  - `admit`: ADMIT ONE (all)
  - `seats`: 대기실 / Lobby / ロビー (the heading reads "대기실 · 참가자 n / 8")
  - `ready`: 참가 / Joined / 参加
  - `empty`: 빈 자리 / Empty / 空席
  - `mood.tag.<mood>`: 애니 OP·ED 맞히기 / Guess the anime OP/ED / アニメOP・ED当て
- Unchanged: the picker names `theme.<mood>`, the CSS, and the markup.

## Acceptance
1. `App.lobbyport.test.jsx` (declared test change) pins the new values for ko sunset, ko manga and en pixel, including the seat heading and the ticket tag line.
2. `npm test`, `npm run lint`, `npm run build` pass; `i18n/keys.test.js` key parity holds.
3. Lead captures the lobby at 1536x730 in a few moods.

## File ownership
- Test writer: `frontend/src/App.lobbyport.test.jsx`.
- Implementer: `frontend/src/i18n/{ko,en,ja}.json` (`skin.*` and `mood.tag.*` only).

## Out of scope
Picker names, the game-screen layout, the CSS.

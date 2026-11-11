# 118 choice-deck (D-126)

## Decision (owner, 2026-11-11)
- "채팅모드는 음반하고 애니메이션 크기가 적당한데, 객관식은 좀 크기가 작은 것 같아. 채팅모드의 크기를 따라가는게 좋은것 같은데"
- Asked to choose a layout, the owner answered: "레코드 아래가 좋아. 그러나 레코드의 비율을 약간 줄이고 밑의 표지 2줄이 한 화면에 보일정도는 좋을것 같은데"

## Measured (lead, mockup in choice mode, k = 8)
With the deck column forced to a width `d`, the bottom edge of the tiles is at `d + 619 px`, the same at every viewport width. Two rows of tiles therefore fit on one screen of height `H` when `d ≤ H − 620`. Examples:

| Screen height | Largest deck that fits |
|---|---|
| 800 | about 180 px |
| 900 | about 280 px |
| 1040 and above | the full chat-mode size, 420 px |

In the mockup today the choice deck is 130–160 px while playing and 220–300 px at the reveal.

## Owner review of samples (2026-11-11)
- The owner was shown big-cover samples first. Then he said "약간 스킵버튼 이런것 때문에 표지가 더 올라가지 못하는것 같기도 하고" and approved a deck-left, covers-right layout: "어 이거 괜찮은것 같아. 대신 큰 모니터 기준으로 오른쪽 중앙의 여백이 비는걸 제외하면".
- The final rule removes `.inner`'s 960 px cap in choice games, so the covers fill the stage up to the chat column.

## Change
- A new file `frontend/src/choice.css`, imported after `start.css`, with exactly:
  `@container (min-width:621px){.choice-mode .inner{max-width:none}.choice-mode .now,.choice-mode .now:has(.revealed){grid-template-columns:clamp(200px,calc(100vh - 600px),300px) minmax(0,1fr);grid-template-areas:"deck choices" "panel choices";grid-template-rows:auto 1fr;align-items:start;gap:16px 28px}.choice-mode .choices.k4,.choice-mode .choices.k6,.choice-mode .choices.k8{--tw:calc((100vh - 304px) / 2.8);max-width:calc(var(--cols) * var(--tw) + (var(--cols) - 1) * 12px);padding-top:0;border-top:0;align-self:start}.choice-mode .now .hint{font-size:1.15rem}}`
- **What the rule does.**
  - The deck is in the top left, with the hint, sub text and skip row under it.
  - The covers take the whole right column. Their width is limited so that two rows fit the screen height.
  - The deck keeps the same size during the reveal.
- **Phones** (stage 620 px and under) keep the mockup's rules.
- **Hint text.** In ko, en and ja, the choice hint changes from "들리는 곡의 애니를 아래 표지에서 골라 보세요" to "들리는 곡의 애니를 표지에서 골라 보세요", because the covers are now beside the deck, not below it.
- `room.css`, `wide.css` and `start.css` are unchanged.

## Measured (prototype on the mockup, with `wide.css`)

| Screen | Deck | Cover k = 6 | Cover k = 8 | Bottom of the 2nd row (k = 8) |
|---|---|---|---|---|
| 1280×800 | 200 | 186×253 | 154×210 | 663 |
| 1900×870 | 270 | 212×289 | 177×241 | 724 |
| 1920×1080 | 300 | 230×313 | 169×231 | 703 |

- The gap between the covers and the chat column is the stage padding (28 px), or 62–69 px for k = 6 on narrow stages.
- Before this change, the covers were 121×161 and the deck was 160.

## Acceptance
1. **`src/choiceCss.test.js`** (node, a declared rewrite of the locked test): `choice.css` holds exactly the rule above, and `main.jsx` imports it after `./start.css`.
2. **Declared change:** tests that pin the old choice hint text use the new text.
3. **Commands:** `npm test`, `npm run lint` and `npm run build` pass.
4. **Browser check (lead):** in a choice game:
   - at 1280×800 (k = 8), and at 1900×870 and 1920×1080 (k = 6 and 8), the 2nd tile row is within the viewport while a song plays;
   - the deck size is unchanged at the reveal;
   - 390×844 equals the mockup's layout.

## File ownership
- **Test writer:** `src/choiceCss.test.js` and the `vite.config.js` `NODE_TESTS` line.
- **Implementer:** `src/choice.css` and `src/main.jsx`.

## Revision 2 (owner, 2026-11-11: "내 노트북 기준으로, 레코드가 너무 작아. 더 키울수있을것 같은데. 그리고 사진도 더 키울수 있을것 같은데. 큰 모니터 기준으로도 괜찮아")
- **The owner's laptop**, measured on this PC: a gram at 1920×1080 with 125 % scaling (`1536x864` logical). A maximized browser gives about 1536×730 CSS px.
- **Measured at 1536×730 with revision 1:** the header is 114 px (two rows), the deck 200, and the covers 159×217.
- **`choice.css` becomes exactly the rule below.** Besides the choice layout, it adds:
  - on screens at least 1300 px wide, the header `.moodbar` sits on the logo row (`flex-basis:auto`);
  - in choice games at 900 px height or less, the panel's `.sub` text is hidden;
  - the deck takes 28 % of the `.now` width, capped by the height;
  - the covers fit the height left, with the header-dependent overhead in `--oh`.

  `@media (min-width:1300px){.moodbar{flex-basis:auto}}@media (max-height:900px){.choice-mode .now .sub{display:none}}.choice-mode{--oh:290px}@media (min-width:1300px){.choice-mode{--oh:242px}}@container (min-width:621px){.choice-mode .inner{max-width:none}.choice-mode .now,.choice-mode .now:has(.revealed){grid-template-columns:clamp(200px,min(calc(100vh - 400px),28%),340px) minmax(0,1fr);grid-template-areas:"deck choices" "panel choices";grid-template-rows:auto 1fr;align-items:start;gap:16px 28px}.choice-mode .choices.k4,.choice-mode .choices.k6,.choice-mode .choices.k8{--tw:calc((100vh - var(--oh)) / 2.75);max-width:calc(var(--cols) * var(--tw) + (var(--cols) - 1) * 12px);padding-top:0;border-top:0;align-self:start}.choice-mode .now .hint{font-size:1.15rem}}`

- **Measured with this rule** (prototype on the local site; covers given as width; "bottom" is the bottom of the 2nd row):

  | Screen | Deck | k = 6 | k = 8 | Bottom (k = 6) |
  |---|---|---|---|---|
  | 1536×730 | 283 | 186×253 | 174×237 | 701 |
  | 1900×870 | 283 | 236 | 174 | 833 |
  | 1440×900 | 283 | 236 | 174 | 835 |
  | 1280×800 | 239 | 194 | 144 | 770 |

  At 1280×800 the k = 8 covers are slightly smaller than in revision 1 (154). The owner saw the 1536×730 and 1900×870 captures.

## Revision 3: one page scroll (owner, 2026-11-11: "스크롤이 약간 좀 그렇네. 왜 굳이 레코드쪽 스크롤이 따로 있지? 그냥 전체로 내리면 되는거 아닌가?")
- **The problem.** The mockup's `.stage` scrolls by itself (`overflow-y:auto`) inside a 100 %-high `.app`, so the left side has its own scrollbar.
- **The change.** Above 900 px wide, the whole page scrolls instead. The chat column stays in view (sticky, full viewport height, input at the bottom). At 900 px and below, the mockup's own rules already make the page scroll.
- **New file `frontend/src/scroll.css`,** imported after `choice.css`, with exactly:
  `@media (min-width:901px){html,body{height:auto}.app{height:auto;min-height:100vh}.stage{overflow:visible}.chat{position:sticky;top:0;height:100vh;align-self:start}}`
- **Prototype** on the local site at 1536×730 (choice game, k = 6, after one wheel scroll of 400 px):
  - the window scrolled 285 px, the stage's own `scrollTop` stayed 0, and the document height is 1015;
  - the chat column stays at top 0 and bottom 730.
- **Acceptance.**
  - `src/scrollCss.test.js` (node): `scroll.css` holds exactly the rule, imported after `./choice.css`.
  - `npm test`, lint and build pass.
  - Browser check: at 1536×730 and 1900×870, in the lobby and in the game, the window scrolls, `.stage` never scrolls, and the chat input stays visible.

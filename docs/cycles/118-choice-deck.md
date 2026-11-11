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

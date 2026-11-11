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

## Change
- A new `frontend/src/choice.css`, imported after `start.css`, with exactly:
  `@container (min-width:621px){.choice-mode .now,.choice-mode .now:has(.revealed){grid-template-columns:clamp(180px,calc(100vh - 620px),420px) minmax(0,1fr)}}`
- The mockup's grid areas stay as they are: the deck and panel on top, the tiles in the row below. The deck keeps the same size during the reveal, so the tile row no longer jumps.
- Narrow stages (620 px and under, phones) keep the mockup's rules.
- `room.css`, `wide.css` and `start.css` are unchanged.

## Acceptance
1. **`src/choiceCss.test.js`** (node): `choice.css` holds exactly that rule, and `main.jsx` imports it after `./start.css`.
2. **Commands:** `npm test`, `npm run lint` and `npm run build` pass.
3. **Browser check (lead):** in a choice game with k = 8:
   - at 1280×800, 1440×900 and 1920×1080, the bottom of the second tile row is at or above the viewport bottom while a song plays, and the deck is the clamp value for that height;
   - at the reveal, the deck size is unchanged;
   - at 390×844, the layout equals the mockup's.

## File ownership
- **Test writer:** `src/choiceCss.test.js` and the `vite.config.js` `NODE_TESTS` line.
- **Implementer:** `src/choice.css` and `src/main.jsx`.

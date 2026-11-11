# 117 wide-center (D-125)

## Decision (owner, 2026-11-11)
"현재 배치가 조금 그런데. 노트북으로 하면 딱 좋고, 큰 모니터로 하니까 너무 중앙이 벌어져있네". The owner then chose "전체를 가운데로 모으기" from three options; a preview of each at 1920 px was rendered from the mockup by the lead.

Measured in the mockup: `.inner` ends at 980 px while the 380 px chat sits at the right edge. That leaves a 560 px gap at 1920 px and a 1200 px gap at 2560 px.

## Goal
Above 1440 px viewport width, `.app` is at most 1440 px wide and centred, with a 1px `var(--line)` border on its left and right. The background `#sky` stays full width. At 1440 px and below, nothing changes.

## Change
- New `frontend/src/wide.css` with exactly:
  `@media (min-width:1441px){.app{max-width:1440px;margin-inline:auto;border-inline:1px solid var(--line)}}`
- `main.jsx` imports it right after `room.css`.
- `room.css` stays verbatim (`roomCss.test.js` unchanged), and `index.css` is untouched (`indexCss.test.js` unchanged).

## Acceptance
1. **`src/wideCss.test.js`** (node): `wide.css` holds exactly that one rule, and `main.jsx` imports `./wide.css` after `./room.css`.
2. **Commands:** `npm test`, `npm run lint` and `npm run build` pass.
3. **Browser check (lead):**
   - at 1920×1080 and 2560×1440, `.app` is 1440 px wide and centred (left = (vw − 1440) / 2), for the game room and the lobby;
   - at 1440×900 and 1280×800, `.app` is full width as before.

## File ownership
- **Test writer:** `src/wideCss.test.js` and the `vite.config.js` `NODE_TESTS` line.
- **Implementer:** `src/wide.css` and `src/main.jsx`.

## Addendum: the start screen (owner, 2026-11-11: "그리고 방만들기 할 때도 완전히 왼쪽에 붙어있어.")
- **The problem.** The start screen has no chat, but `.main` still reserves the 380 px chat column. The card (`.start-screen`, max 448 px) sits at the left of `.inner`. Measured at 1920×1080 with `wide.css`: stage 241–1299, card 261–709.
- **Change.**
  - The start screen's `.main` gets the class `solo`.
  - A new file `frontend/src/start.css`, imported after `wide.css`, holds exactly:
    `.main.solo{grid-template-columns:minmax(0,1fr)}.main.solo .inner{margin-inline:auto}.start-screen{margin-inline:auto}`
  - The lobby and the game keep the mockup layout.
- **Acceptance.**
  - **Unit test (`src/startCss.test.js`, node):** `start.css` holds exactly those rules, and `main.jsx` imports it after `./wide.css`.
  - **jsdom:** on the start screen `.main` has `solo`; in the lobby and the game it does not.
  - **Browser check:** at 1280 and 1920, the start card's centre equals the centre of `.app` within 2 px.

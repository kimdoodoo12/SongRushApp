# 087 disc-before-start (D-101 clarified)

## Decision (owner, 2026-11-08)
"여전히 음원 불러오는중일때 움직임이 멈춘게 아니라 아예 막대랑 cd가 안보이는데"

Cycle 086 misread D-101. The owner's "loading" is the prepare phase before a song starts, when the stage shows "음원을 불러오는 중...". In that phase the record and equalizer were not drawn at all, because they were drawn only between the start and the reveal. The intent is that they are always drawn while a round runs, standing still until the song actually plays.

## Behaviour
1. **When the disc is drawn.** The disc (`.disc-area` with the record and equalizer) is drawn whenever the game stage has a current round and no reveal (`screen === 'game' && round !== null && reveal === null`). That covers prepare, loading and playing.
2. **When it is paused.** `data-paused="true"` is set unless the song is actually playing, meaning `started` is true and `load` is neither `loading` nor `failed`. So it is paused in prepare, before the start, while loading and after a failed load, and it moves only while the song plays.
3. **Unchanged.** The hint and the skip row still show only from the start, and the reveal still replaces the disc. The CSS pause rule from 086 is unchanged.
4. **Layout.** No theme rule changes. Theme box equality must stay 0. The stage may grow at prepare, because the disc now appears earlier; this is the same in every theme.

## Acceptance
**Tests.** Declared rewrite or addition in `frontend/src/App.loadingdisc.test.jsx`:
- **Prepare phase.** After `prepare` and before `start`, while the audio is loaded or still loading, `.disc-area` is rendered with `data-paused="true"`, and the record and equalizer are present.
- **After start and the audio loaded.** The attribute is absent.
- **The 086 cases still hold:** loading after the start, and a failed load.
- **Reveal.** After the reveal, `.disc-area` is absent.

**Commands.** `npm test`, `npm run lint` and `npm run build` exit 0.

**Lead's browser check.**
- Audio is held with a route, and the round has not started. The disc is visible in all six themes, with a pixel change under 1 %.
- After release and the start, it spins (at least 5 %).
- Theme box differences stay 0 at 1280 and 390.

## File ownership
- **Test writer:** `frontend/src/App.loadingdisc.test.jsx`.
- **Implementer:** `frontend/src/components/GameScreen.jsx`.

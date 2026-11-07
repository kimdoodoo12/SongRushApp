# 085 volume (D-099)

## Decision (owner, 2026-11-08)
"사용자들이 자신이 원하는 불륨으로 들을 수 있게 소리를 조절하게 해줘"

The lead chose these defaults and told the owner; they can be changed.
- **Where:** a volume slider plus a mute button on the game stage.
- **Default:** 80 %.
- **Storage:** remembered per player on the device.
- **Timing:** a change applies at once, even mid-song.
- **Fairness:** volume never changes when a song starts or stops; synchronised playback is untouched.

## Behaviour
1. **Audio graph.**
   - The session creates one `GainNode` per AudioContext, the first time it schedules a song.
   - Every song source connects to that gain node, and the gain node connects to `ctx.destination`.
   - Nothing about playback timing changes (`planPlayback`, start, stop, offset).
2. **Gain.**
   - Volume `v` is an integer from 0 to 100. Gain = `(v/100)^2`, a perceptual curve.
   - Muted means gain 0, whatever `v` is.
   - A change sets `gain.gain.value` at once, so it applies to the song already playing.
3. **Persistence.**
   - The setting is stored in `localStorage['songrush.volume']` as JSON `{"v": <0-100>, "muted": <bool>}`. Every read and write is wrapped in try/catch.
   - A missing value, an invalid value or blocked storage means `{v: 80, muted: false}`.
   - The setting applies to every new session and AudioContext.
4. **UI on the game stage** (shown whenever the stage is shown, in all six themes and three languages).
   - **Mute button.** A `<button aria-pressed>`, labelled by `t('volume.mute')` (음소거 / Mute / ミュート), with a speaker icon (decorative, `aria-hidden`).
   - **Slider.** `<input type="range" min="0" max="100" step="1">`, labelled by `t('volume.label')` (음량 / Volume / 音量). Its `aria-valuetext` reads like `80%`.
   - **Interplay.**
     - Moving the slider above 0 while muted unmutes.
     - Muting keeps the slider value, so unmuting restores it.
     - A slider at 0 shows the muted icon state.
   - **Layout.**
     - The control has fixed boxes, the same in every theme (no layout properties in theme rules; themeLayout stays green), and fits at 390 px without horizontal scroll.
     - It sits on the stage and must not move the existing stage boxes (round, clock, time bar, skip) between themes.
     - It must not capture keys typed in the chat input.
5. **i18n.** The keys `volume.label` and `volume.mute` exist in ko, en and ja, keeping key parity.

## Acceptance
**Tests.** `frontend/src/App.volume.test.jsx` is new, and the fake AudioContext gains `createGain()`:
- **First song.** The source connects to a gain node, the gain node connects to `ctx.destination`, and the gain value is `0.64`, from the default 80.
- **Moving the slider** to 50 sets the gain to `0.25` on the same node while the song plays, and stores `{"v":50,"muted":false}`.
- **Mute** sets the gain to 0 with `aria-pressed=true`. Unmuting restores 0.25.
- **Stored setting.** `{"v":30,"muted":true}` stored before mount gives gain 0 and a slider at 30; unmuting gives `0.09`.
- **Bad or blocked storage** gives the default.
- **Slider above 0 while muted** unmutes.
- **English labels** after switching language come from `en.json`.

**Declared rewrite.** `App.flow.test.jsx:254` changes from `source.connect(ctx.destination)` to the source connecting to the gain node, with the gain node connected to the destination. The test writer lists this.

**Commands.** All other tests pass unchanged. `npm test`, `npm run lint` and `npm run build` exit 0.

**Lead's browser check.**
- The control is visible and usable in the six themes and three languages at 1280 and 390 px.
- Theme box differences stay at 0, and nothing is clipped.
- The volume change is heard. An AnalyserNode is not needed: the check reads the gain through a test hook only if one already exists; otherwise the unit tests stand.

## File ownership
- **Test writer:**
  - `frontend/src/App.volume.test.jsx` (new);
  - `frontend/src/testing/fakes.js` (add `createGain()` returning a fake with `gain.value` and `connect`);
  - `frontend/src/App.flow.test.jsx` (the declared line only).
- **Implementer:** `frontend/src/game/session.js`, `frontend/src/App.jsx`, `frontend/src/components/GameScreen.jsx`, a new `frontend/src/components/VolumeControl.jsx`, `frontend/src/game/volume.js` (new, storage helpers), `frontend/src/i18n/{ko,en,ja}.json` and `frontend/src/index.css`.

## Out of scope
- Server changes.
- Per-song normalisation.
- Keyboard shortcuts.

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
   - App creates one `GainNode` right after it creates the AudioContext in `openSession` (the same synchronous block). It keeps the node in a ref next to `ctxRef`, sets its gain from the stored setting, and connects it to `ctx.destination`. The node is never recreated while the context lives.
   - `startSession` takes a new `output` option. Every song source connects to `output` instead of `ctx.destination`. The session holds no volume state and has no volume API.
   - Nothing about playback timing changes (`planPlayback`, start, stop, offset).
2. **Gain.**
   - Volume `v` is an integer from 0 to 100. Gain = `(v/100)^2`, a perceptual curve.
   - Muted means gain 0, whatever `v` is.
   - A change sets `gain.gain.value` at once, so it applies to the song already playing. There is no AudioParam automation (`setTargetAtTime`, ramps). A small click on mute is accepted.
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
     - Unmuting with the slider at 0 sets it to the default, 80.
   - **Slot.** The control gets a row of its own, rendered as the stage's second child, right after `.stage-head`, in every stage state.
     - The mute button is `h-8 w-8 shrink-0`, and the slider is a fixed-width box.
     - It never goes into `.stage-head` (which already wraps at 390 px), `.stage-foot` (shown only while playing) or `.room-top`.
     - The extra row, about 44 px, moves the chat input down on mobile. This is accepted.
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
- **Second game.** After leaving and creating a room on the same context, `ctx.gains` still has length 1, and the new game's source connects to `ctx.gains[0]`.
- **Before the first song.** Moving the slider to 50 while the stage shows connecting or loading gives `ctx.gains[0].gain.value` of 0.25, and the first source connects to that node.
- **Unmuting at 0.** With the slider at 0, unmuting sets it to 80 (gain 0.64).
- `App.volume.test.jsx` clears `localStorage` in `beforeEach` and `afterEach`.
- **Slider above 0 while muted** unmutes.
- **English labels** after switching language come from `en.json`.

**Declared rewrite.** `App.flow.test.jsx:254` changes from `source.connect(ctx.destination)` to the source connecting to the gain node, with the gain node connected to the destination. The test writer lists this.

**Commands.** All other tests pass unchanged. `npm test`, `npm run lint` and `npm run build` exit 0.

**Lead's browser check.**
- The control is visible and usable in the six themes and three languages at 1280 and 390 px.
- Theme box differences stay at 0, and nothing is clipped.
- After the slider is dragged, its value and `localStorage['songrush.volume']` match. The audible level is not checked in the browser, because no test hook exists; the unit tests are the evidence.

## File ownership
- **Test writer:**
  - `frontend/src/App.volume.test.jsx` (new);
  - `frontend/src/testing/fakes.js` (add `createGain()` returning a fake with `gain.value` and `connect`);
  - `frontend/src/App.flow.test.jsx` (the declared line only).
- **Implementer:** `frontend/src/game/session.js` (the connect target only), `frontend/src/App.jsx`, `frontend/src/components/GameScreen.jsx`, a new `frontend/src/components/VolumeControl.jsx`, `frontend/src/game/volume.js` (new, storage helpers), `frontend/src/i18n/{ko,en,ja}.json` and `frontend/src/index.css`.

## Out of scope
- Server changes.
- Per-song normalisation.
- Keyboard shortcuts.

## Spec review (critic, round 1)
- **H1.** The spec said "per context" but put the node in the session, so a second game would have made a second node. Applied: App owns the node, and the session gets `output`.
- **M1.** No free slot existed on the stage. Applied: a row of its own after `.stage-head`.
- **M2.** A default of 80 lowers today's loudness by about 4 dB. Kept as told to the owner and recorded here.
- **M3.** The audible check could not produce evidence. Applied: the browser checks the value and storage; the unit tests are the evidence for the gain.
- **N1–N3** applied: storage is cleared in tests, there is no automation, and unmuting at 0 sets 80.

## Result
- **Commits.**
  - Tests: `ecb282d`, including the declared rewrite of `App.flow.test.jsx:254` and `createGain` in the fakes.
  - Implementation: `502f63b`.
- **Verifier at `502f63b`.**
  - `npm test`: 29 files, 389 tests passed. The targeted run (volume, flow, themeLayout, i18n keys) passed 78 tests.
  - lint and build: exit 0.
  - The locked-test diff is empty.
  - `session.js` only adds `output` and changes the connect target.
- **Review.** No Blocker, High or Medium. Nits, sent to Follow-ups:
  - With the slider at 0 and not muted, the icon looks muted but `aria-pressed` is false, and two presses are needed to hear sound.
  - The header comment in the locked test still says the session creates the node.
- **Lead's browser check.** Local jar and built client, Playwright on Edge.
  - ko at 1280, en at 390 and ja at 390: the slider shows 80 by default.
  - A click at 30 % of the track gives 28, stored as `{"v":28,"muted":false}`.
  - Mute gives `aria-pressed=true`, stored as `{"v":28,"muted":true}`.
  - No horizontal overflow.
  - Theme box differences are **0** in all three languages at 1280 and 390.
  - The one clip found is the Japanese footer button "お問い合わせ" (94 > 92 px), from cycle 082. It goes to Follow-ups.

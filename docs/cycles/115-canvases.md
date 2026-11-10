# 115 canvases (D-124; mockup Version 21)

## Goal
The site draws the mockup's two canvases exactly as the mockup's script does (`docs/design/108-ui-design-reference.html`):
- `#sky`, the per-mood background scene, from `part`, `initSky`, `drawSky` and `firework`;
- `#viz`, the visualizer around the disc, from `sizeViz`, `drawViz`, the chalk staff with `DOODLES` and the sakura petal storm.

Both are driven by one `requestAnimationFrame` loop, as in the mockup's `loop`.

## Behaviour
1. **Port.** The drawing code moves into `src/canvas/` modules with the same numbers, colours, counts and timings as the mockup. That includes chalk's staff (14 s per turn, eighth-note chalk ticks, the eraser at half a turn, doodles on kicks at least 1.6 s apart and at most 2 at once) and sakura's 170 petals (the `destination-out` 0.55 trail, `lighter`, speed 0.42 + energy×1.3, 12 % spiralling out). Demo-only inputs are replaced:
   - **The analyser.** A side branch off the site's existing gain node: `gain → DynamicsCompressor (the mockup's settings) → AnalyserNode (fftSize 256, smoothing .78)`. The analyser connects to nothing, so the playback path (source → gain → destination) is unchanged and nothing extra is heard.
     - The compressor matches the mockup's level normalisation before its analyser.
     - As in the mockup, whose analyser sits after its volume, the visualizer follows the player's volume, and a muted player sees a still one.
     - It is created with the audio context, in `App.jsx`, next to the gain.
   - **`phase === 'play'`** becomes "a song is audible" (`isAudible`); `live` is false otherwise.
   - **`song.bpm`.** The real songs have no BPM data, so a fixed 120 is used wherever the mockup reads `song.bpm`. Chalk's ticks and the note hops therefore follow 120, not the song. This is the only numeric deviation, and it is recorded.
   - **Not ported:** `sakuraWreath` and `sakSites` are dead code in the mockup (sakura's viz is `senbon`).
   - **State per mount.** The mockup's module-level drawing state (`parts`, `scene`, `chalkNotes`, `SENB`, `sakPetals` and the like) lives in a state object made by a factory (`createCanvasState()`), one per mount and one per test.
   - **Mood change.** When `html[data-mood]` changes, `initSky()` runs again and the per-mood visualizer state is reset, as the mockup's `setMood` does.
   - **Fireworks.** matsuri fires them at the reveal, as `showCover` does.
2. **Mount.** `canvas#sky` (`aria-hidden`) is the first child of the app root, before `.app`, with `div.scan` after it, as in the mockup. `#viz` is the existing canvas in `.deck`.
   - The DPR sizing follows the mockup (`dpr`, resize handling).
   - The loop runs on the start, lobby and game screens and stops on unmount.
   - **Re-entry guard.** Some existing tests stub `requestAnimationFrame` to call back at once (`App.choiceport.test.jsx`). The loop must not recurse in that case: a re-arm requested while a frame callback is running is deferred to a `setTimeout(0)` or ignored, so the app never recurses synchronously.
3. **Reduced motion and hidden tabs.**
   - Exactly as the mockup's `loop`: under `prefers-reduced-motion: reduce`, `drawSky` is skipped after its still first frame, while `drawViz` still runs each frame (the mockup's choice).
   - When `document.hidden`, the loop does no drawing work.
4. **Fairness.** Drawing never touches playback scheduling. `playback.js` and the start-time computation are untouched.

## Acceptance
1. **`src/canvas/*.test.js`** (new, jsdom with a recording 2d-context stub):
   - for each of the 8 moods, `initSky` and `drawSky` and `drawViz` run without throwing and draw;
   - with a null context nothing throws;
   - the chalk staff draws 5 lines;
   - sakura has 170 petals;
   - the doodle gap is at least 1.6 s and at most 2 doodles are live.
2. **`App.canvas.test.jsx`** (new):
   - `canvas#sky` exists once, before `.app`, with `.scan`;
   - the analyser is created once per audio context, connected from the gain, and never connected to the destination (the fake audio context records `connect` calls);
   - under a mocked reduced-motion `matchMedia`, `drawSky` is not called after the first frame, and `drawViz` is;
   - a `data-mood` change calls `initSky` again;
   - with a `requestAnimationFrame` stub that calls back at once, the app renders a game screen without a `RangeError`;
   - the loop is cancelled on unmount.
3. **Unchanged:** the existing playback, sync and drift tests (`App.sync`, `App.drift`, `audio/playback*.test.js`) pass unmodified, and every other test passes.
4. **Commands:** `npm test`, `npm run lint` and `npm run build` pass. `room.css`, `roomCss.test.js` and `indexCss.test.js` are unchanged.
5. **Browser check (lead):** short screen recordings or frame sequences of the mockup and the site side by side, playing, for chalk, sakura, sunset, rain, neon, pixel, matsuri and manga at 1280×800.

## Spec review (critic, round 1)
- **Blocker:** the re-entry guard, with a test.
- **H2:** reduced motion follows the mockup's `loop` exactly.
- **H3:** a mood change re-initialises the scenes.
- **M4:** a compressor in the tap branch, as in the mockup. The volume affects the visualizer, as in the mockup.
- **M5:** a state factory.
- **Nits:** the BPM effect is recorded, and the dead code is excluded.

## File ownership
- **Test writer:** the new tests, and the fake audio context in `src/testing/` if it needs `createAnalyser`.
- **Implementer:** `src/canvas/*` (new), `src/App.jsx`, `src/components/Deck.jsx` (only the `#viz` hook) and `src/components/GameScreen.jsx` (only to pass `live`). Not `room.css`, not `playback.js`.

## Out of scope
- Real BPM detection.
- Server changes.

# 104 devtools-captures (D-115)

## Decision (owner, 2026-11-10)
"즉 내가 포트폴리오용으로 캡쳐하라고했음에도 불구하고 넌 성능측정을 캡쳐 안한거네?"

- **What was wrong.** Cycle 103 had game-screen captures and charts redrawn from the measurements. It had no capture of the measurement itself.
- **What this cycle adds.** The browser's own DevTools **Network panel** is captured on the live site and put into `docs/performance.md`. Future runs make the same captures with one command.

## Goal
`perf/devtools_shot.py` captures the Edge DevTools Network panel on the live site for three views:
- **page load**, with the cache disabled;
- **one choice song**, while loading and when the audio is done;
- **one chat song**, the same two moments.

No captured image may show a presigned-URL signature.

## How it works (proven by the lead's probe, 2026-11-10)
1. **Launch.**
   - Edge runs as a normal process with `--remote-debugging-port`, `--auto-open-devtools-for-tabs`, `--disable-extensions`, `--no-first-run` and `--lang=ko-KR`, and the start URL is the site.
   - The profile directory is a fresh one under the system temp directory.
   - Playwright attaches with `connect_over_cdp`. The DevTools frontend is the page whose URL contains `devtools_app.html`; there is also a `device_mode_emulation_frame` page, which must not be used.
2. **One-time profile setup.**
   - On the first launch with a new profile, set the DevTools settings through `import('./core/common/common.js')` → `Settings.Settings.instance().createSetting(name, default).set(value)`:
     - `network-log-columns`: `{waterfall:{visible:true}, initiator:{visible:false}, 'fulfilled-by':{visible:false}, source:{visible:false}}`
     - `panel-selected-tab`: `network`
     - `currentDockState`: `undocked`
   - Wait about 12 s so Edge writes them to `Default/Preferences`, then end the process and relaunch.
   - Without the wait the settings are lost. Without undocking, the panel is a narrow strip with a blank page area beside it.
3. **Capture a view.**
   - Click the `Network` tab.
   - Clear the Filter box (Ctrl+A, Delete). Then type `-domain:fonts.gstatic.com -domain:fonts.googleapis.com` for the song views, or nothing for page load.
   - **Page load:** check `Disable cache`, reload the site, wait for network idle, stop recording (Ctrl+E), shoot.
   - **Song views:**
     - Clear storage and reload. Switch the site to 한국어. Create a room, and for choice pick 객관식. Set the song count to 5.
     - **Click `Clear network log` just before 게임 시작**, so only that song's requests show.
     - Shoot 3 s after "음원을 불러오는 중" appears.
     - Then wait for `.stage-hint`, stop recording, and shoot again.
     - Also save a game-page screenshot at each moment.
4. **Secret guard.**
   - Before every DevTools screenshot, walk every text node, including those in shadow roots, and replace everything from `?` onwards with `?…` in any node containing `X-Amz-`, `Signature=` or `Credential=`.
   - After the screenshot, collect the deep text again and run `perflib.contains_secret` on it. If anything matches, the image may be stale, so delete it and retake, up to 30 times. If no clean image results, exit non-zero with no image left.
   - The probe showed that a row appearing between the clean-up and the screenshot leaked a token fragment once. That image was deleted. This is why the post-check exists.
5. **Always end the Edge process tree** (`taskkill /PID <pid> /T /F`), even on error.

## Output
`perf/results/shots/<label>-devtools-pageload.png`, `-devtools-choice-loading.png`, `-devtools-choice-done.png`, `-devtools-chat-loading.png`, `-devtools-chat-done.png`, and `-game-{choice,chat}-{loading,done}.png`.

## Acceptance
1. **Tests** (`perf/test_devtools_shot.py`, pure, no browser or network):
   - `edge_args(profile, port, url)` contains each required flag exactly once.
   - `shot_paths(label)` returns the file names above.
   - `deep_text_has_secret(texts)` is true when any text contains a marker and false for the sanitized `?…` form. It reuses `perflib.contains_secret`.
   - `FONT_FILTER` equals the filter string above.
   - The CLI rejects a bare positional argument and an unknown `--views` value.
2. **CLI.** `python -X utf8 perf/devtools_shot.py --label <label> [--base URL] [--views pageload,choice,chat]`. The defaults are the live site and all three views.
3. **Live run by the lead.** All files are produced. The lead reads every image and confirms there is no signature text. The images go into `docs/performance.md`.
4. `data/.venv/Scripts/python.exe -m pytest perf -q` passes. The locked tests are unchanged.

## File ownership
- **Test writer:** `perf/test_devtools_shot.py`.
- **Implementer:** `perf/devtools_shot.py`, and `perf/README.md` (a usage section).
- **Lead:** `docs/performance.md`, the captures under `perf/results/shots/`, and the cycle and backlog docs.

## Out of scope
- Changing `run.py` metrics.
- Any site change.

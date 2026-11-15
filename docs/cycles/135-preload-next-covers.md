# 135 preload-next-covers (F-038, from D-133; after B-039)

## Goal
In a choice room, the client loads the next song's covers in the background during the reveal, so the next song's `ready` does not wait for them.

## Background
- Cycle 134 (B-039): in a choice room every `reveal` that has a next song carries `nextChoices`, the next song's choices exactly as its `prepare` will carry them (a list of `{coverUrl}` rows; the same shape as `prepare.choices`).
- Today `preloadChoices(r)` (frontend/src/game/session.js:449-470) starts one `Image` per cover when the `prepare` arrives, counts `r.imagesLeft`, and `maybeReady` (:438-444) sends `ready` only once the audio and every image have settled (loaded or failed). Covers that load slowly delay the next song for everyone.
- `choiceUrls` (session.js:77) validates the cover URLs of a `prepare` against the allow-list; a URL that fails becomes `null` (an empty tile).

## Design
- A session-level cover store keyed by URL, holding each cover's `Image` object and its state: loading (with the callbacks waiting on it), loaded, or failed. The `Image` object is kept, not just the state: `/cover/` answers with `Cache-Control: no-store` (CoverController.java:121, 151; in S3 mode a 302 to a presigned URL), so the browser cache cannot be relied on, and a kept image is the only reuse the client controls.
- `onReveal` and `showSnapshotReveal` (the snapshot reveal of cycle 131, session.js:646, a separate path): when the reveal has `nextChoices`, each validates them with `choiceUrls` and starts loading every valid URL not already in the store, with an `Image` that is never shown. Nothing reaches the reducer or the screen.
- `preloadChoices(r)` at the `prepare`: a cover already loaded in the store does not count in `imagesLeft`; a cover still loading counts and settles the round when it settles (one load, not two); a cover that failed during the reveal, and any other cover, loads as today (a failure during the reveal gets one more try). So a `prepare` whose covers all loaded during the reveal can send `ready` as soon as its audio is in.
- The store is cleared when a game ends or is left (`gameOver`, `abandon`, the session's shutdown). No eviction inside a game: a game has at most 30 songs of at most 8 covers.
- A reveal without `nextChoices` (a chat room, the last song, an older server) changes nothing.

## Acceptance criteria (Vitest unless said otherwise)
1. A choice-room reveal with `nextChoices` (4 covers): four images are requested at once; no tile, count or card change shows them; the screen is as without the key.
2. When those images have loaded and the next `prepare` arrives with the same covers, `ready` is sent as soon as the audio decodes, with no new image request for those URLs.
3. If one of them is still loading when the `prepare` arrives, `ready` waits for that load to settle, and no second request is made for it.
4. A cover that failed during the reveal is requested again at the `prepare`, and `ready` waits for that request as today.
5. A URL that fails the allow-list in `nextChoices` is not requested.
5a. A rejoin snapshot (and a watching snapshot) whose `song.reveal` has `nextChoices` requests those covers at once, as a live reveal does.
6. A reveal without `nextChoices` requests no image; a chat room's flow is unchanged; every existing test passes unchanged.
7. `npm test`, `npm run lint` and `npm run build` pass from `frontend/`.
8. Browser check (lead), local server with production settings and covers served from disk: a choice room's next song requests its covers during the reveal (network log), and the tiles of the next song show without a second request for them. (Disk mode only; the S3 redirect is not exercised locally.)

## Test list
- New `frontend/src/App.nextCovers.test.jsx`: criteria 1 to 6, with 5a. Models: the image stub and choice-room flow in `App.prefetch.test.jsx` and `App.choice.test.jsx`, the window helpers in `App.window.test.jsx`.

## File ownership
- Test writer: the new test file.
- Implementer: `frontend/src/game/session.js` (and `state.js` only if needed). No CSS.

## Out of scope
- Any server change; showing the next covers early; audio timing (D-119 unchanged).

## Spec review
One round. No Blocker; two High and two Medium, all taken.
- High: `/cover/` is sent with `no-store` (and in S3 mode as a redirect to a presigned URL), so a fire-and-forget load cannot be trusted to be reused. The store keeps the `Image` objects themselves.
- High: the snapshot reveal of cycle 131 is a separate path (`showSnapshotReveal`), not `onReveal`; it now starts the preload too, with criterion 5a.
- Medium: eviction inside a game was not asked for and could drop a pending callback; dropped (clearing at the game's end bounds the store).
- Medium: a cover that failed during the reveal now gets one more try at the `prepare` (criterion 4) instead of being final.
- Checked by the critic: `nextChoices` has the shape `choiceUrls` expects; a held `prepare` loads at once and uses the store; nothing reaches the screen early; the existing image stubs can drive every criterion.

## Result
- Tests first: commit `970af79`. `App.nextCovers.test.jsx` (new, 18 cases). Red, run by the lead: 878 tests, 11 failed, all in the new file, each an assertion that the next covers were not requested.
- Implementation: commit `f679ca9`, first attempt (`session.js` +58): a session cover store that keeps the `Image` objects, `preloadNext` from `onReveal` and `showSnapshotReveal`, `preloadChoices` reusing loaded and loading entries, the store cleared at `gameOver`, `abandon` and shutdown.
- Green, run by the verifier: `npm test` 57 files, 878 tests passed; lint exit 0; build exit 0. The locked test is unchanged since `970af79`. Criteria 1 to 7 confirmed.
- Review of the diff: no Blocker, High or Medium. One Nit to Follow-ups: a rejoin snapshot does not clear the store (harmless, bounded).
- Criterion 8, by the lead in Chromium against the local server (production settings, 60 placeholder songs of distinct series, 108 placeholder covers served from disk), four players in a choice room: the reveal frame carried 6 `nextChoices`; at the frame's arrival (16.3 s) the 5 covers not already loaded were requested; song 2 showed those 6 covers with each requested once in the whole run, and played about 3 s after song 1's music ended. A first run with 16 placeholder songs could not show it: their next covers were the same as the current ones.
- Merged straight to `dev` without a PR or CI, under the owner's new process (D-134, same day).

# 093: anime cover on the reveal (B-020)

From S-007, design `docs/design/088-songs-3000-covers.md` (option F3), decisions D-103 and D-104. The data side exists: `songrush_data.covers` (cycle 090) writes `data/cover-cache/index.json` and `<id>.jpg`; `songrush_data.s3covers` (cycle 092) uploads them to `c/<id>` and writes `data/cover-cache/s3-covers.json`.

## Goal

The reveal message carries `coverUrl` (`/cover/<id>` or null), and `GET /cover/{id}` serves that cover, from disk or by a redirect to S3, while no message before the reveal carries any cover id.

## Behaviour

- **Cover map** (new, package `com.songrush.cover`): loaded once at startup into an immutable `animeId -> coverId` map and its reverse.
  - Disk mode reads `<data-dir>/cover-cache/index.json` (`{"version": 1, "covers": {"<animeId>": {"id", "file", "size"}}}`); a cover counts only when `<id>.jpg` exists with that size.
  - S3 mode (the existing `S3ModeCondition`) reads `<data-dir>/cover-cache/s3-covers.json` (`{"<animeId>": {"id", "key", "size"}}`) and keeps `id -> key`.
  - Every entry is checked: `id` matches `^[A-Za-z0-9_-]{1,64}$`, `file` equals `<id>.jpg` (disk) or `key` equals `c/<id>` (S3), size is a positive integer, anime ids are integers. A bad entry is skipped with a WARN that names no id.
  - A missing or unreadable file, or one that is not the expected JSON shape, logs one WARN and gives an empty map. **Startup never fails because of covers** (unlike the audio map).
  - The map's `toString` and logs show counts only.
- **Reveal**: `Room.endSong` adds `"coverUrl"` to the existing reveal message: `"/cover/" + id` when the song's anime has a cover, else `null`. No other message (prepare, start, song, settings, state snapshots, reconnect snapshot, gameOver) carries a cover id or `coverUrl`. Existing constructors of `Room` and `Lobby` keep working (a no-cover default), so tests that build them directly need no change.
- **`GET /cover/{id}`** (new controller):
  - Unknown id, or an id not matching the id pattern: 404 through the existing JSON error handling (`{"error":"not found"}`), before any body byte.
  - Disk mode: 200 `image/jpeg` with the file bytes, `Cache-Control: no-store`, no `Last-Modified`, `ETag` or `Content-Disposition`; a file that cannot be opened is 404.
  - S3 mode: 302 to a 60-second presigned GET URL of the cover's key in the audio bucket (same presigner settings as `S3AudioLinks`), `Cache-Control: no-store`, no body; a signing failure is 503.
  - An audio token sent to `/cover/` is 404, and a cover id sent to `/audio/` is 404 (separate maps; no shared store with `AudioTokens`).
  - Logs carry at most the first 6 characters of the id.

## Acceptance criteria

1. With a cover for the song's anime, the reveal message has `coverUrl` equal to `/cover/<id>`; without one it has `coverUrl: null`.
2. No message sent before the reveal, and no reconnect or state snapshot, contains the cover id (search every outbound message text for the id).
3. Disk mode: `GET /cover/<id>` returns 200, `image/jpeg`, the exact file bytes, `Cache-Control: no-store`, and none of `Last-Modified`, `ETag`, `Content-Disposition`. Unknown id: 404 JSON.
4. S3 mode: `GET /cover/<id>` returns 302 with a `Location` presigned for bucket and key `c/<id>`, `Cache-Control: no-store`, empty body. Unknown id: 404.
5. A current audio token at `/cover/<token>` is 404; a cover id at `/audio/<id>` is 404.
6. A missing, malformed or wrong-shape cover map logs WARN, startup succeeds, and every `coverUrl` is null. A bad entry is skipped and the others still work; a disk entry whose file is missing or the wrong size is not served.
7. `.\gradlew.bat test` from `backend/` exits 0.

## Test list

- `backend/src/test/java/com/songrush/cover/CoverMapTest.java`: loading rules (criterion 6) for both formats.
- `backend/src/test/java/com/songrush/cover/CoverEndpointTest.java`: disk-mode HTTP (criteria 3, 5) on a real random port with `songrush.data-dir` pointing at a temp folder, in the style of `AudioEndpointTest`.
- `backend/src/test/java/com/songrush/cover/S3CoverEndpointTest.java`: S3-mode HTTP (criterion 4) in the style of `S3AudioEndpointTest` and `S3Fixture`.
- `backend/src/test/java/com/songrush/game/RoomCoverTest.java`: criteria 1 and 2 in the style of the existing Room tests (`GameFixture`).

## File ownership

- Test writer: the four test files above (and new fixtures under `backend/src/test/resources/` if needed).
- Implementer: new files under `backend/src/main/java/com/songrush/cover/`; changes to `game/Room.java`, `game/Lobby.java` and wherever `Lobby` is created (to pass the cover map); no change to `audio/` classes except reading shared constants if needed.

## Out of scope

Showing the cover (F-022), tiers (B-021), running the data tools (I-012).

# 083 feedback-admin (D-097)

## Decision (owner, 2026-11-07)
"바로 Issues로 연동되는것은 아니고 관리자 화면에서 내가 문의를 따로 체크하면 그때 Issues로 가는걸로"

1. **Admin login** uses Nginx Basic Auth on `/admin`. The password lives in an `htpasswd` file on the server. The game server has no login code.
2. **Storage.** Feedback is stored in one file on the server (`/opt/songrush/feedback/`). There is no database.
3. **Admin actions** per item:
   - **Send to Issues.** The title can be edited first. Afterwards the issue number and link are shown.
   - **Hide** a junk item, and restore it.

This changes cycle 082: a public post no longer creates an issue. It is stored, and only the owner's action creates one.

## Backend
1. **Config.**
   - **`songrush.feedback.store-dir`** (env `SONGRUSH_FEEDBACK_DIR`, default empty).
     - When it is blank, storage is disabled, and `POST /api/feedback` answers 503 `feedback-disabled`.
     - The public form now needs only the store, not the token.
   - **`songrush.feedback.token`** is still needed, but only for the admin send action.
   - **`songrush.admin.key`** (env `SONGRUSH_ADMIN_KEY`, trimmed, hidden in `toString`) is a second lock behind Basic Auth.
     - Nginx adds `X-Admin-Key: <key>` only inside `location /admin`. Every `/admin/api/**` request must carry that header with an equal value, compared in constant time.
     - When the key is blank, or the header is missing or wrong, the answer is **404**: no hint that an admin API exists.
     - **The key check is the authoritative lock.** It runs in a filter or interceptor on the matched handler, not on the raw URI string. Nginx Basic Auth is the first lock: `/admin;x/...`-style paths can reach Spring through other nginx locations.
2. **`FeedbackStore`** keeps one file, `<store-dir>/feedback.json`.
   - **Writing.** The whole file is rewritten atomically: write a temp file in the same folder, then `Files.move` with `ATOMIC_MOVE`.
     - Fall back to `REPLACE_EXISTING` **only** on `AtomicMoveNotSupportedException`. Any other failure deletes the temp file and propagates.
     - Every access is serialised with a lock.
   - **Startup.**
     - The file is loaded at startup; a missing file means an empty list.
     - Leftover `feedback.json.*.tmp` files are deleted.
     - When store-dir is set, the folder is created if missing, and the store probes it by writing and deleting a temp file. If that fails, it logs ERROR `feedback: store-dir <dir> not writable (uid N)` and treats storage as disabled (503 `feedback-disabled`).
   - **Record fields:** `id`, `createdAt`, `category`, `title`, `body`, `lang`, `page`, `status`, `issueNumber`, `issueUrl` and `updatedAt`.
     - `id` is 12 random URL-safe characters.
     - `createdAt` is an ISO instant from the injected `Clock`.
     - `status` is `new`, `sent` or `hidden`.
     - `issueNumber` and `issueUrl` are set once the item is sent.
   - **Caps.**
     - With 1000 items in `new`, a post gives 503 `{"error":"feedback-full"}`.
     - When there are more than 2000 records in total, the oldest `sent` or `hidden` records are pruned.
   - **Corrupt file.** An unparsable file at startup is renamed to `feedback.json.broken-<epochMillis>`, logged at ERROR, and replaced by an empty list. It is never overwritten silently.
3. **`POST /api/feedback`** keeps every cycle 082 rule: validation, honeypot, limits, and what counts toward the limits.
   - On acceptance it **stores** a `new` record and answers 201 `{"ok":true}`. It makes no GitHub call.
   - A store I/O failure gives 502 `feedback-failed`.
4. **The admin API** lives under `/admin/api`. All responses carry `Cache-Control: no-store`.
   - **Required headers.** Every request needs the admin key header. Every mutating request also needs `X-Requested-With: songrush-admin` and the media type `application/json` (parameters allowed).
     - The handler or interceptor checks these, not `consumes` (which would answer 415).
     - Without them the answer is 403 `{"error":"forbidden"}`. A custom header forces a CORS preflight, which the app never allows, so another site cannot drive the owner's browser.
   - **`GET /admin/api/feedback?status=new|sent|hidden|all`** (default `new`) returns `{"items":[...]}`, newest first.
   - **`POST /admin/api/feedback/{id}/send`** with body `{"title": optional}`.
     - An edited title follows the 082 title rules. An invalid one gives 400 `bad-feedback`.
     - With no token: 503 `{"error":"github-disabled"}`.
     - **Issue content.** It uses the 082 title, labels and body format, built from the stored record, including its `createdAt`. With an edited title, the body also records the original title in its own fence.
     - **Success:** the record becomes `sent` with `issueNumber` and `issueUrl` (from GitHub's `number` and `html_url`), and the answer is 200 with the record.
     - **Errors:**
       - already sent: 409 `already-sent`;
       - unknown id: 404 `not-found`;
       - a GitHub failure: 502 `feedback-failed`, and the record stays `new`.
     - **In flight.** While a record is in flight, send, hide and unhide on it all answer 409 `in-flight`.
       - The in-flight mark is memory-only. It is set and cleared under the lock.
       - The lock is **not** held during the GitHub call, so a 20 s call cannot block public posts.
     - **Storing after GitHub succeeded.** If the store write fails after GitHub created the issue, the record stays `sent` in memory, the server logs ERROR with the issue number, and it answers 200 with the record plus `"persisted":false`. The next successful write persists it.
     - **An edited title.** The original title goes in its own fence, with the same backtick and `@` rules as cycle 082.
   - **`POST /admin/api/feedback/{id}/hide`** and **`/unhide`** switch between `new` and `hidden`.
     - A `sent` record gives 409 `already-sent`.
     - An unknown id gives 404.
     - Success gives 200 with the record.
5. **`IssueSender.send` returns `CreatedIssue(int number, String url)`.**
   - `GitHubIssueSender` parses `number` and `html_url` from the 2xx body.
   - A 2xx body without them throws `IssueSendException`.
   - This is a **declared rewrite** of the locked `GitHubIssueSenderTest` success cases and of `FeedbackControllerTest`: POST now stores instead of sending, and "disabled" now means a blank store-dir. The test writer lists every changed test.
6. **`/admin` and `/admin/` serve the SPA `index.html`**, the same way `/r/{code}` does.
7. **The Dockerfile** fixes the container user's id: `useradd --system --uid 10001 songrush`. The owner can then `chown` the host folder to 10001.

## Frontend
7. **Routing.** When `location.pathname` is `/admin` or starts with `/admin/`, App renders `AdminScreen` instead of the game. No game socket opens and there is no footer feedback button. The admin screen is **Korean-only**, because only the owner uses it, so its strings are not in the i18n files.
8. **`AdminScreen`.**
   - **Tabs:** `새 문의`, `보낸 문의` and `숨긴 문의`, each with a count once loaded.
   - **Each item** shows:
     - category (버그 / 건의 / 곡 요청 / 기타), the local date and time, lang and page;
     - the title, as an editable input (maxLength 80) on `new` items;
     - the body as plain text with `white-space: pre-wrap`, never as HTML.
   - **Buttons:**
     - `new` items: `Issues로 보내기` and `숨기기`;
     - `hidden` items: `다시 보이기`;
     - `sent` items: a link `#N` to `issueUrl`, which opens in a new tab with `rel="noopener noreferrer"`.
   - **While a request runs**, that item's buttons are disabled. On success the item moves tabs without a reload.
   - **Errors** are shown inline:
     - `github-disabled`: "GitHub 토큰이 설정되지 않았습니다";
     - `already-sent`;
     - `feedback-failed`: "실패했습니다. GitHub에 이미 만들어졌는지 확인한 뒤 다시 시도하세요";
     - network failure.
   - A 404 on the list, meaning no admin access, shows "관리자 기능이 꺼져 있습니다".
   - Every request sends `X-Requested-With: songrush-admin`, and POSTs also send `Content-Type: application/json`.
9. **Layout.** It works at 390 px with no horizontal scroll. The room theme rules are untouched.

## Acceptance
### Backend tests (`com.songrush.feedback`)
- **`FeedbackStoreTest`** (temp dir, `MutableClock`):
  - add, then reload in a new instance, keeps every field;
  - the atomic rewrite leaves no temp files;
  - status changes persist;
  - the `new` cap gives `feedback-full`;
  - the 2000 cap prunes the oldest sent or hidden records;
  - a corrupt file is renamed and logged, and the store starts empty;
  - leftover tmp files are removed at startup;
  - an unwritable folder disables storage (skipped where the OS cannot make a folder read-only for the test user);
  - concurrent adds from many threads all persist.
- **`FeedbackControllerTest`** (declared rewrite):
  - an accepted post stores a `new` record and makes no send;
  - a blank store-dir gives 503;
  - the 082 validation, honeypot and limit cases still hold;
  - a store failure gives 502;
  - a full store gives 503 `feedback-full`.
- **`AdminFeedbackControllerTest`:**
  - a missing, wrong or blank key gives 404;
  - a mutating request without `X-Requested-With` or a JSON content type gives 403;
  - list filtering and order;
  - send:
    - an edited title is used;
    - an invalid edited title gives 400;
    - no token gives 503;
    - success stores the number and URL;
    - 409 on a second send, and on a concurrent second send with exactly one sender call;
    - hide during an in-flight send gives 409;
    - a store failure after a successful send gives 200 with `persisted:false`;
    - unknown id gives 404;
    - a sender failure gives 502 and the record stays `new`;
  - hide and unhide, with 409 on a sent record;
  - every response has `Cache-Control: no-store`.
- **`GitHubIssueSenderTest`** (declared rewrite): a 201 body `{number, html_url}` is parsed; a 201 body without them throws. Every other case is unchanged.
- **`RoomPageController` or its test:** `GET /admin` and `GET /admin/` serve `index.html`.

### Frontend tests (`App.admin.test.jsx`, fake `fetch`)
- Pathname `/admin` renders the admin screen, opens no socket, and shows no feedback button.
- The list loads with the required header, and tab counts show.
- Send posts the edited title; the item moves to `보낸 문의` with the link `#N`.
- Hide and restore.
- Buttons are disabled while a request runs.
- `github-disabled` text; a 404 list shows the off text.
- The body renders as text: `<b>x</b>` stays literal.

### Commands
All earlier tests pass, apart from declared rewrites. `.\gradlew.bat test`, `npm test`, `npm run lint` and `npm run build` exit 0.

### Lead's real check
- The jar runs with a temp store-dir, an admin key, and a token pointing at the local fake GitHub.
- Playwright:
  1. submits two inquiries from the site;
  2. opens `/admin` with the `X-Admin-Key` header injected, playing nginx's part;
  3. sends one, with an edited title, and hides the other;
  4. confirms the fake GitHub received exactly one issue;
  5. restarts the jar and confirms the statuses persist.

## Owner tasks (ops, after deploy; the lead gives exact commands)
- **Folder.** Create `/opt/songrush/feedback`, owned by the container user. Mount it read-write into the app as `/feedback`, and set `SONGRUSH_FEEDBACK_DIR=/feedback`.
- **Secrets.** Put `SONGRUSH_ADMIN_KEY` (a long random string) and `SONGRUSH_FEEDBACK_TOKEN` into `feedback.env`, mode 600, through `env_file`.
- **Password.** Create `/opt/songrush/nginx/htpasswd`, using bcrypt with an interactive password prompt so the password never appears in shell history.
- **Folder owner.** The container uid is fixed at 10001: `sudo install -d -o 10001 -g 10001 -m 700 /opt/songrush/feedback`.
- **Nginx.**
  - Add `location ^~ /admin { auth_basic ...; auth_basic_user_file ...; proxy_set_header X-Admin-Key <key>; ... }`.
  - Keep the prefix without a trailing slash: `location /admin/` would let `/admin;x/api/...` fall through to `location /` without auth.
  - `^~` stops regex locations from taking `/admin` paths.
  - A `proxy_set_header` inside the block drops the inherited ones, so repeat Host, X-Forwarded-For, X-Forwarded-Proto and the rest.
- **Outside-in check after reload.**
  - `/admin/api/feedback`, `/admin;x/api/feedback`, `//admin/api/feedback` and `/%61dmin/api/feedback` must all answer 401 without credentials.
  - With credentials, `/admin/api/feedback` answers 200.
  - `/api/feedback` with a forged `X-Admin-Key` never reaches admin.
- **Privacy note.** Deleting a record is a manual edit on the server, for example when someone writes personal data in a message.
- **Labels.** Create `문의` and `song-request` in the repo.

## File ownership
- **Backend test writer:** `backend/src/test/java/com/songrush/feedback/**`, plus the web test for `/admin` under `backend/src/test/java/com/songrush/web/`.
- **Backend implementer:** `backend/src/main/java/com/songrush/feedback/**`, `backend/src/main/java/com/songrush/web/RoomPageController.java` and `Dockerfile` (the uid line only).
- **Frontend test writer:** `frontend/src/App.admin.test.jsx`.
- **Frontend implementer:** `frontend/src/components/AdminScreen.jsx` (new), `frontend/src/game/admin.js` (new), `frontend/src/App.jsx` and `frontend/src/index.css`.

## Out of scope
- A login form or sessions in the app.
- Editing the body.
- Bulk actions.
- Notifications to the owner when new feedback arrives.
- Replying to users.

## Spec review (critic, round 1)
- **H1.** The nginx block form. Applied: `location ^~ /admin` with no trailing slash, and the key check on the matched handler.
- **H2.** Nothing tested the nginx boundary from outside. Applied: an outside-in curl check in the owner tasks; the lead gives the commands.
- **H3.** The container uid was not fixed. Applied: uid 10001, plus a writable probe at startup.
- **H4.** A send in flight could race with hide or unhide. Applied: all three answer 409 `in-flight`.
- **M1.** A store failure after a successful send could lead to a duplicate issue. Applied: the item stays `sent` in memory and the answer carries `persisted:false`; the timeout case gets clearer wording.
- **M2.** Applied: the move falls back only on `AtomicMoveNotSupportedException`, and tmp files are cleaned at startup.
- **N1–N3** applied: `/admin/` is served too, the media type is checked in the handler (403, not 415), and the original-title fence follows the 082 rules.
- **Checked by the critic and found sound:**
  - the CSRF reasoning (no CORS configuration, and the preflight meets Basic Auth first);
  - case variants;
  - nginx normalisation of `//`, `..` and percent-encoding;
  - atomic rename on Linux with a folder mount.

## Result
- **Commits.**
  - Tests: `0cd3547`.
  - Declared test fix: `340180d`. `FakeIssueSender.reset()` did not reset its counter, so two tests asserting #100 could not both pass in the cached context. The implementer reported it, and the lead confirmed it by reading the test.
  - Implementation: `a4a30d9`.
  - Attempt 2: `8b686f3`.
- **Verifier at `8b686f3`.**
  - Backend: 590 tests, 0 failures, 1 skipped. `FeedbackStoreTest.readOnlyFolderDisablesStorage` needs POSIX permissions, so it skips on Windows and runs on Linux CI.
  - Frontend: 369 tests; lint and build exit 0.
  - The locked-test diff against `340180d` is empty.
- **Declared rewrites.**
  - `FeedbackControllerTest`: POST now stores, and "disabled" means a blank or unwritable store-dir. The issue-format assertions moved unchanged to `AdminFeedbackControllerTest`.
  - `GitHubIssueSenderTest`: success now returns `CreatedIssue`.
  - Each file has a table of what changed.
- **Code review** (critic, on the combined 082+083 diff). No Blocker or High.
  - **M1:** the UI always sends the title, so every issue got a duplicate "원래 제목" fence. Now an unchanged title is not an edit.
  - **M2:** `persisted:false` was not shown. The item now shows a warning.
  - **M3:** with the store disabled, the admin list looked empty. It now answers 503, and the screen says the store is off.
  - All three were fixed in attempt 2.
  - **Nits** go to Follow-ups: 405 and OPTIONS skip the interceptor (the routes are already visible in the public bundle, and no data is exposed); the sent tab shows the stored title rather than the edited one; anti-framing is handled in the owner's nginx block.
- **Lead's real check.** Local jar, a temp store, test-only admin key and token, and a local fake GitHub on 127.0.0.1:9911. Playwright on Edge:
  - Two inquiries submitted from the site gave **0** GitHub calls.
  - `/admin/api/feedback` without the key: **404** `{"error":"not found"}`. With the key: 200.
  - The admin page without the key shows "관리자 기능이 꺼져 있습니다".
  - With the key, the tabs read `새 문의 (2)`, then `(0)/(1)/(1)` after sending one with an edited title and hiding the other.
  - The sent link `#1` has `rel="noopener noreferrer"`. At 390 px there is no horizontal overflow.
  - **The fake GitHub received exactly 1 request.**
    - Path `/repos/kimdoodoo12/SongRushApp/issues`, Bearer token correct, API version `2022-11-28`.
    - Title `[문의/곡 요청] 프리렌 1기 OP 추가 요청`, labels `["문의","song-request"]`.
    - The body has the original title and the user text, each in a four-backtick fence; `<b>x</b>` stays literal.
  - **After a restart,** the statuses persisted: `hidden`, and `sent #1`.
  - **A send with an unchanged title** produced no "원래 제목" fence (2 fence markers, that is, one fence).
- **Process notes.**
  - `.claude/state/locked-paths.txt` disappeared between the lead's lock and unlock twice in this cycle. The lead's own unlock printed "no locked paths". The cause is not identified. The verifier's `git diff <test commit> HEAD` check remained the guard, and it was empty each time.
  - Overlapping Gradle runs corrupted `build/test-results/test/binary` once. The verifier's `cleanTest test` regenerated it.

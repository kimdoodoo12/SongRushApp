# 082 feedback (D-096)

## Decision (owner, 2026-11-07)
"문의하기 같은것도 만들고싶은데 ... github issue에 연동하는 기능"

- **Inside the site.** Players send feedback through a form. They need no GitHub account.
- **Where it lands.** The server creates an issue in the private code repository `kimdoodoo12/SongRushApp`. Only the owner sees it, so there is no moderation or approval step.
- **What the player sees.** After sending, the player sees only "received". Progress is shown inside GitHub, not on the site: the cycle that handles an inquiry cites `#N`, and the dev → main PR says `Closes #N`, so a single issue shows the whole path from inquiry to deploy.
- **No contact field.** No personal data is collected.

## Goal
A "문의하기" link in the footer opens a form. Submitting it creates a labelled GitHub issue through the server, which applies rate limits and keeps the token on the server only.

## Backend
1. **Config** (`songrush.feedback.*`, bound like `SongrushProperties`):
   - `token` (env `SONGRUSH_FEEDBACK_TOKEN`, default empty);
   - `repo` (default `kimdoodoo12/SongRushApp`);
   - `api-base` (default `https://api.github.com`; tests point it at a local fake).
   - The token is trimmed when bound. When it is blank, the feature is **disabled**.
   - The token never appears in logs, responses, exceptions or `toString`; the properties type overrides `toString`.
2. **No status endpoint.** The client learns "disabled" from the POST's 503.
3. **`POST /api/feedback`** takes a JSON body of at most 8 KB:
   ```
   {category, title, body, lang, page, website}
   ```
   - `category` is one of `bug`, `idea`, `song` or `other`.
   - `title` is 1-80 and `body` 1-2000 UTF-16 code units (`String.length()`, matching HTML `maxLength`), both after trimming.
   - The title allows no control characters. The body allows newline and tab and no other control characters.
   - `lang` is one of `ko`, `en` or `ja`; anything else is stored as `?`.
   - `page` is one of `start`, `lobby` or `game`; anything else is stored as `?`.
   - `website` is a honeypot that must be empty or missing.

   **Responses:**
   - **Disabled:** 503 `{"error":"feedback-disabled"}`.
   - **Invalid:** a bad category, bad lengths, malformed JSON or an over-size body gives 400 `{"error":"bad-feedback"}`.
   - **Honeypot filled:** 201 `{"ok":true}`, and **no** issue is created.
   - **Rate limit:** at most 3 counted posts per client address per 10 minutes, and at most 30 per hour in total. Over the limit gives 429 `{"error":"rate-limited"}` with a `Retry-After` header.
     - **Counted:** a post that passes validation and the honeypot is counted before the GitHub call, whether that call then succeeds or fails. Honeypot, invalid, disabled and 429 posts are not counted.
     - **Windows:** both limits are sliding windows over the stored counted timestamps. The per-address check runs first.
     - **`Retry-After`:** the seconds until the oldest counted timestamp in the violated window expires, rounded up, minimum 1.
     - The client address is `request.getRemoteAddr()`, which already honours `X-Forwarded-For` through `server.forward-headers-strategy=native`.
     - The clock is injectable for tests.
   - **Success:** the server makes one call to `POST {api-base}/repos/{repo}/issues`, then answers 201 `{"ok":true}`. The issue number is not returned.
   - **GitHub failure:** any exception while building or sending the request, or a non-2xx answer, gives 502 `{"error":"feedback-failed"}`. The log records the GitHub status, never the token or the user text. On 401/403 it logs at ERROR: `feedback: GitHub rejected the token (status N) - renew SONGRUSH_FEEDBACK_TOKEN`.
4. **The GitHub call.**
   - **Headers:**
     - `Authorization: Bearer <token>`
     - `Accept: application/vnd.github+json`
     - `X-GitHub-Api-Version: 2022-11-28`
     - `User-Agent: SongRushBot/1.0 (+https://animeopguessr.com)`
     - `Content-Type: application/json`
   - **Timeouts:** connect 10 s and request 20 s, using the JDK `java.net.http.HttpClient`. No new dependency.
   - **Request body:** `{"title", "body", "labels"}`.
     - **title:** `[문의/<category ko>] <title>`. The Korean category names are 버그, 건의, 곡 요청 and 기타. Every `@` is replaced with `＠` (U+FF20), so the title cannot mention anyone.
     - **body:** Markdown that includes:
       - category, language, page and the UTC time in ISO-8601;
       - the user's text in a fenced block (four backticks, `text`), with any run of 4 or more backticks shortened to 3, so the text cannot break out of the fence, mention people or render HTML;
       - the footer `_사이트 문의하기로 접수됨_`.
     - **labels:** `["문의", "<category label>"]`. The category labels are `bug`, `enhancement`, `song-request` and `question`.

## Frontend
5. **Footer link.** A `문의하기` button, translated in all three languages, sits in the footer next to the language switcher on every screen. It opens a modal `role="dialog"` with `aria-modal="true"`, labelled by its heading `문의하기`.
6. **The form.** The dialog always shows a form with:
     - a category select (버그 / 건의 / 곡 요청 / 기타);
     - a title input (maxLength 80) and a body textarea (maxLength 2000) with a live counter;
     - a visually hidden honeypot input named `website`, with `tabIndex=-1` and `autoComplete="off"`, hidden from assistive tech;
     - submit and close buttons.
7. **Submitting.**
   - The app sends `{category, title, body, lang: current UI language, page: 'start'|'lobby'|'game', website}`. Submit is disabled while the request is in flight or while the title or body is empty after trimming.
   - **On 201:** the form is replaced by a thank-you message plus a close button.
   - **On 503 `feedback-disabled`:** the form is replaced by a "preparing" message.
   - **On other errors:** the form stays filled and shows a translated message for `rate-limited`, `bad-feedback`, `feedback-failed` and network failure.
   - Escape and the close button close the dialog, and focus returns to the footer button.
8. **Layout.** No layout properties go in the room theme rules: the themeLayout test stays green. The dialog uses the theme colour tokens only.

## Acceptance
### Backend tests
- **`FeedbackControllerTest`** (MockMvc or the Spring test client, with a fake `IssueSender` bean):
  - disabled POST gives 503;
  - each invalid input gives 400, with no send;
  - the honeypot gives 201 with no send;
  - a valid post gives 201, and the fake receives the right title, labels and body content;
  - `@` is replaced in the title;
  - the user text sits inside the fence, and backtick runs are shortened;
  - the 4th post from one address within 10 minutes gives 429 with `Retry-After`;
  - a different address is still accepted;
  - after the clock advances 10 minutes the post is accepted again;
  - the global hourly cap applies;
  - a send failure gives 502 and still consumes quota;
  - invalid, honeypot and 429 posts do not consume quota;
  - the `Retry-After` value follows the rule;
  - a control character in the title gives 400;
  - a properties object with token `t0k-secret` has a `toString()` without it;
  - a token with a trailing `` or spaces is trimmed.
- **`GitHubIssueSenderTest`** (JDK `HttpServer` on localhost as a fake GitHub):
  - the method, path and all headers are exact;
  - the JSON body round-trips;
  - 201 counts as success;
  - 401, 422 and 500 throw the sender's failure exception;
  - a server that never answers fails within the timeout (configurable to be short in the test);
  - the token does not appear in the exception message.

### Frontend tests (`App.feedback.test.jsx`, with a fake `fetch`)
- The footer button opens the dialog.
- A 503 shows the preparing text.
- An enabled form submits the exact JSON, including `lang` and `page`.
- Submit is disabled when the title or body is empty.
- 201 shows thanks.
- 429 shows the rate-limited text, and the inputs are kept.
- Escape closes the dialog and focus returns to the button.
- The texts in English (after switching) come from `en.json`.
- The `keys.test.js` parity still holds, because the new keys go in all three files.

### Commands
All earlier tests pass. `.\gradlew.bat test`, `npm test`, `npm run lint` and `npm run build` exit 0.

### Lead's real check
The jar runs with a token pointing `api-base` at a local fake GitHub. Playwright submits the form, and the fake receives the issue. The live end-to-end check waits until the owner has installed the token.

## Owner tasks (ops, after merge)
- **Labels first.** Create the labels `문의` and `song-request` in the repo. `bug`, `enhancement` and `question` are GitHub defaults; confirm they exist. GitHub may silently drop labels it cannot set.
- **Token.** Create a fine-grained PAT. Repository access is only `SongRushApp`, and the only permission is Issues: Read and write. Set an expiry and note it in a calendar.
- **Server.**
  - Put the token in `/opt/songrush/feedback.env` as `SONGRUSH_FEEDBACK_TOKEN=...`, with mode 600. Never commit it.
  - Add `env_file: feedback.env` to the `app` service in `/opt/songrush/compose.yml`. A compose `.env` alone does not reach the container.
  - Run `docker compose up -d app`.
  - The lead gives the exact steps.
- **Live check.** Submit once from the site, then `gh issue view N --json labels` shows both labels.

## File ownership
- **Backend test writer:** `backend/src/test/java/com/songrush/feedback/**`.
- **Backend implementer:** `backend/src/main/java/com/songrush/feedback/**`, plus `application.properties` if needed.
- **Frontend test writer:** `frontend/src/App.feedback.test.jsx`.
- **Frontend implementer:**
  - `frontend/src/components/FeedbackDialog.jsx` (new) and `frontend/src/App.jsx`;
  - `frontend/src/i18n/{ko,en,ja}.json` and `frontend/src/index.css`;
  - `frontend/src/game/feedback.js` (new, the fetch helper), if wanted.

## Out of scope
- Showing issue status on the site.
- Attachments and screenshots.
- A contact email field.
- Captcha. Add one only if abuse appears.

## Spec review (critic, round 1)
- **High.**
  - **H1.** A compose `.env` does not reach the container. The owner tasks now use `env_file`.
  - **H2.** The rate-limit semantics were ambiguous: what counts, and which window. They are now pinned: sliding windows, what counts, and how `Retry-After` is computed.
  - **H3.** Labels may be silently dropped. They are now created first, and the live check confirms them. No 422 fallback.
- **Medium.**
  - **M1/M2.** The User-Agent is dropped, so no fingerprint-like data is collected (D-096).
  - **M3.** A test now covers the token in `toString`.
  - **M4.** Any exception now gives 502, and the token is trimmed.
  - **M5.** A 401/403 is logged at ERROR with a renewal hint.
  - **M6.** The status endpoint is removed; a 503 shows the preparing message.
- **Nits** applied: the title allows no control characters, and lengths are counted in UTF-16 code units.
- **Checked, no issue.** The critic read the old `deploy/Caddyfile`; the live server runs nginx instead. Tomcat's RemoteIpValve reads `X-Forwarded-For` from the right and trusts only internal proxies (127/8, 172.16/12), so a spoofed left entry is ignored.

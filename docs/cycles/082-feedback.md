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
   - When the token is blank, the feature is **disabled**.
   - The token never appears in logs, responses, exceptions or `toString`.
2. **`GET /api/feedback/status`** returns `{"enabled": true|false}` with `Cache-Control: no-store`.
3. **`POST /api/feedback`** takes a JSON body of at most 8 KB:
   ```
   {category, title, body, lang, page, website}
   ```
   - `category` is one of `bug`, `idea`, `song` or `other`.
   - `title` is 1-80 characters and `body` is 1-2000 characters, both after trimming. Control characters other than newline and tab are rejected.
   - `lang` is one of `ko`, `en` or `ja`; anything else is stored as `?`.
   - `page` is one of `start`, `lobby` or `game`; anything else is stored as `?`.
   - `website` is a honeypot that must be empty or missing.

   **Responses:**
   - **Disabled:** 503 `{"error":"feedback-disabled"}`.
   - **Invalid:** a bad category, bad lengths, malformed JSON or an over-size body gives 400 `{"error":"bad-feedback"}`.
   - **Honeypot filled:** 201 `{"ok":true}`, and **no** issue is created.
   - **Rate limit:** at most 3 accepted posts per client address per 10 minutes, and at most 30 per hour in total. Over the limit gives 429 `{"error":"rate-limited"}` with a `Retry-After` header in seconds.
     - The client address is `request.getRemoteAddr()`, which already honours `X-Forwarded-For` through `server.forward-headers-strategy=native`.
     - The clock is injectable for tests.
     - Requests that are invalid or disabled do not count.
   - **Success:** the server makes one call to `POST {api-base}/repos/{repo}/issues`, then answers 201 `{"ok":true}`. The issue number is not returned.
   - **GitHub failure:** a non-2xx answer, a timeout or an I/O error gives 502 `{"error":"feedback-failed"}`. The log records the GitHub status, never the token or the user text.
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
       - the browser `User-Agent` request header, cut to 200 characters and also put in a fence;
       - the footer `_사이트 문의하기로 접수됨_`.
     - **labels:** `["문의", "<category label>"]`. The category labels are `bug`, `enhancement`, `song-request` and `question`.

## Frontend
5. **Footer link.** A `문의하기` button, translated in all three languages, sits in the footer next to the language switcher on every screen. It opens a modal `role="dialog"` with `aria-modal="true"`, labelled by its heading `문의하기`.
6. **Status check.** On open, the app calls `GET /api/feedback/status`.
   - When the feature is disabled, the dialog shows a "preparing" message and no form.
   - When it is enabled, the form shows:
     - a category select (버그 / 건의 / 곡 요청 / 기타);
     - a title input (maxLength 80) and a body textarea (maxLength 2000) with a live counter;
     - a visually hidden honeypot input named `website`, with `tabIndex=-1` and `autoComplete="off"`, hidden from assistive tech;
     - submit and close buttons.
7. **Submitting.**
   - The app sends `{category, title, body, lang: current UI language, page: 'start'|'lobby'|'game', website}`. Submit is disabled while the request is in flight or while the title or body is empty after trimming.
   - **On 201:** the form is replaced by a thank-you message plus a close button.
   - **On errors:** the form stays filled and shows a translated message for `rate-limited`, `bad-feedback`, `feedback-failed`, `feedback-disabled` and network failure.
   - Escape and the close button close the dialog, and focus returns to the footer button.
8. **Layout.** No layout properties go in the room theme rules: the themeLayout test stays green. The dialog uses the theme colour tokens only.

## Acceptance
### Backend tests
- **`FeedbackControllerTest`** (MockMvc or the Spring test client, with a fake `IssueSender` bean):
  - status enabled and disabled;
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
  - a send failure gives 502;
  - invalid posts do not consume quota.
- **`GitHubIssueSenderTest`** (JDK `HttpServer` on localhost as a fake GitHub):
  - the method, path and all headers are exact;
  - the JSON body round-trips;
  - 201 counts as success;
  - 401, 422 and 500 throw the sender's failure exception;
  - a server that never answers fails within the timeout (configurable to be short in the test);
  - the token does not appear in the exception message.

### Frontend tests (`App.feedback.test.jsx`, with a fake `fetch`)
- The footer button opens the dialog.
- Disabled status shows the preparing text.
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
- Create a fine-grained PAT. Repository access is only `SongRushApp`, and the only permission is Issues: Read and write. Set an expiry.
- Put it on the server as `SONGRUSH_FEEDBACK_TOKEN` in the app service environment, through an `.env` file that is not committed.
- The lead gives the exact steps.

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

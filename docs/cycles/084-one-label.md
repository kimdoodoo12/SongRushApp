# 084 one-label (D-098)

## Decision (owner, 2026-11-08)
"song-request없애고 그거에 맞게 수정하자"

Issues created from site feedback carry a single label, `문의`. The category stays visible in the title (`[문의/곡 요청] ...`) and in the body. The `song-request` label was deleted from the repo, and `문의` already exists.

## Change
- `FeedbackFormat` drops the per-category label. `Issue.labels` is always `["문의"]`. The Korean category names in the title and body are unchanged.

## Acceptance
- **Declared rewrite of `AdminFeedbackControllerTest`.**
  - `categoryMapsToKoreanNameAndLabel` becomes `categoryMapsToKoreanName`. It checks the title prefix and that the labels are exactly `["문의"]` for all four categories.
  - `sendBuildsThe082Issue` and the other label assertions expect `containsExactly("문의")`.
  - No other test changes.
- `.\gradlew.bat test`, `npm test`, `npm run lint` and `npm run build` exit 0.

## File ownership
- **Test writer:** `backend/src/test/java/com/songrush/feedback/AdminFeedbackControllerTest.java`.
- **Implementer:** `backend/src/main/java/com/songrush/feedback/FeedbackFormat.java`.

## Result
- **Commits.** Spec: `a468268`. Declared test rewrite: `25964fb`. Implementation: `e446ef2`.
- **Verifier at `e446ef2`.**
  - Backend: 590 tests, 0 failures, 1 skipped (the POSIX-only store test on Windows).
  - Frontend: 369 tests; lint and build exit 0.
  - The locked-test diff is empty, and `song-request` and `enhancement` appear nowhere in `backend/src/main`.
- **Review.** No findings. Nit: the `GitHubIssueSenderTest` fixture still uses `["문의","bug"]`. It only checks that the sender serialises whatever labels it gets, so it does not conflict.
- **GitHub.** The lead deleted the repo label `song-request` (`gh label delete`); `문의` remains.

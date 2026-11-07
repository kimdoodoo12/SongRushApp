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

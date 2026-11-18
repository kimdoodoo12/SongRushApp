# 160 · Mobile game layout v2 (D-145)

The owner found the cycle-151 game screen hard to use for talking while guessing and picked mockup v2 (https://claude.ai/artifact/HMTXBgSFQwpUiFb8o4c2Ck, boards V2*): one main thing per mode.
- Top: a small spinning disc icon, n / N, time bar, seconds; a second line with "내 순위 N위 · P점 ▾" (opens all scores), pick count in choice rooms, skip with count, 나가기. The players strip, big disc row and room-code line are gone.
- Chat room: the chat log fills the screen; own lines right-aligned in the accent colour; the ✔ correct line prominent; the input fixed and lifted by the keyboard.
- Reveal: the full card for 3.5 s, then a one-line strip (cover, title, OP/ED · song) with 자세히 / 접기.
- Choice room: covers fill the screen (2, 3 or 4 columns, whichever is biggest); the last 3 chat lines float over the covers as fading bubbles that let taps through; a round 채팅 button with the unread count opens a panel (covers in one tappable row, chat log, focused input), 닫기 returns.
- New texts myRank, revealMore, revealLess (ko/en/ja).

Evidence: implementer `flutter analyze` no issues, app tests +153 (a new layout suite at 360x640, 390x844, 430x932 in ko/en/ja; a mutation removing the bubbles' IgnorePointer failed the hit-test check), web build OK. Lead manual run on the local server at 390x844 (`docs/cycles/160/`): chat room with others' and own lines, the correct answer, the strip after the card; choice room with bubbles and the unread count, the panel with one row of covers, the app's line reaching the React web; no console errors.
Seen: the floating bubbles start at the covers' left edge and their left rounding is clipped; follow-up.

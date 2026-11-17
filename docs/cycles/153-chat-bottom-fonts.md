# 153 · Mobile chat from the bottom, a talk placeholder, and smaller fonts (F-M8, F-M9)

## Done
- Chat lines stack from the bottom next to the input in the game's chat log, the choice room's box, the full-chat overlay and the lobby chat (`mobile/lib/ui/chat_panel.dart`). The choice room's input reads "채팅으로 이야기해요" / "Chat with everyone" / "チャットで話そう" (app key `chatTalkPlaceholder`; the web has no such text), the chat room keeps the web's `chat.placeholder`.
- Fonts subset by `mobile/tool/subset_fonts.py` (fontTools): originals moved to `mobile/tool/fonts-src/`, subsets at the same asset paths. Kept: ASCII, Latin-1, Latin Extended-A, symbols, compatibility jamo, the 2,350 KS X 1001 syllables and every character of the ARB files and `lib/**/*.dart`. IBM Plex Sans KR (only a fallback in `palette.dart`) drops the Hangul Jua and Gowun Dodum already have. Hinting dropped. 18,075,068 → 2,885,616 bytes. Rarer Hangul and kana/kanji fall back to the browser's fonts.

## Evidence
- `subset_fonts.py --check`: "every wanted character the source fonts have is in the subsets"; verifier's own fontTools check: Jua and Gowun Dodum miss 0 of the 2,350 syllables and 0 Hangul/ASCII of app_ko.arb; all pubspec font paths exist; Plex is used only in `fontFamilyFallback`.
- `flutter analyze` no issues; app tests +130 all passed (lead, in a copy without flutter_soloud; 9 new in `chat_bottom_test.dart`, no assertion changed).
- Manual run at 390x844 (`docs/cycles/153/`): chat game and choice game to the final ranking, no console errors; lines at the bottom; the reveal shows ko/en/ja/romaji with the Japanese in the fallback font.

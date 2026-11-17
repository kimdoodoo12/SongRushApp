import 'dart:ui' show Locale;

// The UI language (D-095, D-141), as frontend/src/i18n/index.js: Korean,
// English or Japanese; the stored switcher choice first, then the device's
// languages (ko-* to ko, ja-* to ja, anything else to en).

/// The supported languages, in the switcher's order.
const List<String> languages = ['ko', 'en', 'ja'];

/// Each language's name in its own language (LanguageSwitcher.jsx NAMES).
const Map<String, String> languageNames = {'ko': '한국어', 'en': 'English', 'ja': '日本語'};

/// The web client's storage key of the switcher's choice.
const String languageKey = 'songrush.lang';

/// The language to start in: [stored] when it is supported, else the first
/// of [deviceLocales] that is ko or ja (i18next's `load: languageOnly`
/// order), else en.
String pickLanguage({String? stored, required List<Locale> deviceLocales}) {
  if (stored != null && languages.contains(stored)) return stored;
  for (final locale in deviceLocales) {
    final code = locale.languageCode.toLowerCase();
    if (languages.contains(code)) return code;
  }
  return 'en';
}

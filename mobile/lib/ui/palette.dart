import 'package:material_ui/material_ui.dart';

// The sunset look of frontend/src/room.css `:root` (the default mood), as
// Dart constants; room.css itself is the web's and never edited. Moods are
// a later item: only sunset here.

/// The `:root` colours.
abstract final class Palette {
  static const Color bg = Color(0xFF1C0F1A);
  static const Color panel = Color(0xFF2A1624);
  static const Color panel2 = Color(0xFF3A1F30);
  static const Color line = Color(0xFF4A2A3C);
  static const Color ink = Color(0xFFFFF1E6);
  static const Color dim = Color(0xFFC4A3A8);
  static const Color a1 = Color(0xFFFF8A4C);
  static const Color a2 = Color(0xFFFFCF70);
  static const Color a3 = Color(0xFFFF5D8F);
  static const Color ok = Color(0xFF9FF0A8);

  /// `--r`.
  static const double radius = 18;

  /// `color-mix(in srgb, var(--panel) 85%, transparent)` and its kin.
  static Color panelAlpha(double share) => panel.withValues(alpha: share);
}

/// The `:root` font stacks: the bundled families, IBM Plex Sans KR as the
/// fallback of the display and body faces.
abstract final class Fonts {
  static const String display = 'Jua';
  static const String body = 'Gowun Dodum';
  static const String fallback = 'IBM Plex Sans KR';
  static const String mono = 'JetBrains Mono';

  static const List<String> fallbacks = [fallback];

  /// `var(--f-display)`.
  static TextStyle displayStyle({double size = 24, Color color = Palette.ink, double? height}) => TextStyle(
        fontFamily: display,
        fontFamilyFallback: fallbacks,
        fontSize: size,
        fontWeight: FontWeight.w400,
        color: color,
        height: height,
      );

  /// `var(--f-body)`.
  static TextStyle bodyStyle({double size = 15, Color color = Palette.ink, FontWeight weight = FontWeight.w400}) =>
      TextStyle(
        fontFamily: body,
        fontFamilyFallback: fallbacks,
        fontSize: size,
        fontWeight: weight,
        color: color,
        height: 1.5,
      );

  /// `var(--f-mono)`; the variable file takes its weight from the wght
  /// axis (400 or 600 on the web).
  static TextStyle monoStyle({
    double size = 12,
    Color color = Palette.dim,
    bool semiBold = false,
    double letterSpacingEm = 0.12,
  }) =>
      TextStyle(
        fontFamily: mono,
        fontSize: size,
        fontWeight: semiBold ? FontWeight.w600 : FontWeight.w400,
        fontVariations: [FontVariation('wght', semiBold ? 600 : 400)],
        color: color,
        letterSpacing: size * letterSpacingEm,
      );
}

/// Name colours for avatars and chat markers (frontend/src/game/themes.js
/// `nameColour`): the same name gets the same colour on the web and here.
const List<Color> _nameColours = [
  Color(0xFFFF9FB3),
  Color(0xFF8FDCF5),
  Color(0xFFA6E6B0),
  Color(0xFFFFD08A),
  Color(0xFFC9B6FF),
  Color(0xFFFFC2A1),
  Color(0xFF9EE6DC),
  Color(0xFFF5B3E6),
];

/// themes.js `nameColour`: a 31-hash over the UTF-16 code units, kept in
/// 32 bits.
Color nameColour(String name) {
  var hash = 0;
  for (final unit in name.codeUnits) {
    hash = (hash * 31 + unit) & 0xFFFFFFFF;
  }
  return _nameColours[hash % _nameColours.length];
}

/// The app's Material theme over the palette: dark, body font, the room's
/// radius on inputs.
ThemeData songRushTheme() {
  OutlineInputBorder border(Color c) => OutlineInputBorder(
        borderRadius: BorderRadius.circular(Palette.radius),
        borderSide: BorderSide(color: c),
      );
  return ThemeData(
    brightness: Brightness.dark,
    colorScheme: const ColorScheme.dark(
      primary: Palette.a1,
      onPrimary: Palette.bg,
      secondary: Palette.a2,
      onSecondary: Palette.bg,
      error: Palette.a3,
      surface: Palette.panel,
      onSurface: Palette.ink,
      outline: Palette.line,
    ),
    scaffoldBackgroundColor: Palette.bg,
    fontFamily: Fonts.body,
    fontFamilyFallback: Fonts.fallbacks,
    textSelectionTheme: const TextSelectionThemeData(cursorColor: Palette.a1),
    inputDecorationTheme: InputDecorationThemeData(
      filled: true,
      fillColor: Palette.bg,
      isDense: true,
      contentPadding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
      hintStyle: Fonts.bodyStyle(size: 14, color: Palette.dim),
      enabledBorder: border(Palette.line),
      focusedBorder: border(Palette.a1),
      disabledBorder: border(Palette.line),
      border: border(Palette.line),
    ),
  );
}

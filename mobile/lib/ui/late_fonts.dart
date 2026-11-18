import 'dart:developer' as developer;

import 'package:flutter/services.dart';

import 'palette.dart';

// The bundled families the start screen does not need (cycle 159): IBM Plex
// Sans KR is only the fallback of Jua and Gowun Dodum, for a character they
// lack, and JetBrains Mono shows first in the lobby. The web build loads
// every family of pubspec.yaml's `fonts:` before its first frame, so these
// are plain assets there and load here once the first frame is drawn; text
// drawn before that relays out when they arrive.
const Map<String, List<String>> _lateFonts = {
  Fonts.fallback: [
    'assets/fonts/ibmplexsanskr/IBMPlexSansKR-Regular.ttf',
    'assets/fonts/ibmplexsanskr/IBMPlexSansKR-Medium.ttf',
    'assets/fonts/ibmplexsanskr/IBMPlexSansKR-Bold.ttf',
  ],
  Fonts.mono: ['assets/fonts/jetbrainsmono/JetBrainsMono-wght.ttf'],
};

/// Loads the late families from [bundle] (the app's own by default). A
/// file or a family that fails is logged and left out: its text uses the
/// next font.
Future<void> loadLateFonts({AssetBundle? bundle}) async {
  final assets = bundle ?? rootBundle;
  await Future.wait([
    for (final MapEntry(key: family, value: files) in _lateFonts.entries)
      _load(assets, family, files),
  ]);
}

void _warn(String message) => developer.log(message, name: 'songrush.fonts');

Future<void> _load(AssetBundle assets, String family, List<String> files) async {
  // Each file on its own, so one missing file neither stops the others nor
  // leaves their errors unhandled.
  final loaded = await Future.wait([
    for (final file in files)
      assets.load(file).then<ByteData?>((bytes) => bytes, onError: (Object e) {
        _warn('font file $file did not load: $e');
        return null;
      }),
  ]);
  final loader = FontLoader(family);
  var added = 0;
  for (final bytes in loaded) {
    if (bytes == null) continue;
    loader.addFont(Future.value(bytes));
    added++;
  }
  if (added == 0) return;
  try {
    await loader.load();
  } catch (e) {
    _warn('font $family did not load: $e');
  }
}

import 'dart:developer' as developer;

import 'package:songrush_core/songrush_core.dart';

import 'prefs.dart';

/// The manual sound offset's key (cycle 150; the web has no such setting).
const String manualOffsetKey = 'songrush.soundOffset';

/// The round controller's [PlaybackSettingsStore] over [Prefs] (localStorage
/// on the web, beside the nickname and the language): the offset in ms as a
/// decimal string. Anything else reads as none.
class PrefsPlaybackSettingsStore implements PlaybackSettingsStore {
  PrefsPlaybackSettingsStore(this._prefs);

  final Prefs _prefs;

  @override
  int? readManualOffsetMs() {
    final raw = _prefs.read(manualOffsetKey);
    if (raw == null) return null;
    final ms = int.tryParse(raw);
    if (ms == null) developer.log('malformed sound offset', name: 'songrush.storage');
    return ms;
  }

  @override
  void writeManualOffsetMs(int ms) => _prefs.write(manualOffsetKey, '$ms');
}

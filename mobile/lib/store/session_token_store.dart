import 'dart:convert';
import 'dart:developer' as developer;

import 'package:songrush_core/songrush_core.dart';

import 'prefs.dart';

/// The web client's rejoin key (frontend/src/App.jsx `REJOIN_KEY`).
const String rejoinKey = 'songrush.rejoin';

/// The session's [TokenStore] over [Prefs] (sessionStorage on the web, as
/// the web client keeps it): the entry is the web's JSON `{token, code}`
/// under [rejoinKey]. A malformed entry reads as none.
class PrefsTokenStore implements TokenStore {
  PrefsTokenStore(this._prefs);

  final Prefs _prefs;

  @override
  RejoinEntry? read() {
    final raw = _prefs.read(rejoinKey);
    if (raw == null) return null;
    try {
      final json = jsonDecode(raw);
      if (json is Map<String, Object?> && json['token'] is String && json['code'] is String) {
        return RejoinEntry(token: json['token']! as String, code: json['code']! as String);
      }
    } on FormatException catch (e) {
      developer.log('malformed rejoin entry: ${e.message}', name: 'songrush.storage');
    }
    return null;
  }

  @override
  void write(RejoinEntry entry) =>
      _prefs.write(rejoinKey, jsonEncode({'token': entry.token, 'code': entry.code}));

  @override
  void clear() => _prefs.remove(rejoinKey);
}

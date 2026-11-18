import 'dart:developer' as developer;

import 'package:web/web.dart' as web;

import 'prefs.dart';

void _warn(String message) => developer.log(message, name: 'songrush.storage');

/// `window.localStorage`, or null when the browser refuses it (blocked
/// storage throws on access).
Prefs? browserLocalStorage() {
  try {
    return _WebStoragePrefs(web.window.localStorage, 'localStorage');
  } catch (e) {
    _warn('localStorage unavailable: $e');
    return null;
  }
}

/// `window.sessionStorage`, or null when the browser refuses it.
Prefs? browserSessionStorage() {
  try {
    return _WebStoragePrefs(web.window.sessionStorage, 'sessionStorage');
  } catch (e) {
    _warn('sessionStorage unavailable: $e');
    return null;
  }
}

/// One Web Storage area. Each call may throw (a blocked or full storage,
/// as the web client's try/catch around every access): a failed read
/// answers null, a failed write or remove is logged and dropped.
class _WebStoragePrefs implements Prefs {
  _WebStoragePrefs(this._storage, this._name);

  final web.Storage _storage;
  final String _name;

  @override
  String? read(String key) {
    try {
      return _storage.getItem(key);
    } catch (e) {
      _warn('$_name read failed: $e');
      return null;
    }
  }

  @override
  void write(String key, String value) {
    try {
      _storage.setItem(key, value);
    } catch (e) {
      _warn('$_name write failed: $e');
    }
  }

  @override
  void remove(String key) {
    try {
      _storage.removeItem(key);
    } catch (e) {
      _warn('$_name remove failed: $e');
    }
  }
}

import 'dart:developer' as developer;
import 'dart:js_interop';
import 'dart:js_interop_unsafe';

import 'package:web/web.dart' as web;

void _warn(String message) => developer.log(message, name: 'songrush.page');

/// The page was loaded by a reload (App.jsx `pageNavigationType`): the
/// navigation entry's type is `reload`. Any other type, or a browser without
/// the entry, is a fresh navigation.
bool pageWasReloaded() {
  try {
    final entries = web.window.performance.getEntriesByType('navigation').toDart;
    if (entries.isEmpty) return false;
    return entries.first.getProperty('type'.toJS).dartify() == 'reload';
  } catch (e) {
    _warn('navigation type unavailable: $e');
    return false;
  }
}

/// The Screen Wake Lock as an on/off switch, or null where the browser has
/// none (`navigator.wakeLock`).
void Function(bool on)? screenWakeLock() {
  try {
    if (!web.window.navigator.has('wakeLock')) return null;
  } catch (e) {
    _warn('wake lock unavailable: $e');
    return null;
  }
  return _ScreenWakeLock().set;
}

/// One screen wake lock. On asks for a sentinel unless one is held (the
/// browser releases it when the page is hidden, so the next on after a
/// return asks again); off releases it. A refused request (no user
/// activation, a battery saver) is logged: the screen may then dim.
class _ScreenWakeLock {
  web.WakeLockSentinel? _sentinel;
  bool _wanted = false;
  bool _requesting = false;

  void set(bool on) {
    _wanted = on;
    if (on) {
      _request();
    } else {
      _release();
    }
  }

  void _request() {
    final held = _sentinel;
    if (_requesting || (held != null && !held.released)) return;
    final Future<web.WakeLockSentinel> request;
    try {
      request = web.window.navigator.wakeLock.request('screen').toDart;
    } catch (e) {
      _warn('wake lock refused: $e');
      return;
    }
    _requesting = true;
    request.then(
      (sentinel) {
        _requesting = false;
        _sentinel = sentinel;
        // Turned off while the request ran.
        if (!_wanted) _release();
      },
      onError: (Object e) {
        _requesting = false;
        _warn('wake lock refused: $e');
      },
    );
  }

  void _release() {
    final held = _sentinel;
    _sentinel = null;
    if (held == null || held.released) return;
    held.release().toDart.then((_) {}, onError: (Object e) => _warn('wake lock release failed: $e'));
  }
}

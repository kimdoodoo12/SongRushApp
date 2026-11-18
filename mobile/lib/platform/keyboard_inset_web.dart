import 'dart:async';
import 'dart:developer' as developer;
import 'dart:js_interop';
import 'dart:js_interop_unsafe';

import 'package:flutter/foundation.dart';
import 'package:flutter/widgets.dart';
import 'package:web/web.dart' as web;

import 'keyboard_inset_value.dart';
import 'stale_view_guard.dart';

void _warn(String message) => developer.log(message, name: 'songrush.keyboard');

_BrowserKeyboard? _keyboard;
bool _tried = false;

/// The keyboard's height (logical px, [keyboardInsetFrom]), kept current
/// from the visual viewport's `resize` and `scroll`, the window's `resize`,
/// and `focusin`/`focusout` (read again after a frame and once the
/// keyboard's animation is over). Flutter's own view inset is not reset
/// when the keyboard is hidden while the field keeps the focus (Chrome on
/// Android, resizes-visual), so the app uses this one instead. Null when
/// the browser has no `visualViewport`. One reader for the page.
ValueListenable<double>? browserKeyboardInset() {
  if (!_tried) {
    _tried = true;
    try {
      if (web.window.visualViewport != null) _keyboard = _BrowserKeyboard()..listen();
    } catch (e) {
      _warn('visual viewport unavailable: $e');
    }
    final keyboard = _keyboard;
    // Lives with the page, as the reader does (held by the inset's listener).
    if (keyboard != null) _staleViewGuard(keyboard.inset).start();
  }
  return _keyboard?.inset;
}

/// Records [inset], the bottom view inset the app applied, as the page's
/// `window.__srMqInset` for the `?debug=1` overlay in index.html (cycle 162).
void reportAppliedKeyboardInset(double inset) {
  try {
    web.window.setProperty('__srMqInset'.toJS, inset.toJS);
  } catch (e) {
    _warn('applied inset not recorded: $e');
  }
}

// Cycle 163: the view left at the keyboard-open height is measured again
// ([StaleViewGuard]). The engine re-measures on the `resize` event of its
// resize target, the visual viewport (window when there is none:
// FullPageDimensionsProvider), and only with text editing off, hence the
// unfocus first.
StaleViewGuard _staleViewGuard(ValueListenable<double> inset) => StaleViewGuard(
      inset: inset,
      viewHeight: () {
        final view = WidgetsBinding.instance.platformDispatcher.implicitView;
        return view == null ? double.nan : view.physicalSize.height / view.devicePixelRatio;
      },
      innerHeight: () => web.window.innerHeight.toDouble(),
      unfocus: () => FocusManager.instance.primaryFocus?.unfocus(),
      remeasure: () {
        try {
          final web.EventTarget target = web.window.visualViewport ?? web.window;
          target.dispatchEvent(web.Event('resize'));
        } catch (e) {
          _warn('view not re-measured: $e');
        }
      },
      warn: _warn,
    );

class _BrowserKeyboard {
  final ValueNotifier<double> inset = ValueNotifier(0);
  Timer? _settle;

  void listen() {
    final viewport = web.window.visualViewport!;
    final onChange = ((web.Event _) => _read()).toJS;
    viewport.addEventListener('resize', onChange);
    viewport.addEventListener('scroll', onChange);
    web.window.addEventListener('resize', onChange);
    final onFocus = ((web.Event _) => _readSoon()).toJS;
    web.window.addEventListener('focusin', onFocus);
    web.window.addEventListener('focusout', onFocus);
    _read();
  }

  /// After a focus change the keyboard is still moving: read after the
  /// next frame and again once it has settled.
  void _readSoon() {
    _read();
    try {
      web.window.requestAnimationFrame(((double _) => _read()).toJS);
    } catch (e) {
      _warn('animation frame unavailable: $e');
    }
    _settle?.cancel();
    _settle = Timer(const Duration(milliseconds: 400), _read);
  }

  void _read() {
    try {
      final viewport = web.window.visualViewport;
      if (viewport == null) return;
      final value = keyboardInsetFrom(web.window.innerHeight, viewport.height, viewport.offsetTop);
      inset.value = value;
      // Cycle 162: read by the ?debug=1 overlay in index.html.
      web.window.setProperty('__srInset'.toJS, value.toJS);
      // With the keyboard gone the page must be at its top: the browser
      // may have scrolled it to show the focused field.
      if (value == 0 && web.window.scrollY != 0) web.window.scrollTo(0.toJS, 0);
    } catch (e) {
      _warn('keyboard inset unreadable: $e');
    }
  }
}

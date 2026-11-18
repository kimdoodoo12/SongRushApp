import 'dart:async';

import 'package:flutter/foundation.dart';

/// How long the keyboard inset must read 0 before the view is checked.
const Duration staleViewSettle = Duration(milliseconds: 300);

/// The least time between two recoveries.
const Duration staleViewGap = Duration(seconds: 1);

/// The wait between the unfocus and the re-measure, so the text connection
/// is closed (text editing off in the engine) when the resize comes.
const Duration staleViewRemeasureDelay = Duration(milliseconds: 100);

/// Brings back a Flutter view left at the keyboard-open height (cycle 163).
///
/// The web engine (flutter_web_sdk window.dart `_handleBrowserResize`)
/// keeps the view's size on a browser resize while text editing is on, and
/// takes the visual viewport's height while it is off. A resize that came
/// in a moment with editing off while the keyboard was up leaves the view
/// short once the keyboard goes down with editing on again: the app squeezed
/// at the top of the page, the rest empty.
///
/// When [inset] (the browser's keyboard height) has read 0 for
/// [staleViewSettle] and the view ([viewHeight]) is more than 1 px shorter
/// than the window ([innerHeight]), this [unfocus]es (text editing goes
/// off), then after [staleViewRemeasureDelay] asks the engine to measure
/// the view again ([remeasure]). At most once per [staleViewGap]; [warn]
/// is called the first time only. The web build alone makes one.
class StaleViewGuard {
  StaleViewGuard({
    required this.inset,
    required this.viewHeight,
    required this.innerHeight,
    required this.unfocus,
    required this.remeasure,
    required this.warn,
  });

  final ValueListenable<double> inset;
  final double Function() viewHeight;
  final double Function() innerHeight;
  final VoidCallback unfocus;
  final VoidCallback remeasure;
  final void Function(String message) warn;

  Timer? _settle;
  Timer? _remeasure;
  Timer? _cooling;
  bool _warned = false;
  bool _disposed = false;

  void start() {
    inset.addListener(_changed);
    _changed();
  }

  void dispose() {
    _disposed = true;
    inset.removeListener(_changed);
    _settle?.cancel();
    _remeasure?.cancel();
    _cooling?.cancel();
  }

  void _changed() {
    _settle?.cancel();
    _settle = null;
    if (inset.value == 0) _settle = Timer(staleViewSettle, _check);
  }

  void _check() {
    _settle = null;
    if (_disposed || inset.value != 0) return;
    final view = viewHeight();
    final inner = innerHeight();
    if (!view.isFinite || !inner.isFinite || inner - view <= 1) return;
    if (_cooling != null) {
      // Checked again once the gap is over.
      _settle = Timer(staleViewGap, _check);
      return;
    }
    _cooling = Timer(staleViewGap, () => _cooling = null);
    if (!_warned) {
      _warned = true;
      warn('view stuck at ${view.toStringAsFixed(1)} of ${inner.toStringAsFixed(1)} px with no keyboard: re-measuring');
    }
    unfocus();
    _remeasure?.cancel();
    _remeasure = Timer(staleViewRemeasureDelay, () {
      _remeasure = null;
      if (!_disposed) remeasure();
    });
  }
}

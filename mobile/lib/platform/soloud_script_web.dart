import 'dart:async';
import 'dart:developer' as developer;
import 'dart:js_interop';
import 'dart:js_interop_unsafe';

import 'package:web/web.dart' as web;

void _warn(String message) => developer.log(message, name: 'songrush.audio');

// The script index.html used to load with the page. It picks the single- or
// multi-threaded build, loads its glue and WASM (about 2 MB), and sets
// `self.flutter_soloud_ready`, the promise of the instantiated module that
// SoLoud.init waits for. Relative to the page's base (/m/ on the site).
const String _src = 'assets/packages/flutter_soloud/web/init_soloud.js';

// How long a held-off AudioContext gets to resume before the sound counts
// as locked.
const Duration _resumeWait = Duration(seconds: 1);

Future<void>? _loading;

/// Loads init_soloud.js once and waits until its module is ready. A failed
/// load is logged and forgotten, so the next start tries again.
Future<void> loadSoloudScript() {
  final running = _loading;
  if (running != null) return running;
  final loading = _load();
  _loading = loading;
  loading.then((_) {}, onError: (Object e) {
    _warn('sound engine script failed: $e');
    if (identical(_loading, loading)) _loading = null;
  });
  return loading;
}

Future<void> _load() async {
  // An older page that still loads the script itself.
  if (!globalContext.has('flutter_soloud_ready')) {
    final loaded = Completer<void>();
    final script = web.HTMLScriptElement()..src = _src;
    script.onload = ((web.Event _) => loaded.complete()).toJS;
    script.onerror = ((web.Event _) {
      script.remove();
      loaded.completeError(StateError('$_src did not load'));
    }).toJS;
    web.document.head!.appendChild(script);
    await loaded.future;
  }
  final ready = globalContext.getProperty<JSAny?>('flutter_soloud_ready'.toJS);
  if (ready == null || !ready.isA<JSPromise>()) throw StateError('$_src set no flutter_soloud_ready');
  try {
    await (ready as JSPromise).toDart;
  } catch (e) {
    // A module that failed (its WASM did not load): the next start runs
    // the script again.
    globalContext.delete('flutter_soloud_ready'.toJS);
    rethrow;
  }
}

/// Starts loading the script on the page's first pointerdown (as the
/// create or join tap begins), so the module is mostly in by the time 확인
/// starts the engine.
void preloadSoloudScriptOnFirstTouch() {
  try {
    web.document.addEventListener(
      'pointerdown',
      ((web.Event _) {
        // A failure is logged by loadSoloudScript; 확인 tries again.
        loadSoloudScript().then((_) {}, onError: (Object _) {});
      }).toJS,
      web.AddEventListenerOptions(capture: true, once: true, passive: true),
    );
  } catch (e) {
    _warn('sound engine preload unavailable: $e');
  }
}

/// Whether the AudioContext SoLoud plays through (miniaudio's first
/// device's `webaudio`) runs: a suspended one is asked to resume (which
/// works inside a tap, or after one in Chrome) and gets [_resumeWait]. True
/// when there is no such context to check.
Future<bool> soundContextRunning() async {
  final JSObject context;
  try {
    final miniaudio = globalContext.getProperty<JSAny?>('miniaudio'.toJS);
    if (miniaudio == null || !miniaudio.isA<JSObject>()) return true;
    final ma = miniaudio as JSObject;
    if (!ma.has('get_device_by_index')) return true;
    final device = ma.callMethod<JSAny?>('get_device_by_index'.toJS, 0.toJS);
    if (device == null || !device.isA<JSObject>()) return true;
    final found = (device as JSObject).getProperty<JSAny?>('webaudio'.toJS);
    if (found == null || !found.isA<JSObject>()) return true;
    context = found as JSObject;
  } catch (e) {
    _warn('AudioContext unavailable: $e');
    return true;
  }
  bool running() => context.getProperty<JSAny?>('state'.toJS).dartify() == 'running';
  if (running()) return true;
  try {
    final resumed = context.callMethod<JSAny?>('resume'.toJS);
    if (resumed != null && resumed.isA<JSPromise>()) {
      // A browser that waits for a tap leaves the promise pending.
      await (resumed as JSPromise).toDart.timeout(_resumeWait, onTimeout: () => null);
    }
  } catch (e) {
    _warn('AudioContext did not resume: $e');
  }
  return running();
}

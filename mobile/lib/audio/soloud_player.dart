import 'dart:async';
import 'dart:developer' as developer;
import 'dart:math' as math;
import 'dart:typed_data';

import 'package:flutter/foundation.dart' show kIsWeb;
import 'package:flutter_soloud/flutter_soloud.dart';
import 'package:songrush_core/songrush_core.dart';

import '../platform/audio_latency.dart';

// The SongPlayer of the app on flutter_soloud, as the S-009 spike proved it
// on the web (spikes/s009-audio, cycle 142): Ogg Opus from memory with
// loadMem, a start on the engine clock with playScheduled, a late start with
// play + seek, the stop with stopScheduled.

void _log(String message) => developer.log(message, name: 'songrush.audio');

Duration _duration(double seconds) =>
    Duration(microseconds: seconds <= 0 ? 0 : (seconds * 1e6).round());

/// The SoLoud engine and its clock, shared by every [SoloudPlayer].
///
/// [start] must run before the first song loads; on the web, from a user
/// gesture (the sound unlock), since browsers keep audio off until one.
/// Until then [createPlayer]'s players fail to load, so a round is silent
/// but still sends `ready`.
class SoloudEngine {
  SoloudEngine({SoLoud? soloud}) : _soloud = soloud ?? SoLoud.instance;

  final SoLoud _soloud;
  final Stopwatch _mono = Stopwatch()..start();
  Timer? _sampler;
  // (local ms, engine ms) readings over the last [_window].
  final List<({double local, double engine})> _samples = [];

  // The engine clock steps once per mix buffer (2048 frames, about 46 ms),
  // so engine minus local is largest right after a step: the spike took the
  // largest difference over 120 ms. A clock that has not stepped for
  // [_stall] is not running (no device mixing): its raw reading is used.
  static const Duration _sampleEvery = Duration(milliseconds: 10);
  static const double _window = 300;
  static const double _stall = 150;

  SoLoud get soloud => _soloud;

  /// The engine is initialized.
  bool get isStarted => _soloud.isInitialized;

  /// Initializes the engine (once) and starts reading its clock.
  Future<void> start() async {
    if (!_soloud.isInitialized) await _soloud.init();
    _sampler ??= Timer.periodic(_sampleEvery, (_) => _sample());
    _sample();
  }

  /// Stops reading the clock. The engine itself stays up for the app.
  void stop() {
    _sampler?.cancel();
    _sampler = null;
    _samples.clear();
  }

  /// A new player for one song.
  SongPlayer createPlayer() => SoloudPlayer(this);

  double _localMs() => _mono.elapsedMicroseconds / 1000;

  void _sample() {
    if (!_soloud.isInitialized) return;
    final local = _localMs();
    _samples.add((local: local, engine: _soloud.getEngineTime().inMicroseconds / 1000));
    while (_samples.isNotEmpty && local - _samples.first.local > _window) {
      _samples.removeAt(0);
    }
  }

  /// The engine clock in seconds, read between its steps; before [start],
  /// a local monotonic clock (nothing can be scheduled then).
  double get nowSec {
    if (!_soloud.isInitialized) return _localMs() / 1000;
    _sample();
    final last = _samples.last;
    // The last step: the oldest reading with the latest engine time.
    final stepped = _samples.firstWhere((s) => s.engine == last.engine);
    if (last.local - stepped.local > _stall) return last.engine / 1000;
    var best = double.negativeInfinity;
    for (final s in _samples) {
      best = math.max(best, s.engine - s.local);
    }
    return (last.local + best) / 1000;
  }

  /// The output latency in seconds; 0 before [start].
  ///
  /// On the web only the browser's `AudioContext.outputLatency` of SoLoud's
  /// context, or 0 where it is missing, exactly as the React web
  /// (session.js `outputLatency`), so both clients start a song at the same
  /// moment (cycle 152). SoLoud's own getHardwareOutputLatency on the web
  /// adds `baseLatency`, which the React web does not.
  ///
  /// Elsewhere SoLoud's own mix cushion plus the hardware's output latency.
  double get outputLatencySec {
    if (!_soloud.isInitialized) return 0;
    if (kIsWeb) return browserOutputLatency() ?? 0;
    final total = _soloud.getOutputLatency() + _soloud.getHardwareOutputLatency();
    return total.inMicroseconds / 1e6;
  }
}

/// One song on the [SoloudEngine]: its source loaded from memory and the
/// handle playing it.
class SoloudPlayer implements SongPlayer {
  SoloudPlayer(this._engine);

  final SoloudEngine _engine;
  static int _loads = 0;

  AudioSource? _source;
  SoundHandle? _handle;
  bool _disposed = false;

  SoLoud get _soloud => _engine.soloud;

  @override
  Future<void> load(Uint8List bytes) async {
    if (_disposed) throw StateError('load on a disposed player');
    final source = await _soloud.loadMem('songrush-${_loads++}.opus', bytes);
    if (_disposed) {
      await _soloud.disposeSource(source);
      return;
    }
    final old = _source;
    _source = source;
    if (old != null) await _soloud.disposeSource(old);
  }

  @override
  void play({required double when, required double offset, required double stopAt}) {
    final source = _source;
    if (source == null || _disposed) throw StateError('play before load');
    stop();
    final now = nowSec;
    final SoundHandle handle;
    if (offset > 0 || when <= now) {
      // A late start: from where the song is now.
      handle = _soloud.play(source, paused: true);
      final into = offset + math.max(0, now - when);
      if (into > 0) _soloud.seek(handle, _duration(into));
      _soloud.setPause(handle, false);
    } else {
      handle = _soloud.playScheduled(source, _duration(when));
    }
    _soloud.stopScheduled(handle, _duration(stopAt));
    _handle = handle;
  }

  SoundHandle? get _live {
    final handle = _handle;
    if (handle == null || !_soloud.isInitialized || !_soloud.getIsValidVoiceHandle(handle)) return null;
    return handle;
  }

  @override
  void stop() {
    final handle = _live;
    _handle = null;
    if (handle == null) return;
    _soloud.stop(handle).catchError((Object error) => _log('stop failed: $error'));
  }

  @override
  void setStopAt(double stopAt) {
    final handle = _live;
    if (handle != null) _soloud.stopScheduled(handle, _duration(stopAt));
  }

  @override
  void seek(double offsetSec) {
    final handle = _live;
    if (handle != null) _soloud.seek(handle, _duration(offsetSec));
  }

  @override
  double? get positionSec {
    final handle = _live;
    if (handle == null) return null;
    return _soloud.getPosition(handle).inMicroseconds / 1e6;
  }

  @override
  double get nowSec => _engine.nowSec;

  @override
  double get outputLatencySec => _engine.outputLatencySec;

  @override
  void dispose() {
    if (_disposed) return;
    stop();
    _disposed = true;
    final source = _source;
    _source = null;
    if (source != null && _soloud.isInitialized) {
      _soloud.disposeSource(source).catchError((Object error) => _log('dispose failed: $error'));
    }
  }
}

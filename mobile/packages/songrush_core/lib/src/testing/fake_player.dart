import 'dart:async';
import 'dart:typed_data';

import '../audio/player.dart';

/// The engine clock and latency every [FakePlayer] of one test shares.
class FakeAudioClock {
  FakeAudioClock(this._now);

  /// Seconds on the player's clock (for example a fixed base plus the fake
  /// time's elapsed seconds).
  final double Function() _now;

  double get nowSec => _now();

  /// What every player reports as its output latency, in seconds.
  double outputLatencySec = 0;
}

/// One recorded call to a [FakePlayer].
class PlayerCall {
  const PlayerCall(this.name, [this.args = const {}]);

  /// `load`, `play`, `stop`, `setStopAt`, `seek` or `dispose`.
  final String name;
  final Map<String, Object?> args;

  @override
  String toString() => args.isEmpty ? name : '$name$args';
}

/// A [SongPlayer] for tests: it records its calls and plays nothing.
///
/// [positionSec] follows the [clock] from the last [play] or [seek]
/// (the offset before `when`), plus [skewSec] to simulate a player that is
/// ahead (positive) or behind; null when not playing or after its stopAt.
/// [load] completes at once, or fails with [failLoads] left; with
/// [holdLoads] it waits for [completeLoad] / [failLoad].
class FakePlayer implements SongPlayer {
  FakePlayer(this.clock, {this.failLoads = 0, this.holdLoads = false});

  final FakeAudioClock clock;

  /// Loads still to fail (each fails and decrements).
  int failLoads;

  /// Loads wait for [completeLoad] or [failLoad].
  bool holdLoads;

  final List<PlayerCall> calls = [];
  final List<Uint8List> loadedBytes = [];
  Completer<void>? _pendingLoad;

  bool loaded = false;
  bool disposed = false;

  /// The sound being played, or null.
  ({double when, double offset, double stopAt})? playing;

  /// Added to the derived position.
  double skewSec = 0;

  // The position base: at clock time [_baseAt] the sound was at [_baseOffset].
  double _baseAt = 0;
  double _baseOffset = 0;

  /// The calls' names, in order.
  List<String> get callNames => [for (final c in calls) c.name];

  /// The last call named [name], or null.
  PlayerCall? last(String name) {
    for (final c in calls.reversed) {
      if (c.name == name) return c;
    }
    return null;
  }

  @override
  Future<void> load(Uint8List bytes) {
    calls.add(PlayerCall('load', {'bytes': bytes.length}));
    loadedBytes.add(bytes);
    if (holdLoads) {
      final pending = Completer<void>();
      _pendingLoad = pending;
      return pending.future.then((_) => loaded = true);
    }
    if (failLoads > 0) {
      failLoads -= 1;
      return Future.error(StateError('fake load failure'));
    }
    loaded = true;
    return Future.value();
  }

  /// Completes a held [load].
  void completeLoad() => _pendingLoad?.complete();

  /// Fails a held [load].
  void failLoad([Object error = 'fake load failure']) => _pendingLoad?.completeError(error);

  @override
  void play({required double when, required double offset, required double stopAt}) {
    if (!loaded) throw StateError('play before load');
    calls.add(PlayerCall('play', {'when': when, 'offset': offset, 'stopAt': stopAt}));
    playing = (when: when, offset: offset, stopAt: stopAt);
    _baseAt = when;
    _baseOffset = offset;
  }

  @override
  void stop() {
    calls.add(const PlayerCall('stop'));
    playing = null;
  }

  @override
  void setStopAt(double stopAt) {
    calls.add(PlayerCall('setStopAt', {'stopAt': stopAt}));
    final p = playing;
    if (p != null) playing = (when: p.when, offset: p.offset, stopAt: stopAt);
  }

  @override
  void seek(double offsetSec) {
    calls.add(PlayerCall('seek', {'offset': offsetSec}));
    _baseAt = clock.nowSec;
    _baseOffset = offsetSec;
    skewSec = 0;
  }

  @override
  double? get positionSec {
    final p = playing;
    if (p == null) return null;
    final now = clock.nowSec;
    if (now >= p.stopAt) return null;
    if (now < _baseAt) return _baseOffset + skewSec;
    return _baseOffset + (now - _baseAt) + skewSec;
  }

  @override
  double get nowSec => clock.nowSec;

  @override
  double get outputLatencySec => clock.outputLatencySec;

  @override
  void dispose() {
    calls.add(const PlayerCall('dispose'));
    disposed = true;
    playing = null;
  }
}

/// A [SongPlayerFactory] that makes [FakePlayer]s on one clock and keeps them,
/// newest last. [failLoads] and [holdLoads] apply to the next player made.
class FakePlayerFactory {
  FakePlayerFactory(this.clock);

  final FakeAudioClock clock;
  final List<FakePlayer> players = [];
  int failLoads = 0;
  bool holdLoads = false;

  FakePlayer get last => players.last;

  SongPlayer call() {
    final player = FakePlayer(clock, failLoads: failLoads, holdLoads: holdLoads);
    players.add(player);
    return player;
  }
}

/// A [Fetcher] for tests: [responses] by URL, answered at once unless the
/// URL is in [held] (then [complete] or [fail] answers it). A URL with no
/// response fails, as a 404 does. [requests] lists every fetch in order.
class FakeFetcher implements Fetcher {
  final Map<String, Uint8List> responses = {};
  final Set<String> held = {};
  final List<String> requests = [];
  final Map<String, List<Completer<Uint8List>>> _pending = {};

  @override
  Future<Uint8List> fetch(String url) {
    requests.add(url);
    if (held.contains(url)) {
      final c = Completer<Uint8List>();
      _pending.putIfAbsent(url, () => []).add(c);
      return c.future;
    }
    final body = responses[url];
    if (body == null) return Future.error(StateError('fake fetch: no response for $url'));
    return Future.value(body);
  }

  /// Answers the held fetches of [url] with its response (or [bytes]).
  void complete(String url, [Uint8List? bytes]) {
    held.remove(url);
    final body = bytes ?? responses[url] ?? Uint8List(0);
    for (final c in _pending.remove(url) ?? const <Completer<Uint8List>>[]) {
      c.complete(body);
    }
  }

  /// Fails the held fetches of [url].
  void fail(String url) {
    held.remove(url);
    for (final c in _pending.remove(url) ?? const <Completer<Uint8List>>[]) {
      c.completeError(StateError('fake fetch failed: $url'));
    }
  }

  /// How many times [url] was fetched.
  int count(String url) => requests.where((r) => r == url).length;
}

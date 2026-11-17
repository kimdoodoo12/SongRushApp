import '../protocol/client_message.dart';
import '../protocol/server_message.dart';

// NTP-style estimate of the server clock, ported from the web client's
// frontend/src/net/clockSync.js (cycle 047, F-004). [estimateClock] is pure;
// [ClockSync] gets its clock, its send and its timers injected, so it starts
// no timer of its own.

const int _keep = 3;
const int _window = 8;

/// The gap between the pings of one burst.
const int pingSpacingMs = 150;

/// One ping and its pong: the local send time [t0], the local receive time
/// [t1] and the server's `serverTime`, all in epoch ms.
class ClockSample {
  const ClockSample({required this.t0, required this.t1, required this.serverTime});

  final double t0;
  final double t1;
  final double serverTime;
}

/// The server clock estimate: [offsetMs] is server time minus local time;
/// [rttMs] the lowest round trip among the samples used.
class ClockEstimate {
  const ClockEstimate({required this.offsetMs, required this.rttMs});

  final double offsetMs;
  final double rttMs;

  @override
  bool operator ==(Object other) => other is ClockEstimate && other.offsetMs == offsetMs && other.rttMs == rttMs;

  @override
  int get hashCode => Object.hash(offsetMs, rttMs);

  @override
  String toString() => 'ClockEstimate(offset: $offsetMs, rtt: $rttMs)';
}

double _median(List<double> values) {
  final sorted = [...values]..sort();
  final mid = sorted.length ~/ 2;
  if (sorted.length.isOdd) return sorted[mid];
  return (sorted[mid - 1] + sorted[mid]) / 2;
}

/// Keeps the 3 lowest-rtt [samples]; the offset is the median of theirs and
/// rttMs the lowest rtt. A negative rtt (the client clock stepped backwards)
/// is ignored. Null for none.
ClockEstimate? estimateClock(List<ClockSample> samples) {
  final measured = <({double rtt, double offset, int order})>[];
  for (final s in samples) {
    final rtt = s.t1 - s.t0;
    if (rtt < 0) continue;
    measured.add((rtt: rtt, offset: s.serverTime - (s.t0 + rtt / 2), order: measured.length));
  }
  if (measured.isEmpty) return null;
  // JS's sort is stable and Dart's is not: equal rtts keep their input order.
  measured.sort((a, b) {
    final byRtt = a.rtt.compareTo(b.rtt);
    return byRtt != 0 ? byRtt : a.order.compareTo(b.order);
  });
  final kept = measured.take(_keep).toList();
  return ClockEstimate(offsetMs: _median([for (final m in kept) m.offset]), rttMs: kept.first.rtt);
}

/// Sets one timer that runs [fn] after [ms] and returns its cancel function.
typedef ScheduleFn = void Function() Function(void Function() fn, int ms);

/// The pings and samples of one session's clock sync.
///
/// [send] sends one `ping{id}`. [current] is the estimate over the last 8
/// samples; [lowerBound] is the largest `serverTime - t1` over them, never
/// above the true offset by causality (cycle 051), or null. [clear] drops the
/// stored samples and forgets the pending pings, so a pong for a ping sent
/// before it is ignored.
class ClockSync {
  ClockSync({required this._now, required this._send, required this._schedule});

  final int Function() _now;
  final void Function(PingRequest) _send;
  final ScheduleFn _schedule;

  int _nextId = 1;
  // id -> t0 for the pings still waiting for their pong.
  final Map<Object?, int> _pending = {};
  List<ClockSample> _samples = [];
  final Set<void Function()> _cancels = {};

  void _ping() {
    final id = _nextId++;
    _pending[id] = _now();
    _send(PingRequest(id: id));
  }

  /// Sends [count] pings: the first at once, then [pingSpacingMs] apart.
  void burst(int count) {
    if (count <= 0) return;
    _ping();
    for (var i = 1; i < count; i++) {
      late final void Function() cancel;
      cancel = _schedule(() {
        _cancels.remove(cancel);
        _ping();
      }, pingSpacingMs * i);
      _cancels.add(cancel);
    }
  }

  /// Records `{t0, t1: now(), serverTime}` for a pong of a pending ping.
  void onPong(PongMessage msg) {
    if (!_pending.containsKey(msg.id)) return;
    final t0 = _pending.remove(msg.id)!;
    final t1 = _now();
    // A clock stepped backwards: the sample would only take a window slot.
    if (t1 < t0) return;
    final all = [
      ..._samples,
      ClockSample(t0: t0.toDouble(), t1: t1.toDouble(), serverTime: msg.serverTime.toDouble()),
    ];
    _samples = all.length > _window ? all.sublist(all.length - _window) : all;
  }

  ClockEstimate? current() => estimateClock(_samples);

  double? lowerBound() {
    if (_samples.isEmpty) return null;
    return _samples.map((s) => s.serverTime - s.t1).reduce((a, b) => a > b ? a : b);
  }

  void clear() {
    _samples = [];
    _pending.clear();
  }

  /// Cancels the pings of a burst still to go out.
  void stop() {
    for (final cancel in _cancels.toList()) {
      cancel();
    }
    _cancels.clear();
  }
}

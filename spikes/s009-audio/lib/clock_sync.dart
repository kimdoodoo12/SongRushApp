// Port of frontend/src/net/clockSync.js (cycle 047): NTP-style estimate of
// the server clock. estimate() is pure; ClockSync gets its clock, its send
// and its timer factory injected.
import 'dart:async';

const int keep = 3;
const int window = 8;
const int spacingMs = 150;

class Sample {
  const Sample(this.t0, this.t1, this.serverTime);
  final double t0;
  final double t1;
  final double serverTime;
}

class Estimate {
  const Estimate(this.offsetMs, this.rttMs);
  final double offsetMs;
  final double rttMs;
}

double _median(List<double> values) {
  final sorted = [...values]..sort();
  final mid = sorted.length ~/ 2;
  if (sorted.length.isOdd) return sorted[mid];
  return (sorted[mid - 1] + sorted[mid]) / 2;
}

/// Keeps the 3 lowest-rtt samples; the offset is the median of theirs and
/// rttMs the lowest rtt. A negative rtt is ignored. null for none.
Estimate? estimate(List<Sample> samples) {
  final measured = <({double rtt, double offset})>[];
  for (final s in samples) {
    final rtt = s.t1 - s.t0;
    if (rtt < 0) continue;
    measured.add((rtt: rtt, offset: s.serverTime - (s.t0 + rtt / 2)));
  }
  if (measured.isEmpty) return null;
  measured.sort((a, b) => a.rtt.compareTo(b.rtt));
  final kept = measured.take(keep).toList();
  return Estimate(_median(kept.map((m) => m.offset).toList()), kept.first.rtt);
}

class ClockSync {
  ClockSync({required this.now, required this.send});

  final double Function() now;
  final void Function(Map<String, Object?> msg) send;

  int _nextId = 1;
  final Map<int, double> _pending = {};
  List<Sample> _samples = [];
  final Set<Timer> _timers = {};

  void _ping() {
    final id = _nextId++;
    _pending[id] = now();
    send({'type': 'ping', 'id': id});
  }

  void burst(int count) {
    if (count <= 0) return;
    _ping();
    for (var i = 1; i < count; i++) {
      late final Timer t;
      t = Timer(Duration(milliseconds: spacingMs * i), () {
        _timers.remove(t);
        _ping();
      });
      _timers.add(t);
    }
  }

  /// Returns the sample's rtt, or null when the pong was not ours.
  double? onPong(Map<String, dynamic> msg) {
    final id = msg['id'];
    if (id is! int || !_pending.containsKey(id)) return null;
    final t0 = _pending.remove(id)!;
    final t1 = now();
    if (t1 < t0) return null;
    final serverTime = (msg['serverTime'] as num).toDouble();
    _samples = [..._samples, Sample(t0, t1, serverTime)];
    if (_samples.length > window) {
      _samples = _samples.sublist(_samples.length - window);
    }
    return t1 - t0;
  }

  Estimate? current() => estimate(_samples);

  void stop() {
    for (final t in _timers) {
      t.cancel();
    }
    _timers.clear();
  }
}

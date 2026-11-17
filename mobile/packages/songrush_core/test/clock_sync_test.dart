// Ported from frontend/src/net/clockSync.test.js and clockSyncGuards.test.js
// with the same inputs and expectations. Like those, they drive a hand-made
// clock and timer queue, never real or fake global timers.
import 'package:songrush_core/songrush_core.dart';
import 'package:test/test.dart';

/// vitest's toBeCloseTo(x, 9): |actual - x| < 10^-9 / 2.
Matcher close9(num x) => closeTo(x, 5e-10);

/// A sample with the given rtt and offset: offset = serverTime - (t0 + rtt / 2).
ClockSample sample(num t0, num rtt, num offset) => ClockSample(
      t0: t0.toDouble(),
      t1: (t0 + rtt).toDouble(),
      serverTime: (t0 + rtt / 2 + offset).toDouble(),
    );

class QueuedTimer {
  QueuedTimer(this.fn, this.due, this.seq);
  final void Function() fn;
  final int due;
  final int seq;
}

class SentPing {
  SentPing(this.msg, this.at);
  final PingRequest msg;
  final int at;
}

/// A hand-driven clock and timer queue. schedule(fn, ms) is due at t + ms;
/// advanceTo(t) runs every due timer in time order, with the clock set to its
/// due time, then leaves the clock at t. [t] can also be set backwards, like
/// an OS clock step.
class Harness {
  Harness([this.t = 10000]);

  int t;
  List<QueuedTimer> timers = [];
  int _seq = 0;
  final List<SentPing> sent = [];

  void Function() schedule(void Function() fn, int ms) {
    final timer = QueuedTimer(fn, t + ms, _seq++);
    timers.add(timer);
    return () => timers = timers.where((x) => !identical(x, timer)).toList();
  }

  void send(PingRequest msg) => sent.add(SentPing(msg, t));

  int now() => t;

  void advanceTo(int to) {
    for (var guard = 0; guard < 1000; guard++) {
      final due = timers.where((x) => x.due <= to).toList()
        ..sort((a, b) => a.due != b.due ? a.due.compareTo(b.due) : a.seq.compareTo(b.seq));
      if (due.isEmpty) break;
      final next = due.first;
      timers = timers.where((x) => !identical(x, next)).toList();
      t = next.due;
      next.fn();
    }
    t = to;
  }

  ClockSync sync() => ClockSync(now: now, send: send, schedule: schedule);

  List<SentPing> get pings => sent;
}

PongMessage pong(Object? id, num serverTime) => PongMessage(id: id, serverTime: serverTime.toInt());

void main() {
  group('estimate', () {
    // clockSync.test.js: 'returns null for no samples'
    test('returns null for no samples', () {
      expect(estimateClock([]), isNull);
    });

    // clockSync.test.js: 'one sample: its own offset and rtt'
    test('one sample: its own offset and rtt', () {
      final result = estimateClock([const ClockSample(t0: 1000, t1: 1040, serverTime: 6020)])!;
      expect(result.offsetMs, close9(5000));
      expect(result.rttMs, close9(40));
    });

    // clockSync.test.js: 'an odd rtt gives a half-millisecond midpoint'
    test('an odd rtt gives a half-millisecond midpoint', () {
      final result = estimateClock([const ClockSample(t0: 0, t1: 15, serverTime: 100)])!;
      expect(result.offsetMs, close9(92.5));
      expect(result.rttMs, close9(15));
    });

    // clockSync.test.js: 'two samples: the mean of the two offsets; rttMs is the lower rtt'
    test('two samples: the mean of the two offsets; rttMs is the lower rtt', () {
      final result = estimateClock([sample(0, 30, 100), sample(200, 10, 120)])!;
      expect(result.offsetMs, close9(110));
      expect(result.rttMs, close9(10));
    });

    // clockSync.test.js: 'three samples: the middle offset, not the mean'
    test('three samples: the middle offset, not the mean', () {
      final result = estimateClock([sample(0, 20, 100), sample(200, 30, 200), sample(400, 10, 101)])!;
      expect(result.offsetMs, close9(101));
      expect(result.rttMs, close9(10));
    });

    // clockSync.test.js: 'keeps the 3 lowest-rtt samples, in any input order'
    test('keeps the 3 lowest-rtt samples, in any input order', () {
      final samples = [
        sample(0, 80, 900),
        sample(100, 25, 120),
        sample(200, 60, -700),
        sample(300, 10, 100),
        sample(400, 40, 130),
        sample(500, 90, 5000),
      ];
      // Kept: rtt 10 (100), 25 (120), 40 (130); median 120.
      final result = estimateClock(samples)!;
      expect(result.offsetMs, close9(120));
      expect(result.rttMs, close9(10));
    });

    // clockSync.test.js: 'a sample with a large rtt and a wild offset does not move the result'
    test('a sample with a large rtt and a wild offset does not move the result', () {
      final good = [sample(0, 20, 100), sample(200, 30, 110), sample(400, 10, 105)];
      final base = estimateClock(good)!;
      final withWild = estimateClock([...good, sample(600, 2000, 1000000)])!;
      expect(withWild.offsetMs, close9(base.offsetMs));
      expect(withWild.rttMs, close9(base.rttMs));
      expect(withWild.offsetMs, close9(105));
    });

    // New in Dart (no JS counterpart): JS's sort is stable, Dart's is not
    // guaranteed to be; equal rtts must keep their input order.
    test('equal rtts at the cut keep their input order', () {
      final result = estimateClock([
        sample(0, 10, 100),
        sample(100, 10, 200),
        sample(200, 10, 300),
        sample(300, 10, 9000),
        sample(400, 10, 9000),
      ])!;
      expect(result.offsetMs, close9(200));
    });
  });

  group('estimate ignores negative-RTT samples', () {
    // clockSyncGuards.test.js: 'only negative-RTT samples give null'
    test('only negative-RTT samples give null', () {
      expect(estimateClock([sample(1000, -40, 5000)]), isNull);
      expect(estimateClock([sample(1000, -1, 5000), sample(2000, -500, 7000)]), isNull);
    });

    // clockSyncGuards.test.js: 'a negative-RTT sample is not kept, although its rtt is the lowest'
    test('a negative-RTT sample is not kept, although its rtt is the lowest', () {
      final good = [sample(0, 20, 100), sample(200, 30, 110), sample(400, 40, 120)];
      final base = estimateClock(good)!;
      expect(base.offsetMs, close9(110));
      expect(base.rttMs, close9(20));
      final result = estimateClock([sample(600, -1000, -50000), ...good])!;
      expect(result.offsetMs, close9(110));
      expect(result.rttMs, close9(20));
    });

    // clockSyncGuards.test.js: 'several negative-RTT samples among good ones change nothing'
    test('several negative-RTT samples among good ones change nothing', () {
      final good = [sample(0, 10, 300), sample(200, 12, 301), sample(400, 14, 302)];
      final bad = [sample(100, -5, 9000), sample(300, -2000, -9000), sample(500, -1, 1)];
      final result = estimateClock([bad[0], good[0], bad[1], good[1], bad[2], good[2]])!;
      expect(result.offsetMs, close9(301));
      expect(result.rttMs, close9(10));
    });

    // clockSyncGuards.test.js: 'a zero RTT is not negative and is kept'
    test('a zero RTT is not negative and is kept', () {
      final result = estimateClock([const ClockSample(t0: 1000, t1: 1000, serverTime: 4000)])!;
      expect(result.offsetMs, close9(3000));
      expect(result.rttMs, close9(0));
    });
  });

  group('ClockSync', () {
    // clockSync.test.js: 'burst(8) sends 8 pings with distinct ids, the first at once and then 150 ms apart'
    test('burst(8) sends 8 pings with distinct ids, the first at once and then 150 ms apart', () {
      final h = Harness();
      final sync = h.sync();
      final start = h.t;
      sync.burst(8);
      expect(h.pings, hasLength(1));
      expect(h.pings[0].at, start);
      for (var i = 1; i < 8; i++) {
        h.advanceTo(start + 150 * i - 1);
        expect(h.pings, hasLength(i));
        h.advanceTo(start + 150 * i);
        expect(h.pings, hasLength(i + 1));
      }
      h.advanceTo(start + 10000);
      final sent = h.pings;
      expect(sent, hasLength(8));
      expect(sent.map((s) => s.at), [for (var i = 0; i < 8; i++) start + 150 * i]);
      for (final s in sent) {
        expect(s.msg.id, isNotNull);
      }
      expect(sent.map((s) => s.msg.id).toSet(), hasLength(8));
      // Only pings are sent (the Dart send takes nothing else).
      expect(h.sent, hasLength(8));
    });

    // clockSync.test.js: 'current() is null before any pong'
    test('current() is null before any pong', () {
      final h = Harness();
      final sync = h.sync();
      expect(sync.current(), isNull);
      sync.burst(2);
      h.advanceTo(h.t + 1000);
      expect(sync.current(), isNull);
    });

    // clockSync.test.js: 'onPong records {t0, t1: now(), serverTime} for a known id'
    test('onPong records {t0, t1: now(), serverTime} for a known id', () {
      final h = Harness();
      final sync = h.sync();
      final t0 = h.t;
      sync.burst(1);
      final id = h.pings[0].msg.id;
      h.advanceTo(t0 + 40);
      // offset = serverTime - (t0 + 20) = 3000
      sync.onPong(pong(id, t0 + 20 + 3000));
      final result = sync.current()!;
      expect(result.offsetMs, close9(3000));
      expect(result.rttMs, close9(40));
    });

    // clockSync.test.js: 'onPong for an unknown id is ignored'
    test('onPong for an unknown id is ignored', () {
      final h = Harness();
      final sync = h.sync();
      final t0 = h.t;
      sync.burst(1);
      final id = h.pings[0].msg.id;
      h.advanceTo(t0 + 10);
      sync.onPong(pong('no-such-id', t0 + 999999));
      sync.onPong(pong(987654321, t0 + 999999));
      expect(sync.current(), isNull);
      sync.onPong(pong(id, t0 + 5 + 700));
      h.advanceTo(t0 + 12);
      sync.onPong(pong('no-such-id', t0 - 999999));
      final result = sync.current()!;
      expect(result.offsetMs, close9(700));
      expect(result.rttMs, close9(10));
    });

    // clockSync.test.js: 'current() uses only the most recent 8 samples'
    test('current() uses only the most recent 8 samples', () {
      final h = Harness();
      final sync = h.sync();
      final start = h.t;
      // The first 2 samples have the lowest rtt and a wild offset; the last 8
      // agree on 400. Over all 10 the 3 lowest rtts would include both wild ones.
      const rtts = [2, 3, 20, 22, 24, 26, 28, 30, 32, 34];
      const offsets = [90000, 90000, 400, 400, 400, 400, 400, 400, 400, 400];
      sync.burst(10);
      for (var i = 0; i < 10; i++) {
        final t0 = start + 150 * i;
        h.advanceTo(t0);
        final ping = h.pings[i];
        expect(ping.at, t0);
        h.advanceTo(t0 + rtts[i]);
        sync.onPong(pong(ping.msg.id, t0 + rtts[i] / 2 + offsets[i]));
      }
      final result = sync.current()!;
      expect(result.offsetMs, close9(400));
      expect(result.rttMs, close9(20));
    });

    // clockSync.test.js: 'stop() cancels the remaining pings'
    test('stop() cancels the remaining pings', () {
      final h = Harness();
      final sync = h.sync();
      final start = h.t;
      sync.burst(8);
      h.advanceTo(start + 300);
      expect(h.pings, hasLength(3));
      sync.stop();
      h.advanceTo(start + 60000);
      expect(h.pings, hasLength(3));
      expect(h.timers, isEmpty);
    });

    // New in Dart (lowerBound and clear have no direct JS unit test; the web
    // covers them at session level).
    test('lowerBound() is the largest serverTime - t1; clear() drops samples and pending pings', () {
      final h = Harness();
      final sync = h.sync();
      expect(sync.lowerBound(), isNull);
      final t0 = h.t;
      sync.burst(2);
      h.advanceTo(t0 + 40);
      sync.onPong(pong(h.pings[0].msg.id, t0 + 20 + 500)); // serverTime - t1 = 480
      h.advanceTo(t0 + 150 + 10);
      sync.onPong(pong(h.pings[1].msg.id, t0 + 155 + 500)); // 495
      expect(sync.lowerBound(), close9(495));

      sync.burst(1);
      final late = h.pings[2].msg.id;
      sync.clear();
      expect(sync.current(), isNull);
      expect(sync.lowerBound(), isNull);
      h.advanceTo(h.t + 10);
      sync.onPong(pong(late, h.t + 500));
      expect(sync.current(), isNull);
    });
  });

  group('current() ignores negative-RTT samples from onPong', () {
    // clockSyncGuards.test.js: 'a pong received after the clock stepped backwards leaves current() null'
    test('a pong received after the clock stepped backwards leaves current() null', () {
      final h = Harness();
      final sync = h.sync();
      final t0 = h.t;
      sync.burst(1);
      final id = h.pings[0].msg.id;
      // The OS clock steps back 2 s before the pong arrives: t1 - t0 = -1980.
      h.t = t0 - 2000 + 20;
      sync.onPong(pong(id, t0 + 10 + 3000));
      expect(sync.current(), isNull);
    });

    // clockSyncGuards.test.js: 'with good samples, a backwards-stepped pong does not change current()'
    test('with good samples, a backwards-stepped pong does not change current()', () {
      final h = Harness();
      final sync = h.sync();
      final start = h.t;
      sync.burst(4);
      // Three good pongs: rtt 20, 30, 40, offset 500.
      const rtts = [20, 30, 40];
      for (var i = 0; i < 3; i++) {
        final t0 = start + 150 * i;
        h.advanceTo(t0);
        final ping = h.pings[i];
        expect(ping.at, t0);
        h.advanceTo(t0 + rtts[i]);
        sync.onPong(pong(ping.msg.id, t0 + rtts[i] / 2 + 500));
      }
      final before = sync.current()!;
      expect(before.offsetMs, close9(500));
      expect(before.rttMs, close9(20));

      // The fourth ping goes out; the clock then steps back 5 s.
      final t3 = start + 450;
      h.advanceTo(t3);
      final fourth = h.pings[3];
      expect(fourth.at, t3);
      h.t = t3 - 5000;
      // Counted, its rtt -5000 would be kept first and its offset 5500 would
      // move the median.
      sync.onPong(pong(fourth.msg.id, t3 + 500));
      final after = sync.current()!;
      expect(after.offsetMs, close9(500));
      expect(after.rttMs, close9(20));
    });

    // clockSyncGuards.test.js: 'after a backwards step, later good pongs are used again'
    test('after a backwards step, later good pongs are used again', () {
      final h = Harness();
      final sync = h.sync();
      sync.burst(1);
      final first = h.pings[0];
      h.t = first.at - 3000;
      sync.onPong(pong(first.msg.id, first.at + 800));
      expect(sync.current(), isNull);

      // A new ping on the stepped clock, answered after 30 ms.
      final t0 = h.t;
      sync.burst(1);
      final second = h.pings[1];
      expect(second.at, t0);
      h.advanceTo(t0 + 30);
      sync.onPong(pong(second.msg.id, t0 + 15 + 700));
      final result = sync.current()!;
      expect(result.offsetMs, close9(700));
      expect(result.rttMs, close9(30));
    });
  });
}

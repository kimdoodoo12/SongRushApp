// planPlayback cases ported from frontend/src/audio/playback.test.js
// ('planPlayback') and playbackLatency.test.js with the same inputs and
// expectations; canPlayOggOpus and loadTrack belong to the audio engine
// (F-M3). The start offset, its correction and the reveal window port
// session.js helpers; the drift check and the manual offset are new
// (cycle 142).
import 'package:songrush_core/songrush_core.dart';
import 'package:test/test.dart';

/// vitest's toBeCloseTo(x, 9): |actual - x| < 10^-9 / 2.
Matcher close9(num x) => closeTo(x, 5e-10);

const now = 1000000;
const ctx = 10;

void main() {
  group('planPlayback', () {
    // playback.test.js: 'on time: a delay of 3000 ms gives when = ctxTime + 3 and offset 0'
    test('on time: a delay of 3000 ms gives when = ctxTime + 3 and offset 0', () {
      const startAt = now + 3000;
      final plan =
          planPlayback(startAt: startAt, deadlineAt: startAt + 90000, offsetMs: 0, nowMs: now, ctxTime: ctx)!;
      expect(plan.when, close9(13));
      expect(plan.offset, 0);
    });

    // playback.test.js: 'late by 2500 ms: when = ctxTime and offset = 2.5'
    test('late by 2500 ms: when = ctxTime and offset = 2.5', () {
      const startAt = now - 2500;
      final plan =
          planPlayback(startAt: startAt, deadlineAt: startAt + 90000, offsetMs: 0, nowMs: now, ctxTime: ctx)!;
      expect(plan.when, close9(ctx));
      expect(plan.offset, close9(2.5));
    });

    // playback.test.js: 'stopAt follows deadlineAt'
    test('stopAt follows deadlineAt', () {
      const startAt = now + 3000;
      final a = planPlayback(startAt: startAt, deadlineAt: startAt + 90000, offsetMs: 0, nowMs: now, ctxTime: ctx)!;
      final b = planPlayback(startAt: startAt, deadlineAt: startAt + 30000, offsetMs: 0, nowMs: now, ctxTime: ctx)!;
      expect(a.stopAt, close9(ctx + 93));
      expect(b.stopAt, close9(ctx + 33));
    });

    // playback.test.js: 'offsetMs shifts when, offset and stopAt'
    test('offsetMs shifts when, offset and stopAt', () {
      const startAt = now + 3000;
      const deadlineAt = startAt + 90000;
      // Server 500 ms ahead: the start is 500 ms closer.
      final ahead = planPlayback(startAt: startAt, deadlineAt: deadlineAt, offsetMs: 500, nowMs: now, ctxTime: ctx)!;
      expect(ahead.when, close9(ctx + 2.5));
      expect(ahead.offset, 0);
      expect(ahead.stopAt, close9(ctx + 92.5));
      // Server 4000 ms ahead: the start is 1000 ms in the past.
      final late = planPlayback(startAt: startAt, deadlineAt: deadlineAt, offsetMs: 4000, nowMs: now, ctxTime: ctx)!;
      expect(late.when, close9(ctx));
      expect(late.offset, close9(1));
      expect(late.stopAt, close9(ctx + 89));
    });

    // playback.test.js: 'returns null after the deadline'
    test('returns null after the deadline', () {
      const startAt = now - 100000;
      expect(planPlayback(startAt: startAt, deadlineAt: now - 10000, offsetMs: 0, nowMs: now, ctxTime: ctx), isNull);
      // Exactly at the deadline stopAt equals when, which is also over.
      expect(planPlayback(startAt: startAt, deadlineAt: now, offsetMs: 0, nowMs: now, ctxTime: ctx), isNull);
    });
  });

  group('planPlayback outputLatency', () {
    // playbackLatency.test.js: 'outputLatency 0.05 moves when and stopAt 50 ms earlier; offset stays 0'
    test('outputLatency 0.05 moves when and stopAt 50 ms earlier; offset stays 0', () {
      const startAt = now + 3000;
      final base = planPlayback(startAt: startAt, deadlineAt: startAt + 90000, offsetMs: 0, nowMs: now, ctxTime: ctx)!;
      final plan = planPlayback(
          startAt: startAt, deadlineAt: startAt + 90000, offsetMs: 0, nowMs: now, ctxTime: ctx, outputLatency: 0.05)!;
      expect(plan.when, close9(ctx + 2.95));
      expect(plan.stopAt, close9(ctx + 92.95));
      expect(plan.offset, 0);
      expect(base.when - plan.when, close9(0.05));
      expect(base.stopAt - plan.stopAt, close9(0.05));
    });

    // playbackLatency.test.js: 'outputLatency 0 and an omitted outputLatency give the same plan'
    test('outputLatency 0 and an omitted outputLatency give the same plan', () {
      const startAt = now + 1200;
      final omitted =
          planPlayback(startAt: startAt, deadlineAt: startAt + 60000, offsetMs: 300, nowMs: now, ctxTime: ctx)!;
      final zero = planPlayback(
          startAt: startAt, deadlineAt: startAt + 60000, offsetMs: 300, nowMs: now, ctxTime: ctx, outputLatency: 0)!;
      expect(zero.when, close9(omitted.when));
      expect(zero.offset, close9(omitted.offset));
      expect(zero.stopAt, close9(omitted.stopAt));
      expect(omitted.when, close9(ctx + 0.9));
    });

    // playbackLatency.test.js: 'a delay smaller than the latency starts at ctxTime and the offset covers the difference'
    test('a delay smaller than the latency starts at ctxTime and the offset covers the difference', () {
      // 20 ms of delay, 50 ms of latency: 30 ms late.
      const startAt = now + 20;
      final plan = planPlayback(
          startAt: startAt, deadlineAt: startAt + 90000, offsetMs: 0, nowMs: now, ctxTime: ctx, outputLatency: 0.05)!;
      expect(plan.when, close9(ctx));
      expect(plan.offset, close9(0.03));
      expect(plan.stopAt, close9(ctx + 90.02 - 0.05));
    });

    // playbackLatency.test.js: 'a late start adds the latency to the offset'
    test('a late start adds the latency to the offset', () {
      const startAt = now - 2500;
      final plan = planPlayback(
          startAt: startAt, deadlineAt: startAt + 90000, offsetMs: 0, nowMs: now, ctxTime: ctx, outputLatency: 0.05)!;
      expect(plan.when, close9(ctx));
      expect(plan.offset, close9(2.55));
      expect(plan.stopAt, close9(ctx + 87.5 - 0.05));
    });

    // playbackLatency.test.js: 'offsetMs and outputLatency combine'
    test('offsetMs and outputLatency combine', () {
      const startAt = now + 3000;
      final plan = planPlayback(
          startAt: startAt, deadlineAt: startAt + 90000, offsetMs: 500, nowMs: now, ctxTime: ctx, outputLatency: 0.1)!;
      expect(plan.when, close9(ctx + 2.4));
      expect(plan.offset, 0);
      expect(plan.stopAt, close9(ctx + 92.4));
    });

    // playbackLatency.test.js: 'returns null when the latency-adjusted stopAt is not after when'
    test('returns null when the latency-adjusted stopAt is not after when', () {
      final plan = planPlayback(
          startAt: now - 89980, deadlineAt: now + 20, offsetMs: 0, nowMs: now, ctxTime: ctx, outputLatency: 0.05);
      expect(plan, isNull);
    });
  });

  group('planPlayback manualOffsetMs (new, cycle 142)', () {
    test('a positive offset plays later: when and stopAt move later by it', () {
      const startAt = now + 3000;
      final plan = planPlayback(
          startAt: startAt, deadlineAt: startAt + 90000, offsetMs: 0, nowMs: now, ctxTime: ctx, manualOffsetMs: 120)!;
      expect(plan.when, close9(ctx + 3.12));
      expect(plan.offset, 0);
      expect(plan.stopAt, close9(ctx + 93.12));
    });

    test('a negative offset plays earlier and combines with the latency', () {
      const startAt = now + 3000;
      final plan = planPlayback(
        startAt: startAt,
        deadlineAt: startAt + 90000,
        offsetMs: 0,
        nowMs: now,
        ctxTime: ctx,
        outputLatency: 0.05,
        manualOffsetMs: -100,
      )!;
      expect(plan.when, close9(ctx + 2.85));
      expect(plan.stopAt, close9(ctx + 92.85));
    });

    test('on a late start a positive offset seeks in less', () {
      const startAt = now - 2500;
      final plan = planPlayback(
          startAt: startAt, deadlineAt: startAt + 90000, offsetMs: 0, nowMs: now, ctxTime: ctx, manualOffsetMs: 300)!;
      expect(plan.when, close9(ctx));
      expect(plan.offset, close9(2.2));
    });
  });

  group('startOffset (session.js startOffset)', () {
    const estimate = ClockEstimate(offsetMs: 1000, rttMs: 30);

    test('no estimate: the receipt offset, not provisional, no resync', () {
      final r = startOffset(serverTime: 5000, nowMs: 4200);
      expect(r.offsetMs, 800);
      expect(r.provisional, isFalse);
      expect(r.resync, isFalse);
    });

    test('a receipt within rtt + 50 of the estimate uses the estimate', () {
      // receipt 1080: 80 off, the limit is 30 + 50 = 80.
      final r = startOffset(serverTime: 6080, nowMs: 5000, estimate: estimate);
      expect(r.offsetMs, 1000);
      expect(r.provisional, isFalse);
      expect(r.resync, isFalse);
    });

    test('a receipt drifted below the estimate is used, provisional, and re-syncs', () {
      final r = startOffset(serverTime: 5900, nowMs: 5000, estimate: estimate);
      expect(r.offsetMs, 900);
      expect(r.provisional, isTrue);
      expect(r.resync, isTrue);
    });

    test('a receipt drifted above the estimate keeps the estimate and re-syncs', () {
      final r = startOffset(serverTime: 6081, nowMs: 5000, estimate: estimate);
      expect(r.offsetMs, 1000);
      expect(r.provisional, isFalse);
      expect(r.resync, isTrue);
    });
  });

  group('correctedStartOffset (session.js correctStart)', () {
    test('moves only for a lower bound more than 20 ms above, never later', () {
      expect(correctedStartOffset(offsetMs: 900, lowerBound: null), isNull);
      expect(correctedStartOffset(offsetMs: 900, lowerBound: 920), isNull);
      expect(correctedStartOffset(offsetMs: 900, lowerBound: 920.5), 920.5);
      expect(correctedStartOffset(offsetMs: 900, lowerBound: 850), isNull);
    });
  });

  group('revealWindowEnd (session.js windowEndFrom)', () {
    test('min(playUntil, deadlineAt) in local time', () {
      expect(revealWindowEnd(playUntil: 50000, deadlineAt: 60000, offsetMs: 1000, nowMs: 40000), 49000);
      expect(revealWindowEnd(playUntil: 70000, deadlineAt: 60000, offsetMs: 1000, nowMs: 40000), 59000);
    });

    test('none for a missing playUntil or one not later than now', () {
      expect(revealWindowEnd(playUntil: null, deadlineAt: 60000, offsetMs: 0, nowMs: 40000), isNull);
      expect(revealWindowEnd(playUntil: double.nan, deadlineAt: 60000, offsetMs: 0, nowMs: 40000), isNull);
      expect(revealWindowEnd(playUntil: 41000, deadlineAt: 60000, offsetMs: 1000, nowMs: 40000), isNull);
    });

    test('none when the deadline has passed although playUntil has not', () {
      expect(revealWindowEnd(playUntil: 50000, deadlineAt: 40500, offsetMs: 1000, nowMs: 40000), isNull);
    });
  });

  group('expectedPositionMs', () {
    test('the server song position, plus the latency, minus the manual offset', () {
      expect(expectedPositionMs(startAt: 10000, offsetMs: 500, nowMs: 12000), close9(2500));
      expect(expectedPositionMs(startAt: 10000, offsetMs: 500, nowMs: 12000, outputLatency: 0.05), close9(2550));
      expect(expectedPositionMs(startAt: 10000, offsetMs: 500, nowMs: 12000, manualOffsetMs: 200), close9(2300));
      expect(expectedPositionMs(startAt: 10000, offsetMs: 0, nowMs: 9000), close9(-1000));
    });

    test('matches planPlayback: a plan made now starts at the expected position', () {
      final plan = planPlayback(
        startAt: now - 2500,
        deadlineAt: now + 87500,
        offsetMs: 300,
        nowMs: now,
        ctxTime: ctx,
        outputLatency: 0.05,
        manualOffsetMs: 120,
      )!;
      final expected = expectedPositionMs(
          startAt: now - 2500, offsetMs: 300, nowMs: now, outputLatency: 0.05, manualOffsetMs: 120);
      expect(plan.offset * 1000, closeTo(expected, 1e-6));
    });
  });

  group('checkDrift (new, cycle 142)', () {
    test('within the 40 ms threshold: no correction', () {
      final d = checkDrift(expectedMs: 10000, reportedMs: 10040, nowMs: 50000);
      expect(d.correct, isFalse);
      expect(d.seekToMs, isNull);
      expect(d.driftMs, 40);
      expect(checkDrift(expectedMs: 10000, reportedMs: 9960, nowMs: 50000).correct, isFalse);
    });

    test('past the threshold ahead: seek back to the expected position', () {
      final d = checkDrift(expectedMs: 10000, reportedMs: 10041, nowMs: 50000);
      expect(d.correct, isTrue);
      expect(d.seekToMs, 10000);
      expect(d.driftMs, 41);
    });

    test('past the threshold behind: seek forward to the expected position', () {
      final d = checkDrift(expectedMs: 10000, reportedMs: 9650, nowMs: 50000);
      expect(d.seekToMs, 10000);
      expect(d.driftMs, -350);
    });

    test('at most one correction per 2 s', () {
      expect(checkDrift(expectedMs: 10000, reportedMs: 10500, nowMs: 51999, lastCorrectionAtMs: 50000).correct,
          isFalse);
      expect(checkDrift(expectedMs: 10000, reportedMs: 10500, nowMs: 52000, lastCorrectionAtMs: 50000).seekToMs,
          10000);
    });

    test('a custom threshold and interval', () {
      expect(checkDrift(expectedMs: 0, reportedMs: 90, nowMs: 0, thresholdMs: 100).correct, isFalse);
      expect(checkDrift(expectedMs: 0, reportedMs: 90, nowMs: 600, lastCorrectionAtMs: 0, minIntervalMs: 500).correct,
          isTrue);
    });

    test('never before the start', () {
      expect(checkDrift(expectedMs: -500, reportedMs: 0, nowMs: 50000).correct, isFalse);
    });

    test('the manual offset moves the expected position, so a player on the old timeline is corrected', () {
      // 1.5 s in by the server; the player sits there, but the user wants 100 ms later.
      final expected = expectedPositionMs(startAt: 10000, offsetMs: 0, nowMs: 11500, manualOffsetMs: 100);
      final d = checkDrift(expectedMs: expected, reportedMs: 1500, nowMs: 11500);
      expect(d.seekToMs, close9(1400));
      expect(d.driftMs, close9(100));
    });

    test('the constants', () {
      expect(driftThresholdMs, 40);
      expect(driftMinIntervalMs, 2000);
    });
  });
}

// The estimate() vectors of frontend/src/net/clockSync.test.js.
import 'package:flutter_test/flutter_test.dart';
import 'package:s009_audio/clock_sync.dart';

Sample sample(double t0, double rtt, double offset) =>
    Sample(t0, t0 + rtt, t0 + rtt / 2 + offset);

void main() {
  test('returns null for no samples', () {
    expect(estimate([]), isNull);
  });

  test('one sample: its own offset and rtt', () {
    final r = estimate([const Sample(1000, 1040, 6020)])!;
    expect(r.offsetMs, closeTo(5000, 1e-9));
    expect(r.rttMs, closeTo(40, 1e-9));
  });

  test('an odd rtt gives a half-millisecond midpoint', () {
    final r = estimate([const Sample(0, 15, 100)])!;
    expect(r.offsetMs, closeTo(92.5, 1e-9));
    expect(r.rttMs, closeTo(15, 1e-9));
  });

  test('two samples: the mean of the two offsets; rttMs is the lower rtt', () {
    final r = estimate([sample(0, 30, 100), sample(200, 10, 120)])!;
    expect(r.offsetMs, closeTo(110, 1e-9));
    expect(r.rttMs, closeTo(10, 1e-9));
  });

  test('three samples: the middle offset, not the mean', () {
    final r = estimate(
        [sample(0, 20, 100), sample(200, 30, 200), sample(400, 10, 101)])!;
    expect(r.offsetMs, closeTo(101, 1e-9));
    expect(r.rttMs, closeTo(10, 1e-9));
  });

  test('keeps the 3 lowest-rtt samples, in any input order', () {
    final r = estimate([
      sample(0, 80, 900),
      sample(100, 25, 120),
      sample(200, 60, -700),
      sample(300, 10, 100),
      sample(400, 40, 130),
      sample(500, 90, 5000),
    ])!;
    expect(r.offsetMs, closeTo(120, 1e-9));
    expect(r.rttMs, closeTo(10, 1e-9));
  });

  test('a sample with a large rtt and a wild offset does not move the result',
      () {
    final good = [
      sample(0, 20, 100),
      sample(200, 30, 110),
      sample(400, 10, 105)
    ];
    final base = estimate(good)!;
    final withWild = estimate([...good, sample(600, 2000, 1000000)])!;
    expect(withWild.offsetMs, closeTo(base.offsetMs, 1e-9));
    expect(withWild.rttMs, closeTo(base.rttMs, 1e-9));
    expect(withWild.offsetMs, closeTo(105, 1e-9));
  });

  test('a negative rtt is ignored', () {
    expect(estimate([const Sample(100, 90, 5000)]), isNull);
  });
}

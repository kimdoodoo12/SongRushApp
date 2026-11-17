import 'package:flutter_test/flutter_test.dart';
import 'package:songrush_mobile/platform/audio_latency.dart';
import 'package:songrush_mobile/platform/audio_latency_stub.dart' as stub;

// Cycle 152: the web takes the browser's AudioContext.outputLatency as the
// React web does (session.js: finite, else 0). SoLoud is not loaded here.

void main() {
  group('finiteLatency', () {
    test('a finite number is the latency, in seconds', () {
      expect(finiteLatency(0.042), 0.042);
      expect(finiteLatency(0), 0.0);
      expect(finiteLatency(1), 1.0);
      expect(finiteLatency(-0.01), -0.01, reason: 'finite, as Number.isFinite');
    });

    test('missing, not a number, NaN or infinite is null', () {
      expect(finiteLatency(null), isNull);
      expect(finiteLatency('0.04'), isNull);
      expect(finiteLatency(true), isNull);
      expect(finiteLatency(double.nan), isNull);
      expect(finiteLatency(double.infinity), isNull);
      expect(finiteLatency(double.negativeInfinity), isNull);
    });
  });

  test('no browser context off the web: the stub and the export are null', () {
    expect(stub.browserOutputLatency(), isNull);
    expect(browserOutputLatency(), isNull);
  });
}

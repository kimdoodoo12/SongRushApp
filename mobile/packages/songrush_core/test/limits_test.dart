import 'package:songrush_core/songrush_core.dart';
import 'package:test/test.dart';

void main() {
  group('clampSongCount', () {
    test('keeps a count inside the range', () {
      expect(clampSongCount(minSongs), minSongs);
      expect(clampSongCount(defaultSongs), defaultSongs);
      expect(clampSongCount(maxSongs), maxSongs);
    });

    test('raises a count below the minimum', () {
      expect(clampSongCount(4), 5);
      expect(clampSongCount(0), 5);
      expect(clampSongCount(-3), 5);
    });

    test('lowers a count above the maximum', () {
      expect(clampSongCount(101), 100);
      expect(clampSongCount(1 << 40), 100);
    });
  });
}

import 'package:songrush_core/songrush_core.dart';
import 'package:test/test.dart';

void main() {
  test('the library exports the song count limits', () {
    expect(minSongs, 5);
    expect(maxSongs, 100);
    expect(defaultSongs, inInclusiveRange(minSongs, maxSongs));
  });
}

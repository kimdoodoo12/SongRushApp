import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:songrush_mobile/ui/late_fonts.dart';

/// A bundle that records what is asked of it and has none of it.
class _MissingBundle extends CachingAssetBundle {
  final List<String> asked = [];

  @override
  Future<ByteData> load(String key) {
    asked.add(key);
    return Future.error(StateError('no asset $key'));
  }
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  test('the late fonts: the fallback and mono files are asked for; a missing one is logged, not thrown (cycle 159)',
      () async {
    final bundle = _MissingBundle();
    await loadLateFonts(bundle: bundle);
    expect(bundle.asked, unorderedEquals([
      'assets/fonts/ibmplexsanskr/IBMPlexSansKR-Regular.ttf',
      'assets/fonts/ibmplexsanskr/IBMPlexSansKR-Medium.ttf',
      'assets/fonts/ibmplexsanskr/IBMPlexSansKR-Bold.ttf',
      'assets/fonts/jetbrainsmono/JetBrainsMono-wght.ttf',
    ]));
  });

  test('the late fonts load from the app bundle (cycle 159)', () async {
    await loadLateFonts();
  });
}

import 'package:flutter_test/flutter_test.dart';

import 'package:songrush_mobile/main.dart';

void main() {
  testWidgets('start screen shows the title', (WidgetTester tester) async {
    await tester.pumpWidget(const SongRushApp());

    expect(find.text('SongRush'), findsOneWidget);
    expect(find.text('5-100 songs'), findsOneWidget);
  });
}

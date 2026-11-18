import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';
import 'package:songrush_mobile/app/app_controller.dart';

import 'support/harness.dart';

// Cycle 154 (D-140): on the web build the start screen's and the lobby's
// footer has a PC 버전 link (ko/en/ja). It stores songrush.ui = "pc", so the
// React web keeps this phone, and goes to /?ui=pc (or /r/<code>?ui=pc in a
// room; the query keeps the phone there even with a blocked localStorage,
// cycle 155). Off the web (no page to leave) there is no link.
const pcVersion = {'ko': 'PC 버전', 'en': 'PC version', 'ja': 'PC版'};

final Finder pcLink = find.byKey(const ValueKey('pc-version'));

Future<void> tapPcLink(WidgetTester tester, Harness h) async {
  await tester.ensureVisible(pcLink);
  await tester.pump();
  await tester.tap(pcLink);
  await h.settle(tester);
}

void main() {
  for (final MapEntry(key: lng, value: label) in pcVersion.entries) {
    testWidgets('the start screen link reads $label in $lng', (tester) async {
      final h = Harness(deviceLocales: [Locale(lng)], canNavigate: true);
      await h.pumpApp(tester);
      expect(find.descendant(of: pcLink, matching: find.text(label)), findsOneWidget);
      await h.tearDown(tester);
    });
  }

  testWidgets('on the start screen it stores pc and goes to /?ui=pc', (tester) async {
    final h = Harness(canNavigate: true);
    await h.pumpApp(tester);
    expect(h.prefs.read(uiKey), isNull);
    await tapPcLink(tester, h);
    expect(h.prefs.read(uiKey), 'pc');
    expect(h.navigations, ['/?ui=pc']);
    await h.tearDown(tester);
  });

  testWidgets('in the lobby it goes to the room\'s /r/<code>', (tester) async {
    final h = Harness(canNavigate: true, prefs: {nicknameKey: 'Mina'});
    await h.pumpApp(tester);
    await tester.tap(find.byKey(const ValueKey('create')));
    await h.settle(tester);
    await tester.tap(find.byKey(const ValueKey('nick-confirm')));
    await h.settle(tester);
    await h.admit(tester, code: 'K7XQM');
    expect(find.byKey(const ValueKey('ticket-code')), findsOneWidget);
    await tapPcLink(tester, h);
    expect(h.prefs.read(uiKey), 'pc');
    expect(h.navigations, ['/r/K7XQM?ui=pc']);
    await h.tearDown(tester);
  });

  testWidgets('off the web there is no link', (tester) async {
    final h = Harness();
    await h.pumpApp(tester);
    expect(pcLink, findsNothing);
    expect(h.controller.canSwitchToPc, isFalse);
    h.controller.switchToPc();
    expect(h.prefs.read(uiKey), isNull);
    await h.tearDown(tester);
  });
}

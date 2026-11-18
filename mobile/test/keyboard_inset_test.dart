import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';
import 'package:songrush_mobile/platform/keyboard_inset.dart';
import 'package:songrush_mobile/platform/keyboard_inset_stub.dart' as stub;

import 'support/harness.dart';

// Cycle 161: Chrome on Android overlays the keyboard without resizing the
// page (resizes-visual), and Flutter's view inset stayed up after the
// keyboard was hidden. The web build reads the keyboard from the browser's
// visual viewport and puts it in place of Flutter's inset.

void main() {
  group('keyboardInsetFrom', () {
    test('the window below the visual viewport is the keyboard', () {
      // The emulator: innerHeight 810, the visual viewport 498 at the top.
      expect(keyboardInsetFrom(810, 498, 0), 312);
      // The visual viewport scrolled down by 40 px over the page.
      expect(keyboardInsetFrom(810, 498, 40), 272);
      expect(keyboardInsetFrom(810, 497.6, 0), closeTo(312.4, 1e-9));
    });

    test('no keyboard: the viewports match, or differ by under a pixel', () {
      expect(keyboardInsetFrom(810, 810, 0), 0);
      expect(keyboardInsetFrom(810, 809.5, 0), 0);
      expect(keyboardInsetFrom(810, 810.4, 0), 0);
    });

    test('negative or not finite is 0', () {
      expect(keyboardInsetFrom(700, 810, 0), 0);
      expect(keyboardInsetFrom(810, 400, 500), 0);
      expect(keyboardInsetFrom(810, double.nan, 0), 0);
      expect(keyboardInsetFrom(double.infinity, 498, 0), 0);
    });
  });

  test('no browser off the web: the stub and the export are null', () {
    expect(stub.browserKeyboardInset(), isNull);
    expect(browserKeyboardInset(), isNull);
  });

  testWidgets('the given inset replaces the view inset for the screens; Flutter\'s own is ignored', (tester) async {
    final keyboard = ValueNotifier<double>(0);
    final h = Harness(keyboardInset: keyboard);
    await h.pumpApp(tester);
    // Above the start screen's Scaffold (which takes the inset out of its
    // body's MediaQuery).
    final context = tester.element(find.byType(Scaffold));
    // Flutter reports a keyboard the browser does not show (the stale inset).
    tester.view.viewInsets = const FakeViewPadding(bottom: 300 * 3);
    await h.settle(tester);
    expect(MediaQuery.viewInsetsOf(context).bottom, 0);
    keyboard.value = 250;
    await h.settle(tester);
    expect(MediaQuery.viewInsetsOf(context).bottom, 250);
    keyboard.value = 0;
    await h.settle(tester);
    expect(MediaQuery.viewInsetsOf(context).bottom, 0);
    tester.view.resetViewInsets();
    await h.tearDown(tester);
  });

  testWidgets('the nickname dialog keeps 확인 above the keyboard, and centres again when it goes', (tester) async {
    final keyboard = ValueNotifier<double>(0);
    final h = Harness(keyboardInset: keyboard);
    await h.pumpApp(tester);
    h.controller.openCreateDialog();
    await h.settle(tester);
    final centred = tester.getRect(find.byKey(const ValueKey('nick-confirm')));
    keyboard.value = 400;
    await h.settle(tester);
    final confirm = tester.getRect(find.byKey(const ValueKey('nick-confirm')));
    expect(confirm.bottom, lessThanOrEqualTo(phone.height - 400));
    expect(find.byKey(const ValueKey('nick-confirm')).hitTestable(), findsOneWidget);
    expect(find.byKey(const ValueKey('nickname')).hitTestable(), findsOneWidget);
    keyboard.value = 0;
    await h.settle(tester);
    expect(tester.getRect(find.byKey(const ValueKey('nick-confirm'))), centred);
    await h.tearDown(tester);
  });
}

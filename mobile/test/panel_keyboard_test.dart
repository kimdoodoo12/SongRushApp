import 'package:flutter/gestures.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';
import 'package:songrush_core/songrush_core.dart';
import 'package:songrush_mobile/platform/stale_view_guard.dart';

import 'game_layout_test.dart' show expectOneScreen, expectSheet, keyboard, rectOf, screenOf;
import 'game_screen_test.dart' show audio, coverOf, enterGame, logTexts, openChat, png, revealFrame, start;
import 'reconnect_test.dart' show playingSnapshot, reloadInto;
import 'support/harness.dart';

// Cycle 163: a choice room's chat panel in every state of the game, and the
// owner's phone picture (Flutter mobile web, Chrome on Android): after
// sending a line in the panel right after a reveal, with the next song
// loading and the keyboard down again, the screen stayed at the
// keyboard-open height (the top bar, the load line, the panel squeezed to
// its input) with the rest of the page empty and untouchable.
// Cycle 165 (D-146): the panel became a full-screen chat sheet; the same
// sequences run with the sheet.
//
// The cause is the web engine's resize rule (flutter_web_sdk window.dart
// _handleBrowserResize): a browser resize while text editing is on keeps the
// view's size (the keyboard goes into the insets); one while it is off
// resizes the view to the visual viewport. On the web a touch outside a
// TextField unfocuses it on pointer down (EditableText's tap-outside
// action), so a tap on 보내기 closed the text connection until the tap's
// up (the send refocuses the field): the keyboard's animation resized the
// view to its keyboard-open height in that gap, and with editing on again
// the keyboard going down left it there. The tests below run that engine
// rule over the test view ([_WebView]); a mouse tap stands for the web's
// touch tap, as both unfocus a field from outside it.

/// The screen's size before any keyboard.
const Size _full = phone;

/// The web engine's view on a phone with the on-screen keyboard: [keyboard]
/// goes up or down as a browser resize; with text editing on, the view
/// keeps its size and the app's keyboard inset (cycle 161) follows; with it
/// off, the view takes the visual viewport's height.
class _WebView {
  _WebView(this.tester, this.h, this.inset);

  final WidgetTester tester;
  final Harness h;
  final ValueNotifier<double> inset;
  bool up = false;

  bool get editing => tester.testTextInput.isVisible;

  /// A browser resize event at the keyboard's current height.
  void resize() {
    inset.value = up ? keyboard : 0;
    if (!editing) {
      tester.view.physicalSize = Size(_full.width, _full.height - (up ? keyboard : 0)) * 3;
    }
  }

  Future<void> keyboardUp() async {
    up = true;
    resize();
    await h.settle(tester);
  }

  Future<void> keyboardDown() async {
    up = false;
    resize();
    await h.settle(tester);
  }

  /// A tap on [key] whose pointer is down for a moment: a resize event
  /// (the keyboard animating) comes in while it is down.
  Future<void> tap(String key) async {
    final gesture = await tester.startGesture(tester.getCenter(find.byKey(ValueKey(key))), kind: PointerDeviceKind.mouse);
    await tester.pump(const Duration(milliseconds: 40));
    resize();
    await tester.pump(const Duration(milliseconds: 40));
    await gesture.up();
    await h.settle(tester);
    resize();
    await h.settle(tester);
  }
}

/// The chat sheet laid out in full (D-146): the view at the screen's full
/// height; the sheet whole ([expectSheet]: 채팅 and 닫기, the status line,
/// the log and the input with 보내기 shown and tappable above the keyboard
/// by [inset], nothing under it taking a tap); the log a real part of the
/// sheet; the covers, when the song has them, under it.
void expectPanel(WidgetTester tester, String state, {double inset = 0, bool covers = true, bool input = true}) {
  expect(screenOf(tester), _full, reason: '$state: the view keeps the screen\'s height');
  expectSheet(tester, state, inset: inset, input: input);
  final log = rectOf(tester, 'chat-log');
  final bottom = _full.height - inset;
  expect(log.height, greaterThan((bottom - 120) * 0.25), reason: '$state: the log $log fills the sheet');
  if (covers) {
    expect(find.byKey(const ValueKey('tile-0')), findsOneWidget, reason: '$state: the covers under the sheet');
    expect(find.byKey(const ValueKey('tile-0')).hitTestable(), findsNothing, reason: '$state: the covers take no tap');
  }
}

/// Closes the sheet with 닫기 (it slides down) and checks the game screen
/// whole under it.
Future<void> closeSheet(WidgetTester tester, Harness h, String state) async {
  await tester.tap(find.byKey(const ValueKey('chat-close')));
  await h.settle(tester);
  expect(find.byKey(const ValueKey('chat-sheet')), findsNothing, reason: '$state: 닫기');
  expectOneScreen(tester, '$state, closed');
}

const List<String> _first = ['a', 'b', 'c', 'd'];
const List<String> _next = ['e', 'f', 'g', 'h'];

List<Map<String, Object?>> _covers(List<String> ids) => [for (final id in ids) {'coverUrl': coverOf(id)}];

/// A choice room of 10 songs with song 1 playing, its covers in, the next
/// song's audio held (still loading when it comes).
Future<(Harness, _WebView, ({int startAt, int deadlineAt}))> _playing(WidgetTester tester) async {
  final inset = ValueNotifier<double>(0);
  final h = await enterGame(tester, options: {'answerMode': 'choice'}, keyboardInset: inset);
  final web = _WebView(tester, h, inset);
  for (final id in [..._first, ..._next]) {
    h.fetcher.responses[coverOf(id)] = png;
  }
  h.fetcher.held.add(audio(1));
  h.receive({'type': 'prepare', 'index': 0, 'total': 10, 'audioUrl': audio(0), 'choices': _covers(_first)});
  await h.settle(tester);
  final clock = await start(tester, h, 0);
  return (h, web, clock);
}

Map<String, Object?> _reveal({int? playUntil}) => {
      ...revealFrame(0, correctChoice: 1, playUntil: playUntil),
      'nextChoices': _covers(_next),
    };

Map<String, Object?> _prepareNext({int? holdMs}) =>
    {'type': 'prepare', 'index': 1, 'total': 10, 'audioUrl': audio(1), 'choices': _covers(_next), 'holdMs': ?holdMs};

void main() {
  // The owner's sequence, with and without a held reveal (the music
  // playing on after the answer, the next prepare held), the next prepare
  // before or after the keyboard goes down.
  for (final held in [false, true]) {
    for (final prepareFirst in [true, false]) {
      testWidgets('sending in the chat sheet after a reveal keeps the screen whole '
          '(held $held, prepare ${prepareFirst ? 'before' : 'after'} the keyboard goes down)', (tester) async {
        final (h, web, clock) = await _playing(tester);
        await openChat(tester, h);
        await web.keyboardUp();
        expectPanel(tester, 'sheet, keyboard', inset: keyboard);

        h.receive(_reveal(playUntil: held ? clock.startAt + 40000 : null));
        await h.settle(tester);
        await h.advance(tester, const Duration(seconds: 4));
        // The reveal is a line in the sheet; its card waits for 닫기.
        expect(find.byKey(const ValueKey('sheet-reveal')), findsOneWidget);
        expectPanel(tester, 'reveal line, keyboard', inset: keyboard);

        // Right after the reveal: type and tap 보내기.
        await tester.enterText(find.byKey(const ValueKey('chat-input')), 'hello');
        await web.tap('chat-send');
        expect(h.lastSent<ChatRequest>().text, 'hello');
        expect(web.editing, isTrue, reason: 'the field keeps its text connection');
        expect(tester.widget<EditableText>(find.byType(EditableText)).focusNode.hasFocus, isTrue);
        h.receive({'type': 'chat', 'name': 'Mina', 'text': 'hello'});
        await h.settle(tester);
        expectPanel(tester, 'sent, keyboard', inset: keyboard);

        if (prepareFirst) {
          h.receive(_prepareNext(holdMs: held ? 5000 : null));
          await h.advance(tester, Duration(seconds: held ? 40 : 1));
          await web.keyboardDown();
        } else {
          await web.keyboardDown();
          h.receive(_prepareNext(holdMs: held ? 5000 : null));
          await h.advance(tester, Duration(seconds: held ? 40 : 1));
        }
        // The next song loading: the owner's picture.
        expect(find.byKey(const ValueKey('load-line')), findsOneWidget);
        expect(find.byKey(const ValueKey('reveal-strip')), findsNothing);
        expect(find.byKey(const ValueKey('my-rank')), findsOneWidget);
        expectPanel(tester, 'next song loading');
        expect(logTexts(tester).last, 'hello');
        // The controls respond: the sheet's 닫기, then the screen's 나가기.
        await closeSheet(tester, h, 'next song loading');
        await tester.tap(find.byKey(const ValueKey('game-leave')));
        await h.settle(tester);
        expect(find.byKey(const ValueKey('leave-dialog')), findsOneWidget, reason: 'the controls respond');
        await tester.tap(find.byKey(const ValueKey('leave-cancel')));
        await h.settle(tester);
        await openChat(tester, h);

        h.fetcher.complete(audio(1));
        await h.settle(tester);
        await start(tester, h, 1);
        expectPanel(tester, 'song 2 playing');
        await h.tearDown(tester);
      });
    }
  }

  testWidgets('the keyboard send key keeps the text connection too', (tester) async {
    final (h, web, _) = await _playing(tester);
    await openChat(tester, h);
    await web.keyboardUp();
    await tester.enterText(find.byKey(const ValueKey('chat-input')), 'by key');
    await tester.testTextInput.receiveAction(TextInputAction.send);
    web.resize();
    await h.settle(tester);
    expect(h.lastSent<ChatRequest>().text, 'by key');
    expect(web.editing, isTrue);
    await web.keyboardDown();
    expectPanel(tester, 'sent by key');
    await h.tearDown(tester);
  });

  // The sheet in each state of a choice room's game, opened before the
  // state and opened in it, with the keyboard up and down.
  testWidgets('the chat sheet lays out in every state of the game', (tester) async {
    final (h, web, clock) = await _playing(tester);
    Future<void> each(String state, {bool covers = true, bool input = true}) async {
      expectPanel(tester, state, covers: covers, input: input);
      await closeSheet(tester, h, state);
      await openChat(tester, h);
      expectPanel(tester, '$state, opened in it', covers: covers, input: input);
      if (!input) return;
      await web.keyboardUp();
      expectPanel(tester, '$state, keyboard', inset: keyboard, covers: covers);
      await web.keyboardDown();
      expectPanel(tester, '$state, keyboard down', covers: covers);
    }

    await openChat(tester, h);
    await each('playing');
    h.receive(_reveal(playUntil: clock.startAt + 60000));
    await h.settle(tester);
    expect(find.byKey(const ValueKey('sheet-reveal')), findsOneWidget, reason: 'the sheet shows the reveal line');
    await each('reveal');
    await h.advance(tester, const Duration(seconds: 4));
    await each('reveal folded');
    h.receive(_prepareNext(holdMs: 5000));
    await h.settle(tester);
    await each('reveal held');
    await h.advance(tester, const Duration(seconds: 60));
    expect(find.byKey(const ValueKey('load-line')), findsOneWidget);
    await each('next song loading');
    // The fetch fails, and so does its one retry.
    h.fetcher.responses.remove(audio(1));
    h.fetcher.fail(audio(1));
    await h.settle(tester);
    expect(find.text('이번 곡을 재생하지 못했습니다.'), findsOneWidget);
    await each('load failed');
    h.receive({
      'type': 'gameOver',
      'ranking': [
        {'rank': 1, 'name': 'Aki', 'score': 3},
        {'rank': 2, 'name': 'Mina', 'score': 0},
      ],
    });
    await h.settle(tester);
    expect(find.byKey(const ValueKey('final')), findsOneWidget);
    await each('game over', covers: false, input: false);
    await h.tearDown(tester);
  });

  testWidgets('the chat sheet with the sound prompt (a reload into a choice song)', (tester) async {
    final h = await reloadInto(tester, (h) {
      final snapshot = playingSnapshot(h);
      final song = snapshot['song']! as Map<String, Object?>;
      for (final id in _first) {
        h.fetcher.responses[coverOf(id)] = png;
      }
      return {
        ...snapshot,
        'song': {...song, 'choices': _covers(_first)},
      };
    });
    expect(find.byKey(const ValueKey('unlock-sound')), findsOneWidget);
    await openChat(tester, h);
    expectPanel(tester, 'sound prompt');
    tester.view.viewInsets = const FakeViewPadding(bottom: keyboard * 3);
    await h.settle(tester);
    expectPanel(tester, 'sound prompt, keyboard', inset: keyboard);
    // The prompt is the game screen's: under the sheet, back after 닫기.
    expect(find.byKey(const ValueKey('unlock-sound')).hitTestable(), findsNothing);
    tester.view.resetViewInsets();
    await h.settle(tester);
    await closeSheet(tester, h, 'sound prompt');
    expect(find.byKey(const ValueKey('unlock-sound')).hitTestable(), findsOneWidget);
    await h.tearDown(tester);
  });

  // The recovery (cycle 163): a view left at the keyboard-open height with
  // the keyboard down is measured again, the field unfocused first.
  group('stale view guard', () {
    Future<(Harness, _WebView, StaleViewGuard, List<String>, List<int>)> stuck(WidgetTester tester) async {
      final (h, web, _) = await _playing(tester);
      final warnings = <String>[];
      final remeasures = <int>[];
      final guard = StaleViewGuard(
        inset: web.inset,
        viewHeight: () => screenOf(tester).height,
        innerHeight: () => _full.height,
        unfocus: () => FocusManager.instance.primaryFocus?.unfocus(),
        remeasure: () {
          remeasures.add(remeasures.length);
          web.resize();
        },
        warn: warnings.add,
      )..start();
      addTearDown(guard.dispose);
      await openChat(tester, h);
      await web.keyboardUp();
      // The engine left the view at the keyboard-open height while editing
      // was on (as the gap the 보내기 tap made before the fix did).
      tester.view.physicalSize = Size(_full.width, _full.height - keyboard) * 3;
      await h.settle(tester);
      expect(web.editing, isTrue);
      return (h, web, guard, warnings, remeasures);
    }

    testWidgets('the keyboard going down with the view stuck: unfocus, re-measure, the full screen back', (tester) async {
      final (h, web, _, warnings, remeasures) = await stuck(tester);
      await web.keyboardDown();
      // Editing on: the engine keeps the short view.
      expect(screenOf(tester).height, _full.height - keyboard);
      await tester.pump(staleViewSettle + staleViewRemeasureDelay);
      await h.settle(tester);
      expect(remeasures, hasLength(1));
      expect(screenOf(tester), _full, reason: 'the view measured again at the screen height');
      expect(tester.widget<EditableText>(find.byType(EditableText)).focusNode.hasFocus, isFalse);
      expect(web.editing, isFalse);
      expect(warnings, hasLength(1));
      expectPanel(tester, 'recovered', input: true);
      await h.tearDown(tester);
    });

    testWidgets('nothing while the keyboard is up, nor with the view whole; once per second; warned once', (tester) async {
      final (h, web, _, warnings, remeasures) = await stuck(tester);
      // The keyboard up: no check.
      await h.advance(tester, const Duration(seconds: 2));
      expect(remeasures, isEmpty);
      expect(web.editing, isTrue);
      // The view whole with no keyboard: no check either.
      tester.view.physicalSize = _full * 3;
      await web.keyboardDown();
      await h.advance(tester, const Duration(seconds: 2));
      expect(remeasures, isEmpty);
      expect(tester.widget<EditableText>(find.byType(EditableText)).focusNode.hasFocus, isTrue);

      // Stuck twice within a second: one recovery now, the second after
      // the gap; one warning.
      await web.keyboardUp();
      tester.view.physicalSize = Size(_full.width, _full.height - keyboard) * 3;
      await web.keyboardDown();
      await tester.pump(staleViewSettle + staleViewRemeasureDelay);
      expect(remeasures, hasLength(1));
      expect(screenOf(tester), _full);
      await tester.tap(find.byKey(const ValueKey('chat-input')));
      await web.keyboardUp();
      expect(web.editing, isTrue);
      tester.view.physicalSize = Size(_full.width, _full.height - keyboard) * 3;
      await web.keyboardDown();
      await tester.pump(staleViewSettle);
      expect(remeasures, hasLength(1), reason: 'within a second of the last');
      expect(screenOf(tester).height, _full.height - keyboard);
      await h.advance(tester, staleViewGap + staleViewRemeasureDelay);
      expect(remeasures, hasLength(2));
      expect(screenOf(tester), _full);
      expect(warnings, hasLength(1));
      await h.tearDown(tester);
    });
  });
}

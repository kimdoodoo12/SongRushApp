import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';
import 'package:songrush_core/songrush_core.dart';
import 'package:songrush_mobile/ui/chat_panel.dart';
import 'package:songrush_mobile/ui/game_screen.dart';
import 'package:songrush_mobile/ui/offset_dialog.dart';
import 'package:songrush_mobile/ui/palette.dart';

import 'game_screen_test.dart'
    show buttonText, enterGame, floatTexts, logTexts, openChat, prepare, revealFrame, scoreRow, start, textOf;
import 'reconnect_test.dart' show playingSnapshot, reloadInto, reconnecting;
import 'support/harness.dart';

// Cycle 151 (D-142): the game screen fits one phone screen with no page
// scroll; only the chat log, the reveal card, the final ranking and the
// scores scroll inside. The input stays above the on-screen keyboard.
// Cycle 160 (D-145): the v2 layout. A small disc in the top bar and 내 순위
// in place of the players' line; a chat room's log fills the middle; a
// choice room's covers fill it, the chat floating over them, and the chat
// panel opens from a round 채팅 button; the reveal card folds into a strip.

const List<Size> sizes = [Size(360, 640), Size(390, 844), Size(430, 932)];
const List<String> langs = ['ko', 'en', 'ja'];

/// The on-screen keyboard's height in the keyboard checks.
const double keyboard = 300;

/// Eight players, some with long names.
List<Map<String, Object?>> eight() => [
      player('Mina', host: true),
      player('Aki'),
      for (var i = 3; i <= 8; i++) player('Long player name $i', connected: i != 5),
    ];

Future<void> resize(WidgetTester tester, Harness h, Size size) async {
  tester.view.physicalSize = size * 3;
  await h.settle(tester);
}

Future<void> keyboardUp(WidgetTester tester, Harness h) async {
  tester.view.viewInsets = const FakeViewPadding(bottom: keyboard * 3);
  await h.settle(tester);
}

Future<void> keyboardDown(WidgetTester tester, Harness h) async {
  tester.view.resetViewInsets();
  await h.settle(tester);
}

Size screenOf(WidgetTester tester) => tester.view.physicalSize / tester.view.devicePixelRatio;

Rect rectOf(WidgetTester tester, String key) => tester.getRect(find.byKey(ValueKey(key)));

bool shown(String key) => find.byKey(ValueKey(key)).evaluate().isNotEmpty;

/// No overflow; no vertical scroll outside the chat log, the reveal card,
/// the final ranking and the scores; and every shown control of the
/// screen inside the part of the screen above the keyboard ([inset]).
void expectOneScreen(WidgetTester tester, String state, {double inset = 0}) {
  final error = tester.takeException();
  expect(error, isNull, reason: '$state: ${error is FlutterError ? error.toStringDeep() : error}');
  final screen = screenOf(tester);
  final visible = Rect.fromLTWH(0, 0, screen.width, screen.height - inset);
  const allowed = ['chat-log', 'reveal-scroll', 'final-list', 'scores-list'];
  for (final element in find.byType(Scrollable).evaluate()) {
    final scrollable = element.widget as Scrollable;
    if (axisDirectionToAxis(scrollable.axisDirection) != Axis.vertical) continue;
    final inside = allowed.any((key) => find
        .ancestor(of: find.byElementPredicate((e) => identical(e, element)), matching: find.byKey(ValueKey(key)))
        .evaluate()
        .isNotEmpty);
    expect(inside, isTrue, reason: '$state: a vertical scroll outside the log, the card, the ranking and the scores');
  }
  for (final key in [
    'disc',
    'clock',
    'round',
    'my-rank',
    'game-leave',
    'skip',
    'skip-count',
    'pick-count',
    'hint',
    'pick-wrong',
    'chat-input',
    'chat-send',
    'chat-open',
    'chat-close',
    'reveal-strip',
    'reveal-more',
    'reveal-less',
    'to-lobby',
    'unlock-sound',
    'scores-close',
    for (var i = 0; i < 8; i++) 'tile-$i',
  ]) {
    final found = find.byKey(ValueKey(key));
    if (found.evaluate().isEmpty) continue;
    final rect = tester.getRect(found);
    expect(rect.top >= visible.top - 0.5 && rect.bottom <= visible.bottom + 0.5, isTrue,
        reason: '$state: $key at $rect, outside $visible');
    expect(rect.left >= -0.5 && rect.right <= visible.right + 0.5, isTrue, reason: '$state: $key at $rect');
  }
  // The reveal card starts on screen (its inner text may scroll).
  final card = find.byKey(const ValueKey('reveal'));
  if (card.evaluate().isNotEmpty) {
    expect(tester.getRect(card).top, lessThan(visible.bottom), reason: '$state: the card is off screen');
  }
}

/// The newest chat line [text] is inside the log's box.
void expectNewestShown(WidgetTester tester, String text, String state) {
  final log = rectOf(tester, 'chat-log');
  final line = tester.getRect(find.descendant(of: find.byKey(const ValueKey('chat-log')), matching: find.text(text)));
  expect(line.top >= log.top - 0.5 && line.bottom <= log.bottom + 0.5, isTrue, reason: '$state: $line not in $log');
}

/// The chat log's bubble around [text] (its nearest decorated box).
BoxDecoration bubbleOf(WidgetTester tester, String text) {
  final boxes = find.ancestor(
    of: find.descendant(of: find.byKey(const ValueKey('chat-log')), matching: find.text(text)),
    matching: find.byType(Container),
  );
  return tester.widget<Container>(boxes.first).decoration! as BoxDecoration;
}

/// The tiles' rects, by position.
List<Rect> tileRects(WidgetTester tester, int count) => [for (var i = 0; i < count; i++) rectOf(tester, 'tile-$i')];

/// The covers fill their box (D-145): every tile the same size, laid out in
/// the columns [tileLayout] picks for the box, and the block of tiles
/// reaching across the box's width or down its height. A tile's key sits
/// inside its polaroid frame (5 px on the sides, 14 below, 5 above); a
/// short last row is centred.
void expectCoversFill(WidgetTester tester, int count, String state) {
  final tiles = tileRects(tester, count);
  for (final r in tiles) {
    expect(r.width, closeTo(tiles.first.width, 0.01), reason: '$state: same width');
    expect(r.height, closeTo(tiles.first.height, 0.01), reason: '$state: same height');
  }
  final grid = rectOf(tester, 'choices');
  final tops = [for (final r in tiles) r.top.round()];
  final rows = tops.toSet().length;
  final columns = tops.where((top) => top == tops.first).length;
  expect(columns, tileLayout(count, grid.width, grid.height).columns, reason: '$state: the best columns');
  final inner = tiles.reduce((a, b) => a.expandToInclude(b));
  final block = Rect.fromLTRB(inner.left - 5, inner.top - 5, inner.right + 5, inner.bottom + 14);
  final wide = block.width >= grid.width - columns * 1.5 - 1;
  final tall = block.height >= grid.height - rows * 2 - 1;
  expect(wide || tall, isTrue, reason: '$state: tiles $block do not fill $grid');
}

/// The opacity the floating bubble of [text] is drawn with: its
/// background's alpha.
double floatBackAlpha(WidgetTester tester, String text) {
  final boxes = find.ancestor(
    of: find.descendant(of: find.byKey(const ValueKey('chat-float')), matching: find.textContaining(text)),
    matching: find.byType(Container),
  );
  return (tester.widget<Container>(boxes.first).decoration! as BoxDecoration).color!.a;
}

void main() {
  for (final size in sizes) {
    for (final lang in langs) {
      final where = '${size.width.toInt()}x${size.height.toInt()} $lang';

      testWidgets('chat room v2 at $where: log fills, own lines right, reveal folds, keyboard, game over',
          (tester) async {
        final h = await enterGame(tester, language: lang, options: {'gameMode': 'ranked'});
        await resize(tester, h, size);
        h.receive({'type': 'roster', 'phase': 'playing', 'players': eight()});
        await prepare(tester, h, 0, total: 1);
        await start(tester, h, 0);
        h.receive({'type': 'skipState', 'index': 0, 'votes': 1, 'needed': 4});
        for (var i = 0; i < 30; i++) {
          h.receive({'type': 'chat', 'name': i % 3 == 0 ? 'Mina' : 'Aki', 'text': 'line $i'});
        }
        h.receive({'type': 'correct', 'index': 0, 'name': 'Aki', 'rank': 1});
        await h.settle(tester);
        expectOneScreen(tester, 'chat playing');
        // The small disc in the top bar; no players' line, no big disc row.
        expect(tester.getSize(find.byKey(const ValueKey('disc'))).width, lessThanOrEqualTo(34));
        expect(rectOf(tester, 'disc').bottom, lessThan(rectOf(tester, 'my-rank').top));
        expect(find.byKey(const ValueKey('players')), findsNothing);
        expect(find.byKey(const ValueKey('chat-preview')), findsNothing);
        // The log fills the middle: from under the hint down to the input.
        final log = rectOf(tester, 'chat-log');
        expect(log.top - rectOf(tester, 'hint').bottom, lessThanOrEqualTo(12), reason: 'the log right under the hint');
        expect(rectOf(tester, 'chat-input').top - log.bottom, inInclusiveRange(0, 24), reason: 'the log down to the input');
        expect(log.height, greaterThan(screenOf(tester).height * 0.5), reason: 'the log takes the screen');
        expectNewestShown(tester, logTexts(tester).last, 'chat playing');
        // Own lines on the right in the accent colour; others on the left
        // with their name.
        final own = tester.getRect(find.descendant(of: find.byKey(const ValueKey('chat-log')), matching: find.text('line 27')));
        final other = tester.getRect(find.descendant(of: find.byKey(const ValueKey('chat-log')), matching: find.text('line 28')));
        expect(own.left, greaterThan(log.center.dx), reason: 'own line right-aligned');
        expect(log.right - own.right, lessThanOrEqualTo(32), reason: 'own line at the right edge');
        expect(other.left, lessThan(log.left + 150), reason: 'others on the left');
        expect(other.right, lessThan(own.right - 40), reason: 'others not at the right edge');
        expect(bubbleOf(tester, 'line 27').color, Palette.a1);
        expect(bubbleOf(tester, 'line 28').color, Palette.panel2);
        expect(
          find.descendant(of: find.byKey(const ValueKey('chat-log')), matching: find.byKey(const ValueKey('chat-name'))),
          findsWidgets,
        );

        await keyboardUp(tester, h);
        expectOneScreen(tester, 'chat playing, keyboard', inset: keyboard);
        expect(rectOf(tester, 'chat-log').height, lessThan(log.height), reason: 'the log shrinks above the keyboard');
        expect(rectOf(tester, 'chat-input').bottom, lessThanOrEqualTo(screenOf(tester).height - keyboard));
        h.receive({'type': 'chat', 'name': 'Aki', 'text': 'after the keyboard'});
        await h.settle(tester);
        expectNewestShown(tester, 'after the keyboard', 'chat playing, keyboard, new line');
        await keyboardDown(tester, h);

        h.receive(revealFrame(0, ranking: [
          {'name': 'Aki', 'rank': 1, 'points': 3},
          {'name': 'Long player name 3', 'rank': 2, 'points': 2},
          {'name': 'Mina', 'rank': 3, 'points': 1},
        ]));
        await h.settle(tester);
        // The full card first, over the log.
        expectOneScreen(tester, 'chat reveal, card');
        expect(find.byKey(const ValueKey('reveal')), findsOneWidget);
        expect(find.byKey(const ValueKey('reveal-strip')), findsNothing);
        expect(rectOf(tester, 'chat-log').top, greaterThan(rectOf(tester, 'reveal').top));
        await keyboardUp(tester, h);
        expectOneScreen(tester, 'chat reveal card, keyboard', inset: keyboard);
        await keyboardDown(tester, h);
        // 3.5 s after the reveal it folds into the strip.
        await h.advance(tester, revealFoldDelay);
        expect(find.byKey(const ValueKey('reveal-strip')), findsOneWidget);
        expect(find.byKey(const ValueKey('reveal')), findsNothing);
        expectOneScreen(tester, 'chat reveal, strip');
        expect(textOf(tester, 'strip-who'), isNotEmpty);
        expect(tester.getSize(find.byKey(const ValueKey('reveal-strip'))).height, lessThan(100));
        final folded = rectOf(tester, 'chat-log');
        await keyboardUp(tester, h);
        expectOneScreen(tester, 'chat reveal strip, keyboard', inset: keyboard);
        await keyboardDown(tester, h);
        // 자세히 opens the card again; 접기 folds it.
        await tester.tap(find.byKey(const ValueKey('reveal-strip')));
        await h.settle(tester);
        expect(find.byKey(const ValueKey('reveal')), findsOneWidget);
        expectOneScreen(tester, 'chat reveal, opened again');
        await tester.tap(find.byKey(const ValueKey('reveal-less')));
        await h.settle(tester);
        expect(find.byKey(const ValueKey('reveal-strip')), findsOneWidget);
        expect(rectOf(tester, 'chat-log'), folded);

        h.receive({
          'type': 'gameOver',
          'ranking': [
            for (var i = 1; i <= 8; i++) {'rank': i, 'name': i == 2 ? 'Mina' : 'Long player name $i', 'score': 10 - i},
          ],
        });
        await h.settle(tester);
        expectOneScreen(tester, 'game over');
        // The ranking on top, the chat below it.
        expect(rectOf(tester, 'chat-log').top, greaterThan(rectOf(tester, 'final').bottom));
        expect(buttonText(tester, 'my-rank'), contains('2'));
        h.receive({'type': 'roster', 'phase': 'lobby', 'players': eight()});
        await h.settle(tester);
        expectOneScreen(tester, 'game over, lobby open');
        expect(find.byKey(const ValueKey('to-lobby')), findsOneWidget);
        await h.tearDown(tester);
      });

      for (final count in [4, 6, 8]) {
        testWidgets('choice room v2 with $count tiles at $where: covers fill, floating chat, chat panel, reveal',
            (tester) async {
          final h = await enterGame(tester, language: lang, options: {'answerMode': 'choice', 'gameMode': 'ranked'});
          await resize(tester, h, size);
          h.receive({'type': 'roster', 'phase': 'playing', 'players': eight()});
          await prepare(tester, h, 0, total: 1, choices: [for (var i = 0; i < count; i++) 'c$i']);
          await start(tester, h, 0);
          h.receive({'type': 'pickState', 'index': 0, 'picked': 0, 'total': 8});
          h.receive({'type': 'skipState', 'index': 0, 'votes': 1, 'needed': 4});
          await h.settle(tester);
          expectOneScreen(tester, 'choice playing');
          expect(find.byKey(const ValueKey('tile-cover')), findsNWidgets(count));
          // No input until 채팅; the pick count beside 내 순위.
          expect(find.byKey(const ValueKey('chat-input')), findsNothing);
          expect(rectOf(tester, 'pick-count').center.dy, closeTo(rectOf(tester, 'my-rank').center.dy, 2));
          expectCoversFill(tester, count, 'choice playing');
          if (count == 4 && size.height >= 844) {
            expect({for (final r in tileRects(tester, 4)) r.left.round()}.length, 2, reason: '2 x 2');
          }
          // The 채팅 button at the bottom right over the covers' box, with
          // every line so far unread.
          final button = rectOf(tester, 'chat-open');
          expect(button.right, closeTo(screenOf(tester).width - 12, 1));
          expect(textOf(tester, 'chat-unread'), '${h.controller.game!.log.length}');

          // Lines float over the bottom left of the covers, newest the most
          // opaque; they pass taps through to the tiles.
          h.receive({'type': 'chat', 'name': 'Aki', 'text': 'first'});
          await h.advance(tester, const Duration(seconds: 1));
          h.receive({'type': 'chat', 'name': 'Long player name 3', 'text': 'second'});
          await h.advance(tester, const Duration(seconds: 1));
          h.receive({'type': 'chat', 'name': 'Aki', 'text': 'third'});
          await h.settle(tester);
          expect(floatTexts(tester), ['Aki first', 'Long player name 3 second', 'Aki third']);
          expect(floatBackAlpha(tester, 'third'), greaterThan(floatBackAlpha(tester, 'second')));
          expect(floatBackAlpha(tester, 'second'), greaterThan(floatBackAlpha(tester, 'first')));
          final float = rectOf(tester, 'chat-float');
          // Inset from the covers' left edge, so a bubble's rounded left
          // end shows whole (cycle 164).
          expect(float.left, closeTo(12 + floatInset, 1));
          // Where the covers fill the width, the bubbles start inside their
          // polaroid frames (5 px around 'tile-N'), not at their edge.
          final frameLeft = tileRects(tester, count).map((r) => r.left - 5).reduce((a, b) => a < b ? a : b);
          if (frameLeft - 12 <= 2) {
            expect(float.left - frameLeft, greaterThanOrEqualTo(floatInset - 2), reason: 'inset into the covers');
          }
          expect(float.right, lessThanOrEqualTo(button.left));
          expect(float.bottom, closeTo(button.bottom, 1));
          expect(find.ancestor(of: find.byKey(const ValueKey('chat-float')), matching: find.byType(IgnorePointer)),
              findsWidgets);
          expect(textOf(tester, 'chat-unread'), '${h.controller.game!.log.length}');
          expectOneScreen(tester, 'choice, floating');
          // Each fades out about 6 s after it came: the first goes first.
          await h.advance(tester, const Duration(milliseconds: 4250));
          expect(floatTexts(tester), ['Long player name 3 second', 'Aki third']);
          await h.advance(tester, const Duration(seconds: 2));
          expect(floatTexts(tester), isEmpty);
          // The bubbles take no pointer: a hit test on one finds nothing of
          // it; a tap on a bubble over a tile reaches the tile.
          h.receive({'type': 'chat', 'name': 'Aki', 'text': 'over a tile'});
          await h.settle(tester);
          final bubble = tester.getRect(find
              .ancestor(
                of: find.descendant(of: find.byKey(const ValueKey('chat-float')), matching: find.textContaining('over a tile')),
                matching: find.byType(Container),
              )
              .first);
          final floatBoxes = {
            for (final e in find
                .descendant(of: find.byKey(const ValueKey('chat-float')), matching: find.byWidgetPredicate((_) => true))
                .evaluate())
              e.renderObject,
          };
          final path = tester.hitTestOnBinding(bubble.center).path;
          expect(path.where((entry) => floatBoxes.contains(entry.target)), isEmpty, reason: 'bubbles take no pointer');
          final tiles = tileRects(tester, count);
          final hit = tiles.indexWhere((r) => r.overlaps(bubble));
          if (hit >= 0) {
            await tester.tapAt(tiles[hit].intersect(bubble).center);
            await h.settle(tester);
            expect(h.lastSent<PickRequest>().choice, hit, reason: 'the tap went through the bubble');
          }
          final picked = h.sent.whereType<PickRequest>().length;

          // 채팅 opens the panel: one row of tiles, the log, the input
          // (focused); the bubbles and the button go.
          final before = tileRects(tester, count);
          await openChat(tester, h);
          expect(find.byKey(const ValueKey('chat-panel')), findsOneWidget);
          expect(find.byKey(const ValueKey('chat-open')), findsNothing);
          expect(find.byKey(const ValueKey('chat-float')), findsNothing);
          expect(tester.widget<EditableText>(find.byType(EditableText)).focusNode.hasFocus, isTrue);
          final row = tileRects(tester, count);
          expect({for (final r in row) r.top.round()}.length, 1, reason: 'one row');
          expect(row.first.bottom, lessThanOrEqualTo(rectOf(tester, 'chat-panel').top));
          expect(rectOf(tester, 'chat-input').top, greaterThan(rectOf(tester, 'chat-log').top));
          expect(logTexts(tester).last, 'over a tile');
          expectOneScreen(tester, 'choice, panel');
          await keyboardUp(tester, h);
          expectOneScreen(tester, 'choice, panel, keyboard', inset: keyboard);
          for (var i = 0; i < count; i++) {
            expect(find.byKey(ValueKey('tile-$i')).hitTestable(), findsOneWidget, reason: 'tile $i tappable');
          }
          expect(rectOf(tester, 'chat-input').bottom, lessThanOrEqualTo(screenOf(tester).height - keyboard));
          // A pick works with the panel open (when none went through above).
          if (picked == 0) {
            await tester.tap(find.byKey(ValueKey('tile-${count - 1}')));
            await h.settle(tester);
            expect(h.lastSent<PickRequest>().choice, count - 1);
          }
          await keyboardDown(tester, h);
          // 닫기 brings the covers back; nothing unread.
          await tester.tap(find.byKey(const ValueKey('chat-close')));
          await h.settle(tester);
          expect(find.byKey(const ValueKey('chat-panel')), findsNothing);
          expect(find.byKey(const ValueKey('chat-input')), findsNothing);
          expect(find.byKey(const ValueKey('chat-unread')), findsNothing);
          expect(floatTexts(tester), isEmpty);
          expect(tileRects(tester, count), before);
          h.receive({'type': 'chat', 'name': 'Aki', 'text': 'after closing'});
          await h.settle(tester);
          expect(textOf(tester, 'chat-unread'), '1');

          h.receive(revealFrame(0, correctChoice: count - 1, ranking: [
            {'name': 'Long player name 3', 'rank': 1, 'points': 3},
          ]));
          await h.settle(tester);
          expectOneScreen(tester, 'choice reveal, card');
          expect(find.byKey(const ValueKey('reveal')), findsOneWidget);
          expect(find.byKey(ValueKey('tile-mark-${count - 1}')), findsOneWidget);
          expect(rectOf(tester, 'tile-0').top, greaterThan(rectOf(tester, 'reveal').top));
          await h.advance(tester, const Duration(seconds: 4));
          expectOneScreen(tester, 'choice reveal, strip');
          expect(find.byKey(const ValueKey('reveal')), findsNothing);
          for (final r in tileRects(tester, count)) {
            expect(r.top, greaterThanOrEqualTo(rectOf(tester, 'reveal-strip').bottom), reason: 'the strip above the covers');
          }
          expectCoversFill(tester, count, 'choice reveal, strip');
          await tester.tap(find.byKey(const ValueKey('reveal-strip')));
          await h.settle(tester);
          expect(find.byKey(const ValueKey('reveal')), findsOneWidget);
          expectOneScreen(tester, 'choice reveal, opened again');
          // The panel during the reveal.
          await openChat(tester, h);
          expectOneScreen(tester, 'choice reveal, panel');
          await keyboardUp(tester, h);
          expectOneScreen(tester, 'choice reveal, panel, keyboard', inset: keyboard);
          await keyboardDown(tester, h);
          await tester.tap(find.byKey(const ValueKey('chat-close')));
          await h.settle(tester);

          h.receive({
            'type': 'gameOver',
            'ranking': [
              {'rank': 1, 'name': 'Long player name 3', 'score': 3},
              {'rank': 2, 'name': 'Mina', 'score': 0},
            ],
          });
          await h.settle(tester);
          expectOneScreen(tester, 'choice game over');
          expect(find.byKey(const ValueKey('final')), findsOneWidget);
          expect(find.byKey(const ValueKey('chat-open')), findsOneWidget);
          await openChat(tester, h);
          expectOneScreen(tester, 'choice game over, panel');
          expect(find.byKey(const ValueKey('chat-input')), findsNothing);
          await tester.tap(find.byKey(const ValueKey('chat-close')));
          await h.settle(tester);
          h.receive({'type': 'roster', 'phase': 'lobby', 'players': eight()});
          await h.settle(tester);
          expectOneScreen(tester, 'choice game over, lobby open');
          expect(find.byKey(const ValueKey('to-lobby')), findsOneWidget);
          await h.tearDown(tester);
        });
      }

      testWidgets('the scores sheet fits at $where', (tester) async {
        final h = await enterGame(tester, language: lang);
        await resize(tester, h, size);
        h.receive({'type': 'roster', 'phase': 'playing', 'players': eight()});
        await prepare(tester, h, 0);
        await start(tester, h, 0);
        await tester.tap(find.byKey(const ValueKey('my-rank')));
        await h.settle(tester);
        expect(find.byKey(const ValueKey('scores')), findsOneWidget);
        expectOneScreen(tester, 'scores');
        for (final p in eight()) {
          expect(find.byKey(ValueKey('score-${p['name']}')), findsOneWidget);
        }
        await tester.tap(find.byKey(const ValueKey('scores-close')));
        await h.settle(tester);
        expect(find.byKey(const ValueKey('scores')), findsNothing);
        await h.tearDown(tester);
      });
    }
  }

  for (final count in [4, 8]) {
    testWidgets('a pick works with the chat panel open ($count tiles)', (tester) async {
      final h = await enterGame(tester, options: {'answerMode': 'choice'});
      await prepare(tester, h, 0, choices: [for (var i = 0; i < count; i++) 'c$i']);
      await start(tester, h, 0);
      await openChat(tester, h);
      await keyboardUp(tester, h);
      expect(find.byKey(const ValueKey('chat-input')).hitTestable(), findsOneWidget);
      await tester.tap(find.byKey(const ValueKey('tile-2')));
      await h.settle(tester);
      final pick = h.lastSent<PickRequest>();
      expect((pick.index, pick.choice), (0, 2));
      // The panel stays open; the talk goes on.
      expect(find.byKey(const ValueKey('chat-panel')), findsOneWidget);
      await tester.enterText(find.byKey(const ValueKey('chat-input')), 'hello');
      await tester.tap(find.byKey(const ValueKey('chat-send')));
      await h.settle(tester);
      expect(h.lastSent<ChatRequest>().text, 'hello');
      await keyboardDown(tester, h);
      await h.tearDown(tester);
    });
  }

  // Cycle 161: Chrome on Android hides the keyboard without the page
  // resizing; the browser's keyboard inset (in place of Flutter's) going
  // back to 0 brings back the top bar's second line and the panel's height.
  testWidgets('a choice room with the chat panel: the keyboard going 300 -> 0 restores the layout', (tester) async {
    final inset = ValueNotifier<double>(0);
    final h = await enterGame(tester, options: {'answerMode': 'choice'}, keyboardInset: inset);
    await prepare(tester, h, 0, choices: ['a', 'b', 'c', 'd']);
    await start(tester, h, 0);
    await openChat(tester, h);
    final panel = rectOf(tester, 'chat-panel');
    final input = rectOf(tester, 'chat-input');
    expect(shown('my-rank'), isTrue);
    expect(shown('skip'), isTrue);
    expect(shown('game-leave'), isTrue);

    inset.value = keyboard;
    await h.settle(tester);
    expect(shown('my-rank'), isFalse, reason: 'typing in the panel: the first line only');
    expect(shown('game-leave'), isFalse);
    expect(rectOf(tester, 'chat-input').bottom, lessThanOrEqualTo(phone.height - keyboard));
    expectOneScreen(tester, 'panel, keyboard', inset: keyboard);

    // Hidden while the field keeps the focus.
    inset.value = 0;
    await h.settle(tester);
    expect(tester.widget<EditableText>(find.byType(EditableText)).focusNode.hasFocus, isTrue);
    expect(shown('my-rank'), isTrue);
    expect(shown('skip'), isTrue);
    expect(shown('game-leave'), isTrue);
    expect(rectOf(tester, 'chat-panel'), panel);
    expect(rectOf(tester, 'chat-input'), input);
    expect(find.byKey(const ValueKey('game-leave')).hitTestable(), findsOneWidget);
    expectOneScreen(tester, 'panel, keyboard hidden');
    await h.tearDown(tester);
  });

  testWidgets('내 순위: the rank by score with ties shared, the scores list with the solvers marked', (tester) async {
    final h = await enterGame(tester);
    h.receive({
      'type': 'roster',
      'phase': 'playing',
      'players': [player('Mina', host: true), player('Aki'), player('Yui'), player('Ren')],
    });
    await prepare(tester, h, 0);
    await start(tester, h, 0);
    expect(buttonText(tester, 'my-rank'), '내 순위 1위 · 0점 ▾', reason: 'everyone at 0 shares 1위');
    h.receive({
      'type': 'scores',
      'scores': [
        {'name': 'Aki', 'score': 5},
        {'name': 'Yui', 'score': 3},
        {'name': 'Mina', 'score': 3},
        {'name': 'Ren', 'score': 1},
      ],
    });
    h.receive({'type': 'correct', 'index': 0, 'name': 'Ren'});
    await h.settle(tester);
    expect(buttonText(tester, 'my-rank'), '내 순위 2위 · 3점 ▾');
    await tester.tap(find.byKey(const ValueKey('my-rank')));
    await h.settle(tester);
    // Highest first, the roster's order on a tie.
    final rows = [for (final name in ['Aki', 'Mina', 'Yui', 'Ren']) rectOf(tester, 'score-$name').top];
    expect([...rows]..sort(), rows);
    await tester.tap(find.byKey(const ValueKey('scores-close')));
    await h.settle(tester);
    expect(await scoreRow(tester, h, 'Aki'), '1위 Aki 5점');
    expect(await scoreRow(tester, h, 'Mina'), '2위 Mina (나) 3점');
    expect(await scoreRow(tester, h, 'Yui'), '2위 Yui 3점');
    expect(await scoreRow(tester, h, 'Ren'), '4위 Ren 1점 정답!');
    // A tap outside closes it too.
    await tester.tap(find.byKey(const ValueKey('my-rank')));
    await h.settle(tester);
    await tester.tapAt(const Offset(5, 5));
    await h.settle(tester);
    expect(find.byKey(const ValueKey('scores')), findsNothing);
    await h.tearDown(tester);
  });

  testWidgets('a new song clears the reveal strip; the card shows again for the next reveal', (tester) async {
    final h = await enterGame(tester, options: {'answerMode': 'choice'});
    await prepare(tester, h, 0, choices: ['a', 'b', 'c', 'd']);
    await start(tester, h, 0);
    h.receive(revealFrame(0, correctChoice: 1));
    await h.settle(tester);
    await h.advance(tester, revealFoldDelay);
    expect(find.byKey(const ValueKey('reveal-strip')), findsOneWidget);
    expect(textOf(tester, 'strip-title'), '진격의 거인');
    await prepare(tester, h, 1, choices: ['e', 'f', 'g', 'h']);
    expect(find.byKey(const ValueKey('reveal-strip')), findsNothing);
    expect(find.byKey(const ValueKey('reveal')), findsNothing);
    expect(find.byKey(const ValueKey('tile-mark-1')), findsNothing);
    await start(tester, h, 1);
    h.receive(revealFrame(1, correctChoice: 0));
    await h.settle(tester);
    expect(find.byKey(const ValueKey('reveal')), findsOneWidget);
    await h.tearDown(tester);
  });

  for (final (lang, title) in [('en', 'Attack on Titan'), ('ja', '進撃の巨人')]) {
    testWidgets('the strip shows the title in the screen language ($lang)', (tester) async {
      final h = await enterGame(tester, language: lang);
      await prepare(tester, h, 0);
      await start(tester, h, 0);
      h.receive(revealFrame(0, winner: 'Aki'));
      await h.settle(tester);
      await h.advance(tester, revealFoldDelay);
      expect(textOf(tester, 'strip-title'), title);
      expect(
        tester.widget<Text>(find.byKey(const ValueKey('strip-theme'))).textSpan!.toPlainText(),
        'OP1 · Guren no Yumiya ${String.fromCharCode(0x2014)} Linked Horizon',
      );
      await h.tearDown(tester);
    });
  }

  for (final (lang, name) in [('ko', '싱크'), ('en', 'Sync'), ('ja', '同期')]) {
    testWidgets('the game header has no offset button in $lang, playing or at game over (cycle 152)', (tester) async {
      final h = await enterGame(tester, language: lang);
      await prepare(tester, h, 0);
      await start(tester, h, 0);
      void expectNone(String state) {
        expect(find.byKey(const ValueKey('offset-open')), findsNothing, reason: state);
        expect(find.byType(OffsetButton), findsNothing, reason: state);
        expect(find.byTooltip(name), findsNothing, reason: state);
        expect(find.bySemanticsLabel(name), findsNothing, reason: state);
        expect(find.byIcon(Icons.tune), findsNothing, reason: state);
      }

      expectNone('playing');
      // 나가기 at the end of the top bar's second line (12 px side padding
      // on the 390 px phone), on the line of 내 순위 (D-145).
      final leave = rectOf(tester, 'game-leave');
      expect(leave.right, closeTo(phone.width - 12, 0.5));
      expect(leave.center.dy, closeTo(rectOf(tester, 'my-rank').center.dy, 2));
      h.receive({
        'type': 'gameOver',
        'ranking': [
          {'rank': 1, 'name': 'Mina', 'score': 5},
        ],
      });
      await h.settle(tester);
      expectNone('game over');
      expect(find.byKey(const ValueKey('game-leave')), findsNothing);
      await h.tearDown(tester);
    });
  }

  testWidgets('the sound prompt, the load line and the reconnecting line keep the input above the keyboard',
      (tester) async {
    final h = await reloadInto(tester, (h) => playingSnapshot(h), failLoads: 2);
    tester.view.physicalSize = const Size(360, 640) * 3;
    await h.settle(tester);
    expect(find.byKey(const ValueKey('unlock-sound')), findsOneWidget);
    expect(find.text('이번 곡을 재생하지 못했습니다.'), findsOneWidget);
    h.sockets.autoOpen = false;
    h.socket.drop(1006);
    await h.settle(tester);
    expect(find.text(reconnecting), findsOneWidget);
    expectOneScreen(tester, 'prompts');
    await keyboardUp(tester, h);
    expectOneScreen(tester, 'prompts, keyboard', inset: keyboard);
    expect(find.byKey(const ValueKey('chat-input')).hitTestable(), findsOneWidget);
    await h.tearDown(tester);
  });

  test('tile layout: 2, 3 or 4 columns, whichever gives the biggest covers; or the columns asked for', () {
    expect(tileLayout(4, 336, 300).columns, 2);
    expect(tileLayout(6, 336, 300).columns, 3);
    expect(tileLayout(6, 336, 900).columns, 2);
    // Tall and narrow: 4 rows of 2.
    expect(tileLayout(8, 336, 700).columns, 2);
    // Short and wide: 2 rows of 4.
    expect(tileLayout(8, 336, 300).columns, 4);
    // In between: 3 rows of 3, 3 and 2.
    expect(tileLayout(8, 336, 420).columns, 3);
    // A short strip: one or two rows.
    expect(tileLayout(4, 336, 150).columns, 4);
    expect(tileLayout(6, 336, 150).columns, 3);
    expect(tileLayout(4, 336, 700).columns, 2);
    // Never more columns than tiles; the fewest columns on a tie.
    expect(tileLayout(2, 336, 100).columns, 2);
    expect(tileLayout(4, 336, 520).columns, 2);
    // One row when asked.
    expect(tileLayout(8, 336, 900, columns: 8).columns, 8);
    expect(gridHeight(4, 336, 900, columns: 4), (tileLayout(4, 336, 900, columns: 4).coverWidth * 4 / 3).floor() + 19);
    // The grid fits the space it is given.
    for (final (n, w, h) in [(4, 336.0, 300.0), (6, 366.0, 500.0), (8, 406.0, 250.0), (8, 336.0, 40.0), (8, 336.0, 0.0)]) {
      final l = tileLayout(n, w, h);
      final rows = (n / l.columns).ceil();
      final tileW = l.coverWidth + 10;
      final tileH = (l.coverWidth * 4 / 3).floorToDouble() + 19;
      expect(l.coverWidth, greaterThanOrEqualTo(0));
      expect(l.columns * tileW + (l.columns - 1) * 8, lessThanOrEqualTo(w + 0.01));
      if (l.coverWidth > 0) expect(rows * tileH + (rows - 1) * 8, lessThanOrEqualTo(h + 0.01));
    }
  });

  test('floating lines last about 6 s, three at most', () {
    expect(floatLife, const Duration(seconds: 6));
    expect(floatMax, 3);
    expect(revealFoldDelay, const Duration(milliseconds: 3500));
  });
}

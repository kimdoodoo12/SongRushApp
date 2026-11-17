import 'dart:math' as math;

import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';
import 'package:songrush_mobile/app/game_model.dart';
import 'package:songrush_mobile/ui/chat_panel.dart';
import 'package:songrush_mobile/ui/game_screen.dart';
import 'package:songrush_mobile/ui/offset_dialog.dart';

import 'game_screen_test.dart' show enterGame, logTexts, prepare, previewTexts, revealFrame, start;
import 'reconnect_test.dart' show playingSnapshot, reloadInto, reconnecting;
import 'support/harness.dart';

// Cycle 151 (D-142): the game screen fits one phone screen with no page
// scroll; only the chat log, the reveal card and the final ranking scroll
// inside. The input stays above the on-screen keyboard.

const List<Size> sizes = [Size(360, 640), Size(390, 844), Size(430, 932)];
const List<String> langs = ['ko', 'en', 'ja'];

/// The on-screen keyboard's height in the keyboard checks.
const double keyboard = 300;

/// Eight players, some with long names, so the players' line overflows
/// sideways.
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

/// No overflow; no vertical scroll outside the chat log, the reveal card
/// and the final ranking; and every shown control of the screen inside
/// the part of the screen above the keyboard ([inset]).
void expectOneScreen(WidgetTester tester, String state, {double inset = 0}) {
  final error = tester.takeException();
  expect(error, isNull, reason: '$state: ${error is FlutterError ? error.toStringDeep() : error}');
  final screen = screenOf(tester);
  final visible = Rect.fromLTWH(0, 0, screen.width, screen.height - inset);
  const allowed = ['chat-log', 'reveal-scroll', 'final-list'];
  for (final element in find.byType(Scrollable).evaluate()) {
    final scrollable = element.widget as Scrollable;
    if (axisDirectionToAxis(scrollable.axisDirection) != Axis.vertical) continue;
    final inside = allowed.any((key) => find
        .ancestor(of: find.byElementPredicate((e) => identical(e, element)), matching: find.byKey(ValueKey(key)))
        .evaluate()
        .isNotEmpty);
    expect(inside, isTrue, reason: '$state: a vertical scroll outside the chat log, the card and the ranking');
  }
  for (final key in [
    'clock',
    'round',
    'game-leave',
    'skip',
    'skip-count',
    'hint',
    'pick-count',
    'pick-wrong',
    'chat-input',
    'chat-send',
    'chat-preview',
    'to-lobby',
    'unlock-sound',
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
  // The tiles sit above the latest lines.
  final preview = find.byKey(const ValueKey('chat-preview'));
  if (preview.evaluate().isNotEmpty) {
    for (var i = 0; i < 8; i++) {
      final tile = find.byKey(ValueKey('tile-$i'));
      if (tile.evaluate().isEmpty) continue;
      expect(tester.getRect(tile).bottom, lessThanOrEqualTo(tester.getRect(preview).top + 0.5), reason: '$state: tile $i');
    }
  }
}

/// The preview's lines wholly inside its box.
int shownPreviewLines(WidgetTester tester) {
  final box = tester.getRect(find.byKey(const ValueKey('chat-preview-lines')));
  return find
      .descendant(of: find.byKey(const ValueKey('chat-preview-lines')), matching: find.byType(Text))
      .evaluate()
      .where((e) {
    final r = tester.getRect(find.byElementPredicate((x) => identical(x, e)));
    return r.top >= box.top - 0.5 && r.bottom <= box.bottom + 0.5;
  }).length;
}

/// A choice room's chat box (or the game over's): as many of the latest
/// lines as fit, never fewer than two, newest at the bottom; it takes all
/// the height left down to the form; the tiles above it stay within 45 %
/// of the middle.
void expectChatBox(WidgetTester tester, Harness h, String state) {
  final box = tester.getRect(find.byKey(const ValueKey('chat-preview-lines')));
  final total = h.controller.game!.log.length;
  final fit = (box.height / previewLineHeight).floor();
  expect(fit, greaterThanOrEqualTo(2), reason: '$state: room for two lines');
  expect(shownPreviewLines(tester), math.min(fit, total), reason: '$state: as many lines as fit');
  final newest = h.controller.game!.log.last;
  if (newest is SaidLine) {
    expect(previewTexts(tester).last, newest.text, reason: '$state: the newest line at the bottom');
  }
  final preview = tester.getRect(find.byKey(const ValueKey('chat-preview')));
  final form = find.byKey(const ValueKey('chat-input'));
  if (form.evaluate().isNotEmpty) {
    expect(tester.getRect(form).top - preview.bottom, lessThanOrEqualTo(24), reason: '$state: the box fills down to the form');
  }
  final tiles = find.byKey(const ValueKey('choices'));
  if (tiles.evaluate().isNotEmpty) {
    final middleTop = tester.getRect(find.byKey(const ValueKey('players'))).bottom;
    final middle = preview.bottom - middleTop;
    expect(tester.getSize(tiles).height, lessThanOrEqualTo(middle * 0.45 + 1), reason: '$state: tiles within 45 %');
  }
}

/// The newest chat line [text] is inside the log's box.
void expectNewestShown(WidgetTester tester, String text, String state) {
  final log = tester.getRect(find.byKey(const ValueKey('chat-log')));
  final line = tester.getRect(find.descendant(of: find.byKey(const ValueKey('chat-log')), matching: find.text(text)));
  expect(line.top >= log.top - 0.5 && line.bottom <= log.bottom + 0.5, isTrue, reason: '$state: $line not in $log');
}

void main() {
  for (final size in sizes) {
    for (final lang in langs) {
      final where = '${size.width.toInt()}x${size.height.toInt()} $lang';

      testWidgets('chat room fits one screen at $where: playing, reveal, game over, with the keyboard', (tester) async {
        final h = await enterGame(tester, language: lang, options: {'gameMode': 'ranked'});
        await resize(tester, h, size);
        h.receive({'type': 'roster', 'phase': 'playing', 'players': eight()});
        await prepare(tester, h, 0, total: 1);
        await start(tester, h, 0);
        h.receive({'type': 'skipState', 'index': 0, 'votes': 1, 'needed': 4});
        for (var i = 0; i < 30; i++) {
          h.receive({'type': 'chat', 'name': i.isEven ? 'Aki' : 'Mina', 'text': 'line $i'});
        }
        h.receive({'type': 'correct', 'index': 0, 'name': 'Aki', 'rank': 1});
        await h.settle(tester);
        expectOneScreen(tester, 'chat playing');
        // The spinning disc with the hint beside it, over the log.
        expect(find.byKey(const ValueKey('disc')), findsOneWidget);
        expect(tester.getSize(find.byKey(const ValueKey('disc'))).width, inInclusiveRange(64, 84));
        expect(tester.getRect(find.byKey(const ValueKey('hint'))).left,
            greaterThan(tester.getRect(find.byKey(const ValueKey('disc'))).right));
        expect(tester.getRect(find.byKey(const ValueKey('chat-log'))).top,
            greaterThan(tester.getRect(find.byKey(const ValueKey('disc'))).bottom));
        // The log fills the middle and shows the newest line.
        expect(find.byKey(const ValueKey('chat-log')), findsOneWidget);
        expect(find.byKey(const ValueKey('chat-preview')), findsNothing);
        final open = tester.getRect(find.byKey(const ValueKey('chat-log')));
        expectNewestShown(tester, logTexts(tester).last, 'chat playing');

        await keyboardUp(tester, h);
        expectOneScreen(tester, 'chat playing, keyboard', inset: keyboard);
        final shrunk = tester.getRect(find.byKey(const ValueKey('chat-log')));
        expect(shrunk.height, lessThan(open.height), reason: 'the log shrinks above the keyboard');
        expectNewestShown(tester, logTexts(tester).last, 'chat playing, keyboard');
        h.receive({'type': 'chat', 'name': 'Aki', 'text': 'after the keyboard'});
        await h.settle(tester);
        expectNewestShown(tester, 'after the keyboard', 'chat playing, keyboard, new line');
        expectOneScreen(tester, 'chat playing, keyboard, new line', inset: keyboard);
        await keyboardDown(tester, h);
        expectOneScreen(tester, 'chat playing, keyboard down');

        h.receive(revealFrame(0, ranking: [
          {'name': 'Aki', 'rank': 1, 'points': 3},
          {'name': 'Long player name 3', 'rank': 2, 'points': 2},
          {'name': 'Mina', 'rank': 3, 'points': 1},
        ]));
        await h.settle(tester);
        expectOneScreen(tester, 'chat reveal');
        // The card replaces the disc row.
        expect(find.byKey(const ValueKey('disc')), findsNothing);
        // The card and, below it, the log.
        expect(find.byKey(const ValueKey('chat-log')), findsOneWidget);
        expect(tester.getRect(find.byKey(const ValueKey('chat-log'))).top,
            greaterThan(tester.getRect(find.byKey(const ValueKey('reveal'))).top));
        await keyboardUp(tester, h);
        expectOneScreen(tester, 'chat reveal, keyboard', inset: keyboard);
        await keyboardDown(tester, h);

        h.receive({
          'type': 'gameOver',
          'ranking': [
            for (var i = 1; i <= 8; i++) {'rank': i, 'name': i == 2 ? 'Mina' : 'Long player name $i', 'score': 10 - i},
          ],
        });
        await h.settle(tester);
        expectOneScreen(tester, 'game over');
        expect(find.byKey(const ValueKey('final')), findsOneWidget);
        h.receive({'type': 'roster', 'phase': 'lobby', 'players': eight()});
        await h.settle(tester);
        expectOneScreen(tester, 'game over, lobby open');
        expect(find.byKey(const ValueKey('to-lobby')), findsOneWidget);
        await h.tearDown(tester);
      });

      for (final count in [4, 6, 8]) {
        testWidgets('choice room with $count tiles fits one screen at $where: playing, full chat, reveal, game over',
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
          // The small disc beside the hint, above the tiles.
          expect(tester.getSize(find.byKey(const ValueKey('disc'))).width, 40);
          expect(tester.getRect(find.byKey(const ValueKey('disc'))).bottom,
              lessThanOrEqualTo(tester.getRect(find.byKey(const ValueKey('tile-0'))).top));
          expect(tester.getRect(find.byKey(const ValueKey('hint'))).left,
              greaterThan(tester.getRect(find.byKey(const ValueKey('disc'))).right));
          expectChatBox(tester, h, 'choice playing');
          // Every tile the same size; 2 or 4 columns (6: 2 or 3).
          final first = tester.getRect(find.byKey(const ValueKey('tile-0')));
          final columns = {
            for (var i = 0; i < count; i++) tester.getRect(find.byKey(ValueKey('tile-$i'))).left.round(),
          }.length;
          expect(columns, count == 6 ? anyOf(2, 3) : anyOf(2, 4));
          for (var i = 1; i < count; i++) {
            final tile = tester.getSize(find.byKey(ValueKey('tile-$i')));
            expect(tile.width, closeTo(first.width, 0.01));
            expect(tile.height, closeTo(first.height, 0.01));
          }

          await tester.tap(find.byKey(const ValueKey('tile-0')));
          await h.settle(tester);
          h.receive({'type': 'pick-result', 'index': 0, 'choice': 0});
          h.receive({'type': 'pickState', 'index': 0, 'picked': 1, 'total': 8});
          h.receive({'type': 'wrong', 'index': 0, 'name': 'Aki'});
          for (var i = 0; i < 20; i++) {
            h.receive({'type': 'chat', 'name': 'Aki', 'text': 'line $i'});
          }
          await h.settle(tester);
          expect(find.byKey(const ValueKey('pick-wrong')), findsOneWidget);
          final texts = previewTexts(tester);
          expect(texts.sublist(texts.length - 2), ['line 18', 'line 19']);
          expect(find.byKey(const ValueKey('chat-all')), findsOneWidget);
          expectOneScreen(tester, 'choice picked');
          expectChatBox(tester, h, 'choice picked');
          // More lines than the 3 of a fixed box on every size (it flexes).
          expect(shownPreviewLines(tester), greaterThan(3));
          await keyboardUp(tester, h);
          expectOneScreen(tester, 'choice picked, keyboard', inset: keyboard);
          expectChatBox(tester, h, 'choice picked, keyboard');
          expect(find.byKey(const ValueKey('tile-0')), findsOneWidget, reason: 'the tiles stay, smaller');
          await keyboardDown(tester, h);

          // The box opens the full chat over the tiles; 닫기 closes it.
          await tester.tap(find.byKey(const ValueKey('chat-preview')));
          await h.settle(tester);
          expect(find.byKey(const ValueKey('chat-full')), findsOneWidget);
          expect(logTexts(tester).last, 'line 19');
          expectNewestShown(tester, 'line 19', 'full chat');
          expectOneScreen(tester, 'full chat');
          await tester.tap(find.byKey(const ValueKey('chat-close')));
          await h.settle(tester);
          expect(find.byKey(const ValueKey('chat-full')), findsNothing);
          expect(find.byKey(const ValueKey('chat-log')), findsNothing);
          expect(find.byKey(const ValueKey('tile-0')).hitTestable(), findsOneWidget);

          h.receive(revealFrame(0, correctChoice: count - 1, ranking: [
            {'name': 'Long player name 3', 'rank': 1, 'points': 3},
          ]));
          await h.settle(tester);
          expectOneScreen(tester, 'choice reveal');
          expect(find.byKey(const ValueKey('reveal')), findsOneWidget);
          expect(find.byKey(ValueKey('tile-mark-${count - 1}')), findsOneWidget);
          expect(find.byKey(const ValueKey('tile-mark-0')), findsOneWidget);
          expect(tester.getRect(find.byKey(const ValueKey('tile-0'))).top,
              greaterThan(tester.getRect(find.byKey(const ValueKey('reveal'))).top));
          expectChatBox(tester, h, 'choice reveal');
          await keyboardUp(tester, h);
          expectOneScreen(tester, 'choice reveal, keyboard', inset: keyboard);
          expectChatBox(tester, h, 'choice reveal, keyboard');
          expect(find.byKey(const ValueKey('tile-0')), findsOneWidget, reason: 'the tiles stay, smaller');
          await keyboardDown(tester, h);

          h.receive({
            'type': 'gameOver',
            'ranking': [
              {'rank': 1, 'name': 'Long player name 3', 'score': 3},
              {'rank': 2, 'name': 'Mina', 'score': 0},
            ],
          });
          await h.settle(tester);
          expectOneScreen(tester, 'choice game over');
          expectChatBox(tester, h, 'choice game over');
          await h.tearDown(tester);
        });
      }
    }
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
      // 나가기 alone at the end of the top bar's first line (12 px side
      // padding on the 390 px phone), on the line of song n / N.
      final leave = tester.getRect(find.byKey(const ValueKey('game-leave')));
      expect(leave.right, closeTo(phone.width - 12, 0.5));
      final round = tester.getRect(find.byKey(const ValueKey('round')));
      expect(leave.center.dy, closeTo(round.center.dy, 4));
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

  testWidgets('the players line: own name marked, solvers marked, no seat grid', (tester) async {
    final h = await enterGame(tester);
    await prepare(tester, h, 0);
    await start(tester, h, 0);
    h.receive({'type': 'correct', 'index': 0, 'name': 'Aki'});
    await h.settle(tester);
    expect(find.text('Mina (나)'), findsOneWidget);
    expect(find.text('Aki'), findsOneWidget);
    expect(tester.widget<Text>(find.byKey(const ValueKey('seat-score-Aki'))).textSpan!.toPlainText(), '0점 정답!');
    expect(tester.widget<Text>(find.byKey(const ValueKey('seat-score-Mina'))).textSpan!.toPlainText(), '0점');
    expect(find.text('빈 자리'), findsNothing);
    // One line: both players at the same height.
    expect(tester.getRect(find.byKey(const ValueKey('game-seat-Mina'))).top,
        tester.getRect(find.byKey(const ValueKey('game-seat-Aki'))).top);
    await h.tearDown(tester);
  });

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

  test('tile layout: 2 or 4 columns for 4 and 8, 2 or 3 for 6, whichever gives bigger tiles', () {
    expect(tileLayout(4, 336, 300).columns, 2);
    expect(tileLayout(6, 336, 300).columns, 3);
    expect(tileLayout(6, 336, 900).columns, 2);
    expect(tileLayout(4, 336, 300).columns, 2);
    // Tall and narrow: 4 rows of 2.
    final tall = tileLayout(8, 336, 700);
    expect(tall.columns, 2);
    // Short and wide: 2 rows of 4.
    final short = tileLayout(8, 336, 300);
    expect(short.columns, 4);
    // A short strip: 4 and 6 tiles take one or two rows.
    expect(tileLayout(4, 336, 150).columns, 4);
    expect(tileLayout(6, 336, 150).columns, 3);
    expect(tileLayout(4, 336, 700).columns, 2);
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
}

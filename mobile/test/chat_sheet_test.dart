import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';
import 'package:songrush_core/songrush_core.dart';
import 'package:songrush_mobile/ui/game_screen.dart';
import 'package:songrush_mobile/ui/palette.dart';

import 'game_layout_test.dart'
    show bubbleOf, eight, expectNewestShown, expectOneScreen, expectSheet, keyboard, keyboardDown, keyboardUp, langs,
        rectOf, resize, screenOf, sizes;
import 'game_screen_test.dart' show enterGame, logTexts, openChat, prepare, revealFrame, start, textOf;

// Cycle 165 (D-146): a choice room's chat is a full-screen sheet over the
// game screen (V3ChatSheet): it slides up from the 채팅 button, with a
// dimmed strip of the screen above it, 채팅 / 닫기 ▾, a thin status line, the
// whole log and the input above the keyboard; 닫기, a back or a swipe down
// closes it. The game runs on under it; a reveal while it is open is one
// line in its log, and the card shows once it closes.

final String _check = String.fromCharCode(0x2714);

const Map<String, String> _solved = {
  'ko': '정답! · 닫으면 정답 카드가 보여요',
  'en': 'got it! · Close to see the answer card',
  'ja': '正解！ · 閉じると正解カードが見られます',
};

const Map<String, String> _none = {
  'ko': '정답 공개 · 닫으면 정답 카드가 보여요',
  'en': 'Answer revealed · Close to see the answer card',
  'ja': '正解発表 · 閉じると正解カードが見られます',
};

/// The sheet's reveal line as plain text.
String revealLineText(WidgetTester tester) => tester
    .widget<Text>(find.descendant(of: find.byKey(const ValueKey('sheet-reveal')), matching: find.byType(Text)))
    .data!;

bool shown(String key) => find.byKey(ValueKey(key)).evaluate().isNotEmpty;

/// The visible part of the log ends with the reveal line (its last line).
void expectRevealLineLast(WidgetTester tester, String state) {
  final log = rectOf(tester, 'chat-log');
  final line = rectOf(tester, 'sheet-reveal');
  expect(line.bottom, lessThanOrEqualTo(log.bottom + 0.5), reason: '$state: the line $line in the log $log');
  expect(log.bottom - line.bottom, lessThan(40), reason: '$state: the line at the log\'s bottom');
}

void main() {
  for (final size in sizes) {
    for (final lang in langs) {
      final where = '${size.width.toInt()}x${size.height.toInt()} $lang';

      testWidgets('the chat sheet at $where: full screen, status line, keyboard, reveal line, card on close, game over',
          (tester) async {
        final h = await enterGame(tester, language: lang, options: {'answerMode': 'choice', 'gameMode': 'ranked'});
        await resize(tester, h, size);
        h.receive({'type': 'roster', 'phase': 'playing', 'players': eight()});
        await prepare(tester, h, 0, total: 10, choices: [for (var i = 0; i < 8; i++) 'c$i']);
        await start(tester, h, 0);
        h.receive({'type': 'pickState', 'index': 0, 'picked': 1, 'total': 8});
        h.receive({'type': 'skipState', 'index': 0, 'votes': 1, 'needed': 4});
        for (var i = 0; i < 30; i++) {
          h.receive({'type': 'chat', 'name': i % 3 == 0 ? 'Mina' : 'Aki', 'text': 'line $i'});
        }
        await h.settle(tester);
        expectOneScreen(tester, 'before');

        await openChat(tester, h);
        expectSheet(tester, 'open');
        // The status line: song n / N, the seconds as on the top bar, the
        // pick count.
        expect(textOf(tester, 'sheet-round'), '1 / 10');
        expect(textOf(tester, 'sheet-clock'), textOf(tester, 'clock'));
        expect(textOf(tester, 'sheet-picks'), startsWith('· '));
        expect(textOf(tester, 'sheet-picks'), endsWith('1/8'));
        expect(textOf(tester, 'sheet-picks'), contains(textOf(tester, 'pick-count')));
        // The whole log from the bottom: own lines right in the accent
        // colour, the others left with their names.
        final log = rectOf(tester, 'chat-log');
        expectNewestShown(tester, 'line 29', 'open');
        final own = tester.getRect(find.descendant(of: find.byKey(const ValueKey('chat-log')), matching: find.text('line 27')));
        final other = tester.getRect(find.descendant(of: find.byKey(const ValueKey('chat-log')), matching: find.text('line 28')));
        expect(own.left, greaterThan(log.center.dx), reason: 'own line right-aligned');
        expect(log.right - own.right, lessThanOrEqualTo(32), reason: 'own line at the right edge');
        expect(other.left, lessThan(log.left + 150), reason: 'others on the left');
        expect(bubbleOf(tester, 'line 27').color, Palette.a1);
        expect(bubbleOf(tester, 'line 28').color, Palette.panel2);
        expect(tester.widget<EditableText>(find.byType(EditableText)).focusNode.hasFocus, isTrue, reason: 'the input focused');

        await keyboardUp(tester, h);
        expectSheet(tester, 'open, keyboard', inset: keyboard);
        expect(rectOf(tester, 'chat-log').height, lessThan(log.height), reason: 'the log shrinks above the keyboard');
        h.receive({'type': 'chat', 'name': 'Aki', 'text': 'after the keyboard'});
        await h.settle(tester);
        expectNewestShown(tester, 'after the keyboard', 'open, keyboard, new line');

        // A reveal while it is open: one line in the log; the game screen's
        // card waits.
        h.receive(revealFrame(0, correctChoice: 1, ranking: [
          {'name': 'Aki', 'rank': 1, 'points': 3},
          {'name': 'Long player name 3', 'rank': 2, 'points': 2},
        ]));
        await h.settle(tester);
        expect(revealLineText(tester), '$_check Aki, Long player name 3 ${_solved[lang]}');
        expectSheet(tester, 'reveal, keyboard', inset: keyboard);
        expectRevealLineLast(tester, 'reveal, keyboard');
        await keyboardDown(tester, h);
        expectSheet(tester, 'reveal');
        // Longer than the card's fold: it still shows in full on 닫기.
        await h.advance(tester, const Duration(seconds: 5));
        await tester.tap(find.byKey(const ValueKey('chat-close')));
        await h.settle(tester);
        expect(shown('chat-sheet'), isFalse);
        expect(find.byKey(const ValueKey('reveal')).hitTestable(), findsOneWidget, reason: 'the card on close');
        expect(shown('reveal-strip'), isFalse);
        expectOneScreen(tester, 'closed, card');
        await h.advance(tester, revealFoldDelay);
        expect(find.byKey(const ValueKey('reveal-strip')).hitTestable(), findsOneWidget);
        expectOneScreen(tester, 'closed, strip');
        // Opened again during the same reveal: no line, the strip stays.
        await openChat(tester, h);
        expect(shown('sheet-reveal'), isFalse);
        expectSheet(tester, 'reveal, opened again');
        await tester.tap(find.byKey(const ValueKey('chat-close')));
        await h.settle(tester);
        expect(find.byKey(const ValueKey('reveal-strip')).hitTestable(), findsOneWidget);

        h.receive({
          'type': 'gameOver',
          'ranking': [
            for (var i = 1; i <= 8; i++) {'rank': i, 'name': i == 2 ? 'Mina' : 'Long player name $i', 'score': 10 - i},
          ],
        });
        await h.settle(tester);
        await openChat(tester, h);
        expectSheet(tester, 'game over', input: false);
        await tester.tap(find.byKey(const ValueKey('chat-close')));
        await h.settle(tester);
        h.receive({'type': 'roster', 'phase': 'lobby', 'players': eight()});
        await h.settle(tester);
        await openChat(tester, h);
        expectSheet(tester, 'game over, lobby open', input: false);
        await tester.tap(find.byKey(const ValueKey('chat-close')));
        await h.settle(tester);
        expect(find.byKey(const ValueKey('to-lobby')).hitTestable(), findsOneWidget);
        await h.tearDown(tester);
      });
    }

    testWidgets('a reveal nobody solved, while the sheet is open (${size.width.toInt()}): 정답 공개, then the card',
        (tester) async {
      for (final lang in langs) {
        final h = await enterGame(tester, language: lang, options: {'answerMode': 'choice'});
        await resize(tester, h, size);
        await prepare(tester, h, 0, choices: ['a', 'b', 'c', 'd']);
        await start(tester, h, 0);
        await openChat(tester, h);
        h.receive(revealFrame(0, correctChoice: 2));
        await h.settle(tester);
        expect(revealLineText(tester), _none[lang], reason: lang);
        expectSheet(tester, 'no winner $lang');
        await tester.tap(find.byKey(const ValueKey('chat-close')));
        await h.settle(tester);
        expect(find.byKey(const ValueKey('reveal')).hitTestable(), findsOneWidget, reason: lang);
        expect(textOf(tester, 'tile-mark-2'), isNotEmpty);
        await h.tearDown(tester);
      }
    });
  }

  for (final lang in langs) {
    testWidgets('a first-come winner while the sheet is open ($lang): ✔ name and the hint', (tester) async {
      final h = await enterGame(tester, language: lang, options: {'answerMode': 'choice'});
      await prepare(tester, h, 0, choices: ['a', 'b', 'c', 'd']);
      await start(tester, h, 0);
      await openChat(tester, h);
      h.receive({'type': 'correct', 'index': 0, 'name': 'Aki'});
      h.receive(revealFrame(0, winner: 'Aki', correctChoice: 1));
      await h.settle(tester);
      expect(revealLineText(tester), '$_check Aki ${_solved[lang]}');
      // After the solver's own line in the log.
      expect(rectOf(tester, 'sheet-reveal').top,
          greaterThan(tester.getRect(find.descendant(of: find.byKey(const ValueKey('chat-log')), matching: find.textContaining('Aki')).first).top));
      expectRevealLineLast(tester, 'winner');
      // Lines after the reveal come under it (the winner's own next line
      // would go above the ✔ line: the model keeps it with the win, D-094).
      h.receive({'type': 'chat', 'name': 'Mina', 'text': 'easy'});
      await h.settle(tester);
      expect(logTexts(tester).last, 'easy');
      expect(rectOf(tester, 'sheet-reveal').bottom,
          lessThanOrEqualTo(tester.getRect(find.descendant(of: find.byKey(const ValueKey('chat-log')), matching: find.text('easy'))).top));
      await h.tearDown(tester);
    });
  }

  testWidgets('the game runs on under the sheet: the status line follows; the next song clears the reveal line',
      (tester) async {
    final h = await enterGame(tester, options: {'answerMode': 'choice'});
    await prepare(tester, h, 0, total: 3, choices: ['a', 'b', 'c', 'd']);
    await start(tester, h, 0);
    await openChat(tester, h);
    expect(textOf(tester, 'sheet-round'), '1 / 3');
    expect(shown('sheet-picks'), isFalse, reason: 'no pick count before pickState');
    final first = textOf(tester, 'sheet-clock');
    await h.advance(tester, const Duration(seconds: 10));
    expect(textOf(tester, 'sheet-clock'), isNot(first));
    expect(textOf(tester, 'sheet-clock'), textOf(tester, 'clock'));
    h.receive({'type': 'pickState', 'index': 0, 'picked': 1, 'total': 2});
    await h.settle(tester);
    expect(textOf(tester, 'sheet-picks'), '· 선택 1/2');
    // The time bar's fill follows the song's clock.
    final fill = tester.widget<FractionallySizedBox>(find.byKey(const ValueKey('sheet-timebar-fill'))).widthFactor!;
    expect(fill, closeTo(tester.widget<FractionallySizedBox>(find.byKey(const ValueKey('timebar-fill'))).widthFactor!, 0.001));
    expect(fill, lessThan(1));

    h.receive(revealFrame(0, correctChoice: 1));
    await h.settle(tester);
    expect(shown('sheet-reveal'), isTrue);
    // The next song while it is open: the line goes, the status line moves on.
    await prepare(tester, h, 1, total: 3, choices: ['e', 'f', 'g', 'h']);
    expect(shown('sheet-reveal'), isFalse);
    expect(textOf(tester, 'sheet-round'), '2 / 3');
    await start(tester, h, 1);
    expect(h.controller.game!.round!.index, 1);
    expect(textOf(tester, 'sheet-clock'), textOf(tester, 'clock'));
    expectSheet(tester, 'song 2');
    await tester.tap(find.byKey(const ValueKey('chat-close')));
    await h.settle(tester);
    // No old card after 닫기: the new song's covers take a pick.
    expect(shown('reveal'), isFalse);
    expect(shown('reveal-strip'), isFalse);
    await tester.tap(find.byKey(const ValueKey('tile-3')));
    await h.settle(tester);
    expect(h.lastSent<PickRequest>().choice, 3);
    await h.tearDown(tester);
  });

  testWidgets('opening reads every line: the unread count resets, lines while open stay read, then counts again',
      (tester) async {
    final h = await enterGame(tester, options: {'answerMode': 'choice'});
    await prepare(tester, h, 0, choices: ['a', 'b', 'c', 'd']);
    await start(tester, h, 0);
    for (final text in ['one', 'two', 'three']) {
      h.receive({'type': 'chat', 'name': 'Aki', 'text': text});
    }
    await h.settle(tester);
    expect(textOf(tester, 'chat-unread'), '${h.controller.game!.log.length}');
    await openChat(tester, h);
    h.receive({'type': 'chat', 'name': 'Aki', 'text': 'four'});
    h.receive({'type': 'chat', 'name': 'Aki', 'text': 'five'});
    await h.settle(tester);
    expect(logTexts(tester).sublist(logTexts(tester).length - 2), ['four', 'five']);
    await tester.tap(find.byKey(const ValueKey('chat-close')));
    await h.settle(tester);
    expect(shown('chat-unread'), isFalse, reason: 'nothing unread after the sheet');
    expect(find.byKey(const ValueKey('chat-open')).hitTestable(), findsOneWidget);
    h.receive({'type': 'chat', 'name': 'Aki', 'text': 'six'});
    await h.settle(tester);
    expect(textOf(tester, 'chat-unread'), '1');
    await openChat(tester, h);
    expect(shown('chat-unread'), isFalse, reason: 'opening reads the new line at once');
    await tester.tap(find.byKey(const ValueKey('chat-close')));
    await h.settle(tester);
    expect(shown('chat-unread'), isFalse);
    await h.tearDown(tester);
  });

  testWidgets('the sheet slides up and down in about 200 ms', (tester) async {
    final h = await enterGame(tester, options: {'answerMode': 'choice'});
    await prepare(tester, h, 0, choices: ['a', 'b', 'c', 'd']);
    await start(tester, h, 0);
    expect(sheetDuration, const Duration(milliseconds: 200));
    await tester.tap(find.byKey(const ValueKey('chat-open')));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 100));
    final half = rectOf(tester, 'chat-sheet').top;
    expect(half, greaterThan(100), reason: 'on its way up');
    await tester.pump(const Duration(milliseconds: 120));
    expect(rectOf(tester, 'chat-sheet').top, closeTo(40, 0.5));
    await tester.tap(find.byKey(const ValueKey('chat-close')));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 100));
    expect(rectOf(tester, 'chat-sheet').top, greaterThan(100), reason: 'on its way down');
    expect(find.byKey(const ValueKey('chat-input')).evaluate().isEmpty ||
        !tester.widget<EditableText>(find.byType(EditableText)).focusNode.hasFocus, isTrue, reason: 'the field lets go at once');
    await tester.pump(const Duration(milliseconds: 120));
    expect(shown('chat-sheet'), isFalse);
    await h.tearDown(tester);
  });

  testWidgets('a swipe down on the handle or the header closes the sheet; a short slow one springs back; a tap on the '
      'dimmed strip closes it', (tester) async {
    final h = await enterGame(tester, options: {'answerMode': 'choice'});
    await prepare(tester, h, 0, choices: ['a', 'b', 'c', 'd']);
    await start(tester, h, 0);
    await openChat(tester, h);
    // Short and slow: back up.
    await tester.timedDrag(find.byKey(const ValueKey('chat-sheet-handle')), const Offset(0, 80), const Duration(milliseconds: 800));
    await h.settle(tester);
    expect(shown('chat-sheet'), isTrue);
    expect(rectOf(tester, 'chat-sheet').top, closeTo(40, 0.5));
    // Long: closed.
    await tester.drag(find.byKey(const ValueKey('chat-sheet-title')), const Offset(0, 400));
    await h.settle(tester);
    expect(shown('chat-sheet'), isFalse);
    expect(find.byKey(const ValueKey('chat-open')).hitTestable(), findsOneWidget);
    // Short and fast (a flick) on the handle: closed.
    await openChat(tester, h);
    await tester.fling(find.byKey(const ValueKey('chat-sheet-handle')), const Offset(0, 100), 1500);
    await h.settle(tester);
    expect(shown('chat-sheet'), isFalse);
    // A swipe down on the log scrolls it, it does not close the sheet.
    await openChat(tester, h);
    await tester.drag(find.byKey(const ValueKey('chat-log')), const Offset(0, 300));
    await h.settle(tester);
    expect(shown('chat-sheet'), isTrue);
    // A tap on the dimmed strip at the top.
    await tester.tapAt(Offset(screenOf(tester).width / 2, 20));
    await h.settle(tester);
    expect(shown('chat-sheet'), isFalse);
    expect(h.sent.whereType<PickRequest>(), isEmpty);
    await h.tearDown(tester);
  });

  testWidgets('a chat room has no sheet: its chat stays on the game screen', (tester) async {
    final h = await enterGame(tester);
    await prepare(tester, h, 0);
    await start(tester, h, 0);
    expect(shown('chat-open'), isFalse);
    expect(shown('chat-sheet'), isFalse);
    expect(find.byKey(const ValueKey('chat-input')).hitTestable(), findsOneWidget);
    await h.tearDown(tester);
  });
}

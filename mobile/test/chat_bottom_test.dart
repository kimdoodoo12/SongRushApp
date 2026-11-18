import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import 'game_screen_test.dart' show enterGame, openChat, prepare, start;
import 'lobby_screen_test.dart' show enterLobby;

// Cycle 153: chat lines stack from the bottom, next to the input, like a
// chat app (the chat room's log, a choice room's box, the full chat, the
// lobby chat); a choice room's input asks for talk, not a title (D-135).
// Cycle 160 (D-145): a choice room's chat is the panel the 채팅 button
// opens; its input shows only then.

/// [text]'s rect inside the chat log.
Rect lineIn(WidgetTester tester, String text) =>
    tester.getRect(find.descendant(of: find.byKey(const ValueKey('chat-log')), matching: find.text(text)));

/// A log with few lines: the newest line's bottom near the log's bottom,
/// the first line well below the log's top.
void expectBottomStacked(WidgetTester tester, String first, String newest, String state) {
  final log = tester.getRect(find.byKey(const ValueKey('chat-log')));
  final last = lineIn(tester, newest);
  final top = lineIn(tester, first);
  // Bottom padding (8-12) and the bubble's 5 + 6 px under the text.
  expect(log.bottom - last.bottom, inInclusiveRange(0, 30), reason: '$state: newest $last at the bottom of $log');
  expect(top.top - log.top, greaterThan(log.height / 2), reason: '$state: first $top not at the top of $log');
}

String hintOf(WidgetTester tester) =>
    tester.widget<TextField>(find.byKey(const ValueKey('chat-input'))).decoration!.hintText!;

const Map<String, String> titleHint = {
  'ko': '애니 제목을 입력하세요',
  'en': 'Type the anime title',
  'ja': 'アニメのタイトルを入力',
};

const Map<String, String> talkHint = {
  'ko': '채팅으로 이야기해요',
  'en': 'Chat with everyone',
  'ja': 'チャットで話そう',
};

void main() {
  testWidgets('chat room: few lines sit at the bottom by the input; many keep the newest there', (tester) async {
    final h = await enterGame(tester);
    await prepare(tester, h, 0);
    await start(tester, h, 0);
    h.receive({'type': 'chat', 'name': 'Aki', 'text': 'hello'});
    await h.settle(tester);
    expectBottomStacked(tester, '게임을 시작해요', 'hello', 'chat room, few');
    // The input right under the log's box.
    final input = tester.getRect(find.byKey(const ValueKey('chat-input')));
    expect(input.top - tester.getRect(find.byKey(const ValueKey('chat-log'))).bottom, inInclusiveRange(0, 40));

    for (var i = 0; i < 40; i++) {
      h.receive({'type': 'chat', 'name': i.isEven ? 'Aki' : 'Mina', 'text': 'line $i'});
    }
    await h.settle(tester);
    final log = tester.getRect(find.byKey(const ValueKey('chat-log')));
    final newest = lineIn(tester, 'line 39');
    expect(log.bottom - newest.bottom, inInclusiveRange(0, 30), reason: 'many: the newest at the bottom');
    expect(find.descendant(of: find.byKey(const ValueKey('chat-log')), matching: find.text('게임을 시작해요')).hitTestable(),
        findsNothing,
        reason: 'many: the oldest scrolled up out of view');
    await h.tearDown(tester);
  });

  testWidgets('choice room: the open chat panel stacks from the bottom', (tester) async {
    final h = await enterGame(tester, options: {'answerMode': 'choice'});
    await prepare(tester, h, 0, choices: ['a', 'b', 'c', 'd']);
    await start(tester, h, 0);
    h.receive({'type': 'chat', 'name': 'Aki', 'text': 'hello'});
    await h.settle(tester);
    await openChat(tester, h);
    expect(find.byKey(const ValueKey('chat-panel')), findsOneWidget);
    expectBottomStacked(tester, '게임을 시작해요', 'hello', 'chat panel');
    await h.tearDown(tester);
  });

  testWidgets('lobby chat: few lines sit at the bottom of the log', (tester) async {
    final h = await enterLobby(tester);
    h.receive({'type': 'chat', 'name': 'Mina', 'text': 'hello'});
    h.receive({'type': 'chat', 'name': 'Aki', 'text': 'hi there'});
    await h.settle(tester);
    expectBottomStacked(tester, 'hello', 'hi there', 'lobby');
    await h.tearDown(tester);
  });

  for (final lang in ['ko', 'en', 'ja']) {
    testWidgets('a chat room\'s input asks for the title ($lang)', (tester) async {
      final h = await enterGame(tester, language: lang);
      await prepare(tester, h, 0);
      expect(hintOf(tester), titleHint[lang]);
      await h.tearDown(tester);
    });

    testWidgets('a choice room\'s input asks for talk, not a title ($lang)', (tester) async {
      final h = await enterGame(tester, language: lang, options: {'answerMode': 'choice'});
      await prepare(tester, h, 0, choices: ['a', 'b', 'c', 'd']);
      expect(find.byKey(const ValueKey('chat-input')), findsNothing, reason: 'no input until 채팅 opens the panel');
      await openChat(tester, h);
      expect(hintOf(tester), talkHint[lang]);
      expect(find.text(titleHint[lang]!), findsNothing);
      await h.tearDown(tester);
    });
  }
}

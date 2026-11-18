import 'dart:typed_data';

import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';
import 'package:songrush_core/songrush_core.dart';
import 'package:songrush_mobile/app/app_controller.dart';

import 'game_screen_test.dart' show audio, enterGame, prepare, revealFrame, start;
import 'lobby_screen_test.dart' show enterLobby;
import 'reconnect_test.dart' show stored;
import 'support/harness.dart';

// Cycle 164: the phone's back button or gesture. On the web the engine turns
// the browser's back into a `popRoute`, which the app's PopScope hands to
// AppController.handleBack; handlePopRoute below is that same path.

const String exitAsk = 'SongRush를 나갈까요?';
const String roomAsk = '방에서 나갈까요?';
const String gameAsk = '게임을 나갈까요?';

Future<void> back(WidgetTester tester, Harness h) async {
  await tester.binding.handlePopRoute();
  await h.settle(tester);
}

bool shown(String key) => find.byKey(ValueKey(key)).evaluate().isNotEmpty;

void main() {
  testWidgets('start screen: a back asks before leaving the site; 취소 and a second back stay, 나가기 leaves',
      (tester) async {
    final h = Harness();
    await h.pumpApp(tester);
    await back(tester, h);
    expect(find.text(exitAsk), findsOneWidget);
    expect(h.controller.exitAsked, isTrue);

    await tester.tap(find.byKey(const ValueKey('leave-cancel')));
    await h.settle(tester);
    expect(find.text(exitAsk), findsNothing);
    expect(h.exits, 0);

    // A back while it is open closes it.
    await back(tester, h);
    expect(find.text(exitAsk), findsOneWidget);
    await back(tester, h);
    expect(find.text(exitAsk), findsNothing);
    expect(h.exits, 0);
    expect(h.controller.screen, Screen.start);

    await back(tester, h);
    await tester.tap(find.byKey(const ValueKey('leave-confirm')));
    await h.settle(tester);
    expect(h.exits, 1);
    expect(find.text(exitAsk), findsNothing);
    expect(h.sockets.sockets, isEmpty);
    await h.tearDown(tester);
  });

  testWidgets('start screen: a back closes the nickname dialog first', (tester) async {
    final h = Harness(initialCode: 'K7XQM');
    await h.pumpApp(tester);
    expect(h.controller.dialog, isNotNull);
    await back(tester, h);
    expect(h.controller.dialog, isNull);
    expect(find.text(exitAsk), findsNothing);
    await back(tester, h);
    expect(find.text(exitAsk), findsOneWidget);
    await h.tearDown(tester);
  });

  testWidgets('start screen: a back closes the sound offset setting first', (tester) async {
    final h = Harness();
    await h.pumpApp(tester);
    h.controller.openOffset();
    await back(tester, h);
    expect(h.controller.offsetOpen, isFalse);
    expect(h.controller.exitAsked, isFalse);
    await h.tearDown(tester);
  });

  testWidgets('lobby: a back asks 방에서 나갈까요?; 취소 and a back stay, 나가기 leaves to the start screen',
      (tester) async {
    final h = await enterLobby(tester);
    await back(tester, h);
    expect(find.text(roomAsk), findsOneWidget);
    expect(h.sent.whereType<LeaveRequest>(), isEmpty);

    await tester.tap(find.byKey(const ValueKey('leave-cancel')));
    await h.settle(tester);
    expect(find.text(roomAsk), findsNothing);
    expect(h.controller.screen, Screen.lobby);

    await back(tester, h);
    await back(tester, h);
    expect(find.text(roomAsk), findsNothing);
    expect(h.controller.screen, Screen.lobby);
    expect(h.sent.whereType<LeaveRequest>(), isEmpty);

    await back(tester, h);
    final socket = h.socket;
    await tester.tap(find.byKey(const ValueKey('leave-confirm')));
    await h.settle(tester);
    expect(socket.sentMessages.whereType<LeaveRequest>(), hasLength(1));
    expect(h.controller.screen, Screen.start);
    expect(find.byKey(const ValueKey('create')), findsOneWidget);
    // On the start screen the next back asks about the site, not the room.
    await back(tester, h);
    expect(find.text(exitAsk), findsOneWidget);
    await h.tearDown(tester);
  });

  testWidgets('lobby: the confirm goes when the game starts', (tester) async {
    final h = await enterLobby(tester);
    h.fetcher.responses[audio(0)] = Uint8List(8);
    await back(tester, h);
    expect(h.controller.leaveAsked, isTrue);
    h.receive({'type': 'roster', 'phase': 'playing', 'players': [player('Mina', host: true)]});
    h.receive({'type': 'settings'});
    await prepare(tester, h, 0);
    expect(h.controller.screen, Screen.game);
    expect(h.controller.leaveAsked, isFalse);
    expect(find.text(gameAsk), findsNothing);
    await h.tearDown(tester);
  });

  testWidgets('chat game: a back opens the game\'s leave dialog; 나가기 leaves', (tester) async {
    final h = await enterGame(tester);
    await prepare(tester, h, 0);
    await back(tester, h);
    expect(find.text(gameAsk), findsOneWidget);
    await back(tester, h);
    expect(find.text(gameAsk), findsNothing);
    expect(h.controller.screen, Screen.game);

    // The 나가기 button and a back share the one dialog.
    await tester.tap(find.byKey(const ValueKey('game-leave')));
    await h.settle(tester);
    expect(find.text(gameAsk), findsOneWidget);
    await back(tester, h);
    expect(find.text(gameAsk), findsNothing);

    await back(tester, h);
    final socket = h.socket;
    await tester.tap(find.byKey(const ValueKey('leave-confirm')));
    await h.settle(tester);
    expect(socket.sentMessages.whereType<LeaveRequest>(), hasLength(1));
    expect(h.controller.screen, Screen.start);
    await h.tearDown(tester);
  });

  testWidgets('choice game: a back closes the scores, then the chat panel, then asks', (tester) async {
    final h = await enterGame(tester, options: {'answerMode': 'choice'});
    await prepare(tester, h, 0, choices: ['c0', 'c1', 'c2', 'c3']);
    await start(tester, h, 0);

    await tester.tap(find.byKey(const ValueKey('chat-open')));
    await h.settle(tester);
    expect(shown('chat-close'), isTrue);
    await tester.tap(find.byKey(const ValueKey('my-rank')));
    await h.settle(tester);
    expect(shown('scores'), isTrue);

    await back(tester, h);
    expect(shown('scores'), isFalse);
    expect(shown('chat-close'), isTrue);
    expect(find.text(gameAsk), findsNothing);

    await back(tester, h);
    expect(shown('chat-close'), isFalse);
    expect(shown('chat-open'), isTrue);
    expect(find.text(gameAsk), findsNothing);

    await back(tester, h);
    expect(find.text(gameAsk), findsOneWidget);
    expect(h.sent.whereType<LeaveRequest>(), isEmpty);
    expect(h.controller.screen, Screen.game);
    await h.tearDown(tester);
  });

  testWidgets('game over: a back asks about the room; 나가기 leaves', (tester) async {
    final h = await enterGame(tester);
    await prepare(tester, h, 0, total: 1);
    await start(tester, h, 0);
    h.receive(revealFrame(0, winner: 'Aki'));
    h.receive({
      'type': 'gameOver',
      'ranking': [
        {'rank': 1, 'name': 'Aki', 'score': 10},
        {'rank': 2, 'name': 'Mina', 'score': 5},
      ],
    });
    await h.settle(tester);
    expect(h.controller.screen, Screen.over);
    await back(tester, h);
    expect(find.text(roomAsk), findsOneWidget);
    await tester.tap(find.byKey(const ValueKey('leave-cancel')));
    await h.settle(tester);
    expect(find.text(roomAsk), findsNothing);
    expect(h.controller.screen, Screen.over);

    await back(tester, h);
    await tester.tap(find.byKey(const ValueKey('leave-confirm')));
    await h.settle(tester);
    expect(h.controller.screen, Screen.start);
    await h.tearDown(tester);
  });

  testWidgets('rejoining: a back gives the room up for the start screen', (tester) async {
    final h = Harness(stored: stored, reloaded: true);
    await h.pumpApp(tester);
    expect(h.controller.screen, Screen.rejoining);
    await back(tester, h);
    expect(h.controller.screen, Screen.start);
    expect(h.tokens.read(), isNull);
    expect(find.byKey(const ValueKey('create')), findsOneWidget);
    await h.tearDown(tester);
  });

  testWidgets('disconnected: a back goes to the start screen', (tester) async {
    final h = await enterLobby(tester);
    h.socket.drop(replacedCloseCode);
    await h.settle(tester);
    expect(h.controller.screen, Screen.disconnected);
    await back(tester, h);
    expect(h.controller.screen, Screen.start);
    expect(find.text(exitAsk), findsNothing);
    await h.tearDown(tester);
  });
}

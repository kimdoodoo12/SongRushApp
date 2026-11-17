import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';
import 'package:songrush_core/songrush_core.dart';
import 'package:songrush_mobile/app/app_controller.dart';
import 'package:songrush_mobile/app/lobby_selection.dart';

import 'support/harness.dart';

const _countField = ValueKey('count');

/// Opens a room as Mina: the host, or a guest of Aki.
Future<Harness> enterLobby(WidgetTester tester, {bool host = true}) async {
  final h = Harness(prefs: {nicknameKey: 'Mina'});
  await h.pumpApp(tester);
  if (host) {
    h.controller.openCreateDialog();
  } else {
    h.controller.openJoinDialog('K7XQM');
  }
  await h.settle(tester);
  await tester.tap(find.byKey(const ValueKey('nick-confirm')));
  await h.settle(tester);
  await h.admit(
    tester,
    players: host ? [player('Mina', host: true)] : [player('Aki', host: true), player('Mina')],
  );
  return h;
}

String shownText(WidgetTester tester, String key) => tester.widget<Text>(find.byKey(ValueKey(key))).data!;

bool isStepEnabled(WidgetTester tester, String key) {
  final ink = tester.widget<InkWell>(find.descendant(of: find.byKey(ValueKey(key)), matching: find.byType(InkWell)));
  return ink.onTap != null;
}

void main() {
  testWidgets('the host sees editable settings and sends them to the guests', (tester) async {
    final h = await enterLobby(tester);
    expect(shownText(tester, 'who'), '방장만 바꿀 수 있어요');
    expect(find.byKey(const ValueKey('start')), findsOneWidget);
    expect(find.text('방장이 게임을 시작하기를 기다리는 중...'), findsNothing);
    expect(find.text('Mina (방장) (나)'), findsOneWidget);
    expect(find.text('빈 자리'), findsNWidgets(7));
    // Entering the lobby as host sends the selection once.
    final first = h.lastSent<LobbySettingsRequest>();
    expect(first.songCount, defaultSongs);
    expect(first.options.toJson(), isEmpty);

    await tapVisible(tester, find.text('오프닝만'));
    await h.settle(tester);
    expect(h.lastSent<LobbySettingsRequest>().options.types, ['OP']);

    await tapVisible(tester, find.text('객관식'));
    await h.settle(tester);
    expect(find.text('표지 6장 중에서 골라요 (난이도에 따라 4·6·8장)'), findsOneWidget);
    await tapVisible(tester, find.text('어려움'));
    await h.settle(tester);
    expect(find.text('표지 8장 중에서 골라요 (난이도에 따라 4·6·8장)'), findsOneWidget);
    await tapVisible(tester, find.text('순위전'));
    await h.settle(tester);
    final last = h.lastSent<LobbySettingsRequest>();
    expect(last.options.toJson(), {
      'types': ['OP'],
      'tier': 'hard',
      'answerMode': 'choice',
      'gameMode': 'ranked',
    });
    expect(find.text('모두 맞히거나 고를 때까지 곡이 이어지고 순위대로 점수를 받아요'), findsOneWidget);
    // The header's settings line.
    expect(find.text('오프닝만 · 어려움 · 객관식 · 순위전'), findsOneWidget);
    await h.tearDown(tester);
  });

  testWidgets('the song count is typed and clamped to 5-100', (tester) async {
    final h = await enterLobby(tester);
    final field = find.byKey(_countField);

    await tester.ensureVisible(field);
    await tester.enterText(field, '150');
    await tester.testTextInput.receiveAction(TextInputAction.done);
    await h.settle(tester);
    expect(h.controller.selection.songCount, 100);
    expect(h.lastSent<LobbySettingsRequest>().songCount, 100);
    expect(find.text('100'), findsOneWidget);
    expect(isStepEnabled(tester, 'count-up'), isFalse);

    await tester.enterText(field, '2');
    await tester.testTextInput.receiveAction(TextInputAction.done);
    await h.settle(tester);
    expect(h.controller.selection.songCount, 5);
    expect(h.lastSent<LobbySettingsRequest>().songCount, 5);
    expect(isStepEnabled(tester, 'count-down'), isFalse);

    // An empty draft reverts.
    await tester.enterText(field, '');
    await tester.testTextInput.receiveAction(TextInputAction.done);
    await h.settle(tester);
    expect(h.controller.selection.songCount, 5);
    expect(find.text('5'), findsOneWidget);

    await tapVisible(tester, find.byKey(const ValueKey('count-up')));
    await h.settle(tester);
    expect(h.controller.selection.songCount, 6);
    expect(find.text('6'), findsOneWidget);
    await h.tearDown(tester);
  });

  testWidgets('a guest sees the host settings read-only', (tester) async {
    final h = await enterLobby(tester, host: false);
    expect(shownText(tester, 'who'), '방장이 설정을 고르는 중이에요');
    expect(shownText(tester, 'count-shown'), String.fromCharCode(0x2014));
    expect(find.byKey(_countField), findsNothing);
    expect(find.byKey(const ValueKey('start')), findsNothing);
    expect(find.text('방장이 게임을 시작하기를 기다리는 중...'), findsOneWidget);
    expect(find.text('Aki (방장)'), findsOneWidget);
    expect(find.text('Mina (나)'), findsOneWidget);

    h.receive({
      'type': 'lobbySettings',
      'songCount': 30,
      'tier': 'easy',
      'answerMode': 'choice',
      'gameMode': 'ranked',
    });
    await h.settle(tester);
    expect(shownText(tester, 'who'), '방장이 정한 설정이에요');
    expect(shownText(tester, 'count-shown'), '30');
    expect(find.text('표지 4장 중에서 골라요 (난이도에 따라 4·6·8장)'), findsOneWidget);
    expect(
      h.controller.shownSelection,
      const LobbySelection(songCount: 30, tier: 'easy', answerMode: 'choice', gameMode: 'ranked'),
    );

    // The controls do nothing for a guest.
    // The answer format's 채팅 (the chat panel's title is the second).
    await tapVisible(tester, find.text('채팅').first);
    await h.settle(tester);
    expect(h.sent.whereType<LobbySettingsRequest>(), isEmpty);
    expect(h.controller.shownSelection.answerMode, 'choice');
    await h.tearDown(tester);
  });

  testWidgets('chat lines go out and come in', (tester) async {
    final h = await enterLobby(tester, host: false);
    expect(find.text('K7XQM 방에 들어왔어요. 방장이 설정을 고르는 중이에요'), findsOneWidget);
    expect(find.text('2명 접속 중'), findsOneWidget);

    final input = find.byKey(const ValueKey('chat-input'));
    await tester.ensureVisible(input);
    await tester.enterText(input, '   ');
    await tapVisible(tester, find.byKey(const ValueKey('chat-send')));
    await h.settle(tester);
    expect(h.sent.whereType<ChatRequest>(), isEmpty);

    await tester.enterText(input, 'hello');
    await tapVisible(tester, find.byKey(const ValueKey('chat-send')));
    await h.settle(tester);
    expect(h.lastSent<ChatRequest>().text, 'hello');
    expect(tester.widget<TextField>(input).controller!.text, '');

    h.receive({'type': 'chat', 'name': 'Mina', 'text': 'hello'});
    h.receive({'type': 'chat', 'name': 'Aki', 'text': 'hi there'});
    await h.settle(tester);
    expect(find.text('hello'), findsOneWidget);
    expect(find.text('hi there'), findsOneWidget);
    await h.tearDown(tester);
  });

  testWidgets('a refused start shows the reason and disables start until the selection changes', (tester) async {
    final h = await enterLobby(tester);
    expect(isEnabled(tester, const ValueKey('start')), isTrue);
    await tapVisible(tester, find.byKey(const ValueKey('start')));
    await h.settle(tester);
    final start = h.lastSent<StartRequest>();
    expect(start.songCount, defaultSongs);

    h.receive({'type': 'error', 'code': 'not-enough-songs', 'available': 3});
    await h.settle(tester);
    expect(find.text('출제할 수 있는 곡이 3곡뿐입니다.'), findsOneWidget);
    expect(isEnabled(tester, const ValueKey('start')), isFalse);

    await tapVisible(tester, find.text('쉬움'));
    await h.settle(tester);
    expect(isEnabled(tester, const ValueKey('start')), isTrue);
    await h.tearDown(tester);
  });

  testWidgets('a reversed year range warns, disables start and is not sent', (tester) async {
    final h = await enterLobby(tester);
    final before = h.sent.whereType<LobbySettingsRequest>().length;
    h.controller.setSelection(h.controller.selection.copyWith(yearFrom: 2020, yearTo: 2014));
    await h.settle(tester);
    expect(find.text('시작 연도가 끝 연도보다 늦습니다.'), findsOneWidget);
    expect(isEnabled(tester, const ValueKey('start')), isFalse);
    expect(h.sent.whereType<LobbySettingsRequest>().length, before);
    await h.tearDown(tester);
  });

  testWidgets('leave tells the server and returns to the start screen', (tester) async {
    final h = await enterLobby(tester);
    final socket = h.socket;
    await tapVisible(tester, find.byKey(const ValueKey('leave')));
    await h.settle(tester);
    expect(socket.sentMessages.last, isA<LeaveRequest>());
    expect(socket.closedByClient, isTrue);
    expect(find.byKey(const ValueKey('create')), findsOneWidget);
    expect(find.byKey(const ValueKey('ticket-code')), findsNothing);
    await h.tearDown(tester);
  });

  testWidgets('a closed room returns to the start screen with the reason', (tester) async {
    final h = await enterLobby(tester, host: false);
    h.receive({'type': 'error', 'code': 'room-closed'});
    await h.settle(tester);
    expect(find.byKey(const ValueKey('create')), findsOneWidget);
    expect(find.text('방이 닫혔습니다.'), findsOneWidget);
    await h.tearDown(tester);
  });
  for (final host in [true, false]) {
    testWidgets('the ${host ? 'host' : 'guest'} lobby fits a 360 px phone in every language', (tester) async {
      final h = await enterLobby(tester, host: host);
      tester.view.physicalSize = const Size(360, 640) * 3;
      for (final lng in ['en', 'ja', 'ko']) {
        h.controller.setLanguage(lng);
        await h.settle(tester);
        // A RenderFlex overflow would have failed the test by now.
        expect(find.byKey(const ValueKey('leave')), findsOneWidget);
      }
      await h.tearDown(tester);
    });
  }
}

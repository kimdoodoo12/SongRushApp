import 'dart:convert';

import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';
import 'package:songrush_core/songrush_core.dart';
import 'package:songrush_mobile/app/app_controller.dart';
import 'package:songrush_mobile/app/game_model.dart';

import 'support/harness.dart';

// A 1x1 transparent PNG: the covers' bytes.
final Uint8List png = base64Decode(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==',
);

String audio(int i) => '/audio/a$i';
String coverOf(String id) => '/cover/$id';

/// Mina (host) and Aki in a room whose game has begun: the roster says
/// playing and the server's settings are in. [options] are the game's.
Future<Harness> enterGame(
  WidgetTester tester, {
  Map<String, Object?> options = const {},
  String language = 'ko',
}) async {
  final h = Harness(prefs: {nicknameKey: 'Mina', 'songrush.lang': language});
  h.fetcher.responses[audio(0)] = Uint8List(8);
  h.fetcher.responses[audio(1)] = Uint8List(8);
  await h.pumpApp(tester);
  h.controller.openCreateDialog();
  await h.settle(tester);
  await tester.tap(find.byKey(const ValueKey('nick-confirm')));
  await h.settle(tester);
  await h.admit(tester, players: [player('Mina', host: true), player('Aki')]);
  h.receive({
    'type': 'roster',
    'phase': 'playing',
    'players': [player('Mina', host: true), player('Aki')],
  });
  h.receive({'type': 'settings', ...options});
  await h.settle(tester);
  return h;
}

/// prepare of song [index] (of [total]), with [choices] cover ids.
Future<void> prepare(
  WidgetTester tester,
  Harness h,
  int index, {
  int total = 3,
  List<String>? choices,
  int? holdMs,
}) async {
  h.fetcher.responses[audio(index)] = Uint8List(8);
  for (final id in choices ?? const <String>[]) {
    h.fetcher.responses[coverOf(id)] = png;
  }
  h.receive({
    'type': 'prepare',
    'index': index,
    'total': total,
    'audioUrl': audio(index),
    if (choices != null) 'choices': [for (final id in choices) {'coverUrl': coverOf(id)}],
    'holdMs': ?holdMs,
  });
  await h.settle(tester);
}

/// start of song [index]: at the app's now + 500 ms, 90 s long, sent at
/// the app's now (so the offset is 0); then the clock passes its start.
Future<({int startAt, int deadlineAt})> start(WidgetTester tester, Harness h, int index) async {
  final startAt = h.nowMs + 500;
  final deadlineAt = startAt + 90000;
  h.receive({'type': 'start', 'index': index, 'startAt': startAt, 'deadlineAt': deadlineAt, 'serverTime': h.nowMs});
  await h.settle(tester);
  await h.advance(tester, const Duration(milliseconds: 750));
  return (startAt: startAt, deadlineAt: deadlineAt);
}

Map<String, Object?> revealFrame(
  int index, {
  String? winner,
  int? correctChoice,
  List<Map<String, Object?>>? ranking,
  int? playUntil,
}) =>
    {
      'type': 'reveal',
      'index': index,
      'reveal': '진격의 거인',
      'titles': {
        'ko': ['진격의 거인 (애니메이션)'],
        'en': ['Attack on Titan'],
        'ja': ['進撃の巨人'],
        'romaji': ['Shingeki no Kyojin'],
      },
      'song': 'Guren no Yumiya',
      'artists': ['Linked Horizon'],
      'themeType': 'OP',
      'themeSlug': 'OP1',
      'winner': winner,
      'coverUrl': coverOf('aot'),
      'correctChoice': ?correctChoice,
      'ranking': ?ranking,
      'playUntil': ?playUntil,
    };

/// The chat log's texts, in order.
List<String> logTexts(WidgetTester tester) {
  final log = find.byKey(const ValueKey('chat-log'));
  return [
    for (final w in tester.widgetList<Text>(find.descendant(of: log, matching: find.byType(Text)))) w.data ?? '',
  ];
}

String textOf(WidgetTester tester, String key) {
  final widget = tester.widget(find.byKey(ValueKey(key)));
  if (widget is Text) return widget.data ?? widget.textSpan!.toPlainText();
  throw StateError('$key is not a Text');
}

String buttonText(WidgetTester tester, String key) => tester
    .widget<Text>(find.descendant(of: find.byKey(ValueKey(key)), matching: find.byType(Text)))
    .data!;

double fillOf(WidgetTester tester) =>
    tester.widget<FractionallySizedBox>(find.byKey(const ValueKey('timebar-fill'))).widthFactor!;

void main() {
  testWidgets('chat answers: wrong guesses as chat, the correct line, the echo above it, chat after the reveal',
      (tester) async {
    final h = await enterGame(tester);
    // The lobby stays until the first prepare, as on the web.
    expect(h.controller.screen, Screen.lobby);
    await prepare(tester, h, 0);
    expect(h.controller.screen, Screen.game);
    expect(textOf(tester, 'round'), '1 / 3 번째 곡');
    expect(textOf(tester, 'hint'), '곡이 나오면 애니 제목을 채팅으로 맞혀 보세요');
    expect(find.byKey(const ValueKey('skip')), findsNothing);
    expect(h.lastSent<ReadyRequest>().index, 0);

    await start(tester, h, 0);
    expect(textOf(tester, 'hint'), '들리는 곡의 애니 제목을 채팅으로 맞혀 보세요');
    expect(logTexts(tester), ['게임을 시작해요', '1번째 곡 시작']);

    await tester.enterText(find.byKey(const ValueKey('chat-input')), 'naruto');
    await tester.testTextInput.receiveAction(TextInputAction.send);
    await h.settle(tester);
    expect(h.lastSent<ChatRequest>().text, 'naruto');
    // The server echoes a wrong guess to everyone, as chat.
    h.receive({'type': 'chat', 'name': 'Mina', 'text': 'naruto'});
    await h.settle(tester);
    expect(logTexts(tester).last, 'naruto');

    // Aki's correct answer: the system line first, then the winning message
    // goes just above it (D-094).
    h.receive({'type': 'correct', 'index': 0, 'name': 'Aki'});
    await h.settle(tester);
    expect(logTexts(tester).last, '✔ Aki 정답!');
    h.receive({'type': 'chat', 'name': 'Aki', 'text': '진격의 거인'});
    await h.settle(tester);
    expect(logTexts(tester).sublist(3), ['진격의 거인', '✔ Aki 정답!']);
    expect(textOf(tester, 'hint'), 'Aki 정답!');
    final seat = tester.widget<Text>(find.byKey(const ValueKey('seat-score-Aki')));
    expect(seat.textSpan!.toPlainText(), '0점 정답!');

    h.receive(revealFrame(0, winner: 'Aki'));
    h.receive({
      'type': 'scores',
      'scores': [
        {'name': 'Aki', 'score': 1},
        {'name': 'Mina', 'score': 0},
      ],
    });
    await h.settle(tester);
    expect(textOf(tester, 'reveal-who'), '정답: Aki');
    expect(tester.widget<Text>(find.byKey(const ValueKey('seat-score-Aki'))).textSpan!.toPlainText(), '1점 정답!');
    // After the reveal chat is ordinary chat.
    h.receive({'type': 'chat', 'name': 'Mina', 'text': 'gg'});
    await h.settle(tester);
    expect(logTexts(tester).last, 'gg');
    expect(find.byKey(const ValueKey('clock')), findsOneWidget);
    expect(textOf(tester, 'clock'), '');
    await h.tearDown(tester);
  });

  testWidgets('the time bar counts to the deadline on the server clock', (tester) async {
    final h = await enterGame(tester);
    await prepare(tester, h, 0);
    expect(textOf(tester, 'clock'), '');
    expect(fillOf(tester), 1);
    final s = await start(tester, h, 0);
    // 250 ms past the start: 89.75 s left.
    expect(h.nowMs, s.startAt + 250);
    expect(textOf(tester, 'clock'), '90초');
    await h.advance(tester, const Duration(seconds: 30));
    expect(textOf(tester, 'clock'), '60초');
    expect(fillOf(tester), closeTo(59.75 / 90, 0.01));
    await h.advance(tester, const Duration(seconds: 50));
    expect(textOf(tester, 'clock'), '10초');
    final clock = tester.widget<Text>(find.byKey(const ValueKey('clock')));
    expect(clock.style!.color, const Color(0xFFFF5D8F));
    await h.tearDown(tester);
  });

  testWidgets('ranked: a correct line with its rank, every solver marked, the 3/2/1 points on the card',
      (tester) async {
    final h = await enterGame(tester, options: {'gameMode': 'ranked'});
    await prepare(tester, h, 0);
    await start(tester, h, 0);
    // Another player's correct answer: no echo follows, the song goes on.
    h.receive({'type': 'correct', 'index': 0, 'name': 'Aki', 'rank': 1});
    await h.settle(tester);
    expect(logTexts(tester).last, '✔ Aki 정답! (1등)');
    expect(textOf(tester, 'hint'), '들리는 곡의 애니 제목을 채팅으로 맞혀 보세요');
    // The own correct answer: its echo goes above the own line.
    h.receive({'type': 'correct', 'index': 0, 'name': 'Mina', 'rank': 2});
    h.receive({'type': 'chat', 'name': 'Mina', 'text': 'shingeki'});
    await h.settle(tester);
    expect(logTexts(tester).sublist(logTexts(tester).length - 3), ['✔ Aki 정답! (1등)', 'shingeki', '✔ Mina 정답! (2등)']);
    expect(tester.widget<Text>(find.byKey(const ValueKey('seat-score-Aki'))).textSpan!.toPlainText(), '0점 정답!');
    expect(tester.widget<Text>(find.byKey(const ValueKey('seat-score-Mina'))).textSpan!.toPlainText(), '0점 정답!');

    h.receive(revealFrame(0, winner: 'Aki', ranking: [
      {'name': 'Aki', 'rank': 1, 'points': 3},
      {'name': 'Mina', 'rank': 2, 'points': 2},
    ]));
    await h.settle(tester);
    expect(textOf(tester, 'reveal-who'), '정답자: Aki, Mina');
    expect(find.text('1등'), findsOneWidget);
    expect(find.text('+3점'), findsOneWidget);
    expect(find.text('2등'), findsOneWidget);
    expect(find.text('+2점'), findsOneWidget);
    await h.tearDown(tester);
  });

  testWidgets('choice room: covers from the round controller, one pick, the wrong pick, the count, the marks',
      (tester) async {
    final h = await enterGame(tester, options: {'answerMode': 'choice'});
    await prepare(tester, h, 0, choices: ['c1', 'c2', 'c3', 'c4']);
    expect(find.byKey(const ValueKey('choices')), findsOneWidget);
    expect(find.byKey(const ValueKey('tile-cover')), findsNWidgets(4));
    expect(textOf(tester, 'hint'), '곡이 나오면 표지 중에서 그 애니를 골라 보세요');
    // Before the start the tiles take no pick, and nothing is marked.
    await tapVisible(tester, find.byKey(const ValueKey('tile-0')));
    await h.settle(tester);
    expect(h.sent.whereType<PickRequest>(), isEmpty);
    expect(find.byKey(const ValueKey('tile-mark-0')), findsNothing);

    await start(tester, h, 0);
    expect(textOf(tester, 'hint'), '들리는 곡의 애니를 표지에서 골라 보세요');
    h.receive({'type': 'pickState', 'index': 0, 'picked': 0, 'total': 2});
    await h.settle(tester);
    expect(textOf(tester, 'pick-count'), '선택 0/2');

    await tapVisible(tester, find.byKey(const ValueKey('tile-1')));
    await h.settle(tester);
    final pick = h.lastSent<PickRequest>();
    expect((pick.index, pick.choice), (0, 1));
    // One pick per song.
    await tapVisible(tester, find.byKey(const ValueKey('tile-2')));
    await h.settle(tester);
    expect(h.sent.whereType<PickRequest>().length, 1);

    h.receive({'type': 'pick-result', 'index': 0, 'choice': 1});
    h.receive({'type': 'pickState', 'index': 0, 'picked': 1, 'total': 2});
    await h.settle(tester);
    expect(textOf(tester, 'pick-wrong'), '2번은 오답이에요. 정답 공개를 기다려 주세요.');
    expect(textOf(tester, 'tile-mark-1'), '✕ 오답');
    expect(textOf(tester, 'pick-count'), '선택 1/2');
    // Another player's wrong pick: a red notice; chat never scores (D-135).
    h.receive({'type': 'wrong', 'index': 0, 'name': 'Aki'});
    h.receive({'type': 'chat', 'name': 'Aki', 'text': '진격의 거인'});
    await h.settle(tester);
    expect(logTexts(tester).sublist(logTexts(tester).length - 2), ['✘ Aki 오답', '진격의 거인']);

    h.receive(revealFrame(0, correctChoice: 2));
    await h.settle(tester);
    expect(textOf(tester, 'tile-mark-2'), '✔ 정답');
    expect(textOf(tester, 'tile-mark-1'), '✕ 오답');
    expect(find.byKey(const ValueKey('tile-mark-0')), findsNothing);
    expect(find.byKey(const ValueKey('pick-count')), findsNothing);
    expect(textOf(tester, 'reveal-who'), '정답자 없음');
    await h.tearDown(tester);
  });

  testWidgets('a refused pick frees the tiles; already-picked keeps them locked', (tester) async {
    final h = await enterGame(tester, options: {'answerMode': 'choice'});
    await prepare(tester, h, 0, choices: ['c1', 'c2', 'c3', 'c4']);
    await start(tester, h, 0);
    await tapVisible(tester, find.byKey(const ValueKey('tile-0')));
    await h.settle(tester);
    h.receive({'type': 'error', 'code': 'too-early', 'for': 'pick'});
    await h.settle(tester);
    expect(h.controller.game!.canPick, isTrue);
    expect(find.text('알 수 없는 오류'), findsNothing);
    await tapVisible(tester, find.byKey(const ValueKey('tile-3')));
    await h.settle(tester);
    expect(h.sent.whereType<PickRequest>().map((p) => p.choice), [0, 3]);
    h.receive({'type': 'error', 'code': 'already-picked', 'for': 'pick'});
    await h.settle(tester);
    expect(h.controller.game!.canPick, isFalse);
    expect(h.controller.game!.picked, isNull);
    await h.tearDown(tester);
  });

  testWidgets('skip: the vote toggles with its count; a held prepare closes it and keeps the reveal', (tester) async {
    final h = await enterGame(tester);
    await prepare(tester, h, 0);
    final s = await start(tester, h, 0);
    expect(buttonText(tester, 'skip'), '스킵');
    await tapVisible(tester, find.byKey(const ValueKey('skip')));
    await h.settle(tester);
    expect(h.lastSent<SkipVoteRequest>().index, 0);
    expect(find.text('스킵 취소'), findsOneWidget);
    h.receive({'type': 'skipState', 'index': 0, 'votes': 1, 'needed': 2});
    await h.settle(tester);
    expect(textOf(tester, 'skip-count'), '스킵 1/2');
    // A rate-limited vote was not counted.
    await tapVisible(tester, find.byKey(const ValueKey('skip')));
    await h.settle(tester);
    expect(find.text('스킵'), findsOneWidget);
    h.receive({'type': 'error', 'code': 'rate-limited', 'for': 'skipVote'});
    await h.settle(tester);
    expect(find.text('스킵 취소'), findsOneWidget);

    // The reveal with the music playing on (a window): the card, the clock
    // and the skip from zero.
    h.receive(revealFrame(0, winner: 'Aki', playUntil: s.startAt + 60000));
    await h.settle(tester);
    expect(find.byKey(const ValueKey('reveal')), findsOneWidget);
    expect(buttonText(tester, 'skip'), '스킵');
    expect(textOf(tester, 'clock'), isNot(''));

    // The next song held behind the reveal: the skip goes, the card stays.
    await prepare(tester, h, 1, holdMs: 5000);
    expect(find.byKey(const ValueKey('skip')), findsNothing);
    expect(textOf(tester, 'reveal-who'), '정답: Aki');
    expect(textOf(tester, 'round'), '1 / 3 번째 곡');
    await h.advance(tester, const Duration(seconds: 5));
    await h.settle(tester);
    expect(find.byKey(const ValueKey('reveal')), findsNothing);
    expect(textOf(tester, 'round'), '2 / 3 번째 곡');
    expect(textOf(tester, 'hint'), '곡이 나오면 애니 제목을 채팅으로 맞혀 보세요');
    await h.tearDown(tester);
  });

  testWidgets('the window ends: the clock stops, the card stays', (tester) async {
    final h = await enterGame(tester);
    await prepare(tester, h, 0);
    await start(tester, h, 0);
    h.receive(revealFrame(0, playUntil: h.nowMs + 3000));
    await h.settle(tester);
    expect(textOf(tester, 'clock'), isNot(''));
    await h.advance(tester, const Duration(seconds: 4));
    expect(textOf(tester, 'clock'), '');
    expect(find.byKey(const ValueKey('skip')), findsNothing);
    expect(find.byKey(const ValueKey('reveal')), findsOneWidget);
    await h.tearDown(tester);
  });

  for (final (lang, who, labels, next) in [
    ('ko', '정답: Aki', ['KO', 'EN', 'JA', 'Romaji'], '잠시 후 다음 곡이 시작돼요'),
    ('en', 'Winner: Aki', ['KO', 'EN', 'JA', 'Romaji'], 'The next song starts in a moment'),
    ('ja', '正解: Aki', ['KO', 'EN', 'JA', 'Romaji'], 'まもなく次の曲が始まります'),
  ]) {
    testWidgets('the reveal card in $lang: titles in four languages, cover, theme line', (tester) async {
      final h = await enterGame(tester, language: lang);
      h.fetcher.responses[coverOf('aot')] = png;
      await prepare(tester, h, 0);
      await start(tester, h, 0);
      h.receive(revealFrame(0, winner: 'Aki'));
      await h.settle(tester);
      expect(textOf(tester, 'reveal-who'), who);
      for (final label in labels) {
        expect(find.text(label), findsOneWidget);
      }
      // The KO line is the reveal's own (Korean) title, not the raw source.
      expect(textOf(tester, 'title-ko'), '진격의 거인');
      expect(textOf(tester, 'title-en'), 'Attack on Titan');
      expect(textOf(tester, 'title-ja'), '進撃の巨人');
      expect(textOf(tester, 'title-romaji'), 'Shingeki no Kyojin');
      expect(
        tester.widget<Text>(find.byKey(const ValueKey('theme-line'))).textSpan!.toPlainText(),
        'OP1 · Guren no Yumiya ${String.fromCharCode(0x2014)} Linked Horizon',
      );
      expect(find.text(next), findsOneWidget);
      expect(find.byKey(const ValueKey('reveal-cover')), findsOneWidget);
      expect(h.fetcher.count(coverOf('aot')), 1);
      await h.tearDown(tester);
    });
  }

  testWidgets('no cover is fetched before the reveal', (tester) async {
    final h = await enterGame(tester);
    await prepare(tester, h, 0);
    await start(tester, h, 0);
    expect(h.fetcher.requests.where((u) => u.startsWith('/cover/')), isEmpty);
    expect(find.byKey(const ValueKey('reveal-cover')), findsNothing);
    await h.tearDown(tester);
  });

  testWidgets('나가기 asks first: 취소 stays, 나가기 leaves', (tester) async {
    final h = await enterGame(tester);
    await prepare(tester, h, 0);
    await tester.tap(find.byKey(const ValueKey('game-leave')));
    await h.settle(tester);
    expect(find.text('게임을 나갈까요?'), findsOneWidget);
    await tester.tap(find.byKey(const ValueKey('leave-cancel')));
    await h.settle(tester);
    expect(find.text('게임을 나갈까요?'), findsNothing);
    expect(h.sent.whereType<LeaveRequest>(), isEmpty);
    expect(h.controller.screen, Screen.game);

    // Escape cancels too.
    await tester.tap(find.byKey(const ValueKey('game-leave')));
    await h.settle(tester);
    await tester.sendKeyEvent(LogicalKeyboardKey.escape);
    await h.settle(tester);
    expect(find.text('게임을 나갈까요?'), findsNothing);

    await tester.tap(find.byKey(const ValueKey('game-leave')));
    await h.settle(tester);
    final socket = h.socket;
    await tester.tap(find.byKey(const ValueKey('leave-confirm')));
    await h.settle(tester);
    expect(socket.sentMessages.whereType<LeaveRequest>(), hasLength(1));
    expect(h.controller.screen, Screen.start);
    await h.tearDown(tester);
  });

  testWidgets('game over: the final ranking, then 대기실로 once the room is back in the lobby', (tester) async {
    final h = await enterGame(tester);
    await prepare(tester, h, 0, total: 1);
    await start(tester, h, 0);
    h.receive(revealFrame(0, winner: 'Aki'));
    await h.settle(tester);
    expect(find.text('잠시 후 최종 순위를 보여 줘요'), findsOneWidget);
    h.receive({
      'type': 'gameOver',
      'ranking': [
        {'rank': 1, 'name': 'Aki', 'score': 10},
        {'rank': 2, 'name': 'Mina', 'score': 5},
      ],
    });
    await h.settle(tester);
    expect(h.controller.screen, Screen.over);
    expect(find.text('1위'), findsOneWidget);
    expect(find.text('10점'), findsOneWidget);
    expect(find.text('5점 (나)'), findsOneWidget);
    expect(textOf(tester, 'final-score'), '최종 점수: 5점');
    // The last card stays under the ranking; no 나가기, no chat form.
    expect(find.byKey(const ValueKey('reveal')), findsOneWidget);
    expect(find.byKey(const ValueKey('game-leave')), findsNothing);
    expect(find.byKey(const ValueKey('chat-input')), findsNothing);
    expect(logTexts(tester).last, '게임 종료');
    expect(find.byKey(const ValueKey('to-lobby')), findsNothing);

    h.receive({
      'type': 'roster',
      'phase': 'lobby',
      'players': [player('Mina', host: true), player('Aki')],
    });
    await h.settle(tester);
    await tapVisible(tester, find.byKey(const ValueKey('to-lobby')));
    await h.settle(tester);
    expect(h.controller.screen, Screen.lobby);
    expect(find.byKey(const ValueKey('ticket-code')), findsOneWidget);
    expect(logTexts(tester), ['대기실로 돌아왔어요']);
    await h.tearDown(tester);
  });

  testWidgets('an abandoned game goes back to the lobby with its notice', (tester) async {
    final h = await enterGame(tester);
    await prepare(tester, h, 0);
    h.receive({
      'type': 'roster',
      'phase': 'lobby',
      'players': [player('Mina', host: true)],
    });
    await h.settle(tester);
    expect(h.controller.screen, Screen.lobby);
    expect(find.text('게임이 중단되어 대기실로 돌아왔습니다.'), findsOneWidget);
    await h.tearDown(tester);
  });

  for (final lang in ['ko', 'en', 'ja']) {
    testWidgets('fits 360 px in $lang: 8 tiles, the reveal, the final ranking', (tester) async {
      final h = await enterGame(tester, language: lang, options: {'answerMode': 'choice', 'gameMode': 'ranked'});
      tester.view.physicalSize = const Size(360, 740) * 3;
      await h.settle(tester);
      await prepare(tester, h, 0, total: 1, choices: [for (var i = 0; i < 8; i++) 'c$i']);
      await start(tester, h, 0);
      h.receive({'type': 'pickState', 'index': 0, 'picked': 0, 'total': 2});
      h.receive({'type': 'skipState', 'index': 0, 'votes': 1, 'needed': 2});
      await h.settle(tester);
      expect(tester.takeException(), isNull);
      h.receive(revealFrame(0, correctChoice: 3, ranking: [
        {'name': 'Aki', 'rank': 1, 'points': 3},
      ]));
      await h.settle(tester);
      expect(tester.takeException(), isNull);
      h.receive({
        'type': 'gameOver',
        'ranking': [
          {'rank': 1, 'name': 'Aki', 'score': 3},
          {'rank': 2, 'name': 'Mina', 'score': 0},
        ],
      });
      await h.settle(tester);
      expect(tester.takeException(), isNull);
      expect(h.controller.game!.view, GameView.over);
      await h.tearDown(tester);
    });
  }
}

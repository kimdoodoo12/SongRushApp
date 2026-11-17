import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';
import 'package:songrush_core/songrush_core.dart';
import 'package:songrush_mobile/app/app_controller.dart';
import 'package:songrush_mobile/store/playback_settings_store.dart';
import 'package:songrush_mobile/ui/offset_dialog.dart';

import 'game_screen_test.dart' show audio, buttonText, enterGame, prepare, revealFrame, start, textOf;
import 'support/harness.dart';

// Cycle 150: the reconnecting line, the disconnected and replaced screens,
// the reload rejoin, the return from the background, the sound prompt, the
// wake lock and the manual sound offset, on the core's fakes.

const String reconnecting = '연결이 끊겨 다시 연결하는 중...';
const String disconnected = '서버와 연결이 끊어졌습니다.';
const String replaced = '다른 창에서 접속해 이 연결이 끊겼습니다.';
const RejoinEntry stored = RejoinEntry(token: 'tok-1', code: 'K7XQM');

List<Map<String, Object?>> two() => [player('Mina', host: true), player('Aki')];

/// Mina's lobby with a rejoin token.
Future<Harness> enterLobby(WidgetTester tester, {Map<String, String> prefs = const {}}) async {
  final h = Harness(prefs: {nicknameKey: 'Mina', ...prefs});
  await h.pumpApp(tester);
  h.controller.openCreateDialog();
  await h.settle(tester);
  await tester.tap(find.byKey(const ValueKey('nick-confirm')));
  await h.settle(tester);
  await h.admit(tester, players: two());
  h.receive({'type': 'token', 'token': 'tok-1'});
  await h.settle(tester);
  return h;
}

/// A playing snapshot of song 0 (of 3), started [agoMs] before the app's
/// now, optionally inside its reveal window.
Map<String, Object?> playingSnapshot(Harness h, {int agoMs = 20000, bool revealed = false}) {
  final startAt = h.nowMs - agoMs;
  return {
    'type': 'snapshot',
    'phase': 'playing',
    'players': two(),
    'song': {
      'index': 0,
      'total': 3,
      'audioUrl': audio(0),
      'startAt': startAt,
      'deadlineAt': startAt + 90000,
      if (revealed) 'reveal': revealFrame(0, winner: 'Aki', playUntil: startAt + 60000),
    },
    'scores': [
      {'name': 'Mina', 'score': 3},
      {'name': 'Aki', 'score': 0},
    ],
  };
}

/// A reload into the stored room, answered with joined and [snapshot].
Future<Harness> reloadInto(WidgetTester tester, Map<String, Object?> Function(Harness h) snapshot,
    {int failLoads = 0}) async {
  final h = Harness(stored: stored, reloaded: true);
  h.fetcher.responses[audio(0)] = Uint8List(8);
  h.players.failLoads = failLoads;
  await h.pumpApp(tester);
  h.receive({'type': 'joined', 'code': 'K7XQM', 'name': 'Mina'});
  h.receive(snapshot(h));
  await h.settle(tester);
  h.answerPings();
  await h.settle(tester);
  return h;
}

/// The page goes hidden, as the engine reports it (through inactive).
void hide(WidgetTester tester) {
  tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.inactive);
  tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.hidden);
}

/// The page is shown again (through inactive, where onShow runs).
void show(WidgetTester tester) {
  tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.inactive);
  tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
}

double playOffset(Harness h) => h.players.last.last('play')!.args['offset']! as double;

/// The sound offset setting over the app's screen while the controller's
/// offsetOpen is set, as app.dart showed it before cycle 152 took it out of
/// the UI. Remove and dispose the entry before the harness's tearDown.
OverlayEntry showOffsetSetting(WidgetTester tester, Harness h) => overApp(
      tester,
      (_) => ListenableBuilder(
        listenable: h.controller,
        builder: (_, _) => h.controller.offsetOpen ? SoundOffsetDialog(controller: h.controller) : const SizedBox.shrink(),
      ),
    );

void main() {
  group('reconnect UI', () {
    testWidgets('the lobby shows the reconnecting line until the rejoin is back', (tester) async {
      final h = await enterLobby(tester);
      h.socket.drop(1006);
      await h.settle(tester);
      expect(find.text(reconnecting), findsOneWidget);
      expect(h.controller.screen, Screen.lobby);

      await h.advance(tester, const Duration(milliseconds: 500));
      expect(h.sockets.sockets, hasLength(2));
      expect(h.socket.sentJson.first, {'type': 'rejoin', 'token': 'tok-1'});
      h.receive({'type': 'joined', 'code': 'K7XQM', 'name': 'Mina'});
      h.receive({'type': 'snapshot', 'phase': 'lobby', 'players': two(), 'scores': []});
      await h.settle(tester);
      expect(find.text(reconnecting), findsNothing);
      expect(h.controller.screen, Screen.lobby);
      await h.tearDown(tester);
    });

    testWidgets('a drop mid-song: the line, then the rejoin snapshot starts the song late', (tester) async {
      final h = await enterGame(tester);
      h.receive({'type': 'token', 'token': 'tok-1'});
      await prepare(tester, h, 0);
      final s = await start(tester, h, 0);
      final first = h.players.last;
      expect(first.playing, isNotNull);

      h.socket.drop(1006);
      await h.settle(tester);
      expect(find.text(reconnecting), findsOneWidget);
      expect(first.playing, isNull, reason: 'the sound stops on the drop');
      expect(h.controller.screen, Screen.game);

      await h.advance(tester, const Duration(milliseconds: 500));
      h.receive({'type': 'joined', 'code': 'K7XQM', 'name': 'Mina'});
      h.receive({
        'type': 'snapshot',
        'phase': 'playing',
        'players': two(),
        'song': {'index': 0, 'total': 3, 'audioUrl': audio(0), 'startAt': s.startAt, 'deadlineAt': s.deadlineAt},
        'scores': [],
      });
      await h.settle(tester);
      h.answerPings();
      await h.settle(tester);
      expect(find.text(reconnecting), findsNothing);
      expect(textOf(tester, 'round'), '1 / 3 번째 곡');
      expect(h.players.last, isNot(same(first)));
      expect(h.players.last.playing, isNotNull);
      expect(playOffset(h), greaterThan(0.5), reason: 'joined where the song is');
      await h.tearDown(tester);
    });

    testWidgets('no rejoin within 30 s: the disconnected screen, then 처음으로', (tester) async {
      final h = await enterGame(tester);
      h.receive({'type': 'token', 'token': 'tok-1'});
      await prepare(tester, h, 0);
      h.socket.drop(1006);
      await h.settle(tester);
      await h.advance(tester, const Duration(seconds: 31), step: const Duration(seconds: 1));
      await h.settle(tester);
      expect(h.controller.screen, Screen.disconnected);
      expect(textOf(tester, 'lost'), disconnected);
      expect(find.text('K7XQM'), findsOneWidget);
      expect(buttonText(tester, 'reset'), '처음으로');
      expect(h.tokens.read(), isNull);

      await tester.tap(find.byKey(const ValueKey('reset')));
      await h.settle(tester);
      expect(h.controller.screen, Screen.start);
      expect(find.byKey(const ValueKey('create')), findsOneWidget);
      expect(find.text(disconnected), findsNothing);
      await h.tearDown(tester);
    });

    testWidgets('a 4001 close shows the replaced text and never reconnects', (tester) async {
      final h = await enterLobby(tester);
      h.socket.drop(replacedCloseCode);
      await h.settle(tester);
      expect(h.controller.screen, Screen.disconnected);
      expect(textOf(tester, 'lost'), replaced);
      await h.advance(tester, const Duration(seconds: 5), step: const Duration(seconds: 1));
      expect(h.sockets.sockets, hasLength(1));
      await h.tearDown(tester);
    });

    testWidgets('the final score stays on the disconnected screen after a game', (tester) async {
      final h = await enterGame(tester);
      h.receive({'type': 'token', 'token': 'tok-1'});
      await prepare(tester, h, 0);
      h.receive({
        'type': 'gameOver',
        'ranking': [
          {'rank': 1, 'name': 'Aki', 'score': 10},
          {'rank': 2, 'name': 'Mina', 'score': 5},
        ],
      });
      await h.settle(tester);
      h.socket.drop(replacedCloseCode);
      await h.settle(tester);
      expect(find.text('최종 점수: 5점'), findsOneWidget);
      expect(textOf(tester, 'lost'), replaced);
      await h.tearDown(tester);
    });

    testWidgets('room-closed goes to the start screen with its text, as the web', (tester) async {
      final h = await enterLobby(tester);
      h.receive({'type': 'error', 'code': 'room-closed'});
      await h.settle(tester);
      expect(h.controller.screen, Screen.start);
      expect(h.controller.startError?.error?.code, 'room-closed');
      await h.tearDown(tester);
    });
  });

  group('reload rejoin', () {
    testWidgets('a reload resumes the stored room: the line, then the game mid-song', (tester) async {
      final h = Harness(stored: stored, reloaded: true);
      h.fetcher.responses[audio(0)] = Uint8List(8);
      await h.pumpApp(tester);
      expect(h.controller.screen, Screen.rejoining);
      expect(find.text(reconnecting), findsOneWidget);
      expect(find.text('K7XQM'), findsOneWidget);
      expect(h.socket.sentJson.first, {'type': 'rejoin', 'token': 'tok-1'});

      h.receive({'type': 'joined', 'code': 'K7XQM', 'name': 'Mina'});
      h.receive(playingSnapshot(h));
      await h.settle(tester);
      h.answerPings();
      await h.settle(tester);
      expect(h.controller.screen, Screen.game);
      expect(find.text(reconnecting), findsNothing);
      expect(textOf(tester, 'round'), '1 / 3 번째 곡');
      expect(h.controller.game!.score, 3);
      expect(playOffset(h), closeTo(20, 1), reason: 'the late start from the snapshot');
      // No tap since the reload: the sound prompt shows.
      expect(buttonText(tester, 'unlock-sound'), '탭하여 소리 켜기');
      await h.tearDown(tester);
    });

    testWidgets('a reload into a reveal window shows the card and plays the window', (tester) async {
      final h = await reloadInto(tester, (h) => playingSnapshot(h, revealed: true));
      expect(find.byKey(const ValueKey('reveal')), findsOneWidget);
      expect(h.players.last.playing, isNotNull);
      await h.tearDown(tester);
    });

    testWidgets('a reload into the lobby shows the lobby', (tester) async {
      final h = await reloadInto(tester, (h) => {'type': 'snapshot', 'phase': 'lobby', 'players': two(), 'scores': []});
      expect(h.controller.screen, Screen.lobby);
      expect(find.byKey(const ValueKey('unlock-sound')), findsNothing);
      await h.tearDown(tester);
    });

    testWidgets('rejoin-failed goes to the start screen with its text', (tester) async {
      final h = Harness(stored: stored, reloaded: true);
      await h.pumpApp(tester);
      h.receive({'type': 'error', 'code': 'rejoin-failed'});
      await h.settle(tester);
      expect(h.controller.screen, Screen.start);
      expect(find.text('다시 연결하지 못했습니다. 방에 다시 참가해 주세요.'), findsOneWidget);
      expect(h.tokens.read(), isNull);
      await h.tearDown(tester);
    });

    testWidgets('a start that is not a reload drops the entry; a share link opens its dialog', (tester) async {
      final h = Harness(stored: stored, initialCode: 'AB12C');
      await h.pumpApp(tester);
      expect(h.controller.screen, Screen.start);
      expect(h.tokens.read(), isNull);
      expect(h.sockets.sockets, isEmpty);
      expect(find.byKey(const ValueKey('nickname')), findsOneWidget);
      await h.tearDown(tester);
    });
  });

  group('background and return', () {
    testWidgets('back in view while reconnecting: the next attempt opens at once', (tester) async {
      final h = await enterLobby(tester);
      hide(tester);
      h.sockets.autoOpen = false;
      h.socket.drop(1006);
      await h.settle(tester);
      await h.advance(tester, const Duration(milliseconds: 500));
      expect(h.sockets.sockets, hasLength(2));
      h.socket.failConnect();
      await h.settle(tester);
      // Waiting 1 s for the next attempt; the return opens it now.
      show(tester);
      await tester.pump();
      expect(h.sockets.sockets, hasLength(3));
      h.socket.open();
      await h.settle(tester);
      expect(h.socket.sentJson.first, {'type': 'rejoin', 'token': 'tok-1'});
      await h.tearDown(tester);
    });

    testWidgets('back in view with the socket open: nothing changes', (tester) async {
      final h = await enterGame(tester);
      await prepare(tester, h, 0);
      await start(tester, h, 0);
      final playing = h.players.last;
      hide(tester);
      await h.settle(tester);
      show(tester);
      await h.settle(tester);
      expect(h.sockets.sockets, hasLength(1));
      expect(h.players.last, same(playing));
      expect(playing.callNames.where((c) => c == 'play'), hasLength(1));
      expect(h.controller.screen, Screen.game);
      await h.tearDown(tester);
    });
  });

  group('sound unlock', () {
    testWidgets('one tap unlocks and the song that failed to load joins where it is', (tester) async {
      final h = await reloadInto(tester, (h) => playingSnapshot(h), failLoads: 2);
      final failed = h.players.last;
      expect(failed.playing, isNull);
      expect(find.byKey(const ValueKey('unlock-sound')), findsOneWidget);
      expect(h.unlocks, 0);

      await h.advance(tester, const Duration(seconds: 2));
      h.players.failLoads = 0;
      await tester.tap(find.byKey(const ValueKey('unlock-sound')));
      await h.settle(tester);
      expect(h.unlocks, 1);
      expect(find.byKey(const ValueKey('unlock-sound')), findsNothing);
      expect(h.players.last, isNot(same(failed)));
      expect(h.players.last.playing, isNotNull);
      expect(playOffset(h), closeTo(22, 1));
      expect(h.sent.whereType<ReadyRequest>(), isEmpty, reason: 'a rejoined song sends no ready');
      await h.tearDown(tester);
    });

    testWidgets('a game entered through 확인 never shows the prompt', (tester) async {
      final h = await enterGame(tester);
      await prepare(tester, h, 0);
      expect(find.byKey(const ValueKey('unlock-sound')), findsNothing);
      await h.tearDown(tester);
    });
  });

  group('wake lock', () {
    testWidgets('on during a game, off at game over, asked again on return', (tester) async {
      final h = await enterGame(tester);
      expect(h.awake, isEmpty, reason: 'not before the first song');
      await prepare(tester, h, 0);
      expect(h.awake, [true]);
      hide(tester);
      show(tester);
      await h.settle(tester);
      expect(h.awake, [true, true]);
      h.receive({
        'type': 'gameOver',
        'ranking': [
          {'rank': 1, 'name': 'Mina', 'score': 5},
        ],
      });
      await h.settle(tester);
      expect(h.awake, [true, true, false]);
      hide(tester);
      show(tester);
      await h.settle(tester);
      expect(h.awake, [true, true, false]);
      await h.tearDown(tester);
    });

    testWidgets('off when leaving a game', (tester) async {
      final h = await enterGame(tester);
      await prepare(tester, h, 0);
      h.controller.leave();
      await h.settle(tester);
      expect(h.awake, [true, false]);
      await h.tearDown(tester);
    });
  });

  group('manual sound offset', () {
    for (final (lang, name) in [('ko', '싱크'), ('en', 'Sync'), ('ja', '同期')]) {
      testWidgets('the lobby header has no offset button in $lang (cycle 152)', (tester) async {
        final h = await enterLobby(tester, prefs: {'songrush.lang': lang});
        expect(h.controller.screen, Screen.lobby);
        expect(find.byKey(const ValueKey('offset-open')), findsNothing);
        expect(find.byType(OffsetButton), findsNothing);
        expect(find.byTooltip(name), findsNothing);
        expect(find.bySemanticsLabel(name), findsNothing);
        expect(find.byIcon(Icons.tune), findsNothing);
        await h.tearDown(tester);
      });
    }

    testWidgets('the setting: steps of 10, stored, given to the round controller', (tester) async {
      final h = await enterLobby(tester);
      final entry = showOffsetSetting(tester, h);
      expect(find.byKey(const ValueKey('offset-dialog')), findsNothing, reason: 'closed until opened');
      h.controller.openOffset();
      await h.settle(tester);
      expect(find.byKey(const ValueKey('offset-dialog')), findsOneWidget);
      expect(textOf(tester, 'offset-value'), '0ms');
      for (var i = 0; i < 3; i++) {
        await tester.tap(find.byKey(const ValueKey('offset-later')));
        await h.settle(tester);
      }
      expect(textOf(tester, 'offset-value'), '+30ms');
      expect(h.prefs.read(manualOffsetKey), '30');
      expect(h.controller.rounds!.manualOffsetMs, 30);
      for (var i = 0; i < 5; i++) {
        await tester.tap(find.byKey(const ValueKey('offset-earlier')));
        await h.settle(tester);
      }
      expect(textOf(tester, 'offset-value'), '${String.fromCharCode(0x2212)}20ms');
      expect(h.prefs.read(manualOffsetKey), '-20');
      await tester.tap(find.byKey(const ValueKey('offset-reset')));
      await h.settle(tester);
      expect(textOf(tester, 'offset-value'), '0ms');
      await tester.tap(find.byKey(const ValueKey('offset-close')));
      await h.settle(tester);
      expect(find.byKey(const ValueKey('offset-dialog')), findsNothing);
      expect(h.controller.offsetOpen, isFalse);
      expect(h.controller.screen, Screen.lobby);
      entry
        ..remove()
        ..dispose();
      await h.tearDown(tester);
    });

    testWidgets('the slider covers -500..+500 and stops at its ends', (tester) async {
      final h = await enterLobby(tester, prefs: {manualOffsetKey: '490'});
      expect(h.controller.manualOffsetMs, 490);
      final entry = showOffsetSetting(tester, h);
      h.controller.openOffset();
      await h.settle(tester);
      final slider = tester.widget<Slider>(find.byKey(const ValueKey('offset-slider')));
      expect((slider.min, slider.max, slider.divisions, slider.value), (-500.0, 500.0, 100, 490.0));
      await tester.tap(find.byKey(const ValueKey('offset-later')));
      await h.settle(tester);
      expect(textOf(tester, 'offset-value'), '+500ms');
      expect(isEnabled(tester, const ValueKey('offset-later')), isFalse);
      slider.onChanged!(-512.4);
      await h.settle(tester);
      expect(h.controller.manualOffsetMs, -500);
      expect(h.prefs.read(manualOffsetKey), '-500');
      entry
        ..remove()
        ..dispose();
      await h.tearDown(tester);
    });

    testWidgets('a stored offset still applies with no button: the next song plays with it', (tester) async {
      final h = await enterGame(tester, language: 'en', prefs: {manualOffsetKey: '100'});
      await prepare(tester, h, 0);
      expect(h.controller.screen, Screen.game);
      expect(find.byTooltip('Sync'), findsNothing);
      expect(h.controller.manualOffsetMs, 100);

      final startAt = h.nowMs + 500;
      h.receive({'type': 'start', 'index': 0, 'startAt': startAt, 'deadlineAt': startAt + 90000, 'serverTime': h.nowMs});
      await h.settle(tester);
      // 0.5 s ahead on the player's clock, 0.1 s later for the offset.
      expect(h.players.last.last('play')!.args['when']! as double, closeTo(0.6, 1e-6));
      await h.tearDown(tester);
    });

    testWidgets('the setting over the game, in English; the next song plays with it', (tester) async {
      final h = await enterGame(tester, language: 'en');
      await prepare(tester, h, 0);
      expect(h.controller.screen, Screen.game);
      expect(find.byTooltip('Sync'), findsNothing);
      expect(find.byKey(const ValueKey('offset-open')), findsNothing);
      final entry = showOffsetSetting(tester, h);
      h.controller.openOffset();
      await h.settle(tester);
      expect(find.text('Sound sync'), findsOneWidget);
      for (var i = 0; i < 10; i++) {
        await tester.tap(find.byKey(const ValueKey('offset-later')));
        await h.settle(tester);
      }
      expect(textOf(tester, 'offset-value'), '+100ms');
      await tester.sendKeyEvent(LogicalKeyboardKey.escape);
      await h.settle(tester);
      expect(find.byKey(const ValueKey('offset-dialog')), findsNothing);

      final startAt = h.nowMs + 500;
      h.receive({'type': 'start', 'index': 0, 'startAt': startAt, 'deadlineAt': startAt + 90000, 'serverTime': h.nowMs});
      await h.settle(tester);
      // 0.5 s ahead on the player's clock, 0.1 s later for the offset.
      expect(h.players.last.last('play')!.args['when']! as double, closeTo(0.6, 1e-6));
      entry
        ..remove()
        ..dispose();
      await h.tearDown(tester);
    });

    testWidgets('the setting in Japanese', (tester) async {
      final h = await enterLobby(tester, prefs: {'songrush.lang': 'ja'});
      final entry = showOffsetSetting(tester, h);
      h.controller.openOffset();
      await h.settle(tester);
      expect(find.text('音ズレ調整'), findsOneWidget);
      expect(buttonText(tester, 'offset-close'), '閉じる');
      entry
        ..remove()
        ..dispose();
      await h.tearDown(tester);
    });

    for (final (lang, name) in [('ko', '싱크'), ('en', 'Sync'), ('ja', '同期')]) {
      testWidgets('the kept offset button is an icon only in $lang; its name is in the tooltip and semantics',
          (tester) async {
        final h = await enterLobby(tester, prefs: {'songrush.lang': lang});
        final entry = overApp(tester, (_) => Center(child: OffsetButton(controller: h.controller)));
        await h.settle(tester);
        final button = find.byKey(const ValueKey('offset-open'));
        expect(find.descendant(of: button, matching: find.byType(Icon)), findsOneWidget);
        expect(find.descendant(of: button, matching: find.byType(Text)), findsNothing);
        expect(find.text(name), findsNothing, reason: 'no visible text');
        expect(find.byTooltip(name), findsOneWidget);
        expect(find.bySemanticsLabel(name), findsOneWidget);
        await tester.tap(button);
        await h.settle(tester);
        expect(h.controller.offsetOpen, isTrue);
        entry
          ..remove()
          ..dispose();
        await h.tearDown(tester);
      });
    }
  });
}

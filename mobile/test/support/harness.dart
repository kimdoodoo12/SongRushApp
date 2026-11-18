import 'package:flutter/foundation.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';
import 'package:songrush_core/songrush_core.dart';
import 'package:songrush_core/testing.dart';
import 'package:songrush_mobile/app.dart';
import 'package:songrush_mobile/app/app_controller.dart';
import 'package:songrush_mobile/store/prefs.dart';

// The app's widget tests drive the real AppController over the core's
// FakeGameSocket, with FakePlayer and FakeFetcher in place of SoLoud and
// HTTP: nothing here imports flutter_soloud (lib/main.dart does).

/// A phone screen: 390 x 844 logical pixels.
const Size phone = Size(390, 844);

class Harness {
  Harness({
    String initialCode = '',
    List<Locale> deviceLocales = const [Locale('ko')],
    Map<String, String>? prefs,
    RejoinEntry? stored,
    bool reloaded = false,
    bool canNavigate = false,
    this.keyboardInset,
  }) : prefs = MemoryPrefs(prefs) {
    if (stored != null) tokens.write(stored);
    controller = AppController(
      socketFactory: sockets.call,
      tokenStore: tokens,
      createPlayer: players.call,
      fetcher: fetcher,
      localPrefs: this.prefs,
      unlockSound: () async => unlocks++,
      keepAwake: awake.add,
      navigate: canNavigate ? navigations.add : null,
      exitApp: () => exits++,
      pageReloaded: reloaded,
      deviceLocales: deviceLocales,
      initialCode: initialCode,
      pageOrigin: 'https://songrush.test',
      warn: warnings.add,
      now: () => nowMs,
    );
  }

  final FakeSocketFactory sockets = FakeSocketFactory();
  final MemoryTokenStore tokens = MemoryTokenStore();
  final FakePlayerFactory players = FakePlayerFactory(FakeAudioClock(() => 0));
  final FakeFetcher fetcher = FakeFetcher();
  final MemoryPrefs prefs;

  /// The keyboard's height as the web build reads it from the browser
  /// (cycle 161), in place of Flutter's view inset; null keeps Flutter's.
  final ValueListenable<double>? keyboardInset;

  /// The app's clock (local epoch ms): fixed unless a test moves it, with
  /// [advance] for the game's time bar.
  int nowMs = 1000000;
  final List<String> warnings = [];
  int unlocks = 0;

  /// Every page navigation (the PC version link), in order; only with
  /// `canNavigate` (the web build).
  final List<String> navigations = [];

  /// How many times the app asked to leave the site (the start screen's
  /// exit confirm, cycle 164).
  int exits = 0;

  /// Every wake lock switch, in order.
  final List<bool> awake = [];
  late final AppController controller;

  FakeGameSocket get socket => sockets.last;

  /// What the client sent on the last socket, pings left out.
  List<ClientMessage> get sent => [
        for (final m in socket.sentMessages)
          if (m is! PingRequest) m,
      ];

  /// The last sent message of type [T].
  T lastSent<T extends ClientMessage>() => sent.whereType<T>().last;

  Future<void> pumpApp(WidgetTester tester) async {
    tester.view.physicalSize = phone * 3;
    tester.view.devicePixelRatio = 3;
    addTearDown(tester.view.reset);
    await tester.pumpWidget(SongRushApp(controller: controller, keyboardInset: keyboardInset));
    await settle(tester);
  }

  /// Lets the fake socket's microtasks, the streams and a few frames run.
  /// (pumpAndSettle never settles: the lobby's waiting dots repeat.)
  Future<void> settle(WidgetTester tester) async {
    for (var i = 0; i < 5; i++) {
      await tester.pump(const Duration(milliseconds: 50));
    }
  }

  void receive(Map<String, Object?> frame) => socket.receiveJson(frame);

  final Set<Object?> _pinged = {};

  /// Answers every ping on the last socket not answered yet, with the
  /// server clock [serverAheadMs] ahead of the app's (rtt 0).
  void answerPings({int serverAheadMs = 0}) {
    for (final m in socket.sentJson) {
      if (m['type'] != 'ping' || !_pinged.add((socket, m['id']))) continue;
      receive({'type': 'pong', 'id': m['id'], 'serverTime': nowMs + serverAheadMs});
    }
  }

  /// Moves the app's clock and the test's fake time together by [by], in
  /// steps of [step].
  Future<void> advance(WidgetTester tester, Duration by, {Duration step = const Duration(milliseconds: 250)}) async {
    var left = by;
    while (left > Duration.zero) {
      final d = left < step ? left : step;
      nowMs += d.inMilliseconds;
      await tester.pump(d);
      left -= d;
    }
  }

  /// The server's answer to a create or join: joined, then the roster.
  Future<void> admit(
    WidgetTester tester, {
    String code = 'K7XQM',
    String me = 'Mina',
    List<Map<String, Object?>>? players,
  }) async {
    receive({'type': 'joined', 'code': code, 'name': me});
    receive({
      'type': 'roster',
      'phase': 'lobby',
      'players': players ?? [player(me, host: true)],
    });
    await settle(tester);
  }

  /// Ends the session and the tree, so no timer outlives the test.
  Future<void> tearDown(WidgetTester tester) async {
    await tester.pumpWidget(const SizedBox());
    controller.dispose();
    await tester.pump(const Duration(seconds: 1));
  }
}

Map<String, Object?> player(String name, {bool host = false, bool connected = true}) =>
    {'name': name, 'score': 0, 'host': host, 'connected': connected};

/// The enabled state of a RoomButton (its TextButton) found by [key].
bool isEnabled(WidgetTester tester, Key key) {
  final button = tester.widget<TextButton>(find.descendant(of: find.byKey(key), matching: find.byType(TextButton)));
  return button.onPressed != null;
}

/// Puts [builder]'s widget over the app's screen, in the app's overlay (so
/// it has the app's localizations and theme). Remove and dispose the entry
/// before [Harness.tearDown].
OverlayEntry overApp(WidgetTester tester, WidgetBuilder builder) {
  final entry = OverlayEntry(builder: builder);
  tester.state<NavigatorState>(find.byType(Navigator)).overlay!.insert(entry);
  return entry;
}

/// Scrolls [finder] into view and taps it.
Future<void> tapVisible(WidgetTester tester, Finder finder) async {
  await tester.ensureVisible(finder);
  await tester.pump();
  await tester.tap(finder);
}

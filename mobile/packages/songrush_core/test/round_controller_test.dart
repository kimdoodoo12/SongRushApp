import 'dart:typed_data';

import 'package:fake_async/fake_async.dart';
import 'package:songrush_core/songrush_core.dart';
import 'package:songrush_core/testing.dart';
import 'package:test/test.dart';

const audio0 = '/audio/song0';
const audio1 = '/audio/song1';
const coverA = '/cover/a';
const coverB = '/cover/b';
const coverC = '/cover/c';
const coverD = '/cover/d';

final Uint8List song0Bytes = Uint8List.fromList([1, 2, 3]);
final Uint8List song1Bytes = Uint8List.fromList([4, 5, 6, 7]);

const noTitles = Titles(ko: [], en: [], ja: [], romaji: []);

RevealMessage reveal(int index, {int? playUntil, List<Choice>? nextChoices}) => RevealMessage(
      index: index,
      reveal: 'Title',
      titles: noTitles,
      song: null,
      artists: const [],
      themeType: 'OP',
      themeSlug: 'OP1',
      winner: null,
      coverUrl: null,
      playUntil: playUntil,
      nextChoices: nextChoices,
    );

List<Choice> choices(List<String> urls) => [for (final u in urls) Choice(coverUrl: u)];

/// A session on a fake socket with a round controller on fake players, in a
/// fake clock. The server clock is [serverOffsetMs] ahead of the local one;
/// the engine clock reads [engineBase] seconds at the start.
class Harness {
  Harness(this.async, {PlaybackSettingsStore? settings}) : settings = settings ?? MemoryPlaybackSettingsStore() {
    clock = FakeAudioClock(() => engineBase + async.elapsed.inMicroseconds / 1e6);
    clock.outputLatencySec = 0.1;
    players = FakePlayerFactory(clock);
    fetcher.responses[audio0] = song0Bytes;
    fetcher.responses[audio1] = song1Bytes;
    for (final c in [coverA, coverB, coverC, coverD]) {
      fetcher.responses[c] = Uint8List.fromList(c.codeUnits);
    }
  }

  final FakeAsync async;
  final FakeSocketFactory sockets = FakeSocketFactory();
  final MemoryTokenStore store = MemoryTokenStore();
  final FakeFetcher fetcher = FakeFetcher();
  final PlaybackSettingsStore settings;
  late final FakeAudioClock clock;
  late final FakePlayerFactory players;
  final List<RoundEvent> events = [];
  final List<String> warnings = [];
  late Session session;
  late RoundController controller;

  static const int clockBase = 1000000;
  static const int serverOffsetMs = 5000;
  static const double engineBase = 50;

  int now() => clockBase + async.elapsed.inMilliseconds;
  int serverNow() => now() + serverOffsetMs;
  double get engineNow => clock.nowSec;

  FakeGameSocket get socket => sockets.last;
  FakePlayer get player => players.last;

  void _attach() {
    controller = RoundController(
      session: session,
      createPlayer: players.call,
      fetcher: fetcher,
      settings: settings,
      now: now,
      warn: warnings.add,
    );
    controller.events.listen(events.add);
    async.flushMicrotasks();
  }

  /// Joins a room and syncs the clock (8 pongs).
  void joinRoom({bool sync = true}) {
    session = Session.join(
      socketFactory: sockets.call,
      tokenStore: store,
      code: 'K7XQM',
      nickname: 'Bob',
      now: now,
    );
    _attach();
    receive(const JoinedMessage(code: 'K7XQM', name: 'Bob'));
    if (sync) syncClock();
  }

  /// A resume: the first socket is a rejoin socket.
  void resumeRoom() {
    session = Session.resume(
      socketFactory: sockets.call,
      tokenStore: store,
      entry: const RejoinEntry(token: 'tok', code: 'K7XQM'),
      now: now,
    );
    _attach();
    receive(const JoinedMessage(code: 'K7XQM', name: 'Bob'));
  }

  void receive(ServerMessage msg) {
    socket.receiveMessage(msg);
    async.flushMicrotasks();
  }

  final Set<Object?> _answered = {};

  /// Answers every ping not yet answered with a pong at [serverOffsetMs]
  /// (+ [extraMs]), rtt 0.
  void answerPings({int extraMs = 0}) {
    for (final m in socket.sentJson) {
      if (m['type'] != 'ping' || _answered.contains(m['id'])) continue;
      _answered.add(m['id']);
      receive(PongMessage(id: m['id'], serverTime: serverNow() + extraMs));
    }
  }

  /// The join burst, answered as it goes out.
  void syncClock() {
    for (var i = 0; i < joinPings; i++) {
      answerPings();
      async.elapse(const Duration(milliseconds: pingSpacingMs));
    }
    answerPings();
  }

  List<int> get readies => [
        for (final m in socket.sentJson)
          if (m['type'] == 'ready') m['index'] as int,
      ];

  void prepare(int index, {String audioUrl = audio0, List<String>? covers, int? holdMs}) => receive(PrepareMessage(
        index: index,
        total: 10,
        audioUrl: audioUrl,
        choices: covers == null ? null : choices(covers),
        holdMs: holdMs,
      ));

  /// A start [inMs] from now on the server, 90 s long, sent with
  /// [serverTime] (default: the server's now).
  ({int startAt, int deadlineAt}) start(int index, {int inMs = 3000, int? serverTime}) {
    final startAt = serverNow() + inMs;
    final deadlineAt = startAt + 90000;
    receive(StartMessage(
      index: index,
      startAt: startAt,
      deadlineAt: deadlineAt,
      serverTime: serverTime ?? serverNow(),
    ));
    return (startAt: startAt, deadlineAt: deadlineAt);
  }

  void elapseMs(int ms) => async.elapse(Duration(milliseconds: ms));
}

Map<String, Object?> playArgs(FakePlayer p) => p.last('play')!.args;

void main() {
  group('prepare and ready', () {
    test('ready goes only after the audio and every cover have settled', () {
      fakeAsync((async) {
        final h = Harness(async)..joinRoom();
        h.fetcher.held.addAll([audio0, coverA, coverB]);
        h.prepare(0, covers: [coverA, coverB]);
        expect(h.fetcher.requests, containsAll([audio0, coverA, coverB]));
        expect(h.events, [const RoundEvent(RoundEventType.prepared, 0)]);

        h.fetcher.complete(audio0);
        async.flushMicrotasks();
        expect(h.player.loadedBytes.single, song0Bytes);
        expect(h.events.last, const RoundEvent(RoundEventType.loaded, 0));
        expect(h.readies, isEmpty, reason: 'covers still loading');

        h.fetcher.complete(coverA);
        async.flushMicrotasks();
        expect(h.readies, isEmpty);
        // A failed cover counts as settled.
        h.fetcher.fail(coverB);
        async.flushMicrotasks();
        expect(h.readies, [0]);
        expect(h.controller.cover(coverA), Uint8List.fromList(coverA.codeUnits));
        expect(h.controller.cover(coverB), isNull);
      });
    });

    test('covers first, audio last: ready follows the audio', () {
      fakeAsync((async) {
        final h = Harness(async)..joinRoom();
        h.fetcher.held.add(audio0);
        h.prepare(0, covers: [coverA, '/not-a-cover']);
        expect(h.fetcher.requests, isNot(contains('/not-a-cover')));
        expect(h.readies, isEmpty);
        h.fetcher.complete(audio0);
        async.flushMicrotasks();
        expect(h.readies, [0]);
      });
    });

    test('a load that fails twice still sends ready, after one retry', () {
      fakeAsync((async) {
        final h = Harness(async)..joinRoom();
        h.players.failLoads = 2;
        h.prepare(0);
        expect(h.fetcher.count(audio0), 2);
        expect(h.events.last, const RoundEvent(RoundEventType.loadFailed, 0));
        expect(h.readies, [0]);
        h.start(0);
        expect(h.player.callNames, isNot(contains('play')));
      });
    });

    test('reloadAudio loads a failed song again and joins it where it is now', () {
      fakeAsync((async) {
        final h = Harness(async)..joinRoom();
        h.players.failLoads = 2;
        h.prepare(0);
        h.start(0);
        h.elapseMs(13000);
        final failed = h.player;
        expect(failed.callNames, isNot(contains('play')));
        h.players.failLoads = 0;
        h.controller.reloadAudio();
        async.flushMicrotasks();
        expect(failed.disposed, isTrue);
        expect(h.player, isNot(same(failed)));
        expect(h.fetcher.count(audio0), 3);
        expect(h.events.last, const RoundEvent(RoundEventType.loaded, 0));
        final args = playArgs(h.player);
        expect(args['when'] as double, closeTo(h.engineNow, 1e-9));
        expect(args['offset'] as double, closeTo(10.1, 1e-6));
        expect(h.readies, [0], reason: 'no second ready');
        // Loaded now: a second call does nothing.
        h.controller.reloadAudio();
        async.flushMicrotasks();
        expect(h.fetcher.count(audio0), 3);
      });
    });

    test('reloadAudio does nothing once the song is revealed, or while the first load runs', () {
      fakeAsync((async) {
        final h = Harness(async)..joinRoom();
        h.fetcher.held.add(audio0);
        h.prepare(0);
        h.controller.reloadAudio();
        async.flushMicrotasks();
        expect(h.players.players, hasLength(1));
        h.fetcher.fail(audio0);
        async.flushMicrotasks();
        h.fetcher.fail(audio0);
        async.flushMicrotasks();
        h.start(0);
        h.receive(reveal(0));
        h.controller.reloadAudio();
        async.flushMicrotasks();
        expect(h.players.players, hasLength(1));
      });
    });

    test('one failed load is retried and the song loads', () {
      fakeAsync((async) {
        final h = Harness(async)..joinRoom();
        h.players.failLoads = 1;
        h.prepare(0);
        expect(h.fetcher.count(audio0), 2);
        expect(h.events.last, const RoundEvent(RoundEventType.loaded, 0));
        expect(h.readies, [0]);
      });
    });

    test('a reveal\'s nextChoices are fetched once and reused by the next prepare', () {
      fakeAsync((async) {
        final h = Harness(async)..joinRoom();
        h.prepare(0, covers: [coverA, coverB]);
        h.start(0);
        h.fetcher.held.add(coverD);
        h.receive(reveal(0, nextChoices: choices([coverC, coverD, coverA])));
        expect(h.fetcher.count(coverC), 1);
        expect(h.fetcher.count(coverD), 1);
        // coverA is already loaded: not fetched again.
        expect(h.fetcher.count(coverA), 1);

        h.fetcher.held.add(audio1);
        h.prepare(1, audioUrl: audio1, covers: [coverC, coverD, coverA]);
        expect(h.fetcher.count(coverC), 1);
        expect(h.fetcher.count(coverD), 1, reason: 'the loading one is waited for, not refetched');
        h.fetcher.complete(audio1);
        async.flushMicrotasks();
        expect(h.readies, [0], reason: 'coverD still loading');
        h.fetcher.complete(coverD);
        async.flushMicrotasks();
        expect(h.readies, [0, 1]);
        // The older song's covers go once the new song shows.
        expect(h.controller.cover(coverB), isNull);
        expect(h.controller.cover(coverC), isNotNull);
      });
    });

    test('a watcher (snapshot after a first join) loads but sends no ready', () {
      fakeAsync((async) {
        final h = Harness(async)..joinRoom();
        h.receive(const SnapshotMessage(
          phase: 'playing',
          players: [],
          song: SnapshotSong(index: 3, total: 10, audioUrl: audio0),
          scores: [],
        ));
        expect(h.player.loaded, isTrue);
        expect(h.readies, isEmpty);
      });
    });
  });

  group('start', () {
    test('plays at the server\'s moment minus the output latency', () {
      fakeAsync((async) {
        final h = Harness(async)..joinRoom();
        h.prepare(0);
        final engine = h.engineNow;
        h.start(0, inMs: 3000);
        final args = playArgs(h.player);
        expect(args['when'] as double, closeTo(engine + 3.0 - 0.1, 1e-9));
        expect(args['offset'], 0.0);
        expect(args['stopAt'] as double, closeTo(engine + 93.0 - 0.1, 1e-9));
        expect(h.events.last, const RoundEvent(RoundEventType.started, 0));
      });
    });

    test('a song loaded after its start seeks in', () {
      fakeAsync((async) {
        final h = Harness(async)..joinRoom();
        h.fetcher.held.add(audio0);
        h.prepare(0);
        h.start(0, inMs: 1000);
        h.elapseMs(1500);
        h.fetcher.complete(audio0);
        async.flushMicrotasks();
        final args = playArgs(h.player);
        expect(args['when'] as double, closeTo(h.engineNow, 1e-9));
        // 0.5 s into the song, plus the latency it needs to reach the speaker.
        expect(args['offset'] as double, closeTo(0.6, 1e-9));
      });
    });

    test('a start for another index or after the reveal is ignored', () {
      fakeAsync((async) {
        final h = Harness(async)..joinRoom();
        h.prepare(0);
        h.start(1);
        expect(h.player.callNames, isNot(contains('play')));
        h.receive(reveal(0));
        h.start(0);
        expect(h.player.callNames, isNot(contains('play')));
      });
    });

    test('a provisional start is moved earlier by stop and reschedule', () {
      fakeAsync((async) {
        final h = Harness(async)..joinRoom();
        h.prepare(0);
        final engine = h.engineNow;
        // The start frame arrived 100 ms late: its receipt offset is 4900.
        h.start(0, inMs: 3000, serverTime: h.serverNow() - 100);
        expect(playArgs(h.player)['when'] as double, closeTo(engine + 3.1 - 0.1, 1e-9));
        expect(h.session.state.clock, isNull, reason: 'the drift cleared the samples');

        // A fresh pong says the offset is at least 5000.
        h.answerPings();
        final p = h.player;
        expect(p.callNames.where((n) => n == 'play').length, 2);
        expect(p.callNames.sublist(p.callNames.length - 2), ['stop', 'play']);
        expect(playArgs(p)['when'] as double, closeTo(engine + 3.0 - 0.1, 1e-9));

        // A bound within 20 ms of the offset in use moves nothing.
        h.elapseMs(400);
        h.answerPings(extraMs: 15);
        expect(p.callNames.where((n) => n == 'play').length, 2);
      });
    });
  });

  group('reveal', () {
    test('a reveal with playUntil keeps the music to its window end', () {
      fakeAsync((async) {
        final h = Harness(async)..joinRoom();
        h.prepare(0);
        h.start(0, inMs: 0);
        h.elapseMs(10000);
        final engine = h.engineNow;
        h.receive(reveal(0, playUntil: h.serverNow() + 2000));
        expect(h.events.last, const RoundEvent(RoundEventType.revealed, 0, window: true));
        final p = h.player;
        expect(p.last('setStopAt')!.args['stopAt'] as double, closeTo(engine + 2.0 - 0.1, 1e-9));
        expect(p.disposed, isFalse);
        h.elapseMs(1999);
        expect(p.disposed, isFalse);
        h.elapseMs(1);
        expect(p.callNames.sublist(p.callNames.length - 2), ['stop', 'dispose']);
        expect(h.events.last, const RoundEvent(RoundEventType.windowEnd, 0));
      });
    });

    test('the window ends at deadlineAt when playUntil is later', () {
      fakeAsync((async) {
        final h = Harness(async)..joinRoom();
        h.prepare(0);
        final s = h.start(0, inMs: 0);
        h.elapseMs(89000);
        h.receive(reveal(0, playUntil: s.deadlineAt + 5000));
        h.elapseMs(999);
        expect(h.player.disposed, isFalse);
        h.elapseMs(1);
        expect(h.player.disposed, isTrue);
      });
    });

    test('a reveal without playUntil stops the music at once', () {
      fakeAsync((async) {
        final h = Harness(async)..joinRoom();
        h.prepare(0);
        h.start(0, inMs: 0);
        h.elapseMs(5000);
        h.receive(reveal(0));
        expect(h.events.last, const RoundEvent(RoundEventType.revealed, 0));
        expect(h.player.callNames.sublist(h.player.callNames.length - 2), ['stop', 'dispose']);
      });
    });
  });

  group('holdMs', () {
    test('a held prepare keeps the last song playing until the hold ends', () {
      fakeAsync((async) {
        final h = Harness(async)..joinRoom();
        h.prepare(0);
        h.start(0, inMs: 0);
        h.elapseMs(10000);
        h.receive(reveal(0, playUntil: h.serverNow() + 20000));
        final old = h.player;

        h.prepare(1, audioUrl: audio1, holdMs: 5000);
        expect(h.controller.held, isTrue);
        expect(h.events.last, const RoundEvent(RoundEventType.held, 1));
        expect(old.callNames, isNot(contains('stop')));
        // The next song loads and is ready during the hold.
        expect(h.readies, [0, 1]);
        expect(h.events.where((e) => e.index == 1).map((e) => e.type), [RoundEventType.held]);

        h.elapseMs(5000);
        expect(old.disposed, isTrue);
        expect(h.controller.held, isFalse);
        expect(h.events.where((e) => e.index == 1).map((e) => e.type),
            [RoundEventType.held, RoundEventType.prepared, RoundEventType.loaded]);
      });
    });

    test('the next start ends the hold early', () {
      fakeAsync((async) {
        final h = Harness(async)..joinRoom();
        h.prepare(0);
        h.start(0, inMs: 0);
        h.elapseMs(10000);
        h.receive(reveal(0, playUntil: h.serverNow() + 20000));
        final old = h.player;
        h.prepare(1, audioUrl: audio1, holdMs: 5000);
        h.elapseMs(1000);
        h.start(1, inMs: 500);
        expect(old.disposed, isTrue);
        expect(h.controller.held, isFalse);
        expect(h.player.callNames, contains('play'));
      });
    });

    test('holdMs without a reveal on screen is not a hold; the old sound stops', () {
      fakeAsync((async) {
        final h = Harness(async)..joinRoom();
        h.prepare(0);
        h.start(0, inMs: 0);
        final old = h.player;
        h.prepare(1, audioUrl: audio1, holdMs: 5000);
        expect(h.controller.held, isFalse);
        expect(old.disposed, isTrue);
        expect(h.events.last.type, RoundEventType.loaded);
      });
    });
  });

  group('snapshot', () {
    test('a song already playing starts late at the first clock estimate', () {
      fakeAsync((async) {
        final h = Harness(async)..joinRoom(sync: false);
        final startAt = h.serverNow() - 10000;
        h.receive(SnapshotMessage(
          phase: 'playing',
          players: const [],
          song: SnapshotSong(index: 2, total: 10, audioUrl: audio0, startAt: startAt, deadlineAt: startAt + 90000),
          scores: const [],
        ));
        expect(h.player.loaded, isTrue);
        expect(h.player.callNames, isNot(contains('play')), reason: 'no clock estimate yet');
        h.answerPings();
        final args = playArgs(h.player);
        expect(args['when'] as double, closeTo(h.engineNow, 1e-9));
        expect(args['offset'] as double, closeTo(10.1, 1e-9));
        expect(h.readies, isEmpty);
      });
    });

    test('a rejoin into PREPARE loads and sends ready', () {
      fakeAsync((async) {
        final h = Harness(async)..resumeRoom();
        h.receive(const SnapshotMessage(
          phase: 'playing',
          players: [],
          song: SnapshotSong(index: 4, total: 10, audioUrl: audio1),
          scores: [],
        ));
        expect(h.readies, [4]);
      });
    });

    test('a rejoin into a reveal window plays only while the window is open', () {
      fakeAsync((async) {
        final h = Harness(async)..resumeRoom();
        final startAt = h.serverNow() - 30000;
        h.receive(SnapshotMessage(
          phase: 'playing',
          players: const [],
          song: SnapshotSong(
            index: 4,
            total: 10,
            audioUrl: audio1,
            startAt: startAt,
            deadlineAt: startAt + 90000,
            reveal: reveal(4, playUntil: h.serverNow() + 3000),
          ),
          scores: const [],
        ));
        expect(h.events.first, const RoundEvent(RoundEventType.revealed, 4));
        expect(h.events.map((e) => e.type), isNot(contains(RoundEventType.started)));
        h.answerPings();
        expect(h.events.map((e) => e.type), contains(RoundEventType.windowOpen));
        final p = h.player;
        expect(playArgs(p)['offset'] as double, closeTo(30.1, 1e-9));
        expect(playArgs(p)['stopAt'] as double, closeTo(h.engineNow + 3.0 - 0.1, 1e-9));
        expect(h.readies, isEmpty);
        h.elapseMs(3000);
        expect(p.disposed, isTrue);
        expect(h.events.last, const RoundEvent(RoundEventType.windowEnd, 4));
      });
    });
  });

  group('drift check', () {
    // Plays song 0 from 2 s ago and returns its player.
    FakePlayer playing(Harness h) {
      h.prepare(0);
      h.start(0, inMs: -2000);
      return h.player;
    }

    test('a player ahead or behind by more than 40 ms is seeked, at most once per 2 s', () {
      fakeAsync((async) {
        final h = Harness(async)..joinRoom();
        final p = playing(h);
        h.elapseMs(1000);
        expect(p.callNames, isNot(contains('seek')));
        expect(h.controller.lastDriftMs, closeTo(0, 1e-6));

        // Ahead by 100 ms: corrected to the expected position.
        p.skewSec = 0.1;
        h.elapseMs(1000);
        // 4 s into the song now, plus the latency.
        expect(p.last('seek')!.args['offset'] as double, closeTo(4.1, 1e-9));
        expect(h.controller.lastDriftMs, closeTo(100, 1e-6));
        final correctedAt = h.now();
        expect(h.controller.lastDriftCorrectionAtMs, correctedAt);

        // Behind by 100 ms one second later: too soon.
        p.skewSec = -0.1;
        h.elapseMs(1000);
        expect(p.callNames.where((n) => n == 'seek').length, 1);
        expect(h.controller.lastDriftMs, closeTo(-100, 1e-6));
        // Two seconds after the last correction: corrected.
        h.elapseMs(1000);
        expect(p.callNames.where((n) => n == 'seek').length, 2);
        expect(p.last('seek')!.args['offset'] as double, closeTo(6.1, 1e-9));
        expect(h.controller.lastDriftCorrectionAtMs, correctedAt + 2000);
      });
    });

    test('40 ms or less is left alone', () {
      fakeAsync((async) {
        final h = Harness(async)..joinRoom();
        final p = playing(h);
        p.skewSec = 0.04;
        h.elapseMs(3000);
        expect(p.callNames, isNot(contains('seek')));
        p.skewSec = -0.04;
        h.elapseMs(3000);
        expect(p.callNames, isNot(contains('seek')));
      });
    });

    test('no check before the song is heard', () {
      fakeAsync((async) {
        final h = Harness(async)..joinRoom();
        h.prepare(0);
        h.start(0, inMs: 5000);
        h.player.skewSec = 1;
        h.elapseMs(4000);
        expect(h.player.callNames, isNot(contains('seek')));
      });
    });
  });

  group('manual offset', () {
    test('it is read from the store, clamped, stored, and applied from the next plan', () {
      fakeAsync((async) {
        final store = MemoryPlaybackSettingsStore(100);
        final h = Harness(async, settings: store)..joinRoom();
        expect(h.controller.manualOffsetMs, 100);
        h.prepare(0);
        final engine = h.engineNow;
        h.start(0, inMs: 3000);
        // 100 ms later than the latency-corrected moment.
        expect(playArgs(h.player)['when'] as double, closeTo(engine + 3.0 - 0.1 + 0.1, 1e-9));

        h.controller.manualOffsetMs = 900;
        expect(h.controller.manualOffsetMs, manualOffsetMaxMs);
        expect(store.readManualOffsetMs(), 500);
        h.controller.manualOffsetMs = -2000;
        expect(store.readManualOffsetMs(), -500);
        // The song playing keeps its plan, so its drift check does not move it.
        h.elapseMs(5000);
        expect(h.player.callNames, isNot(contains('seek')));

        h.receive(reveal(0));
        h.prepare(1, audioUrl: audio1);
        final engine1 = h.engineNow;
        h.start(1, inMs: 3000);
        expect(playArgs(h.player)['when'] as double, closeTo(engine1 + 3.0 - 0.1 - 0.5, 1e-9));
      });
    });

    test('a stored value out of range is clamped; none means 0', () {
      fakeAsync((async) {
        expect((Harness(async, settings: MemoryPlaybackSettingsStore(-900))..joinRoom()).controller.manualOffsetMs,
            manualOffsetMinMs);
        expect((Harness(async)..joinRoom()).controller.manualOffsetMs, 0);
      });
    });
  });

  group('stopping', () {
    test('a new prepare stops the old sound', () {
      fakeAsync((async) {
        final h = Harness(async)..joinRoom();
        h.prepare(0);
        h.start(0, inMs: 0);
        final old = h.player;
        h.prepare(1, audioUrl: audio1);
        expect(old.callNames.sublist(old.callNames.length - 2), ['stop', 'dispose']);
      });
    });

    test('leaving stops the sound and ends the controller', () {
      fakeAsync((async) {
        final h = Harness(async)..joinRoom();
        h.prepare(0);
        h.start(0, inMs: 0);
        h.session.leave();
        async.flushMicrotasks();
        expect(h.player.disposed, isTrue);
        h.elapseMs(5000);
        expect(h.player.callNames, isNot(contains('seek')));
      });
    });

    test('a drop stops the sound; gameOver stops a held-over window', () {
      fakeAsync((async) {
        final h = Harness(async)..joinRoom();
        h.receive(const TokenMessage(token: 'tok'));
        h.prepare(0);
        h.start(0, inMs: 0);
        h.socket.drop();
        async.flushMicrotasks();
        expect(h.session.state.status, SessionStatus.reconnecting);
        expect(h.player.disposed, isTrue);
      });
      fakeAsync((async) {
        final h = Harness(async)..joinRoom();
        h.prepare(0);
        h.start(0, inMs: 0);
        h.receive(reveal(0, playUntil: h.serverNow() + 20000));
        final old = h.player;
        h.prepare(1, audioUrl: audio1, holdMs: 5000);
        h.receive(const GameOverMessage(ranking: []));
        expect(old.disposed, isTrue);
        expect(h.player.disposed, isTrue);
      });
    });

    test('a load that finishes after its round ended plays nothing', () {
      fakeAsync((async) {
        final h = Harness(async)..joinRoom();
        h.fetcher.held.add(audio0);
        h.prepare(0);
        final first = h.player;
        h.start(0, inMs: 0);
        h.prepare(1, audioUrl: audio1);
        h.fetcher.complete(audio0);
        async.flushMicrotasks();
        expect(first.callNames, isNot(contains('load')));
        expect(first.callNames, isNot(contains('play')));
        expect(h.readies, [1]);
      });
    });
  });
}

import 'package:fake_async/fake_async.dart';
import 'package:songrush_core/songrush_core.dart';
import 'package:songrush_core/testing.dart';
import 'package:test/test.dart';

const host = Player(name: 'Alice', score: 0, host: true, connected: true);
const guest = Player(name: 'Bob (2)', score: 0, host: false, connected: true);

/// A session on fake sockets, inside a fake clock.
class Harness {
  Harness(this.async, {bool autoOpen = true}) : factory = FakeSocketFactory(autoOpen: autoOpen);

  final FakeAsync async;
  final FakeSocketFactory factory;
  final MemoryTokenStore store = MemoryTokenStore();
  final List<String> warnings = [];
  final List<SessionState> states = [];
  final List<ServerMessage> messages = [];
  late Session session;

  /// The local clock: epoch ms that follows the fake time.
  static const int clockBase = 1000000;
  int now() => clockBase + async.elapsed.inMilliseconds;

  FakeGameSocket get socket => factory.last;

  void listen() {
    session.states.listen(states.add);
    session.messages.listen(messages.add);
    async.flushMicrotasks();
  }

  void create([String nickname = 'Alice']) {
    session = Session.create(
      socketFactory: factory.call,
      tokenStore: store,
      nickname: nickname,
      warn: warnings.add,
      now: now,
    );
    listen();
  }

  void join(String code, String nickname) {
    session = Session.join(
      socketFactory: factory.call,
      tokenStore: store,
      code: code,
      nickname: nickname,
      warn: warnings.add,
      now: now,
    );
    listen();
  }

  void resume(RejoinEntry entry) {
    session = Session.resume(socketFactory: factory.call, tokenStore: store, entry: entry, warn: warnings.add, now: now);
    listen();
  }

  void receive(ServerMessage msg, [FakeGameSocket? on]) {
    (on ?? socket).receiveMessage(msg);
    async.flushMicrotasks();
  }

  /// create, joined, roster and token, as the server answers a create.
  void createdRoom() {
    create();
    receive(const JoinedMessage(code: 'K7XQM', name: 'Alice'));
    receive(const RosterMessage(phase: 'lobby', players: [host]));
    receive(const TokenMessage(token: 'tok-1'));
  }

  void drop([int? code]) {
    socket.drop(code);
    async.flushMicrotasks();
  }
}

void main() {
  group('create', () {
    test('sends create, then reflects joined, roster and the token', () {
      fakeAsync((async) {
        final h = Harness(async)..create();
        expect(h.session.state.status, SessionStatus.connecting);
        expect(h.socket.sentJson, [
          {'type': 'create', 'nickname': 'Alice'},
        ]);

        h.receive(const JoinedMessage(code: 'K7XQM', name: 'Alice'));
        h.receive(const RosterMessage(phase: 'lobby', players: [host]));
        h.receive(const TokenMessage(token: 'tok-1'));

        final s = h.session.state;
        expect(s.status, SessionStatus.inRoom);
        expect(s.roomCode, 'K7XQM');
        expect(s.myName, 'Alice');
        expect(s.phase, 'lobby');
        expect(s.players.single.name, 'Alice');
        expect(s.isHost, isTrue);
        expect(h.store.read(), const RejoinEntry(token: 'tok-1', code: 'K7XQM'));
        // The token is private: it is not on the message stream.
        expect(h.messages.map((m) => m.type), ['joined', 'roster']);
        expect(h.states.last, same(s));
      });
    });

    test('a socket closed before joined ends as connectFailed', () {
      fakeAsync((async) {
        final h = Harness(async)..create();
        h.drop(1006);
        expect(h.session.state.status, SessionStatus.ended);
        expect(h.session.state.end, SessionEnd.connectFailed);
        async.elapse(const Duration(minutes: 1));
        expect(h.factory.sockets, hasLength(1));
      });
    });

    test('a connect that fails ends as connectFailed', () {
      fakeAsync((async) {
        final h = Harness(async, autoOpen: false)..create();
        h.socket.failConnect();
        async.flushMicrotasks();
        expect(h.session.state.end, SessionEnd.connectFailed);
        expect(h.socket.sent, isEmpty);
      });
    });
  });

  group('join', () {
    test('sends join and shows the duplicate nickname suffix the server gives', () {
      fakeAsync((async) {
        final h = Harness(async)..join('K7XQM', 'Bob');
        expect(h.socket.sentJson, [
          {'type': 'join', 'code': 'K7XQM', 'nickname': 'Bob'},
        ]);
        h.receive(const JoinedMessage(code: 'K7XQM', name: 'Bob (2)'));
        h.receive(const RosterMessage(phase: 'lobby', players: [host, guest]));
        h.receive(const TokenMessage(token: 'tok-2'));

        expect(h.session.state.myName, 'Bob (2)');
        expect(h.session.state.players.map((p) => p.name), ['Alice', 'Bob (2)']);
        expect(h.session.state.isHost, isFalse);
        expect(h.store.read(), const RejoinEntry(token: 'tok-2', code: 'K7XQM'));
      });
    });

    test('becomes host when the roster says so', () {
      fakeAsync((async) {
        final h = Harness(async)..join('K7XQM', 'Bob');
        h.receive(const JoinedMessage(code: 'K7XQM', name: 'Bob (2)'));
        h.receive(const RosterMessage(phase: 'lobby', players: [
          Player(name: 'Alice', score: 0, host: false, connected: false),
          Player(name: 'Bob (2)', score: 0, host: true, connected: true),
        ]));
        expect(h.session.state.isHost, isTrue);
      });
    });

    test('a late joiner takes the playing phase from the snapshot', () {
      fakeAsync((async) {
        final h = Harness(async)..join('K7XQM', 'Bob');
        h.receive(const JoinedMessage(code: 'K7XQM', name: 'Bob (2)'));
        h.receive(const SnapshotMessage(phase: 'playing', players: [host], song: null, scores: []));
        expect(h.session.state.phase, 'playing');
        // Players come from the roster that follows, as on the web.
        expect(h.session.state.players, isEmpty);
        expect(h.messages.last, isA<SnapshotMessage>());
      });
    });
  });

  group('chat', () {
    test('goes out as chat and comes in as lines', () {
      fakeAsync((async) {
        final h = Harness(async)..createdRoom();
        h.session.send(const ChatRequest(text: 'hello'));
        expect(h.socket.sentJson.last, {'type': 'chat', 'text': 'hello'});

        h.receive(const ChatMessage(name: 'Alice', text: 'hello'));
        h.receive(const ChatMessage(name: 'Bob (2)', text: 'hi'));
        expect(h.session.state.chat, const [
          ChatLine(name: 'Alice', text: 'hello'),
          ChatLine(name: 'Bob (2)', text: 'hi'),
        ]);
      });
    });

    test('a frame sent before the socket opens is dropped', () {
      fakeAsync((async) {
        final h = Harness(async, autoOpen: false)..create();
        h.session.send(const ChatRequest(text: 'early'));
        h.socket.open();
        async.flushMicrotasks();
        expect(h.socket.sentJson, [
          {'type': 'create', 'nickname': 'Alice'},
        ]);
      });
    });
  });

  group('lobby settings', () {
    test('the host sends its selection and every member keeps the server one', () {
      fakeAsync((async) {
        final h = Harness(async)..createdRoom();
        h.session.send(const LobbySettingsRequest(
          songCount: 12,
          options: GameOptions(tier: 'hard', answerMode: 'choice'),
        ));
        expect(h.socket.sentJson.last,
            {'type': 'lobbySettings', 'songCount': 12, 'tier': 'hard', 'answerMode': 'choice'});

        h.receive(const LobbySettingsMessage(
          songCount: 12,
          options: GameOptions(types: ['OP'], tier: 'hard', answerMode: 'choice', gameMode: 'ranked'),
        ));
        final ls = h.session.state.lobbySettings!;
        expect(ls.songCount, 12);
        expect(ls.options.types, ['OP']);
        expect(ls.options.gameMode, 'ranked');
      });
    });
  });

  group('errors', () {
    test('an error in the room is shown and the session goes on', () {
      fakeAsync((async) {
        final h = Harness(async)..createdRoom();
        h.receive(const ErrorMessage(code: 'not-enough-songs', available: 5, unit: 'series'));
        final s = h.session.state;
        expect(s.status, SessionStatus.inRoom);
        expect(s.error!.code, 'not-enough-songs');
        expect(s.error!.available, 5);
        // A prepare clears it, as on the web.
        h.receive(const PrepareMessage(index: 0, total: 5, audioUrl: '/audio/x'));
        expect(h.session.state.error, isNull);
      });
    });

    test('a refused pick is not an error', () {
      fakeAsync((async) {
        final h = Harness(async)..createdRoom();
        h.receive(const ErrorMessage(code: 'too-early', forRequest: 'pick'));
        expect(h.session.state.error, isNull);
        expect(h.messages.last, isA<ErrorMessage>());
      });
    });

    test('an error before joined ends the session', () {
      fakeAsync((async) {
        final h = Harness(async)..join('ZZZZZ', 'Bob');
        h.receive(const ErrorMessage(code: 'room-not-found'));
        final s = h.session.state;
        expect(s.status, SessionStatus.ended);
        expect(s.end, SessionEnd.refused);
        expect(s.error!.code, 'room-not-found');
        expect(h.socket.closedByClient, isTrue);
      });
    });

    test('room-closed ends the session and clears the token', () {
      fakeAsync((async) {
        final h = Harness(async)..createdRoom();
        h.receive(const ErrorMessage(code: 'room-closed'));
        expect(h.session.state.end, SessionEnd.refused);
        expect(h.session.state.error!.code, 'room-closed');
        expect(h.store.read(), isNull);
        expect(h.states.last.status, SessionStatus.ended);
      });
    });

    test('an unreadable frame is warned about and skipped', () {
      fakeAsync((async) {
        final h = Harness(async)..createdRoom();
        h.socket.receive('{"type":"chat"}');
        h.socket.receiveJson({'type': 'somethingNew'});
        async.flushMicrotasks();
        expect(h.session.state.status, SessionStatus.inRoom);
        expect(h.warnings, hasLength(2));
        expect(h.messages.last, isA<UnknownMessage>());
      });
    });
  });

  group('rejoin after a drop', () {
    test('a new socket sends the token and the snapshot restores the room', () {
      fakeAsync((async) {
        final h = Harness(async)..createdRoom();
        h.receive(const ChatMessage(name: 'Alice', text: 'before'));
        final first = h.socket;
        h.drop(1006);
        expect(h.session.state.status, SessionStatus.reconnecting);

        async.elapse(const Duration(milliseconds: 499));
        expect(h.factory.sockets, hasLength(1));
        async.elapse(const Duration(milliseconds: 1));
        expect(h.factory.sockets, hasLength(2));
        final second = h.socket;
        expect(second, isNot(same(first)));
        expect(second.sentJson, [
          {'type': 'rejoin', 'token': 'tok-1'},
        ]);

        // A queued broadcast before the snapshot is not handled.
        h.receive(const ChatMessage(name: 'Bob (2)', text: 'queued'));
        h.receive(const JoinedMessage(code: 'K7XQM', name: 'Alice'));
        expect(h.session.state.status, SessionStatus.inRoom);
        h.receive(const ChatMessage(name: 'Bob (2)', text: 'still queued'));
        h.receive(const SnapshotMessage(
          phase: 'playing',
          players: [host, guest],
          song: SnapshotSong(index: 1, total: 5, audioUrl: '/audio/y'),
          scores: [ScoreRow(name: 'Alice', score: 1)],
        ));
        h.receive(const ChatMessage(name: 'Bob (2)', text: 'after'));

        final s = h.session.state;
        expect(s.phase, 'playing');
        expect(s.players.map((p) => p.name), ['Alice', 'Bob (2)']);
        expect(s.chat.map((l) => l.text), ['before', 'after']);
        // No new token on a rejoin: the old one stays.
        expect(h.store.read(), const RejoinEntry(token: 'tok-1', code: 'K7XQM'));
        // Back in: no more attempts.
        async.elapse(const Duration(minutes: 1));
        expect(h.factory.sockets, hasLength(2));
        expect(h.session.state.status, SessionStatus.inRoom);
      });
    });

    test('a lobby snapshot starts the log again and counts a lobby restore', () {
      fakeAsync((async) {
        final h = Harness(async)..createdRoom();
        h.receive(const ChatMessage(name: 'Alice', text: 'before'));
        h.drop();
        async.elapse(const Duration(milliseconds: 500));
        h.receive(const JoinedMessage(code: 'K7XQM', name: 'Alice'));
        h.receive(const SnapshotMessage(phase: 'lobby', players: [host], song: null, scores: []));
        expect(h.session.state.chat, isEmpty);
        expect(h.session.state.lobbyRestores, 1);
      });
    });

    test('rejoin-failed ends the session and clears the token', () {
      fakeAsync((async) {
        final h = Harness(async)..createdRoom();
        h.drop(1006);
        async.elapse(const Duration(milliseconds: 500));
        h.receive(const ErrorMessage(code: 'rejoin-failed'));
        expect(h.session.state.end, SessionEnd.refused);
        expect(h.session.state.error!.code, 'rejoin-failed');
        expect(h.store.read(), isNull);
        async.elapse(const Duration(minutes: 1));
        expect(h.factory.sockets, hasLength(2));
      });
    });

    test('a close with no token ends the session', () {
      fakeAsync((async) {
        final h = Harness(async)..create();
        h.receive(const JoinedMessage(code: 'K7XQM', name: 'Alice'));
        h.drop(1006);
        expect(h.session.state.end, SessionEnd.disconnected);
        async.elapse(const Duration(minutes: 1));
        expect(h.factory.sockets, hasLength(1));
      });
    });
  });

  group('back-off', () {
    /// Drops a joined room and returns the times (since the drop) at which
    /// each new socket was made; [onSocket] plays each attempt.
    List<Duration> attemptTimes(FakeAsync async, Harness h, void Function(FakeGameSocket s) onSocket) {
      h.createdRoom();
      h.factory.autoOpen = false;
      final dropAt = async.elapsed;
      final times = <Duration>[];
      var seen = h.factory.sockets.length;
      h.drop(1006);
      for (var ms = 0; ms < 40000; ms += 50) {
        async.elapse(const Duration(milliseconds: 50));
        while (h.factory.sockets.length > seen) {
          final s = h.factory.sockets[seen++];
          times.add(async.elapsed - dropAt);
          onSocket(s);
          async.flushMicrotasks();
        }
      }
      return times;
    }

    test('refused attempts wait 0.5, 1, 2, 4, 8, 10 s and stop at 30 s', () {
      fakeAsync((async) {
        final h = Harness(async);
        final times = attemptTimes(async, h, (s) => s.failConnect());
        expect(times.map((d) => d.inMilliseconds), [500, 1500, 3500, 7500, 15500, 25500]);
        expect(h.session.state.end, SessionEnd.disconnected);
        expect(h.store.read(), isNull);
      });
    });

    test('an attempt that never answers gets 5 s, then the next wait', () {
      fakeAsync((async) {
        final h = Harness(async);
        final times = attemptTimes(async, h, (s) {});
        expect(times.map((d) => d.inMilliseconds), [500, 6500, 13500, 22500]);
        expect(h.factory.sockets.skip(1).every((s) => s.closedByClient), isTrue);
        expect(h.session.state.end, SessionEnd.disconnected);
      });
    });

    test('the session gives up exactly 30 s after the drop', () {
      fakeAsync((async) {
        final h = Harness(async)..createdRoom();
        h.factory.autoOpen = false;
        h.drop(1006);
        async.elapse(const Duration(milliseconds: 29999));
        expect(h.session.state.status, SessionStatus.reconnecting);
        async.elapse(const Duration(milliseconds: 1));
        expect(h.session.state.end, SessionEnd.disconnected);
        expect(h.socket.closedByClient, isTrue);
      });
    });
  });

  group('4001', () {
    test('a replaced socket never reconnects and clears the token', () {
      fakeAsync((async) {
        final h = Harness(async)..createdRoom();
        h.drop(replacedCloseCode);
        expect(h.session.state.end, SessionEnd.replaced);
        expect(h.store.read(), isNull);
        async.elapse(const Duration(minutes: 1));
        expect(h.factory.sockets, hasLength(1));
      });
    });

    test('a 4001 on a rejoin attempt stops the attempts too', () {
      fakeAsync((async) {
        final h = Harness(async)..createdRoom();
        h.drop(1006);
        async.elapse(const Duration(milliseconds: 500));
        h.drop(replacedCloseCode);
        expect(h.session.state.end, SessionEnd.replaced);
        async.elapse(const Duration(minutes: 1));
        expect(h.factory.sockets, hasLength(2));
      });
    });
  });

  group('leave', () {
    test('sends leave, closes the socket and clears the token', () {
      fakeAsync((async) {
        final h = Harness(async)..createdRoom();
        var done = false;
        h.session.states.listen(null, onDone: () => done = true);
        h.session.leave();
        async.flushMicrotasks();
        expect(h.socket.sentJson.last, {'type': 'leave'});
        expect(h.socket.closedByClient, isTrue);
        expect(h.store.read(), isNull);
        expect(h.session.state.end, SessionEnd.left);
        expect(done, isTrue);
        // Nothing more goes out, and a late frame changes nothing.
        h.session.send(const ChatRequest(text: 'late'));
        expect(h.socket.sentJson.last, {'type': 'leave'});
        async.elapse(const Duration(minutes: 1));
        expect(h.factory.sockets, hasLength(1));
      });
    });
  });

  group('resume', () {
    const entry = RejoinEntry(token: 'tok-9', code: 'K7XQM');

    test('rejoins with the stored token and is restored by the snapshot', () {
      fakeAsync((async) {
        final h = Harness(async);
        h.store.write(entry);
        h.resume(entry);
        expect(h.socket.sentJson, [
          {'type': 'rejoin', 'token': 'tok-9'},
        ]);
        h.receive(const JoinedMessage(code: 'K7XQM', name: 'Alice'));
        h.receive(const SnapshotMessage(phase: 'lobby', players: [host], song: null, scores: []));
        expect(h.session.state.status, SessionStatus.inRoom);
        expect(h.session.state.isHost, isTrue);
        async.elapse(const Duration(minutes: 1));
        expect(h.session.state.status, SessionStatus.inRoom);

        // A later drop reconnects with the same token.
        h.drop(1006);
        async.elapse(const Duration(milliseconds: 500));
        expect(h.socket.sentJson, [
          {'type': 'rejoin', 'token': 'tok-9'},
        ]);
      });
    });

    test('no joined within 5 s fails with rejoin-failed and no retry', () {
      fakeAsync((async) {
        final h = Harness(async);
        h.store.write(entry);
        h.resume(entry);
        async.elapse(const Duration(seconds: 5));
        expect(h.session.state.end, SessionEnd.refused);
        expect(h.session.state.error!.code, 'rejoin-failed');
        expect(h.store.read(), isNull);
        async.elapse(const Duration(minutes: 1));
        expect(h.factory.sockets, hasLength(1));
      });
    });

    test('a close before joined fails the same way', () {
      fakeAsync((async) {
        final h = Harness(async);
        h.resume(entry);
        h.drop(1006);
        expect(h.session.state.end, SessionEnd.refused);
        expect(h.factory.sockets, hasLength(1));
      });
    });
  });

  group('clock sync', () {
    List<Map<String, Object?>> pingsOf(FakeGameSocket s) => [
          for (final m in s.sentJson)
            if (m['type'] == 'ping') m
        ];

    // session.js 'joined': sync.burst(JOIN_PINGS) and the 30 s interval.
    test('joined starts a burst of 8 pings, 150 ms apart', () {
      fakeAsync((async) {
        final h = Harness(async)..createdRoom();
        expect(pingsOf(h.socket), [
          {'type': 'ping', 'id': 1},
        ]);
        async.elapse(const Duration(milliseconds: 149));
        expect(pingsOf(h.socket), hasLength(1));
        async.elapse(const Duration(milliseconds: 1));
        expect(pingsOf(h.socket), hasLength(2));
        async.elapse(const Duration(milliseconds: 900));
        expect(pingsOf(h.socket).map((m) => m['id']), [1, 2, 3, 4, 5, 6, 7, 8]);
        expect(h.session.state.clock, isNull);
      });
    });

    // session.js 'pong': sync.onPong(msg); the estimate is in the state.
    test('pongs feed the estimate, exposed in the state', () {
      fakeAsync((async) {
        final h = Harness(async)..createdRoom();
        final t0 = h.now();
        async.elapse(const Duration(milliseconds: 40));
        // offset = serverTime - (t0 + 20) = 5000
        h.receive(PongMessage(id: 1, serverTime: t0 + 20 + 5000));
        final clock = h.session.state.clock!;
        expect(clock.offsetMs, 5000);
        expect(clock.rttMs, 40);
        expect(h.session.state.clockLowerBoundMs, 4980);
        // The pong is on the message stream too.
        expect(h.messages.last, isA<PongMessage>());

        // A pong for no pending ping changes nothing.
        final before = h.states.length;
        h.receive(PongMessage(id: 99, serverTime: t0));
        expect(h.session.state.clock, clock);
        expect(h.states, hasLength(before));
      });
    });

    test('3 more pings every 30 s while in the room', () {
      fakeAsync((async) {
        final h = Harness(async)..createdRoom();
        async.elapse(const Duration(seconds: 29));
        expect(pingsOf(h.socket), hasLength(8));
        async.elapse(const Duration(seconds: 1));
        expect(pingsOf(h.socket), hasLength(9));
        async.elapse(const Duration(milliseconds: 300));
        expect(pingsOf(h.socket).map((m) => m['id']).skip(8), [9, 10, 11]);
        async.elapse(const Duration(seconds: 30));
        expect(pingsOf(h.socket), hasLength(14));
      });
    });

    // session.js beginReconnect: stopSync, sync.clear; the next joined
    // starts a fresh sync.
    test('a drop stops and clears the sync; the rejoin starts a fresh burst', () {
      fakeAsync((async) {
        final h = Harness(async)..createdRoom();
        final first = h.socket;
        final t0 = h.now();
        async.elapse(const Duration(milliseconds: 20));
        h.receive(PongMessage(id: 1, serverTime: t0 + 10 + 3000));
        expect(h.session.state.clock!.offsetMs, 3000);
        async.elapse(const Duration(milliseconds: 200));
        expect(pingsOf(first), hasLength(2));

        h.drop(1006);
        expect(h.session.state.clock, isNull);
        expect(h.session.state.clockLowerBoundMs, isNull);
        // The rest of the burst and the interval are gone.
        async.elapse(const Duration(milliseconds: 500));
        final second = h.socket;
        expect(second, isNot(same(first)));
        expect(pingsOf(first), hasLength(2));
        expect(pingsOf(second), isEmpty);

        h.receive(const JoinedMessage(code: 'K7XQM', name: 'Alice'));
        // A pong for a ping of the old socket is ignored.
        h.receive(PongMessage(id: 2, serverTime: h.now()));
        expect(h.session.state.clock, isNull);
        async.elapse(const Duration(seconds: 2));
        expect(pingsOf(second).map((m) => m['id']), [3, 4, 5, 6, 7, 8, 9, 10]);
        async.elapse(const Duration(milliseconds: 28500));
        expect(pingsOf(second), hasLength(11));
      });
    });

    test('a joined on the same socket does not start a second interval', () {
      fakeAsync((async) {
        final h = Harness(async)..createdRoom();
        h.receive(const JoinedMessage(code: 'K7XQM', name: 'Alice'));
        async.elapse(const Duration(seconds: 31));
        expect(pingsOf(h.socket), hasLength(11));
      });
    });

    test('a resume starts the sync on its joined', () {
      fakeAsync((async) {
        final h = Harness(async);
        h.resume(const RejoinEntry(token: 'tok-9', code: 'K7XQM'));
        expect(pingsOf(h.socket), isEmpty);
        h.receive(const JoinedMessage(code: 'K7XQM', name: 'Alice'));
        expect(pingsOf(h.socket), hasLength(1));
      });
    });

    test('leaving stops every ping', () {
      fakeAsync((async) {
        final h = Harness(async)..createdRoom();
        h.session.leave();
        async.flushMicrotasks();
        final sent = h.socket.sent.length;
        async.elapse(const Duration(minutes: 2));
        expect(h.socket.sent, hasLength(sent));
        expect(async.pendingTimers, isEmpty);
      });
    });

    // session.js sendReady: ready, then sync.burst(RESYNC_PINGS).
    test('sendReady sends ready and 3 pings', () {
      fakeAsync((async) {
        final h = Harness(async)..createdRoom();
        async.elapse(const Duration(seconds: 2));
        h.session.sendReady(4);
        async.elapse(const Duration(milliseconds: 300));
        expect(h.socket.sentJson.skip(9), [
          {'type': 'ready', 'index': 4},
          {'type': 'ping', 'id': 9},
          {'type': 'ping', 'id': 10},
          {'type': 'ping', 'id': 11},
        ]);
      });
    });

    // session.js startOffset: a drifted receipt clears the samples and bursts.
    test('startOffsetFor uses the estimate, and a drifted receipt re-syncs', () {
      fakeAsync((async) {
        final h = Harness(async)..createdRoom();
        final t0 = h.now();
        async.elapse(const Duration(milliseconds: 20));
        h.receive(PongMessage(id: 1, serverTime: t0 + 10 + 3000));
        async.elapse(const Duration(seconds: 2));
        final sent = pingsOf(h.socket).length;

        // receipt 3060: within rtt 20 + 50.
        final near = h.session.startOffsetFor(h.now() + 3060);
        expect(near.offsetMs, 3000);
        expect(near.provisional, isFalse);
        expect(pingsOf(h.socket), hasLength(sent));
        expect(h.session.state.clock, isNotNull);

        // receipt 2500: drifted below, provisional, and a fresh sync.
        final drifted = h.session.startOffsetFor(h.now() + 2500);
        expect(drifted.offsetMs, 2500);
        expect(drifted.provisional, isTrue);
        expect(h.session.state.clock, isNull);
        async.elapse(const Duration(milliseconds: 300));
        expect(pingsOf(h.socket), hasLength(sent + 3));
      });
    });

    test('startOffsetFor with no estimate uses the receipt', () {
      fakeAsync((async) {
        final h = Harness(async)..createdRoom();
        final r = h.session.startOffsetFor(h.now() + 700);
        expect(r.offsetMs, 700);
        expect(r.provisional, isFalse);
        expect(r.resync, isFalse);
      });
    });
  });

  test('the message stream carries game frames as typed events', () {
    fakeAsync((async) {
      final h = Harness(async)..createdRoom();
      h.receive(const PrepareMessage(index: 0, total: 5, audioUrl: '/audio/x'));
      h.receive(const StartMessage(index: 0, startAt: 3, deadlineAt: 9, serverTime: 0));
      expect(h.messages.whereType<PrepareMessage>().single.audioUrl, '/audio/x');
      expect(h.messages.whereType<StartMessage>().single.deadlineAt, 9);
    });
  });
}

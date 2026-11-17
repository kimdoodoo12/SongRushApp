import 'dart:async';
import 'dart:developer' as developer;

import '../net/game_socket.dart';
import '../protocol/client_message.dart';
import '../protocol/server_message.dart';
import 'session_state.dart';
import 'token_store.dart';

/// Reconnect (F-006, as frontend/src/game/session.js): the waits before each
/// attempt, the last one repeating.
const List<Duration> reconnectWaits = [
  Duration(milliseconds: 500),
  Duration(seconds: 1),
  Duration(seconds: 2),
  Duration(seconds: 4),
  Duration(seconds: 8),
  Duration(seconds: 10),
];

/// The time each attempt (and a resume's only attempt) has to reach `joined`.
const Duration attemptLimit = Duration(seconds: 5);

/// The limit from the drop: the server's grace (`Lobby.GRACE`).
const Duration reconnectLimit = Duration(seconds: 30);

// Frames handled on a rejoin socket before its snapshot (spec 049 H1):
// queued broadcasts may reach it before joined.
const Set<String> _beforeSnapshot = {'joined', 'snapshot', 'pong', 'token', 'error'};

/// One connection attempt: the socket and whether it is open and current.
class _Link {
  _Link(this.socket);

  final GameSocket socket;
  StreamSubscription<String>? subscription;
  bool open = false;
  bool live = true;
}

enum _Mode { create, join, resume }

/// One room session over one socket at a time, ported from the web client's
/// `startSession` (frontend/src/game/session.js): it sends the opening
/// `create`, `join` or `rejoin`, keeps the rejoin token in a [TokenStore],
/// reconnects after an unexpected close and reflects the room in [state].
///
/// A session that has its token reconnects on an unexpected close: new
/// sockets with `rejoin{token}` after the [reconnectWaits], each given
/// [attemptLimit] to reach `joined`, until [reconnectLimit] after the drop.
/// Until the new socket's `snapshot` only `joined`, `snapshot`, `pong`,
/// `token` and `error` are handled. A 4001 close never reconnects.
///
/// [Session.resume] is the first socket after an app restart: it sends
/// `rejoin` for a stored entry; `rejoin-failed`, a close before its first
/// `joined`, or no `joined` within [attemptLimit] ends it, with no retries.
///
/// Every frame except `token` is also on [messages], for the game logic of
/// later items; this class acts on none of the game frames yet.
class Session {
  /// Opens a lobby with the player as host.
  Session.create({
    required GameSocketFactory socketFactory,
    required TokenStore tokenStore,
    required String nickname,
    void Function(String message)? warn,
  }) : this._(socketFactory, tokenStore, warn, _Mode.create, nickname: nickname);

  /// Joins the room [code].
  Session.join({
    required GameSocketFactory socketFactory,
    required TokenStore tokenStore,
    required String code,
    required String nickname,
    void Function(String message)? warn,
  }) : this._(socketFactory, tokenStore, warn, _Mode.join, code: code, nickname: nickname);

  /// Takes back the seat of a stored [entry] (after an app restart).
  Session.resume({
    required GameSocketFactory socketFactory,
    required TokenStore tokenStore,
    required RejoinEntry entry,
    void Function(String message)? warn,
  }) : this._(socketFactory, tokenStore, warn, _Mode.resume, code: entry.code, token: entry.token);

  Session._(
    this._socketFactory,
    this._tokenStore,
    void Function(String message)? warn,
    this._mode, {
    String? code,
    this._nickname,
    this._token,
  })  : _warn = warn ?? _log,
        _code = code,
        _roomCode = _mode == _Mode.resume ? code : null,
        _rejoined = _mode == _Mode.resume,
        _awaitingSnapshot = _mode == _Mode.resume {
    _attach();
    if (_mode == _Mode.resume) {
      // No joined within the limit, connecting or open: the resume failed.
      // joined clears it through _cancelReconnect.
      _attemptTimer = Timer(attemptLimit, () {
        _attemptTimer = null;
        _failResume();
      });
    }
  }

  static void _log(String message) => developer.log(message, name: 'songrush.session');

  final GameSocketFactory _socketFactory;
  final TokenStore _tokenStore;
  final void Function(String message) _warn;
  final _Mode _mode;
  final String? _code;
  final String? _nickname;

  final StreamController<SessionState> _states = StreamController.broadcast();
  final StreamController<ServerMessage> _messages = StreamController.broadcast();
  SessionState _state = const SessionState();

  late _Link _link;
  // false once this session is over; late callbacks then do nothing.
  bool _active = true;
  // joined arrived on the current socket; everJoined on any.
  bool _joined = false;
  bool _everJoined = false;
  // The last token and room code.
  String? _token;
  String? _roomCode;
  // A rejoin is in progress (from the drop until joined); the socket is a
  // rejoin socket, and whether its snapshot is still to come.
  bool _reconnecting = false;
  bool _rejoined;
  bool _awaitingSnapshot;
  int _attempts = 0;
  Timer? _retryTimer;
  Timer? _attemptTimer;
  Timer? _limitTimer;

  /// The current state.
  SessionState get state => _state;

  /// Every new state; done after the one with [SessionStatus.ended].
  Stream<SessionState> get states => _states.stream;

  /// Every accepted server frame except `token` (kept private), delivered
  /// after [state] reflects it.
  Stream<ServerMessage> get messages => _messages.stream;

  /// Sends [message] when the socket is open; dropped otherwise (also while
  /// reconnecting, between sockets), as the web client does.
  void send(ClientMessage message) {
    if (_active) _send(message);
  }

  /// Leaves the room on purpose: the server is told, the socket closes and
  /// the token is cleared.
  void leave() {
    if (_active) _send(const LeaveRequest());
    close();
  }

  /// Ends the session without telling the server (the seat stays in grace);
  /// the token is cleared all the same.
  void close() => _close(SessionEnd.left);

  void _send(ClientMessage message) {
    final link = _link;
    if (!link.live || !link.open) return;
    link.socket.send(message.encode());
  }

  void _setState(SessionState next) {
    _state = next;
    _states.add(next);
  }

  // --- the socket ---

  void _attach() {
    final link = _Link(_socketFactory());
    _link = link;
    final socket = link.socket;
    link.subscription = socket.incoming.listen(
      (frame) {
        if (link.live && identical(link, _link)) _onFrame(frame);
      },
      // The close follows an error; the done handler reports it.
      onError: (Object error) => _warn('socket error: $error'),
      onDone: () => _onSocketClose(link, socket.closeCode),
    );
    socket.connect().then(
      (_) {
        if (!link.live || !identical(link, _link)) return;
        link.open = true;
        _sendOpening();
      },
      onError: (Object error) {
        _warn('socket connect failed: $error');
        _onSocketClose(link, null);
      },
    );
  }

  void _sendOpening() {
    if (_rejoined) {
      _send(RejoinRequest(token: _token!));
    } else if (_mode == _Mode.create) {
      _send(CreateRequest(nickname: _nickname!));
    } else {
      _send(JoinRequest(code: _code!, nickname: _nickname!));
    }
  }

  void _detach(_Link link) {
    link.live = false;
    link.open = false;
    link.subscription?.cancel();
  }

  void _onFrame(String frame) {
    final ServerMessage msg;
    try {
      msg = ServerMessage.decode(frame);
    } on FormatException catch (e) {
      _warn('unreadable server message: ${e.message}');
      return;
    }
    if (_awaitingSnapshot && !_beforeSnapshot.contains(msg.type)) return;
    // Delivered asynchronously, so listeners see the state after _handle.
    if (msg is! TokenMessage) _messages.add(msg);
    _handle(msg);
  }

  void _handle(ServerMessage msg) {
    switch (msg) {
      case JoinedMessage():
        _onJoined(msg);
      case SnapshotMessage():
        _onSnapshot(msg);
      case TokenMessage():
        // Private to this session.
        _token = msg.token;
        final code = _roomCode;
        if (code == null) {
          _warn('token before joined: kept in memory only');
        } else {
          _tokenStore.write(RejoinEntry(token: msg.token, code: code));
        }
      case RosterMessage():
        _setState(_state.copyWith(phase: msg.phase, players: msg.players));
      case LobbySettingsMessage():
        _setState(_state.copyWith(lobbySettings: msg));
      case ChatMessage():
        _setState(_state.copyWith(chat: [..._state.chat, ChatLine(name: msg.name, text: msg.text)]));
      case ErrorMessage():
        _onError(msg);
      case PrepareMessage():
        if (_state.error != null) _setState(_state.copyWith(error: null));
      case UnknownMessage():
        _warn('unknown message ${msg.type}');
      case PongMessage() ||
            SettingsMessage() ||
            RoomThemeMessage() ||
            StartMessage() ||
            CorrectMessage() ||
            WrongMessage() ||
            PickResultMessage() ||
            PickStateMessage() ||
            SkipStateMessage() ||
            RevealMessage() ||
            ScoresMessage() ||
            GameOverMessage():
        // Game frames: on [messages] only, for later items.
        break;
    }
  }

  void _onJoined(JoinedMessage msg) {
    _joined = true;
    _everJoined = true;
    _roomCode = msg.code;
    if (_rejoined) {
      // Back in the room: the reconnect ends; the snapshot shows the rest.
      _cancelReconnect();
      _setState(_state.copyWith(
        status: SessionStatus.inRoom,
        roomCode: msg.code,
        myName: msg.name,
        error: null,
      ));
    } else {
      _setState(_state.copyWith(
        status: SessionStatus.inRoom,
        roomCode: msg.code,
        myName: msg.name,
        error: null,
        chat: const [],
      ));
    }
  }

  // joined, then a snapshot: of a playing game for a late joiner (only the
  // phase is taken; the roster follows), or the whole room on a rejoin.
  void _onSnapshot(SnapshotMessage msg) {
    if (!_joined) return;
    if (!_rejoined) {
      if (msg.phase == 'playing') _setState(_state.copyWith(phase: msg.phase));
      return;
    }
    _awaitingSnapshot = false;
    final lobby = msg.phase == 'lobby';
    _setState(_state.copyWith(
      phase: msg.phase,
      players: msg.players,
      error: null,
      // Back in the lobby the log starts again, as on the web.
      chat: lobby ? const [] : null,
      lobbyRestores: lobby ? _state.lobbyRestores + 1 : null,
    ));
  }

  void _onError(ErrorMessage msg) {
    // A refused pick is told by the tiles, never as an error text.
    if (msg.forRequest == 'pick') return;
    if (!_joined || msg.code == 'room-closed') {
      _close(SessionEnd.refused, error: msg);
    } else {
      _setState(_state.copyWith(error: msg));
    }
  }

  // --- closing and reconnecting ---

  void _shutdown() {
    _active = false;
    _cancelReconnect();
  }

  void _end(SessionEnd reason, {ErrorMessage? error}) {
    _setState(_state.copyWith(
      status: SessionStatus.ended,
      end: reason,
      error: error ?? _state.error,
    ));
    _states.close();
    _messages.close();
  }

  // Closing on purpose: the handlers go first, so the old socket's events
  // never reach the state, and the rejoin entry goes with the session.
  void _close(SessionEnd reason, {ErrorMessage? error}) {
    if (!_active) return;
    _shutdown();
    _detach(_link);
    _link.socket.close();
    _tokenStore.clear();
    _end(reason, error: error);
  }

  void _cancelReconnect() {
    _reconnecting = false;
    _retryTimer?.cancel();
    _attemptTimer?.cancel();
    _limitTimer?.cancel();
    _retryTimer = null;
    _attemptTimer = null;
    _limitTimer = null;
  }

  void _onSocketClose(_Link link, int? code) {
    if (!_active || !link.live || !identical(link, _link)) return;
    _detach(link);
    if (code == replacedCloseCode) {
      // Replaced by another socket: no reconnect.
      _shutdown();
      _tokenStore.clear();
      _end(SessionEnd.replaced);
      return;
    }
    if (_mode == _Mode.resume && !_everJoined) {
      _failResume();
      return;
    }
    if (!_everJoined) {
      // A create or join that never got in.
      _shutdown();
      _end(SessionEnd.connectFailed);
      return;
    }
    if (_reconnecting) {
      // A failed attempt: the next wait applies.
      _attemptTimer?.cancel();
      _attemptTimer = null;
      _scheduleAttempt();
      return;
    }
    if (_token != null) {
      _beginReconnect();
      return;
    }
    // A room without a token cannot rejoin, so this close ends the session.
    _shutdown();
    _end(SessionEnd.disconnected);
  }

  // An unexpected close with a token: the room stays, the join is forgotten
  // and new sockets follow until the limit.
  void _beginReconnect() {
    _reconnecting = true;
    _rejoined = true;
    _awaitingSnapshot = true;
    _joined = false;
    _attempts = 0;
    _setState(_state.copyWith(status: SessionStatus.reconnecting));
    _limitTimer = Timer(reconnectLimit, _giveUp);
    _scheduleAttempt();
  }

  void _scheduleAttempt() {
    final wait = reconnectWaits[_attempts < reconnectWaits.length ? _attempts : reconnectWaits.length - 1];
    _attempts += 1;
    _retryTimer = Timer(wait, _openAttempt);
  }

  void _openAttempt() {
    _retryTimer = null;
    _attach();
    final link = _link;
    // No joined within the limit, connecting or open: the attempt failed.
    _attemptTimer = Timer(attemptLimit, () {
      _attemptTimer = null;
      _detach(link);
      link.socket.close();
      _scheduleAttempt();
    });
  }

  // The resume never got in. The handlers go first, so the socket's later
  // events change nothing.
  void _failResume() {
    _shutdown();
    _detach(_link);
    _link.socket.close();
    _tokenStore.clear();
    _end(SessionEnd.refused, error: const ErrorMessage(code: 'rejoin-failed'));
  }

  // The limit after the drop: the server's grace is over.
  void _giveUp() {
    _limitTimer = null;
    _shutdown();
    _detach(_link);
    _link.socket.close();
    _tokenStore.clear();
    _end(SessionEnd.disconnected);
  }
}

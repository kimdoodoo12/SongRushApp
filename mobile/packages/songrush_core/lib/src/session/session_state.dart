import '../protocol/models.dart';
import '../protocol/server_message.dart';
import '../sync/clock_sync.dart';

/// Where the session is.
enum SessionStatus {
  /// The first socket is opening, or its create, join or rejoin is not
  /// answered yet.
  connecting,

  /// `joined` arrived; the player holds a seat.
  inRoom,

  /// The socket dropped and new sockets try to `rejoin` (up to 30 s).
  reconnecting,

  /// Over for good; see [SessionState.end].
  ended,
}

/// Why a session ended.
enum SessionEnd {
  /// The player left or closed the session.
  left,

  /// The server refused or ended the seat with an error, kept in
  /// [SessionState.error]: any error before `joined` (also `rejoin-failed`
  /// during a reconnect), `room-closed` at any time, or a reload rejoin that
  /// never got in (`rejoin-failed`).
  refused,

  /// A create or join whose socket closed before `joined`.
  connectFailed,

  /// The connection dropped and could not be taken back within 30 s, or
  /// dropped with no token to rejoin with.
  disconnected,

  /// Close code 4001: the seat was taken over by another socket.
  replaced,
}

/// One chat line as the server sent it.
class ChatLine {
  const ChatLine({required this.name, required this.text});

  final String name;
  final String text;

  @override
  bool operator ==(Object other) => other is ChatLine && other.name == name && other.text == text;

  @override
  int get hashCode => Object.hash(name, text);

  @override
  String toString() => '$name: $text';
}

const Object _unset = Object();

/// What the session knows of its room. Immutable; every change is a new
/// state on `Session.states`.
class SessionState {
  const SessionState({
    this.status = SessionStatus.connecting,
    this.end,
    this.roomCode,
    this.myName,
    this.phase,
    this.players = const [],
    this.lobbySettings,
    this.chat = const [],
    this.error,
    this.lobbyRestores = 0,
    this.clock,
    this.clockLowerBoundMs,
  });

  final SessionStatus status;

  /// Set once [status] is [SessionStatus.ended].
  final SessionEnd? end;

  /// From `joined`.
  final String? roomCode;

  /// The name `joined` assigned (a duplicate nickname gets ` (2)`...).
  final String? myName;

  /// `lobby` or `playing`, from the last `roster` or `snapshot`.
  final String? phase;

  /// The last roster, in join order.
  final List<Player> players;

  /// The host's last lobby selection; kept across a game and a rejoin, as
  /// the server keeps it and sends every change.
  final LobbySettingsMessage? lobbySettings;

  /// Lobby and game chat lines, oldest first.
  final List<ChatLine> chat;

  /// The last error the server sent (cleared by `joined`, a rejoin and a
  /// `prepare`); with [SessionEnd.refused], the one that ended the session.
  /// Refused picks (`for: pick`) are not shown here.
  final ErrorMessage? error;

  /// Bumped by every rejoin snapshot of the lobby phase: the host may have
  /// changed meanwhile, so a host sends its lobby selection again.
  final int lobbyRestores;

  /// The server clock estimate over the last 8 pongs (offset = server time
  /// minus local time); null before the first pong of a sync, after a drop
  /// and after a drifted start cleared the samples.
  final ClockEstimate? clock;

  /// The largest `serverTime - t1` over the same pongs, never above the true
  /// offset (cycle 051): what moves a provisional start. Null with [clock].
  final double? clockLowerBoundMs;

  /// The own roster row says host.
  bool get isHost => players.any((p) => p.host && p.name == myName);

  SessionState copyWith({
    SessionStatus? status,
    Object? end = _unset,
    Object? roomCode = _unset,
    Object? myName = _unset,
    Object? phase = _unset,
    List<Player>? players,
    Object? lobbySettings = _unset,
    List<ChatLine>? chat,
    Object? error = _unset,
    int? lobbyRestores,
    Object? clock = _unset,
    Object? clockLowerBoundMs = _unset,
  }) =>
      SessionState(
        status: status ?? this.status,
        end: identical(end, _unset) ? this.end : end as SessionEnd?,
        roomCode: identical(roomCode, _unset) ? this.roomCode : roomCode as String?,
        myName: identical(myName, _unset) ? this.myName : myName as String?,
        phase: identical(phase, _unset) ? this.phase : phase as String?,
        players: players == null ? this.players : List.unmodifiable(players),
        lobbySettings: identical(lobbySettings, _unset)
            ? this.lobbySettings
            : lobbySettings as LobbySettingsMessage?,
        chat: chat == null ? this.chat : List.unmodifiable(chat),
        error: identical(error, _unset) ? this.error : error as ErrorMessage?,
        lobbyRestores: lobbyRestores ?? this.lobbyRestores,
        clock: identical(clock, _unset) ? this.clock : clock as ClockEstimate?,
        clockLowerBoundMs:
            identical(clockLowerBoundMs, _unset) ? this.clockLowerBoundMs : clockLowerBoundMs as double?,
      );

  @override
  String toString() => 'SessionState($status, end: $end, room: $roomCode, me: $myName, '
      'phase: $phase, players: ${players.length}, chat: ${chat.length}, error: ${error?.code}, clock: $clock)';
}

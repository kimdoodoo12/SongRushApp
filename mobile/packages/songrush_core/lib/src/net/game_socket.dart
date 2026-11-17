/// One WebSocket to the server's `/ws`, as the session sees it. The real
/// adapter (web_socket_channel) lives in the app; tests use
/// `FakeGameSocket` from `package:songrush_core/testing.dart`.
///
/// Lifecycle: [connect] once; frames arrive on [incoming] once it completes;
/// [incoming] is done when the socket closes, from either side, and
/// [closeCode] then holds the close status (null when there was none, as on
/// a network loss). A failed [connect] completes with an error and the
/// socket is finished.
abstract interface class GameSocket {
  /// Opens the socket; completes when it is open.
  Future<void> connect();

  /// Text frames from the server. Single subscription; may carry an error
  /// before it is done.
  Stream<String> get incoming;

  /// The close status once [incoming] is done.
  int? get closeCode;

  /// Sends one text frame. Only called while open.
  void send(String frame);

  /// Closes the socket, with [code] when given.
  void close([int? code]);
}

/// Opens a new, unconnected socket to the server; the session calls it once
/// per connection attempt.
typedef GameSocketFactory = GameSocket Function();

/// The close status of a socket replaced by another tab or device (a
/// `rejoin` with its token was accepted elsewhere): never reconnect.
const int replacedCloseCode = 4001;

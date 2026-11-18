import 'dart:async';
import 'dart:convert';

import '../net/game_socket.dart';
import '../protocol/client_message.dart';
import '../protocol/server_message.dart';

/// A [GameSocket] driven by a test: it records what the client sends and
/// plays the server's side.
///
/// [connect] completes when the test calls [open] (or at once with
/// [autoOpen]); [failConnect] makes it fail. [receive] delivers a frame;
/// [drop] closes from the server's side with a close code.
class FakeGameSocket implements GameSocket {
  FakeGameSocket({this.autoOpen = true});

  /// [connect] completes on its own, in a microtask.
  final bool autoOpen;

  final StreamController<String> _incoming = StreamController();
  final Completer<void> _connected = Completer();
  int? _closeCode;

  /// Every frame the client sent, in order.
  final List<String> sent = [];

  /// [connect] was called.
  bool connectCalled = false;

  /// The client called [close], and the code it gave.
  bool closedByClient = false;
  int? clientCloseCode;

  /// The stream is done (closed from either side).
  bool get isClosed => _incoming.isClosed;

  /// [sent], decoded.
  List<ClientMessage> get sentMessages => [for (final f in sent) ClientMessage.decode(f)];

  /// [sent] as JSON objects.
  List<Map<String, Object?>> get sentJson =>
      [for (final f in sent) jsonDecode(f) as Map<String, Object?>];

  @override
  Future<void> connect() {
    connectCalled = true;
    if (autoOpen) scheduleMicrotask(open);
    return _connected.future;
  }

  /// Completes [connect]: the socket is open.
  void open() {
    if (!_connected.isCompleted && !isClosed) _connected.complete();
  }

  /// Fails [connect], as a refused connection does, and ends the stream.
  void failConnect([Object error = 'connection refused']) {
    if (!_connected.isCompleted) _connected.completeError(error);
    if (!isClosed) _incoming.close();
  }

  @override
  Stream<String> get incoming => _incoming.stream;

  @override
  int? get closeCode => _closeCode;

  @override
  void send(String frame) {
    if (isClosed) throw StateError('send on a closed socket');
    if (!_connected.isCompleted) throw StateError('send before open');
    sent.add(frame);
  }

  @override
  void close([int? code]) {
    if (closedByClient) return;
    closedByClient = true;
    clientCloseCode = code;
    if (!isClosed) {
      _closeCode = code;
      _incoming.close();
    }
  }

  /// A text frame from the server.
  void receive(String frame) {
    if (isClosed) throw StateError('receive on a closed socket');
    _incoming.add(frame);
  }

  /// A JSON object from the server.
  void receiveJson(Map<String, Object?> json) => receive(jsonEncode(json));

  /// A typed message from the server.
  void receiveMessage(ServerMessage message) => receive(message.encode());

  /// Closes from the server's side (or the network) with [code]: null for a
  /// loss with no close frame.
  void drop([int? code]) {
    if (isClosed) return;
    _closeCode = code;
    _incoming.close();
  }
}

/// A [GameSocketFactory] that makes [FakeGameSocket]s and keeps them, newest
/// last.
class FakeSocketFactory {
  FakeSocketFactory({this.autoOpen = true});

  /// Passed to each new socket; may be changed between attempts.
  bool autoOpen;

  final List<FakeGameSocket> sockets = [];

  FakeGameSocket get last => sockets.last;

  GameSocket call() {
    final socket = FakeGameSocket(autoOpen: autoOpen);
    sockets.add(socket);
    return socket;
  }
}

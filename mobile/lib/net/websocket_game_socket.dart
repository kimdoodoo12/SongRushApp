import 'dart:async';
import 'dart:developer' as developer;

import 'package:songrush_core/songrush_core.dart';
import 'package:web_socket_channel/web_socket_channel.dart';

/// Opens a channel to [url]; [WebSocketChannel.connect] unless a test
/// passes its own.
typedef ChannelConnector = WebSocketChannel Function(Uri url);

/// The app's [GameSocket] over web_socket_channel (the browser's WebSocket
/// on the web, dart:io's on a device).
///
/// [incoming] exists before [connect], as the session subscribes first; it
/// carries the channel's text frames, then its error if any, and is done
/// when the channel closes from either side. [connect] completes when the
/// channel is ready and fails when it cannot open (the channel's stream then
/// ends, so [incoming] is done too). A binary frame is not part of the
/// protocol: it is logged and dropped.
class WebSocketGameSocket implements GameSocket {
  WebSocketGameSocket(this.url, {ChannelConnector? connector, void Function(String message)? warn})
      : _connector = connector ?? WebSocketChannel.connect,
        _warn = warn ?? _log;

  static void _log(String message) => developer.log(message, name: 'songrush.socket');

  final Uri url;
  final ChannelConnector _connector;
  final void Function(String message) _warn;
  final StreamController<String> _incoming = StreamController();
  WebSocketChannel? _channel;
  bool _closed = false;

  @override
  Stream<String> get incoming => _incoming.stream;

  @override
  int? get closeCode => _channel?.closeCode;

  @override
  Future<void> connect() {
    if (_channel != null) throw StateError('connect called twice');
    if (_closed) return Future.error(StateError('connect after close'));
    final WebSocketChannel channel;
    try {
      channel = _connector(url);
    } catch (e) {
      // A malformed URL throws here rather than failing the future.
      _finish();
      return Future.error(e);
    }
    _channel = channel;
    channel.stream.listen(
      (Object? frame) {
        if (frame is String) {
          if (!_incoming.isClosed) _incoming.add(frame);
        } else {
          _warn('non-text frame dropped');
        }
      },
      onError: (Object error) {
        if (!_incoming.isClosed) _incoming.addError(error);
      },
      onDone: _finish,
    );
    return channel.ready;
  }

  @override
  void send(String frame) {
    final channel = _channel;
    if (channel == null || _closed) throw StateError('send on a socket that is not open');
    channel.sink.add(frame);
  }

  @override
  void close([int? code]) {
    if (_closed) return;
    _closed = true;
    final channel = _channel;
    if (channel == null) {
      _finish();
      return;
    }
    // The stream's done event closes [incoming].
    channel.sink.close(code).catchError((Object error) => _warn('close failed: $error'));
  }

  void _finish() {
    if (!_incoming.isClosed) _incoming.close();
  }
}

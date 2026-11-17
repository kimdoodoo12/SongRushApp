import 'package:flutter_test/flutter_test.dart';
import 'package:songrush_core/songrush_core.dart';
import 'package:songrush_mobile/net/server_url.dart';
import 'package:songrush_mobile/net/websocket_game_socket.dart';
import 'package:songrush_mobile/store/prefs.dart';
import 'package:songrush_mobile/store/session_token_store.dart';

void main() {
  group('socketUrl', () {
    test('is /ws on the page origin, wss on https', () {
      expect(socketUrl(override: '', page: Uri.parse('https://songrush.example/r/K7XQM')).toString(),
          'wss://songrush.example/ws');
      expect(socketUrl(override: '', page: Uri.parse('http://localhost:8080/')).toString(), 'ws://localhost:8080/ws');
    });

    test('takes the build-time override', () {
      expect(socketUrl(override: 'ws://192.168.0.5:8080/ws', page: Uri.parse('http://localhost:5000/')).toString(),
          'ws://192.168.0.5:8080/ws');
    });

    test('httpBase is the socket host over http(s)', () {
      expect(httpBase(Uri.parse('wss://songrush.example/ws')).toString(), 'https://songrush.example/');
      expect(httpBase(Uri.parse('ws://localhost:8080/ws')).toString(), 'http://localhost:8080/');
    });
  });

  test('codeFromPath reads /r/<CODE>, upper-cased and cut to 5', () {
    expect(codeFromPath('/r/k7xqm'), 'K7XQM');
    expect(codeFromPath('/r/K7XQM/'), 'K7XQM');
    expect(codeFromPath('/m/r/abcdefg'), 'ABCDE');
    expect(codeFromPath('/'), '');
    expect(codeFromPath('/admin'), '');
  });

  test('PrefsTokenStore keeps the web client entry format', () {
    final prefs = MemoryPrefs();
    final store = PrefsTokenStore(prefs);
    expect(store.read(), isNull);
    store.write(const RejoinEntry(token: 'secret', code: 'K7XQM'));
    expect(prefs.read(rejoinKey), '{"token":"secret","code":"K7XQM"}');
    expect(store.read(), const RejoinEntry(token: 'secret', code: 'K7XQM'));
    prefs.write(rejoinKey, '{not json');
    expect(store.read(), isNull);
    prefs.write(rejoinKey, '{"token":1,"code":"K7XQM"}');
    expect(store.read(), isNull);
    store.clear();
    expect(prefs.read(rejoinKey), isNull);
  });

  test('a socket whose channel cannot be made fails connect and ends incoming', () async {
    final socket = WebSocketGameSocket(
      Uri.parse('ws://localhost:1/ws'),
      connector: (_) => throw ArgumentError('bad url'),
    );
    final done = socket.incoming.toList();
    await expectLater(socket.connect(), throwsArgumentError);
    expect(await done, isEmpty);
  });
}

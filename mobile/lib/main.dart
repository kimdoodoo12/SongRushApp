import 'package:material_ui/material_ui.dart';

import 'app.dart';
import 'app/app_controller.dart';
import 'audio/soloud_player.dart';
import 'net/http_fetcher.dart';
import 'net/server_url.dart';
import 'net/websocket_game_socket.dart';
import 'platform/page.dart';
import 'store/browser_storage.dart';
import 'store/prefs.dart';
import 'store/session_token_store.dart';

/// Wires the real adapters: the WebSocket to `/ws` on the page's origin (or
/// `--dart-define=SONGRUSH_SERVER=ws://...`), the SoLoud engine (started by
/// the first 확인 tap, the browser's sound unlock), the HTTP fetcher, and the
/// browser's storage (sessionStorage for the rejoin entry and localStorage
/// for the nickname, the language and the sound offset, as the web client;
/// memory off the web), the page's reload check (a reload rejoins the stored
/// room), the Screen Wake Lock and the page navigation of the PC version
/// link (both none off the web).
void main() {
  WidgetsFlutterBinding.ensureInitialized();
  final socket = socketUrl();
  final engine = SoloudEngine();
  final page = Uri.base;
  final web = page.scheme == 'http' || page.scheme == 'https';
  final controller = AppController(
    socketFactory: () => WebSocketGameSocket(socket),
    tokenStore: PrefsTokenStore(browserSessionStorage() ?? MemoryPrefs()),
    createPlayer: engine.createPlayer,
    fetcher: HttpFetcher(base: httpBase(socket)),
    localPrefs: browserLocalStorage() ?? MemoryPrefs(),
    unlockSound: engine.start,
    keepAwake: screenWakeLock(),
    navigate: pageNavigator(),
    pageReloaded: web && pageWasReloaded(),
    deviceLocales: WidgetsBinding.instance.platformDispatcher.locales,
    initialCode: web ? codeFromPath(page.path) : '',
    pageOrigin: web ? page.origin : '',
  );
  runApp(SongRushApp(controller: controller));
}

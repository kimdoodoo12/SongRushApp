/// A server URL given at build time (`--dart-define=SONGRUSH_SERVER=ws://...`),
/// for local runs where the app is not served by the backend; empty
/// otherwise.
const String serverOverride = String.fromEnvironment('SONGRUSH_SERVER');

/// The game socket's URL: [override] when set, else `/ws` on the page's
/// origin ([page], `Uri.base` on the web), as the web client's `socketUrl`:
/// `wss` on an https page, `ws` otherwise.
Uri socketUrl({String override = serverOverride, Uri? page}) {
  if (override.isNotEmpty) return Uri.parse(override);
  final base = page ?? Uri.base;
  return Uri(
    scheme: base.scheme == 'https' ? 'wss' : 'ws',
    host: base.host,
    port: base.hasPort ? base.port : null,
    path: '/ws',
  );
}

/// The origin the server's `/audio/` and `/cover/` paths resolve against:
/// the socket's host over http(s).
Uri httpBase(Uri socket) => Uri(
      scheme: socket.scheme == 'wss' ? 'https' : 'http',
      host: socket.host,
      port: socket.hasPort ? socket.port : null,
      path: '/',
    );

/// The room code of a `/r/<CODE>` share link (the web's `codeFromPath`),
/// upper-cased and cut to 5; '' for any other path. The link may sit under
/// a base path (`/m/r/ABCDE`).
String codeFromPath(String path) {
  final match = RegExp(r'(?:^|/)r/([^/]+)/?$').firstMatch(path);
  if (match == null) return '';
  final code = match.group(1)!.toUpperCase();
  return code.length > 5 ? code.substring(0, 5) : code;
}

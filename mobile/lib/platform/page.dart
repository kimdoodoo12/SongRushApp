// The browser page's own APIs on the web build (the navigation type, the
// Screen Wake Lock, leaving for another path of the site); no-ops elsewhere
// (tests on the VM, a device build).
export 'page_stub.dart' if (dart.library.js_interop) 'page_web.dart';

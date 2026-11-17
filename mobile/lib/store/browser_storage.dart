// The browser's localStorage and sessionStorage as [Prefs] on the web build;
// null elsewhere (tests on the VM, a device build), where the app uses
// MemoryPrefs.
export 'browser_storage_stub.dart' if (dart.library.js_interop) 'browser_storage_web.dart';

// The on-screen keyboard's height read from the browser on the web build
// (cycle 161); none elsewhere (tests on the VM, a device build, where
// Flutter's own MediaQuery.viewInsets stays).
export 'keyboard_inset_stub.dart' if (dart.library.js_interop) 'keyboard_inset_web.dart';
export 'keyboard_inset_value.dart';

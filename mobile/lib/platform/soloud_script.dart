// flutter_soloud's web start-up script (init_soloud.js), loaded when the
// sound engine first starts rather than with the page (cycle 159), and the
// state of the browser's AudioContext the engine plays through, on the web
// build; nothing to load and no context elsewhere (tests on the VM, a
// device build).
export 'soloud_script_stub.dart' if (dart.library.js_interop) 'soloud_script_web.dart';

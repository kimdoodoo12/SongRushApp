// The browser's AudioContext.outputLatency of the context SoLoud plays
// through, on the web build; null elsewhere (tests on the VM, a device
// build).
export 'audio_latency_stub.dart' if (dart.library.js_interop) 'audio_latency_web.dart';
export 'latency_value.dart';

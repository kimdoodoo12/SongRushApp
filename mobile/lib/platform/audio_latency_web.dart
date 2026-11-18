import 'dart:developer' as developer;
import 'dart:js_interop';
import 'dart:js_interop_unsafe';

import 'latency_value.dart';

void _warn(String message) => developer.log(message, name: 'songrush.audio');

/// `outputLatency` of the AudioContext SoLoud plays through, in seconds:
/// miniaudio's first device (`window.miniaudio.get_device_by_index(0)`,
/// SoLoud opens one) and its `webaudio` context. Null when miniaudio, the
/// device or the context is missing, or the value is not a finite number.
double? browserOutputLatency() {
  try {
    final miniaudio = globalContext.getProperty<JSAny?>('miniaudio'.toJS);
    if (miniaudio == null || !miniaudio.isA<JSObject>()) return null;
    final ma = miniaudio as JSObject;
    if (!ma.has('get_device_by_index')) return null;
    final device = ma.callMethod<JSAny?>('get_device_by_index'.toJS, 0.toJS);
    if (device == null || !device.isA<JSObject>()) return null;
    final context = (device as JSObject).getProperty<JSAny?>('webaudio'.toJS);
    if (context == null || !context.isA<JSObject>()) return null;
    return finiteLatency((context as JSObject).getProperty<JSAny?>('outputLatency'.toJS).dartify());
  } catch (e) {
    _warn('outputLatency unavailable: $e');
    return null;
  }
}

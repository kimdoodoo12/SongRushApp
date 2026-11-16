// S-009 spike (cycle 142): a throwaway Flutter web page that joins a room,
// loads each song with flutter_soloud from memory and starts it at the
// server's startAt. Query parameters:
//   server=ws://host:8080/ws  (default: this page's host, /ws)
//   code=ABCDE&name=Spike     (join after the button tap)
//   mode=scheduled|timer      (engine-clock playScheduled, or play() on a Dart timer)
//   adj=ms                    (extra delay subtracted from the start, for tuning by ear)
//   selftest=<url>            (no server: load the url, play it seeked to 10 s)
//   auto=1                    (skip the button; only for headless automation)
import 'dart:async';
import 'dart:convert';
import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:flutter_soloud/flutter_soloud.dart';
import 'package:http/http.dart' as http;
import 'package:web_socket_channel/web_socket_channel.dart';

import 'clock_sync.dart';

void main() => runApp(const MaterialApp(home: SpikePage()));

// A monotonic local clock in epoch ms: Date.now() once, then the stopwatch
// (performance.now() on the web), so it has sub-ms steps and never jumps.
final int _epochAnchor = DateTime.now().millisecondsSinceEpoch;
final Stopwatch _mono = Stopwatch()..start();
double nowMs() => _epochAnchor + _mono.elapsedMicroseconds / 1000;

String _f(num v) => v.toStringAsFixed(1);

// A timer delay of ms (never negative). Not clamp(0, 1 << 40): dart2js shifts
// in 32 bits, so that bound is 256 us on the web.
Duration _after(double ms) =>
    Duration(microseconds: ms <= 0 ? 0 : (ms * 1000).round());

class SpikePage extends StatefulWidget {
  const SpikePage({super.key});

  @override
  State<SpikePage> createState() => _SpikePageState();
}

class _SpikePageState extends State<SpikePage> {
  final Map<String, String> _q = Uri.base.queryParameters;
  late final TextEditingController _server =
      TextEditingController(text: _q['server'] ?? _defaultServer());
  late final TextEditingController _code =
      TextEditingController(text: _q['code'] ?? '');
  late final TextEditingController _name =
      TextEditingController(text: _q['name'] ?? 'Spike');
  late final bool _timerMode = _q['mode'] == 'timer';
  late final double _adjMs = double.tryParse(_q['adj'] ?? '') ?? 0;

  final List<String> _log = [];
  String _status = '';
  bool _started = false;

  WebSocketChannel? _ws;
  ClockSync? _sync;
  final http.Client _http = http.Client();

  // The current song.
  int? _index;
  AudioSource? _source;
  SoundHandle? _handle;
  Timer? _startTimer;
  Timer? _stopTimer;
  Timer? _checkTimer;
  Map<String, dynamic>? _pendingStart;

  static String _defaultServer() {
    final base = Uri.base;
    final scheme = base.scheme == 'https' ? 'wss' : 'ws';
    return Uri(scheme: scheme, host: base.host, port: base.port, path: '/ws')
        .toString();
  }

  @override
  void initState() {
    super.initState();
    log('page ${Uri.base} mode=${_timerMode ? 'timer' : 'scheduled'} adj=$_adjMs');
    if (_q['auto'] == '1') {
      WidgetsBinding.instance.addPostFrameCallback((_) => _go());
    }
  }

  void log(String line) {
    final text = '${DateTime.now().toIso8601String().substring(11, 23)} $line';
    // ignore: avoid_print
    print('[s009] $text');
    if (!mounted) return;
    setState(() {
      _log.add(text);
      if (_log.length > 400) _log.removeAt(0);
    });
  }

  void _setStatus(String s) {
    if (mounted) setState(() => _status = s);
  }

  Future<void> _go() async {
    if (_started) return;
    setState(() => _started = true);
    try {
      if (!SoLoud.instance.isInitialized) {
        await SoLoud.instance.init();
      }
      log('soloud init ok; engine=${SoLoud.instance.getEngineTime().inMicroseconds / 1000} ms'
          ' hwLatency=${_hwLatencyMs()} ms');
    } catch (e) {
      log('ERROR soloud init: $e');
      return;
    }
    final selftest = _q['selftest'];
    if (selftest != null) {
      await _selfTest(selftest);
      return;
    }
    if (_code.text.isNotEmpty) _connect();
  }

  double _hwLatencyMs() =>
      SoLoud.instance.getHardwareOutputLatency().inMicroseconds / 1000;

  // ---- self-test: Opus from memory, seek, position, scheduled start ----

  Future<void> _selfTest(String url) async {
    try {
      final bytes = await _download(Uri.base.resolve(url));
      final src = await SoLoud.instance.loadMem('selftest-$url', bytes);
      log('selftest loaded ${bytes.length} bytes, length='
          '${SoLoud.instance.getLength(src).inMilliseconds} ms');
      final h = SoLoud.instance.play(src, paused: true);
      SoLoud.instance.seek(h, const Duration(seconds: 10));
      log('selftest after seek(10 s) position='
          '${SoLoud.instance.getPosition(h).inMilliseconds} ms');
      SoLoud.instance.setPause(h, false);
      await Future<void>.delayed(const Duration(milliseconds: 1000));
      log('selftest 1000 ms after unpause position='
          '${SoLoud.instance.getPosition(h).inMilliseconds} ms '
          'hwLatency=${_hwLatencyMs()} ms');
      await SoLoud.instance.stop(h);
      final e0 = SoLoud.instance.getEngineTime();
      final at = e0 + const Duration(milliseconds: 500);
      final h2 = SoLoud.instance.playScheduled(src, at,
          duration: const Duration(milliseconds: 1500));
      log('selftest playScheduled at engine ${at.inMilliseconds} ms (now ${e0.inMilliseconds})');
      await Future<void>.delayed(const Duration(milliseconds: 1000));
      log('selftest 1000 ms later engine=${SoLoud.instance.getEngineTime().inMilliseconds} ms '
          'position=${SoLoud.instance.getPosition(h2).inMilliseconds} ms (expect ~500)');
      await Future<void>.delayed(const Duration(milliseconds: 1500));
      log('selftest after auto-stop valid=${SoLoud.instance.getIsValidVoiceHandle(h2)}');
      log('SELFTEST DONE');
    } catch (e) {
      log('ERROR selftest: $e');
    }
  }

  // ---- socket ----

  Uri get _httpBase {
    final ws = Uri.parse(_server.text);
    return ws.replace(scheme: ws.scheme == 'wss' ? 'https' : 'http', path: '/');
  }

  void _connect() {
    final uri = Uri.parse(_server.text);
    log('connecting $uri');
    final WebSocketChannel ws;
    try {
      ws = WebSocketChannel.connect(uri);
    } catch (e) {
      log('ERROR connect: $e');
      return;
    }
    _ws = ws;
    _sync = ClockSync(now: nowMs, send: _send);
    ws.ready.then((_) {
      log('socket open');
      _send({'type': 'join', 'code': _code.text.trim(), 'nickname': _name.text.trim()});
    }, onError: (Object e) => log('ERROR socket: $e'));
    ws.stream.listen(
      (data) {
        if (data is String) _onMessage(data);
      },
      onError: (Object e) => log('ERROR socket stream: $e'),
      onDone: () => log('socket closed code=${ws.closeCode} reason=${ws.closeReason}'),
    );
  }

  void _send(Map<String, Object?> msg) {
    _ws?.sink.add(jsonEncode(msg));
  }

  void _onMessage(String text) {
    final Map<String, dynamic> msg;
    try {
      msg = jsonDecode(text) as Map<String, dynamic>;
    } catch (e) {
      log('ERROR bad frame: $e');
      return;
    }
    switch (msg['type']) {
      case 'pong':
        final rtt = _sync!.onPong(msg);
        final est = _sync!.current();
        if (rtt != null && est != null) {
          _setStatus('offset ${_f(est.offsetMs)} ms, best rtt ${_f(est.rttMs)} ms');
          log('pong rtt=${_f(rtt)} -> offset=${_f(est.offsetMs)} bestRtt=${_f(est.rttMs)}');
        }
      case 'token':
        log('token received');
      case 'joined':
        log('joined code=${msg['code']} name=${msg['name']}');
        _sync!.burst(8);
      case 'roster':
        log('roster ${msg['players'] ?? ''}');
      case 'lobbySettings':
        log('lobbySettings');
      case 'prepare':
        _onPrepare(msg);
      case 'start':
        _onStart(msg);
      case 'reveal':
        log('reveal index=${msg['index']}');
        _stopSong('reveal');
      case 'scores':
        log('scores');
      case 'gameOver':
        log('gameOver');
        _stopSong('gameOver');
      case 'error':
        log('server error ${msg['code']}');
      case 'chat':
        log('chat ${msg['name']}: ${msg['text']}');
      default:
        break;
    }
  }

  // ---- songs ----

  Future<Uint8List> _download(Uri url) async {
    // The browser's fetch follows the S3 302 itself.
    final res = await _http.get(url);
    if (res.statusCode != 200) {
      throw Exception('audio fetch ${res.statusCode} for $url');
    }
    return res.bodyBytes;
  }

  Future<void> _onPrepare(Map<String, dynamic> msg) async {
    final index = msg['index'] as int;
    log('prepare index=$index/${msg['total']} holdMs=${msg['holdMs']}');
    await _stopSong('new prepare');
    final old = _source;
    _source = null;
    _pendingStart = null;
    _index = index;
    if (old != null) await SoLoud.instance.disposeSource(old);
    final url = _httpBase.resolve(msg['audioUrl'] as String);
    final t0 = nowMs();
    try {
      final bytes = await _download(url);
      final t1 = nowMs();
      final src = await SoLoud.instance.loadMem('song-$index-${t0.round()}', bytes);
      final t2 = nowMs();
      if (_index != index) {
        await SoLoud.instance.disposeSource(src);
        return;
      }
      _source = src;
      log('loaded index=$index ${bytes.length} B download=${_f(t1 - t0)} ms '
          'decode=${_f(t2 - t1)} ms length=${SoLoud.instance.getLength(src).inMilliseconds} ms');
      _send({'type': 'ready', 'index': index});
      _sync!.burst(3);
      final pending = _pendingStart;
      if (pending != null) {
        _pendingStart = null;
        _schedule(pending);
      }
    } catch (e) {
      log('ERROR load index=$index: $e');
    }
  }

  void _onStart(Map<String, dynamic> msg) {
    final serverNow = (msg['serverTime'] as num?)?.toDouble();
    log('start index=${msg['index']} startAt=${msg['startAt']} deadlineAt=${msg['deadlineAt']} '
        'serverTime=$serverNow');
    if (msg['index'] != _index) return;
    if (_source == null) {
      log('start before the load finished; it plays when loaded (late)');
      _pendingStart = msg;
      return;
    }
    _schedule(msg);
  }

  // Engine time minus local time, measured over ~120 ms: the engine clock
  // steps once per mix buffer, so the largest difference is the one read
  // right after a step.
  Future<double> _engineMinusLocal() async {
    var best = double.negativeInfinity;
    for (var i = 0; i < 24; i++) {
      final d = SoLoud.instance.getEngineTime().inMicroseconds / 1000 - nowMs();
      if (d > best) best = d;
      await Future<void>.delayed(const Duration(milliseconds: 5));
    }
    return best;
  }

  Future<void> _schedule(Map<String, dynamic> msg) async {
    final est = _sync!.current();
    if (est == null) {
      log('ERROR no clock estimate yet; using offset 0');
    }
    final offset = est?.offsetMs ?? 0;
    final src = _source!;
    final index = _index;
    final hw = _hwLatencyMs();
    final lead = hw + _adjMs;
    // Local epoch ms at which the first sample must be heard / stop.
    final target = (msg['startAt'] as num).toDouble() - offset;
    final stopLocal = (msg['deadlineAt'] as num).toDouble() - offset;
    log('plan offset=${_f(offset)} rtt=${_f(est?.rttMs ?? -1)} target=${_f(target)} '
        '(in ${_f(target - nowMs())} ms) hwLatency=${_f(hw)} adj=${_f(_adjMs)}');

    if (_timerMode || target - lead - nowMs() < 200) {
      // play() on a Dart timer; a late start seeks by the lateness.
      final wait = target - lead - nowMs();
      _startTimer = Timer(_after(wait), () {
        if (_index != index) return;
        final called = nowMs();
        final late = called - (target - lead);
        final h = SoLoud.instance.play(src, paused: true);
        if (late > 0) SoLoud.instance.seek(h, Duration(microseconds: (late * 1000).round()));
        SoLoud.instance.setPause(h, false);
        _handle = h;
        log('play() called at ${_f(called)} (target-lead ${_f(target - lead)}, '
            '${late > 0 ? 'late, seek ${_f(late)}' : 'early ${_f(-late)}'} ms)');
        _stopTimer = Timer(_after(stopLocal - lead - nowMs()),
            () => _stopSong('deadline'));
        _check(h, target, lead);
      });
      return;
    }

    // Engine-clock scheduling: map the local target onto getEngineTime().
    final diff = await _engineMinusLocal();
    if (_index != index) return;
    final engineAt = target - lead + diff;
    final engineStop = stopLocal - lead + diff;
    final called = nowMs();
    final h = SoLoud.instance.playScheduled(
        src, Duration(microseconds: (engineAt * 1000).round()));
    SoLoud.instance.stopScheduled(h, Duration(microseconds: (engineStop * 1000).round()));
    _handle = h;
    log('playScheduled called at ${_f(called)} engineAt=${_f(engineAt)} '
        'engineNow=${_f(SoLoud.instance.getEngineTime().inMicroseconds / 1000)} '
        'engine-local=${_f(diff)} stopAt engine ${_f(engineStop)}');
    _check(h, target, lead);
  }

  // Logs SoLoud's own position against the one the plan expects.
  void _check(SoundHandle h, double target, double lead) {
    _checkTimer?.cancel();
    final due = target - lead + 1000 - nowMs();
    _checkTimer = Timer(_after(due), () {
      if (_handle != h) return;
      final pos = SoLoud.instance.getPosition(h).inMicroseconds / 1000;
      final expected = nowMs() - (target - lead);
      log('check position=${_f(pos)} ms expected=${_f(expected)} ms '
          'error=${_f(pos - expected)} ms (engine view; not acoustic) hwLatency=${_f(_hwLatencyMs())}');
    });
  }

  Future<void> _stopSong(String why) async {
    _startTimer?.cancel();
    _stopTimer?.cancel();
    _checkTimer?.cancel();
    final h = _handle;
    _handle = null;
    if (h != null && SoLoud.instance.getIsValidVoiceHandle(h)) {
      log('stop ($why)');
      await SoLoud.instance.stop(h);
    }
  }

  @override
  void dispose() {
    _sync?.stop();
    _ws?.sink.close();
    _http.close();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('S-009 audio spike')),
      body: Padding(
        padding: const EdgeInsets.all(8),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            TextField(controller: _server, decoration: const InputDecoration(labelText: 'server')),
            Row(children: [
              Expanded(child: TextField(controller: _code, decoration: const InputDecoration(labelText: 'code'))),
              const SizedBox(width: 8),
              Expanded(child: TextField(controller: _name, decoration: const InputDecoration(labelText: 'name'))),
            ]),
            const SizedBox(height: 8),
            SizedBox(
              height: 64,
              child: FilledButton(
                onPressed: _started ? null : _go,
                child: const Text('소리 켜고 참가', style: TextStyle(fontSize: 22)),
              ),
            ),
            const SizedBox(height: 8),
            Text(_status),
            const Divider(),
            Expanded(
              child: ListView.builder(
                reverse: true,
                itemCount: _log.length,
                itemBuilder: (_, i) => Text(_log[_log.length - 1 - i],
                    style: const TextStyle(fontFamily: 'monospace', fontSize: 12)),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

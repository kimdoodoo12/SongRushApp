import 'dart:async';
import 'dart:developer' as developer;
import 'dart:math' as math;
import 'dart:typed_data';

import '../protocol/models.dart';
import '../protocol/server_message.dart';
import '../session/session.dart';
import '../session/session_state.dart';
import '../sync/playback.dart';
import 'playback_settings.dart';
import 'player.dart';

/// A reveal's or a choice's cover path (cycle 097/100): the server's
/// CoverMap.ID rule under /cover/. Anything else is never fetched.
final RegExp coverUrlPattern = RegExp(r'^/cover/[A-Za-z0-9_-]{1,64}$');

/// A frame's choices as their cover URLs, in order, or null when it has
/// none. A coverUrl that breaks [coverUrlPattern] stays as null, so the
/// positions still match the server's (session.js `choiceUrls`).
List<String?>? choiceUrls(List<Choice>? choices) {
  if (choices == null || choices.isEmpty) return null;
  return [for (final c in choices) coverUrlPattern.hasMatch(c.coverUrl) ? c.coverUrl : null];
}

/// The in-song drift check runs this often while a song plays (cycle 142).
const Duration driftCheckInterval = Duration(seconds: 1);

/// What the round controller tells the screen (the audio part of
/// session.js's dispatches).
enum RoundEventType {
  /// A prepare is held behind the last reveal ([RoundController.held]): the
  /// screen keeps that reveal; [prepared] follows when the hold ends.
  held,

  /// A new song is on (a prepare, or a held one whose hold ended).
  prepared,

  /// Its audio is loaded / failed twice (the song then plays silent).
  loaded,
  loadFailed,

  /// The song's start is known: it plays from the server's moment once
  /// loaded. Also for a snapshot's late start.
  started,

  /// The song's reveal; [RoundEvent.window] says the music plays on.
  revealed,

  /// A snapshot taken in a reveal window began that window here.
  windowOpen,

  /// The reveal window is over and its music stopped.
  windowEnd,

  /// A lobby roster during a game: the game was abandoned.
  abandoned,
}

/// One event of [RoundController.events] for the song [index].
class RoundEvent {
  const RoundEvent(this.type, this.index, {this.window = false});

  final RoundEventType type;

  /// The song's index; null for [RoundEventType.abandoned].
  final int? index;

  /// [RoundEventType.revealed] only: a reveal window opened.
  final bool window;

  @override
  bool operator ==(Object other) =>
      other is RoundEvent && other.type == type && other.index == index && other.window == window;

  @override
  int get hashCode => Object.hash(type, index, window);

  @override
  String toString() => 'RoundEvent(${type.name}, $index${window ? ', window' : ''})';
}

// A round's start: the server's moments and the offset in use (a
// provisional start's offset moves earlier, spec 051).
class _Start {
  _Start({required this.startAt, required this.deadlineAt, required this.offsetMs});

  final int startAt;
  final int deadlineAt;
  double offsetMs;
}

enum _CoverState { loading, loaded, failed }

// A cover fetched for a round or ahead of one (cycle 135): its bytes once
// loaded, and the callbacks to run when a load settles.
class _Cover {
  _CoverState state = _CoverState.loading;
  Uint8List? bytes;
  List<void Function()> waiters = [];
}

// The current song (session.js `newRound`). revealed: the answer is out;
// over: the music is finished, so a late load, the clock correction and the
// late start are ignored. held: a prepare with holdMs whose `prepared` is
// not told yet; outcome is its load's result, told when the hold ends.
// lateStart: a snapshot song's moments, kept until a clock estimate exists.
class _Round {
  _Round(this.index, {required this.watching, required this.lateStart, required this.choices});

  final int index;
  final bool watching;
  final ({int startAt, int deadlineAt})? lateStart;
  final List<String?>? choices;

  bool revealed = false;
  bool over = false;
  Timer? windowTimer;
  // The local time (epoch ms) the reveal window ends, or null.
  double? windowEndMs;
  SongPlayer? player;
  bool loaded = false;
  bool playing = false;
  _Start? start;
  bool provisional = false;
  bool audioDone = false;
  int imagesLeft = 0;
  bool readySent = false;
  bool held = false;
  RoundEventType? outcome;
  RevealMessage? snapshotReveal;

  // The plan in use: the output latency (s) and the manual offset (ms) it
  // took, the local time the sound stops, and the drift check's state.
  double planLatencySec = 0;
  int planManualOffsetMs = 0;
  double stopLocalMs = 0;
  Timer? driftTimer;
  int? lastCorrectionAtMs;
  double? lastDriftMs;

  // The player's lead on the server's moment, in seconds (planPlayback's).
  double get leadSec => planLatencySec - planManualOffsetMs / 1000;
}

/// Plays each song of a [Session]'s room at the server's moment, as the web
/// client's session.js drives Web Audio (cycle 147, F-M3).
///
/// On `prepare` it downloads the song through [Fetcher] into a new [SongPlayer]
/// (one retry, as playback.js `loadTrack`) and the choice covers, then sends
/// `ready`: after the audio has loaded or failed and every cover has loaded
/// or failed. A reveal's `nextChoices` are fetched ahead and reused. On
/// `start` it plans the song with [planPlayback] over
/// [Session.startOffsetFor], the player's output latency and the
/// [manualOffsetMs]; a song loaded after its start seeks in. A provisional
/// start moves earlier on a pong whose lower bound says so (stop and
/// reschedule). A `reveal` with a `playUntil` keeps the music to
/// `min(playUntil, deadlineAt)`; a `prepare` with `holdMs` during a reveal
/// leaves the last song playing until the hold ends or the next start. A
/// snapshot's song starts late at the first clock estimate. While a song
/// plays, its position is checked every [driftCheckInterval] against the
/// server's and corrected with [checkDrift].
///
/// It ends with the session (or [dispose]); a drop stops the sound.
class RoundController {
  RoundController({
    required Session session,
    required this._createPlayer,
    required this._fetcher,
    PlaybackSettingsStore? settings,
    int Function()? now,
    void Function(String message)? warn,
  })  : _session = session,
        _settings = settings ?? MemoryPlaybackSettingsStore(),
        _now = now ?? _systemNow,
        _warn = warn ?? _log {
    _manualOffsetMs = clampManualOffset(_settings.readManualOffsetMs() ?? 0);
    _status = session.state.status;
    _joined = _status == SessionStatus.inRoom;
    _messageSub = session.messages.listen(_onMessage, onDone: dispose);
    _stateSub = session.states.listen(_onState);
  }

  static void _log(String message) => developer.log(message, name: 'songrush.audio');

  static int _systemNow() => DateTime.now().millisecondsSinceEpoch;

  final Session _session;
  final SongPlayerFactory _createPlayer;
  final Fetcher _fetcher;
  final PlaybackSettingsStore _settings;
  final int Function() _now;
  final void Function(String message) _warn;

  late final StreamSubscription<ServerMessage> _messageSub;
  late final StreamSubscription<SessionState> _stateSub;
  final StreamController<RoundEvent> _events = StreamController.broadcast();

  bool _active = true;
  late SessionStatus _status;
  late bool _joined;
  late int _manualOffsetMs;
  _Round? _round;
  // The round a held prepare replaced while its reveal window still plays
  // (cycle 129): it runs until the hold ends, the next start, its window's
  // end, gameOver or a rejoin snapshot.
  _Round? _prev;
  // A game is on (prepare or a playing snapshot) and its gameOver has not
  // arrived; a lobby roster then means it was abandoned.
  bool _inGame = false;
  Timer? _holdTimer;
  // Covers by URL (cycle 135): this round's and the next one's.
  final Map<String, _Cover> _covers = {};

  /// What the screen needs to follow the songs.
  Stream<RoundEvent> get events => _events.stream;

  /// The current song's index, or null.
  int? get currentIndex => _round?.index;

  /// The current song is a prepare still held behind the last reveal.
  bool get held => _round?.held ?? false;

  /// A cover's bytes once loaded (a choice of this song, or of the next one
  /// fetched during the reveal); null otherwise.
  Uint8List? cover(String url) {
    final entry = _covers[url];
    return entry != null && entry.state == _CoverState.loaded ? entry.bytes : null;
  }

  /// The player's manual offset in ms (positive plays later), from the
  /// settings store; set values are clamped to
  /// [manualOffsetMinMs]..[manualOffsetMaxMs], stored, and used from the
  /// next plan (the song playing keeps its plan).
  int get manualOffsetMs => _manualOffsetMs;

  set manualOffsetMs(int ms) {
    _manualOffsetMs = clampManualOffset(ms);
    _settings.writeManualOffsetMs(_manualOffsetMs);
  }

  /// The local time (epoch ms) of the current song's last drift correction,
  /// or null.
  int? get lastDriftCorrectionAtMs => _round?.lastCorrectionAtMs;

  /// The current song's last measured drift in ms (positive: the player is
  /// ahead), or null before the first check.
  double? get lastDriftMs => _round?.lastDriftMs;

  /// Stops every sound and timer; the controller is done. Called when the
  /// session's messages end.
  void dispose() {
    if (!_active) return;
    _active = false;
    _clearHold();
    final r = _round;
    if (r != null) _retire(r);
    _stopPrev();
    _clearCovers();
    _messageSub.cancel();
    _stateSub.cancel();
    _events.close();
  }

  void _emit(RoundEventType type, int? index, {bool window = false}) {
    if (_active) _events.add(RoundEvent(type, index, window: window));
  }

  static Duration _delay(double ms) => Duration(microseconds: ms <= 0 ? 0 : (ms * 1000).round());

  // --- session ---

  void _onState(SessionState state) {
    final before = _status;
    _status = state.status;
    if (_status == before) return;
    if (_status == SessionStatus.reconnecting) {
      // The sound stops; the join is forgotten until the rejoin's joined
      // (session.js beginReconnect). A held prepare's previous round is left
      // to the rejoin snapshot or its own window end.
      _joined = false;
      _dropRound();
    } else if (_status == SessionStatus.ended) {
      dispose();
    }
  }

  void _onMessage(ServerMessage msg) {
    if (!_active) return;
    switch (msg) {
      case JoinedMessage():
        _joined = true;
      case PrepareMessage():
        _onPrepare(msg);
      case StartMessage():
        _onStart(msg);
      case RevealMessage():
        _onReveal(msg);
      case SnapshotMessage():
        _onSnapshot(msg);
      case PongMessage():
        final r = _round;
        if (r != null) {
          _lateStart(r);
          _correctStart(r);
        }
      case GameOverMessage():
        _clearHold();
        // A reveal window, current or held over, ends with the game.
        _stopPrev();
        final r = _round;
        if (r != null) _retire(r);
        _clearCovers();
        _inGame = false;
      case RosterMessage():
        if (msg.phase == 'lobby' && _inGame) _abandon();
      default:
        break;
    }
  }

  // --- rounds ---

  bool _current(_Round r) => _active && identical(_round, r) && !r.over;

  // A round's music ends: its timers go and its player is stopped and freed.
  void _retire(_Round r) {
    _clearWindow(r);
    r.driftTimer?.cancel();
    r.driftTimer = null;
    r.over = true;
    r.playing = false;
    final player = r.player;
    r.player = null;
    if (player != null) {
      player.stop();
      player.dispose();
    }
  }

  void _clearWindow(_Round r) {
    r.windowTimer?.cancel();
    r.windowTimer = null;
  }

  void _stopPrev() {
    final p = _prev;
    if (p == null) return;
    _retire(p);
    _prev = null;
  }

  void _clearHold() {
    _holdTimer?.cancel();
    _holdTimer = null;
  }

  // The current round ends (a drop, a rejoin snapshot, an abandoned game).
  void _dropRound() {
    _clearHold();
    final r = _round;
    if (r == null) return;
    final windowOpen = r.windowTimer != null && !r.over;
    _retire(r);
    r.revealed = true;
    if (windowOpen) _emit(RoundEventType.windowEnd, r.index);
    _round = null;
  }

  void _abandon() {
    _inGame = false;
    _dropRound();
    _stopPrev();
    _clearCovers();
    _emit(RoundEventType.abandoned, null);
  }

  void _onPrepare(PrepareMessage msg) {
    _clearHold();
    final last = _round;
    // A reveal is on screen: the last round was revealed, or is a held round
    // whose hold keeps the reveal still.
    final revealShown = last != null && (last.revealed || last.held);
    final holdMs = msg.holdMs;
    final hold = revealShown && holdMs != null && holdMs > 0;
    // A held prepare leaves the last round playing as prev until the hold
    // ends; any other prepare stops it.
    _stopPrev();
    if (last != null) {
      if (hold) {
        _prev = last;
      } else {
        _retire(last);
      }
    }
    final r = _Round(msg.index, watching: false, lateStart: null, choices: choiceUrls(msg.choices));
    _round = r;
    _inGame = true;
    if (hold) {
      r.held = true;
      _emit(RoundEventType.held, r.index);
      _holdTimer = Timer(Duration(milliseconds: holdMs), () {
        _holdTimer = null;
        if (_active && identical(_round, r)) _endHold(r);
      });
    } else {
      _showPrepared(r);
    }
    _load(r, msg.audioUrl);
  }

  // The round shows as the current song: the covers of older songs go.
  void _showPrepared(_Round r) {
    final keep = {...?r.choices};
    _covers.removeWhere((url, entry) {
      if (keep.contains(url)) return false;
      entry.waiters = [];
      return true;
    });
    _emit(RoundEventType.prepared, r.index);
  }

  // The hold ends (its timer, or the round's start): the last song's window
  // stops and the prepared round shows, then its load's result if known.
  void _endHold(_Round r) {
    _clearHold();
    if (!r.held) return;
    r.held = false;
    _stopPrev();
    _showPrepared(r);
    final outcome = r.outcome;
    if (outcome != null) _emit(outcome, r.index);
  }

  // A played round sends ready once its audio has loaded (or failed, so the
  // game is not held up) and every cover has loaded or failed. A watched
  // round, or one already running (a rejoin into PLAYING), sends none.
  void _maybeReady(_Round r) {
    if (r.watching || r.lateStart != null || r.readySent) return;
    if (!r.audioDone || r.imagesLeft > 0) return;
    r.readySent = true;
    _session.sendReady(r.index);
  }

  void _load(_Round r, String audioUrl) {
    _preloadChoices(r);
    final player = _createPlayer();
    r.player = player;
    _loadAudio(player, audioUrl, () => _current(r)).then(
      (_) {
        if (!_current(r)) return;
        r.loaded = true;
        r.audioDone = true;
        _loadOutcome(r, RoundEventType.loaded);
        _maybeReady(r);
        _schedule(r);
      },
      onError: (Object error) {
        if (!_current(r)) return;
        _warn('audio load failed twice: $error');
        r.audioDone = true;
        _loadOutcome(r, RoundEventType.loadFailed);
        _maybeReady(r);
      },
    );
  }

  void _loadOutcome(_Round r, RoundEventType outcome) {
    if (r.held) {
      r.outcome = outcome;
    } else {
      _emit(outcome, r.index);
    }
  }

  // One retry after a failed fetch or load; the second failure is thrown.
  // Bytes that arrive for a round no longer [current] are not loaded.
  Future<void> _loadAudio(SongPlayer player, String url, bool Function() current) async {
    try {
      final bytes = await _fetcher.fetch(url);
      if (current()) await player.load(bytes);
    } catch (error) {
      if (!current()) return;
      _warn('audio load failed, retrying: $error');
      final bytes = await _fetcher.fetch(url);
      if (current()) await player.load(bytes);
    }
  }

  // --- covers ---

  // The round's covers load before ready (spec 100 A3), so every player can
  // see them from the same moment; a failed one counts as settled. One
  // loaded during the reveal does not count; one still loading counts and
  // settles with that load; a failed one loads again.
  void _preloadChoices(_Round r) {
    final choices = r.choices;
    if (choices == null) return;
    void settled() {
      if (!_current(r)) return;
      r.imagesLeft -= 1;
      _maybeReady(r);
    }

    for (final url in choices) {
      if (url == null) continue;
      final stored = _covers[url];
      if (stored != null && stored.state == _CoverState.loaded) continue;
      r.imagesLeft += 1;
      final entry = stored != null && stored.state == _CoverState.loading ? stored : _fetchCover(url);
      entry.waiters.add(settled);
    }
  }

  // A reveal's nextChoices (cycle 134): each valid cover not already loaded
  // or loading is fetched now, so the next prepare can use it. A failed one
  // is left to that prepare.
  void _preloadNext(List<Choice>? nextChoices) {
    final urls = choiceUrls(nextChoices);
    if (urls == null) return;
    for (final url in urls) {
      if (url == null || _covers.containsKey(url)) continue;
      _fetchCover(url);
    }
  }

  _Cover _fetchCover(String url) {
    final entry = _Cover();
    _covers[url] = entry;
    void settle(_CoverState state, [Uint8List? bytes]) {
      if (entry.state != _CoverState.loading) return;
      entry.state = state;
      entry.bytes = bytes;
      final waiters = entry.waiters;
      entry.waiters = [];
      for (final fn in waiters) {
        fn();
      }
    }

    _fetcher.fetch(url).then(
      (bytes) => settle(_CoverState.loaded, bytes),
      onError: (Object error) {
        _warn('cover load failed: $error');
        settle(_CoverState.failed);
      },
    );
    return entry;
  }

  // The game ended or was left: the covers and their waiters go.
  void _clearCovers() {
    for (final entry in _covers.values) {
      entry.waiters = [];
    }
    _covers.clear();
  }

  // --- start and playback ---

  void _onStart(StartMessage msg) {
    final r = _round;
    // A song revealed before its start here (cycle 129, 8a) never starts.
    if (r == null || msg.index != r.index || r.over) return;
    if (r.held) _endHold(r);
    final offset = _session.startOffsetFor(msg.serverTime);
    r.provisional = offset.provisional;
    _beginStart(r, _Start(startAt: msg.startAt, deadlineAt: msg.deadlineAt, offsetMs: offset.offsetMs));
  }

  // The round's start is known; it plays once loaded.
  void _beginStart(_Round r, _Start start) {
    r.start = start;
    _stopPrev();
    _emit(RoundEventType.started, r.index);
    _schedule(r);
  }

  double _outputLatency(SongPlayer player) {
    final latency = player.outputLatencySec;
    return latency.isFinite && latency >= 0 ? latency : 0;
  }

  // Plays the round once both its audio and its start are known, whichever
  // comes last (a late load plays from the current point).
  void _schedule(_Round r) {
    final start = r.start;
    final player = r.player;
    if (!r.loaded || start == null || player == null || r.playing || r.over) return;
    final latency = _outputLatency(player);
    final manual = _manualOffsetMs;
    final nowMs = _now();
    final ctxTime = player.nowSec;
    final plan = planPlayback(
      startAt: start.startAt,
      deadlineAt: start.deadlineAt,
      offsetMs: start.offsetMs,
      nowMs: nowMs,
      ctxTime: ctxTime,
      outputLatency: latency,
      manualOffsetMs: manual,
    );
    if (plan == null) return;
    r.planLatencySec = latency;
    r.planManualOffsetMs = manual;
    var stopAt = plan.stopAt;
    var stopLocalMs = start.deadlineAt - start.offsetMs - r.leadSec * 1000;
    final windowEnd = r.windowEndMs;
    if (windowEnd != null) {
      // A reveal window already open: the music stops at its end.
      stopAt = math.min(stopAt, ctxTime + (windowEnd - nowMs) / 1000 - r.leadSec);
      stopLocalMs = math.min(stopLocalMs, windowEnd - r.leadSec * 1000);
      if (stopAt <= plan.when) return;
    }
    try {
      player.play(when: plan.when, offset: plan.offset, stopAt: stopAt);
    } catch (error) {
      // The round stays silent; ready and the screen go on.
      _warn('play failed: $error');
      return;
    }
    r.playing = true;
    r.stopLocalMs = stopLocalMs;
    r.driftTimer?.cancel();
    r.driftTimer = Timer.periodic(driftCheckInterval, (_) => _checkDrift(r));
  }

  // A provisional start moves earlier when a fresh pong's lower bound is
  // more than 20 ms above the offset in use; it never moves later, so it is
  // never early (spec 051).
  void _correctStart(_Round r) {
    final start = r.start;
    if (!r.provisional || r.over || start == null) return;
    final corrected = correctedStartOffset(
      offsetMs: start.offsetMs,
      lowerBound: _session.state.clockLowerBoundMs,
    );
    if (corrected == null) return;
    start.offsetMs = corrected;
    if (r.playing) {
      r.player?.stop();
      r.playing = false;
      r.driftTimer?.cancel();
      r.driftTimer = null;
    }
    _schedule(r);
  }

  // The in-song check (cycle 142): the player's position against the
  // server's, with the plan's latency and manual offset; a correction seeks.
  void _checkDrift(_Round r) {
    final start = r.start;
    final player = r.player;
    if (!_active || r.over || !r.playing || start == null || player == null) return;
    final nowMs = _now();
    // The sound has stopped (its deadline or window end).
    if (nowMs >= r.stopLocalMs) return;
    final position = player.positionSec;
    if (position == null) return;
    final expected = expectedPositionMs(
      startAt: start.startAt,
      offsetMs: start.offsetMs,
      nowMs: nowMs,
      outputLatency: r.planLatencySec,
      manualOffsetMs: r.planManualOffsetMs,
    );
    final drift = checkDrift(
      expectedMs: expected,
      reportedMs: position * 1000,
      nowMs: nowMs,
      lastCorrectionAtMs: r.lastCorrectionAtMs,
    );
    r.lastDriftMs = drift.driftMs;
    final seekTo = drift.seekToMs;
    if (seekTo == null) return;
    try {
      player.seek(seekTo / 1000);
    } catch (error) {
      _warn('drift seek failed: $error');
      return;
    }
    r.lastCorrectionAtMs = nowMs;
  }

  // --- reveal ---

  void _onReveal(RevealMessage msg) {
    final r = _round;
    double? windowEnd;
    final start = r?.start;
    if (r != null && start != null && !r.over) {
      windowEnd = revealWindowEnd(
        playUntil: msg.playUntil,
        deadlineAt: start.deadlineAt,
        offsetMs: start.offsetMs,
        nowMs: _now(),
      );
    }
    if (r != null) {
      r.revealed = true;
      r.provisional = false;
      _clearWindow(r);
      if (windowEnd == null) {
        _retire(r);
      } else {
        _openWindow(r, windowEnd);
      }
    }
    _emit(RoundEventType.revealed, msg.index, window: windowEnd != null);
    _preloadNext(msg.nextChoices);
  }

  // The music plays on to [windowEnd] (local epoch ms), then stops.
  void _openWindow(_Round r, double windowEnd) {
    r.windowEndMs = windowEnd;
    final player = r.player;
    if (r.playing && player != null) {
      final nowMs = _now();
      final stopAt = player.nowSec + (windowEnd - nowMs) / 1000 - r.leadSec;
      player.setStopAt(stopAt);
      r.stopLocalMs = windowEnd - r.leadSec * 1000;
    }
    r.windowTimer = Timer(_delay(windowEnd - _now()), () => _endWindow(r));
  }

  // The window's end: the music stops; the screen keeps the card.
  void _endWindow(_Round r) {
    r.windowTimer = null;
    if (!_active || r.over || (!identical(_round, r) && !identical(_prev, r))) return;
    _retire(r);
    if (identical(_prev, r)) _prev = null;
    _emit(RoundEventType.windowEnd, r.index);
  }

  // --- snapshots ---

  void _onSnapshot(SnapshotMessage msg) {
    if (!_joined) return;
    if (_session.isRejoin) {
      _onRejoinSnapshot(msg);
      return;
    }
    // joined, then a snapshot of a playing game: watch its current song.
    if (msg.phase != 'playing') return;
    _inGame = true;
    final last = _round;
    if (last != null) _retire(last);
    final song = msg.song;
    if (song == null) {
      _round = null;
      return;
    }
    _beginSnapshotSong(song, watching: true);
  }

  // A rejoin's snapshot rebuilds the round: PLAYING starts late once a clock
  // estimate exists, PREPARE loads and sends ready.
  void _onRejoinSnapshot(SnapshotMessage msg) {
    _clearHold();
    _dropRound();
    _stopPrev();
    final playing = msg.phase == 'playing';
    _inGame = playing;
    final song = playing ? msg.song : null;
    if (song == null) return;
    _beginSnapshotSong(song, watching: false);
  }

  void _beginSnapshotSong(SnapshotSong song, {required bool watching}) {
    final startAt = song.startAt;
    final deadlineAt = song.deadlineAt;
    final late = startAt != null && deadlineAt != null ? (startAt: startAt, deadlineAt: deadlineAt) : null;
    final r = _Round(song.index, watching: watching, lateStart: late, choices: choiceUrls(song.choices));
    _round = r;
    final reveal = song.reveal;
    if (reveal != null) {
      // A snapshot in a reveal window (cycle 131): revealed at once; the
      // window opens at the first clock estimate (_lateStart).
      r.revealed = true;
      r.snapshotReveal = reveal;
      // Without a startAt nothing can play from the right point.
      if (late == null) r.over = true;
      _emit(RoundEventType.revealed, r.index);
      _preloadNext(reveal.nextChoices);
    }
    _load(r, song.audioUrl);
    _lateStart(r);
  }

  // A snapshot's song already playing has no serverTime, so it starts late
  // once the first clock estimate exists (spec 048 review H1). One taken in
  // a reveal window begins only while its window is still open here.
  void _lateStart(_Round r) {
    final late = r.lateStart;
    if (late == null || r.start != null || r.over) return;
    final estimate = _session.state.clock;
    if (estimate == null) return;
    final start = _Start(startAt: late.startAt, deadlineAt: late.deadlineAt, offsetMs: estimate.offsetMs);
    final reveal = r.snapshotReveal;
    if (reveal == null) {
      _beginStart(r, start);
      return;
    }
    final nowMs = _now();
    final windowEnd = revealWindowEnd(
      playUntil: reveal.playUntil,
      deadlineAt: start.deadlineAt,
      offsetMs: start.offsetMs,
      nowMs: nowMs,
    );
    if (windowEnd == null) {
      r.over = true;
      return;
    }
    r.windowEndMs = windowEnd;
    _beginStart(r, start);
    _emit(RoundEventType.windowOpen, r.index);
    r.windowTimer = Timer(_delay(windowEnd - nowMs), () => _endWindow(r));
  }
}

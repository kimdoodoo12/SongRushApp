import 'dart:async';
import 'dart:math' as math;

import 'package:flutter/foundation.dart';
import 'package:songrush_core/songrush_core.dart';

// What the game and game-over screens show, as the web's reducer
// (frontend/src/game/state.js) builds it from session.js's dispatches: the
// Session's game frames and the RoundController's events, turned into
// display state. Nothing here plays sound; the RoundController does.

/// The screen a game shows: the song stage, or the final ranking.
enum GameView { game, over }

/// The song's audio, as state.js `load`.
enum LoadState { loading, loaded, failed }

/// One line of a chat log (state.js `chat`): what a player said, a correct
/// answer's system line, or a client-side notice.
sealed class LogLine {
  const LogLine();
}

/// A chat line as the server sent it.
final class SaidLine extends LogLine {
  const SaidLine({required this.name, required this.text});

  final String name;
  final String text;
}

/// `✔ <name> 정답!` (D-094); [rank] in a ranked game only. Not [placed]
/// while the winner's own echo may still come, which then goes above it.
final class CorrectLine extends LogLine {
  const CorrectLine({required this.winner, this.rank, this.placed = false});

  final String winner;
  final int? rank;
  final bool placed;
}

enum NoticeKind { lobbyJoined, lobbyBack, gameStart, songStart, gameOver, wrongPick }

/// A client-side notice (state.js `kind: 'notice'`), translated when shown:
/// [value] is the room code (lobbyJoined), the song number (songStart) or
/// the name (wrongPick).
final class NoticeLine extends LogLine {
  const NoticeLine(this.kind, [this.value = '']);

  final NoticeKind kind;
  final String value;
}

/// The time bar's clock (state.js `timer`), in ms.
class GameTimer {
  const GameTimer({required this.maxMs, required this.remainingMs});

  final int maxMs;
  final int remainingMs;
}

/// The display ticks this often while a song has a clock (session.js
/// `TICK_MS`).
const Duration tickInterval = Duration(milliseconds: 250);

// A song's moments and the offset (server minus local, ms) the screen's
// clock uses.
class _Clock {
  const _Clock({required this.startAt, required this.deadlineAt, required this.offsetMs});

  final int startAt;
  final int deadlineAt;
  final double offsetMs;
}

// The own pick of a song (session.js ownPick): the position (null when the
// server holds one of unknown position) and whether it was wrong.
class _OwnPick {
  const _OwnPick({required this.index, required this.choice, required this.wrong});

  final int index;
  final int? choice;
  final bool wrong;
}

/// The game's display state, from one [Session] and its [RoundController].
///
/// Game frames come from [Session.messages]; the song's progress (held,
/// prepared, loaded, started, revealed, the reveal window, abandoned) from
/// [RoundController.events], so a held prepare keeps the last reveal on
/// screen until the controller says the new song shows. The time bar counts
/// to the song's deadline on the server clock: the start's offset is
/// [startOffset] over the session's clock estimate at its receipt (a
/// snapshot's song: the estimate when it starts).
///
/// Actions: [sendChat] is the app's; [pick], [skipVote] and [toLobby] are
/// here. A choice room's covers come from [RoundController.cover] only, so
/// nothing marks the correct tile before the reveal; the reveal's own cover
/// is fetched after the reveal.
class GameModel extends ChangeNotifier {
  GameModel({
    required this._session,
    required this._rounds,
    required this._fetcher,
    required this._now,
    required this._warn,
  }) {
    _messageSub = _session.messages.listen(_onMessage);
    _eventSub = _rounds.events.listen(_onEvent);
  }

  final Session _session;
  final RoundController _rounds;
  final Fetcher _fetcher;
  final int Function() _now;
  final void Function(String message) _warn;
  late final StreamSubscription<ServerMessage> _messageSub;
  late final StreamSubscription<RoundEvent> _eventSub;
  bool _disposed = false;

  // --- what the screen reads ---

  /// null: no game on screen (the lobby).
  GameView? view;

  /// The song on screen: `index` from 0, of `total`.
  ({int index, int total})? round;
  LoadState? load;
  GameTimer? timer;

  /// The local clock has reached the started song's start.
  bool reached = false;

  /// Between the song's start and its reveal, or the end of its window.
  bool started = false;

  /// A reveal window is open: the revealed song plays on.
  bool windowOpen = false;

  /// The game log, oldest first.
  List<LogLine> log = const [];

  /// The first-come winner of this song, or null (always null in ranked).
  String? correct;

  /// Ranked: every name correct this song, in rank order.
  List<String> correctNames = const [];

  /// The song's reveal frame, or null; kept under the final ranking.
  RevealMessage? reveal;

  /// The reveal's cover, once fetched.
  Uint8List? revealCover;

  /// The own skip vote this song; the server's count; the skip closed by a
  /// held prepare in a reveal window.
  bool skipVoted = false;
  SkipCount? skipCount;
  bool skipClosed = false;

  /// A choice song's pick count, or null.
  PickCount? pickCount;

  /// A choice room's cover URL per position (null for a refused URL), or
  /// null without choices. Kept from prepare to the next prepare.
  List<String?>? choices;
  int? picked;
  bool pickWrong = false;
  bool pickLocked = false;
  int? correctChoice;

  /// Joined mid-song: watching it, playing from the next.
  bool watching = false;

  /// Rejoined between songs: waiting for the next prepare.
  bool waitingNext = false;

  /// The game's applied options (the header line), or null.
  GameOptions? settings;

  /// The last `scores` rows, and the own score from them.
  List<ScoreRow>? scoreboard;
  int? score;

  /// The `gameOver` ranking and the own final score.
  List<FinalRank>? ranking;
  int? finalScore;

  /// The room is back in its lobby after the game: 대기실로 shows.
  bool lobbyOpen = false;

  /// The game was abandoned (a lobby roster during it); the app shows the
  /// lobby with its notice. Cleared by the app.
  bool abandoned = false;

  // --- internals ---

  _Clock? _clock;
  Timer? _ticker;
  int? _announced;
  _OwnPick? _ownPick;
  int _skipsSent = 0;
  int _skipsRefused = 0;
  final Map<int, PrepareMessage> _prepares = {};
  final Map<int, _Clock> _starts = {};
  final Map<int, ({int startAt, int deadlineAt})> _lateStarts = {};
  final Map<int, RevealMessage> _reveals = {};
  final Set<int> _preparedEarly = {};
  String? _coverUrl;

  String? get _me => _session.state.myName;

  /// The song is audible (state.js isAudible, without the sound prompt).
  bool get audible =>
      view == GameView.game &&
      round != null &&
      (reveal == null || windowOpen) &&
      started &&
      reached &&
      load == LoadState.loaded;

  /// The skip button shows (GameScreen.jsx canSkip).
  bool get canSkip => view == GameView.game && started && !watching && (reveal == null || (windowOpen && !skipClosed));

  /// The song plays and is not revealed.
  bool get playing => view == GameView.game && started && reveal == null;

  /// The tiles take a pick (GameScreen.jsx canPick).
  bool get canPick =>
      started && reached && !watching && !pickLocked && picked == null && correct == null && reveal == null;

  /// The pick count shows (GameScreen.jsx pickShown).
  bool get pickShown => view == GameView.game && choices != null && started && reveal == null && pickCount != null;

  /// The song on screen is the game's last.
  bool get lastSong => round != null && round!.index + 1 >= round!.total;

  /// The names that solved the reveal's song.
  List<String> get solvers {
    final r = reveal;
    if (r == null) return const [];
    final rows = r.ranking;
    if (rows != null) return [for (final row in rows) row.name];
    final winner = r.winner;
    return winner == null ? const [] : [winner];
  }

  // --- actions ---

  /// Picks the tile at [choice] (session.js pick): one per song, while the
  /// song is audible and not revealed. Watchers do not pick.
  void pick(int choice) {
    final r = round;
    final list = choices;
    if (_disposed || r == null || list == null || watching || _clock == null || reveal != null) return;
    if (choice < 0 || choice >= list.length || !_reachedNow()) return;
    if (_ownPick?.index == r.index) return;
    _ownPick = _OwnPick(index: r.index, choice: choice, wrong: false);
    _session.send(PickRequest(index: r.index, choice: choice));
    picked = choice;
    pickWrong = false;
    pickLocked = true;
    notifyListeners();
  }

  /// Toggles the own skip vote (session.js skipVote).
  void skipVote() {
    final r = round;
    if (_disposed || r == null || !canSkip) return;
    _session.send(SkipVoteRequest(index: r.index));
    _skipsSent += 1;
    _ownSkip();
    notifyListeners();
  }

  /// 대기실로 after the game (state.js toLobby).
  void toLobby() {
    if (view != GameView.over) return;
    view = null;
    settings = null;
    notifyListeners();
  }

  @override
  void dispose() {
    _disposed = true;
    _stopTicker();
    _messageSub.cancel();
    _eventSub.cancel();
    super.dispose();
  }

  // --- frames ---

  void _onMessage(ServerMessage msg) {
    if (_disposed) return;
    switch (msg) {
      case PrepareMessage():
        _prepares[msg.index] = msg;
        if (_preparedEarly.remove(msg.index)) _applyPrepare(msg.index);
      case StartMessage():
        final offset = startOffset(serverTime: msg.serverTime, nowMs: _now(), estimate: _session.state.clock);
        _starts[msg.index] = _Clock(startAt: msg.startAt, deadlineAt: msg.deadlineAt, offsetMs: offset.offsetMs);
      case ChatMessage():
        _chat(msg.name, msg.text);
      case CorrectMessage():
        _correct(msg);
      case WrongMessage():
        if (_isCurrentChoiceSong(msg.index) && msg.name.isNotEmpty) {
          _append(NoticeLine(NoticeKind.wrongPick, msg.name));
        }
      case PickResultMessage():
        _pickResult(msg);
      case PickStateMessage():
        if (_isCurrentChoiceSong(msg.index)) {
          pickCount = PickCount(picked: msg.picked, total: msg.total);
          _changed();
        }
      case SkipStateMessage():
        if (_rounds.currentIndex == msg.index) {
          skipCount = SkipCount(votes: msg.votes, needed: msg.needed);
          _changed();
        }
      case RevealMessage():
        _reveals[msg.index] = msg;
      case ScoresMessage():
        scoreboard = msg.scores;
        final own = _ownRow(msg.scores.map((r) => (r.name, r.score)));
        if (own != null) score = own;
        _changed();
      case GameOverMessage():
        _gameOver(msg);
      case RosterMessage():
        if (msg.phase == 'lobby' && view == GameView.over && !lobbyOpen) {
          lobbyOpen = true;
          _changed();
        }
      case SettingsMessage():
        settings = msg.options;
        _changed();
      case SnapshotMessage():
        _snapshot(msg);
      case ErrorMessage():
        _error(msg);
      default:
        break;
    }
  }

  void _onEvent(RoundEvent e) {
    if (_disposed) return;
    final index = e.index;
    switch (e.type) {
      case RoundEventType.held:
        // The skip goes at once; the reveal and its clock stay (cycle 130).
        skipClosed = true;
        _changed();
      case RoundEventType.prepared:
        if (_prepares.containsKey(index)) {
          _applyPrepare(index!);
        } else if (index != null) {
          _preparedEarly.add(index);
        }
      case RoundEventType.loaded:
        if (round?.index == index) {
          load = LoadState.loaded;
          _changed();
        }
      case RoundEventType.loadFailed:
        if (round?.index == index) {
          load = LoadState.failed;
          _changed();
        }
      case RoundEventType.started:
        _applyStart(index!);
      case RoundEventType.revealed:
        _applyReveal(index!, e.window);
      case RoundEventType.windowOpen:
        if (round?.index == index && reveal != null) {
          windowOpen = true;
          _changed();
        }
      case RoundEventType.windowEnd:
        if (windowOpen && round?.index == index) {
          timer = null;
          _clock = null;
          _stopTicker();
          started = false;
          windowOpen = false;
          _changed();
        }
      case RoundEventType.abandoned:
        _abandon();
    }
  }

  // --- songs ---

  void _applyPrepare(int index) {
    final msg = _prepares.remove(index);
    if (msg == null) return;
    _prepares.removeWhere((i, _) => i < index);
    if (index == 0) {
      // A new game starts from a clean slate (D-062).
      log = const [NoticeLine(NoticeKind.gameStart)];
      _announced = null;
      score = null;
      finalScore = null;
      lobbyOpen = false;
      scoreboard = null;
      ranking = null;
    }
    final names = [for (final c in msg.correct ?? const <CorrectEntry>[]) c.name];
    // A ranked re-prepare naming the own name: answered, no more picks.
    final me = _me;
    if (me != null && names.contains(me)) {
      final own = _ownPick;
      _ownPick = _OwnPick(index: index, choice: own?.index == index ? own!.choice : null, wrong: false);
    }
    view = GameView.game;
    abandoned = false;
    round = (index: msg.index, total: msg.total);
    load = LoadState.loading;
    _clearClock();
    started = false;
    windowOpen = false;
    correct = null;
    correctNames = names;
    _setReveal(null);
    watching = false;
    waitingNext = false;
    _resetSkips();
    skipCount = null;
    skipClosed = false;
    pickCount = null;
    _setChoices(choiceUrls(msg.choices), index);
    _changed();
  }

  void _applyStart(int index) {
    if (round?.index != index) return;
    var clock = _starts.remove(index);
    if (clock == null) {
      // A snapshot's song: its moments, on the estimate it starts with.
      final late = _lateStarts.remove(index);
      final estimate = _session.state.clock;
      if (late == null) return;
      clock = _Clock(
        startAt: late.startAt,
        deadlineAt: late.deadlineAt,
        offsetMs: estimate?.offsetMs ?? 0,
      );
    }
    _clock = clock;
    started = true;
    _tick();
    _stopTicker();
    _ticker = Timer.periodic(tickInterval, (_) {
      if (_disposed) return;
      _tick();
      _changed();
    });
    _changed();
  }

  void _applyReveal(int index, bool open) {
    final msg = _reveals.remove(index);
    if (msg == null) return;
    _reveals.removeWhere((i, _) => i < index);
    if (!open) {
      _clearClock();
      started = false;
    }
    windowOpen = open;
    skipCount = null;
    pickCount = null;
    final list = choices;
    final cc = msg.correctChoice;
    correctChoice = list != null && cc != null && cc >= 0 && cc < list.length ? cc : null;
    // In a window the vote goes on from zero (cycle 130).
    if (open) _resetSkips();
    _setReveal(msg);
    _changed();
  }

  void _setReveal(RevealMessage? msg) {
    reveal = msg;
    revealCover = null;
    _coverUrl = null;
    final url = msg?.coverUrl;
    if (url == null || !coverUrlPattern.hasMatch(url)) return;
    _coverUrl = url;
    final known = _rounds.cover(url);
    if (known != null) {
      revealCover = known;
      return;
    }
    _fetcher.fetch(url).then((bytes) {
      if (_disposed || _coverUrl != url) return;
      revealCover = bytes;
      notifyListeners();
    }, onError: (Object error) {
      // The card shows without its cover.
      _warn('reveal cover failed: $error');
    });
  }

  void _setChoices(List<String?>? urls, int index) {
    choices = urls;
    final own = _ownPick;
    if (own == null || own.index != index) {
      if (own != null) _ownPick = null;
      picked = null;
      pickWrong = false;
      pickLocked = false;
    } else {
      picked = own.choice;
      pickWrong = own.wrong;
      pickLocked = true;
    }
    correctChoice = null;
  }

  void _gameOver(GameOverMessage msg) {
    final rows = msg.ranking;
    _append(const NoticeLine(NoticeKind.gameOver), notify: false);
    view = GameView.over;
    _clearClock();
    started = false;
    windowOpen = false;
    skipClosed = false;
    finalScore = _ownRow(rows.map((r) => (r.name, r.score)));
    ranking = rows;
    settings = null;
    _ownPick = null;
    _changed();
  }

  void _abandon() {
    _ownPick = null;
    _resetSkips();
    view = null;
    abandoned = true;
    _clearClock();
    started = false;
    windowOpen = false;
    watching = false;
    correctNames = const [];
    skipCount = null;
    pickCount = null;
    settings = null;
    _setChoices(null, -1);
    _changed();
  }

  void _snapshot(SnapshotMessage msg) {
    final rejoin = _session.isRejoin;
    if (!rejoin && msg.phase != 'playing') return;
    if (rejoin && msg.phase == 'lobby') {
      // Back in the lobby (a rejoin into it).
      view = null;
      round = null;
      load = null;
      _clearClock();
      started = false;
      windowOpen = false;
      waitingNext = false;
      lobbyOpen = false;
      settings = null;
      _setChoices(null, -1);
      _changed();
      return;
    }
    final song = msg.song;
    view = GameView.game;
    settings = null;
    if (rejoin && msg.scores.isNotEmpty) {
      scoreboard = msg.scores;
      score = _ownRow(msg.scores.map((r) => (r.name, r.score))) ?? score;
    }
    watching = !rejoin;
    waitingNext = rejoin && song == null;
    round = song == null ? null : (index: song.index, total: song.total);
    load = song == null ? null : LoadState.loading;
    _clearClock();
    started = false;
    windowOpen = false;
    correct = null;
    correctNames = [for (final c in song?.correct ?? const <CorrectEntry>[]) c.name];
    _setReveal(null);
    _resetSkips();
    skipCount = null;
    skipClosed = false;
    final ps = song?.pickState;
    final urls = choiceUrls(song?.choices);
    pickCount = urls == null ? null : ps;
    if (song != null) {
      final ownChoice = song.picked;
      if (ownChoice != null && urls != null && ownChoice >= 0 && ownChoice < urls.length) {
        _ownPick = _OwnPick(index: song.index, choice: ownChoice, wrong: false);
      }
      final startAt = song.startAt;
      final deadlineAt = song.deadlineAt;
      if (startAt != null && deadlineAt != null) {
        _lateStarts[song.index] = (startAt: startAt, deadlineAt: deadlineAt);
      }
      final r = song.reveal;
      if (r != null) {
        _reveals[song.index] = r;
        // A song whose answer is out gets no song-start line.
        _announced = song.index;
      }
      if (song.skipState != null) skipCount = song.skipState;
    }
    _setChoices(urls, song?.index ?? -1);
    _changed();
  }

  // --- answers ---

  void _correct(CorrectMessage msg) {
    final ranked = msg.rank != null || settings?.gameMode == 'ranked';
    if (ranked) {
      // The song goes on; only the own echo of a chat room goes above the
      // line (state.js R3).
      final choiceRoom = choices != null || settings?.answerMode == 'choice';
      final echoFollows = !choiceRoom && _me == msg.name;
      if (!correctNames.contains(msg.name)) correctNames = [...correctNames, msg.name];
      _append(CorrectLine(winner: msg.name, rank: msg.rank, placed: !echoFollows));
      return;
    }
    correct = msg.name;
    _append(CorrectLine(winner: msg.name));
  }

  void _chat(String name, String text) {
    final line = SaidLine(name: name, text: text);
    final last = log.isEmpty ? null : log.last;
    // The winner's first chat after correct (D-094) goes just above the
    // system line.
    if (last is CorrectLine && !last.placed && last.winner == name) {
      log = [
        ...log.sublist(0, log.length - 1),
        line,
        CorrectLine(winner: last.winner, rank: last.rank, placed: true),
      ];
      _changed();
      return;
    }
    _append(line);
  }

  void _pickResult(PickResultMessage msg) {
    final r = round;
    final list = choices;
    if (r == null || msg.index != r.index || list == null || msg.choice < 0 || msg.choice >= list.length) return;
    _ownPick = _OwnPick(index: r.index, choice: msg.choice, wrong: true);
    picked = msg.choice;
    pickWrong = true;
    pickLocked = true;
    _changed();
  }

  void _error(ErrorMessage msg) {
    final r = round;
    if (msg.forRequest == 'pick') {
      // A refused pick (session.js onError): already-picked keeps the tiles
      // locked with none marked; any other refusal frees them.
      final own = _ownPick;
      if (own != null && !own.wrong && r != null && own.index == r.index) {
        if (msg.code == 'already-picked') {
          _ownPick = _OwnPick(index: r.index, choice: null, wrong: true);
          picked = null;
          pickWrong = false;
          pickLocked = true;
        } else {
          _ownPick = null;
          picked = null;
          pickWrong = false;
          pickLocked = false;
        }
        _changed();
      }
      return;
    }
    // A refused skip vote was not counted (spec 050).
    if (msg.code == 'rate-limited' && msg.forRequest == 'skipVote' && _skipsRefused < _skipsSent) {
      _skipsRefused += 1;
      _ownSkip();
      _changed();
    }
  }

  // --- helpers ---

  bool _isCurrentChoiceSong(int index) => _rounds.currentIndex == index && choices != null;

  int? _ownRow(Iterable<(String, int)> rows) {
    final me = _me;
    for (final (name, value) in rows) {
      if (name == me) return value;
    }
    return null;
  }

  void _resetSkips() {
    _skipsSent = 0;
    _skipsRefused = 0;
    _ownSkip();
  }

  void _ownSkip() => skipVoted = (_skipsSent - _skipsRefused).isOdd;

  void _append(LogLine line, {bool notify = true}) {
    log = [...log, line];
    if (notify) _changed();
  }

  bool _reachedNow() {
    final clock = _clock;
    return clock != null && _now() >= clock.startAt - clock.offsetMs;
  }

  void _tick() {
    final clock = _clock;
    if (clock == null) return;
    final max = clock.deadlineAt - clock.startAt;
    final left = (clock.deadlineAt - clock.offsetMs - _now()).round();
    timer = GameTimer(maxMs: max, remainingMs: math.min(max, math.max(0, left)));
    reached = _reachedNow();
  }

  void _clearClock() {
    _clock = null;
    timer = null;
    _stopTicker();
  }

  void _stopTicker() {
    _ticker?.cancel();
    _ticker = null;
  }

  // Every change: the "<n>번째 곡 시작" line once the song is audible
  // (state.js reducer), then the listeners.
  void _changed() {
    final r = round;
    if (audible && r != null && _announced != r.index) {
      _announced = r.index;
      log = [...log, NoticeLine(NoticeKind.songStart, '${r.index + 1}')];
    }
    notifyListeners();
  }
}

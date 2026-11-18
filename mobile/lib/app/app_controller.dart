import 'dart:async';
import 'dart:developer' as developer;
import 'dart:ui' show Locale;

import 'package:flutter/foundation.dart';
import 'package:songrush_core/songrush_core.dart';

import '../store/playback_settings_store.dart';
import '../store/prefs.dart';
import 'game_model.dart';
import 'language.dart';
import 'lobby_selection.dart';

/// The web client's key of the last confirmed nickname (App.jsx
/// `NICKNAME_KEY`).
const String nicknameKey = 'songrush.nickname';

/// The site's UI choice (cycle 154, D-140; mobileRedirect.js `UI_KEY`):
/// "pc" keeps a phone on the React web, "mobile" was picked there.
const String uiKey = 'songrush.ui';

/// Room codes are 5 characters (StartScreen.jsx `CODE_LENGTH`).
const int codeLength = 5;

/// The nickname field's limit (NicknameDialog.jsx `NICKNAME_MAX`).
const int nicknameMax = 16;

/// The chat field's limit (GameScreen.jsx ChatForm `maxLength`).
const int chatMax = 200;

/// Rooms hold at most 8 players (D-046).
const int seatCount = 8;

/// The server's refusals of a `start` that depend on the selection: the
/// same selection would be refused again, so start stays disabled until it
/// changes.
const Set<String> _selectionRefusals = {
  'not-enough-songs',
  'bad-song-count',
  'bad-filter',
  'bad-answer-mode',
  'bad-game-mode',
};

/// [rejoining]: a reload rejoin before its `joined` (the web's game frame
/// with the reconnecting line); [disconnected]: the room was lost (the web's
/// disconnected screen, see [LostRoom]).
enum Screen { start, rejoining, lobby, game, over, disconnected }

enum DialogMode { create, join }

/// The nickname dialog (D-123, D-127): what opened it and whether 확인 has
/// started an attempt, whose error then shows in the dialog.
class NicknameDialogState {
  const NicknameDialogState({required this.mode, this.code = '', this.attempted = false});

  final DialogMode mode;

  /// The room code of a join.
  final String code;
  final bool attempted;
}

/// Why the player is back on the start screen, or why an attempt failed:
/// the server's error, else how the session ended.
class StartProblem {
  const StartProblem({this.error, this.end});

  final ErrorMessage? error;
  final SessionEnd? end;
}

/// A room that ended without the player leaving or the server refusing
/// (state.js `disconnected` and `replaced`): the connection could not be
/// taken back within 30 s, or another window took the seat (4001). The
/// disconnected screen shows it until 처음으로.
class LostRoom {
  const LostRoom({required this.replaced, this.code, this.finalScore});

  final bool replaced;
  final String? code;

  /// The own final score, when the game had ended.
  final int? finalScore;
}

/// The app's one controller (Flutter built-ins only): the screen, the
/// nickname dialog, the language, the host's lobby selection, and the room
/// [Session] with its [RoundController] and [GameModel], made together when
/// 확인 opens a create or join (cycle 147: the round controller exists
/// before any frame arrives, on the same clock). The game screens follow
/// the [GameModel] (cycle 149); this controller tells its listeners only
/// when the game's screen changes, so the time bar's ticks rebuild the game
/// screen alone.
///
/// The first tap that opens a session (확인) also runs [unlockSound], the
/// browser's audio unlock (SoloudEngine.start on the web); 시작 runs it again
/// when the first one failed, and so does the game's 탭하여 소리 켜기 prompt,
/// shown while the unlock has not completed (a reload rejoin has no tap):
/// once it completes, the current song loads again and joins where it is
/// ([RoundController.reloadAudio]). As the web App does, the host sends its
/// selection as `lobbySettings` on entering the lobby, on every change and
/// after a rejoin into the lobby; a reversed year range is not sent.
///
/// Reconnecting and lifecycle (cycle 150, design 141's rule): on a page
/// reload (`pageReloaded`) a stored rejoin entry resumes its room with
/// [Session.resume], as App.jsx's reload rejoin; any other start drops the
/// entry. The session's own reconnect keeps the room through a drop; a lost
/// room shows the disconnected screen. [pageShown] (the page or app back in
/// view) asks a reconnecting session for its next attempt at once. During a
/// game the screen is kept awake through `keepAwake` (the Screen Wake Lock
/// on the web). The manual sound offset is kept in the local prefs and used
/// by the round controller from the next song.
class AppController extends ChangeNotifier {
  AppController({
    required this._socketFactory,
    required this._tokenStore,
    required this._createPlayer,
    required this._fetcher,
    required Prefs localPrefs,
    this._unlockSound,
    this._keepAwake,
    this._navigate,
    bool pageReloaded = false,
    List<Locale> deviceLocales = const [],
    String initialCode = '',
    this.pageOrigin = '',
    int Function()? now,
    void Function(String message)? warn,
  })  : _prefs = localPrefs,
        _playback = PrefsPlaybackSettingsStore(localPrefs),
        _now = now ?? _systemNow,
        _warn = warn ?? _log,
        initialCode = initialCode.length > codeLength ? initialCode.substring(0, codeLength) : initialCode {
    _language = pickLanguage(stored: _prefs.read(languageKey), deviceLocales: deviceLocales);
    dialogName = _prefs.read(nicknameKey) ?? '';
    addListener(_syncAwake);
    // A reload takes the room back (App.jsx reloadPlan); any other start
    // drops the entry, as does a malformed one.
    final stored = _tokenStore.read();
    if (stored != null && pageReloaded) {
      _resume(stored);
      return;
    }
    _tokenStore.clear();
    // A share link opens the join dialog at once (App.jsx), once per load,
    // not on a reload rejoin.
    if (this.initialCode.length == codeLength) {
      _dialog = NicknameDialogState(mode: DialogMode.join, code: this.initialCode);
    }
  }

  static void _log(String message) => developer.log(message, name: 'songrush.app');

  static int _systemNow() => DateTime.now().millisecondsSinceEpoch;

  final GameSocketFactory _socketFactory;
  final TokenStore _tokenStore;
  final SongPlayerFactory _createPlayer;
  final Fetcher _fetcher;
  final Prefs _prefs;
  final PlaybackSettingsStore _playback;
  final Future<void> Function()? _unlockSound;
  final void Function(bool on)? _keepAwake;
  final void Function(String path)? _navigate;
  final int Function() _now;
  final void Function(String message) _warn;

  /// The room code of a `/r/<CODE>` link, for the start screen's field.
  final String initialCode;

  /// The page's origin (`https://host`), for the share link; '' off the web.
  final String pageOrigin;

  late String _language;
  NicknameDialogState? _dialog;
  StartProblem? _startError;
  bool _joining = false;
  LobbySelection _selection = const LobbySelection();

  Session? _session;
  RoundController? _rounds;
  GameModel? _game;
  GameView? _gameView;
  StreamSubscription<SessionState>? _stateSub;
  StreamSubscription<ServerMessage>? _messageSub;
  SessionState? _state;

  // The sound unlock: running or done (null when not started or failed).
  Future<void>? _unlocking;
  bool _soundReady = false;
  bool _disposed = false;

  // The room code of a reload rejoin not joined yet.
  String? _resumingCode;
  LostRoom? _lost;
  bool _offsetOpen = false;
  // The wake lock was last asked on.
  bool _awake = false;

  // lobbySettings bookkeeping: whether the last state was host-in-lobby and
  // the lobbyRestores it saw.
  bool _hostInLobby = false;
  int _restoresSeen = 0;

  // The lobby's log (state.js lobbyLog): the session's chat lines from this
  // index, after the intro line (none after an abandoned game).
  int _lobbyChatFrom = 0;
  NoticeKind? _lobbyIntro = NoticeKind.lobbyJoined;
  bool _abandonedNotice = false;

  // The selection of a start not answered yet, and of the last refused one.
  LobbySelection? _startPending;
  LobbySelection? _refused;

  /// The text field's nickname (prefilled with the last confirmed one).
  String dialogName = '';

  // --- read side ---

  /// ko, en or ja.
  String get language => _language;

  Screen get screen {
    if (_lost != null) return Screen.disconnected;
    if (_state?.roomCode == null) return _resumingCode != null ? Screen.rejoining : Screen.start;
    return switch (_gameView) {
      GameView.game => Screen.game,
      GameView.over => Screen.over,
      null => Screen.lobby,
    };
  }

  NicknameDialogState? get dialog => _dialog;

  /// An attempt is connecting: 확인 is disabled.
  bool get joining => _joining;

  /// The last attempt's or the last room's problem.
  StartProblem? get startError => _startError;

  /// The room as the session sees it; null on the start screen.
  SessionState? get session => _state;

  /// The lobby's chat lines, oldest first (since the room was entered, or
  /// since the lobby came back after a game).
  List<ChatLine> get chat {
    final all = _state?.chat ?? const <ChatLine>[];
    return _lobbyChatFrom <= all.length ? all.sublist(_lobbyChatFrom) : all;
  }

  /// The lobby's log: its intro line (joined, or back after a game), then
  /// the [chat] lines.
  List<LogLine> get lobbyLog {
    final intro = _lobbyIntro;
    return [
      if (intro != null) NoticeLine(intro, _state?.roomCode ?? ''),
      for (final line in chat) SaidLine(name: line.name, text: line.text),
    ];
  }

  /// The lobby's notice after an abandoned game (state.js ABANDONED_NOTICE).
  bool get abandonedNotice => _abandonedNotice;

  /// The game's display state while in a room.
  GameModel? get game => _game;

  /// The own selection (the host's controls).
  LobbySelection get selection => _selection;

  /// The own player is the room's host.
  bool get isHost => _state?.isHost ?? false;

  /// What the lobby's settings card shows: the own selection for the host,
  /// the host's received one for a guest (pending before any).
  LobbySelection get shownSelection {
    if (isHost) return _selection;
    final received = _state?.lobbySettings;
    return received == null ? const LobbySelection.pending() : LobbySelection.fromSettings(received);
  }

  /// The server refused a start with the current selection.
  bool get startRefused => _refused != null && _refused == _selection;

  /// The host may press start: in the lobby phase, connected, the years in
  /// order, and not just refused with this very selection.
  bool get canStart =>
      isHost &&
      _state?.status == SessionStatus.inRoom &&
      _state?.phase == 'lobby' &&
      !_selection.isReversed &&
      !startRefused;

  /// The room's share link (LobbyScreen.jsx Ticket): `<origin>/r/<code>`.
  String shareLink(String code) => '$pageOrigin/r/$code';

  /// The audio unlock has completed.
  bool get soundReady => _soundReady;

  /// The sound is still locked (no completed unlock): the game shows the
  /// 탭하여 소리 켜기 prompt (state.js soundBlocked).
  bool get soundBlocked => _unlockSound != null && !_soundReady;

  /// The room code of a reload rejoin still connecting.
  String? get rejoiningCode => _resumingCode;

  /// The lost room of the disconnected screen.
  LostRoom? get lostRoom => _lost;

  /// The sound offset setting is open.
  bool get offsetOpen => _offsetOpen;

  /// The manual sound offset in ms (positive plays later).
  int get manualOffsetMs => _rounds?.manualOffsetMs ?? clampManualOffset(_playback.readManualOffsetMs() ?? 0);

  /// The round controller of the room, for the game screens to come.
  RoundController? get rounds => _rounds;

  // --- start screen ---

  /// The PC version link shows only where the page can navigate (the web
  /// build, `navigate`; cycle 154, D-140).
  bool get canSwitchToPc => _navigate != null;

  /// The React web's path for this screen: `/r/<code>` in a room (or while
  /// rejoining one), `/` otherwise, with `?ui=pc`, which keeps the phone on
  /// the React web even when its localStorage is blocked (cycle 155).
  String get pcPath {
    final code = _state?.roomCode ?? _resumingCode;
    return code == null || code.isEmpty ? '/?ui=pc' : '/r/$code?ui=pc';
  }

  /// The PC version link: stores the choice so the React web keeps this
  /// device (`songrush.ui` = "pc"), then leaves for [pcPath].
  void switchToPc() {
    final navigate = _navigate;
    if (navigate == null) return;
    _prefs.write(uiKey, 'pc');
    navigate(pcPath);
  }

  void setLanguage(String code) {
    if (!languages.contains(code) || code == _language) return;
    _language = code;
    _prefs.write(languageKey, code);
    notifyListeners();
  }

  void openCreateDialog() => _openDialog(const NicknameDialogState(mode: DialogMode.create));

  /// Opens the join dialog for a full [code].
  void openJoinDialog(String code) {
    final upper = code.toUpperCase();
    if (upper.length != codeLength) return;
    _openDialog(NicknameDialogState(mode: DialogMode.join, code: upper));
  }

  void _openDialog(NicknameDialogState dialog) {
    if (_session != null) return;
    dialogName = _prefs.read(nicknameKey) ?? dialogName;
    _dialog = dialog;
    notifyListeners();
  }

  void setDialogName(String name) {
    dialogName = name;
  }

  /// 확인: the create or join with the trimmed name. Runs inside the tap, so
  /// the sound unlock starts in the user gesture.
  void confirmNickname(String name) {
    final dialog = _dialog;
    final nickname = name.trim();
    if (dialog == null || nickname.isEmpty || _joining) return;
    _unlock();
    dialogName = nickname;
    _prefs.write(nicknameKey, nickname);
    _closeSession();
    _dialog = NicknameDialogState(mode: dialog.mode, code: dialog.code, attempted: true);
    _startError = null;
    _joining = true;
    _open(dialog.mode == DialogMode.create
        ? (factory, now) => Session.create(
            socketFactory: factory, tokenStore: _tokenStore, nickname: nickname, now: now, warn: _warn)
        : (factory, now) => Session.join(
            socketFactory: factory,
            tokenStore: _tokenStore,
            code: dialog.code,
            nickname: nickname,
            now: now,
            warn: _warn));
    notifyListeners();
  }

  /// Escape, 취소 or a tap outside: closes without sending; an attempt in
  /// progress is dropped with its error.
  void cancelDialog() {
    final dialog = _dialog;
    if (dialog == null) return;
    if (dialog.attempted) {
      _closeSession();
      _resetRoom();
      _startError = null;
    }
    _dialog = null;
    notifyListeners();
  }

  // --- lobby ---

  /// A host's control change; sent to the guests at once.
  void setSelection(LobbySelection next) {
    if (next == _selection) return;
    _selection = next;
    _sendLobbySettings();
    notifyListeners();
  }

  /// The host's start: the selection's count and its non-default options.
  void startGame() {
    final session = _session;
    if (session == null || !canStart) return;
    _unlock();
    _startPending = _selection;
    session.send(StartRequest(songCount: _selection.songCountToSend, options: _selection.toOptions()));
  }

  /// Sends a chat line; a blank one is not sent.
  void sendChat(String text) {
    final session = _session;
    if (session == null || text.trim().isEmpty) return;
    session.send(ChatRequest(text: text));
  }

  /// 대기실로 on the game-over screen: the lobby, its log starting again
  /// with the back line (state.js toLobby).
  void toLobby() {
    final game = _game;
    if (game == null || game.view != GameView.over) return;
    _lobbyChatFrom = _state?.chat.length ?? 0;
    _lobbyIntro = NoticeKind.lobbyBack;
    _abandonedNotice = false;
    game.toLobby();
  }

  /// 탭하여 소리 켜기: the sound unlock, inside the tap.
  void unlockSound() => _unlock();

  /// 처음으로 on the disconnected screen: the start screen.
  void reset() {
    if (_lost == null) return;
    _lost = null;
    _startError = null;
    notifyListeners();
  }

  void openOffset() {
    if (_offsetOpen) return;
    _offsetOpen = true;
    notifyListeners();
  }

  void closeOffset() {
    if (!_offsetOpen) return;
    _offsetOpen = false;
    notifyListeners();
  }

  /// Sets the manual sound offset ([manualOffsetMinMs]..[manualOffsetMaxMs]),
  /// stored at once and used from the next song.
  void setManualOffset(int ms) {
    final value = clampManualOffset(ms);
    if (value == manualOffsetMs) return;
    final rounds = _rounds;
    if (rounds != null) {
      rounds.manualOffsetMs = value;
    } else {
      _playback.writeManualOffsetMs(value);
    }
    notifyListeners();
  }

  /// The page (or the app) is back in view. Decided by the session's state:
  /// a reconnecting session tries now instead of after its back-off wait; a
  /// session in the room keeps its socket (if it died meanwhile, its close
  /// starts the reconnect, and the rejoin snapshot the late start), and its
  /// song goes on where the drift check puts it. The wake lock, released by
  /// the browser on hiding, is asked again during a game.
  void pageShown() {
    final session = _session;
    if (session != null && session.state.status == SessionStatus.reconnecting) session.reconnectNow();
    if (_awake) _keepAwake?.call(true);
  }

  /// 나가기: tells the server and returns to the start screen.
  void leave() {
    final session = _session;
    _detach();
    session?.leave();
    _resetRoom();
    _startError = null;
    notifyListeners();
  }

  @override
  void dispose() {
    _disposed = true;
    final session = _session;
    _detach();
    session?.close();
    if (_awake) _keepAwake?.call(false);
    _awake = false;
    super.dispose();
  }

  // --- the session ---

  void _open(Session Function(GameSocketFactory factory, int Function() now) make) {
    final session = make(_socketFactory, _now);
    // Right after the session, before any frame can arrive (they come
    // asynchronously): the round controller sees every prepare.
    final rounds = RoundController(
      session: session,
      createPlayer: _createPlayer,
      fetcher: _fetcher,
      settings: _playback,
      now: _now,
      warn: _warn,
    );
    final game = GameModel(session: session, rounds: rounds, fetcher: _fetcher, now: _now, warn: _warn);
    game.addListener(_onGame);
    _session = session;
    _rounds = rounds;
    _game = game;
    _stateSub = session.states.listen((s) {
      if (identical(_session, session)) _onState(s);
    });
    _messageSub = session.messages.listen((m) {
      if (identical(_session, session)) _onMessage(m);
    });
  }

  void _resume(RejoinEntry entry) {
    _resumingCode = entry.code;
    _open((factory, now) => Session.resume(
        socketFactory: factory, tokenStore: _tokenStore, entry: entry, now: now, warn: _warn));
  }

  // The screen is kept awake while a game is on screen (not its game-over).
  void _syncAwake() {
    final want = _session != null && _gameView == GameView.game;
    if (want == _awake) return;
    _awake = want;
    _keepAwake?.call(want);
  }

  // Forgets the session without closing it.
  void _detach() {
    _stateSub?.cancel();
    _messageSub?.cancel();
    _stateSub = null;
    _messageSub = null;
    _session = null;
    // The round controller ends with the session's messages.
    _rounds = null;
    _game?.removeListener(_onGame);
    _game?.dispose();
    _game = null;
    _gameView = null;
  }

  // The game's screen changed: the app's home follows.
  void _onGame() {
    final game = _game;
    if (game == null) return;
    final view = game.view;
    if (view == _gameView && !game.abandoned) return;
    if (game.abandoned) {
      // Back in the lobby with the notice; its log starts again, empty.
      game.abandoned = false;
      _abandonedNotice = true;
      _lobbyChatFrom = _state?.chat.length ?? 0;
      _lobbyIntro = null;
    } else if (view != null) {
      _abandonedNotice = false;
    }
    _gameView = view;
    notifyListeners();
  }

  void _closeSession() {
    final session = _session;
    if (session == null) return;
    _detach();
    session.close();
  }

  void _resetRoom() {
    _state = null;
    _joining = false;
    _resumingCode = null;
    _offsetOpen = false;
    _hostInLobby = false;
    _restoresSeen = 0;
    _startPending = null;
    _refused = null;
    _lobbyChatFrom = 0;
    _lobbyIntro = NoticeKind.lobbyJoined;
    _abandonedNotice = false;
  }

  void _onState(SessionState next) {
    final previous = _state;
    _state = next;
    if (next.status == SessionStatus.ended) {
      _onEnded(next);
      notifyListeners();
      return;
    }
    if (next.status == SessionStatus.inRoom && previous?.roomCode == null) {
      // joined: the lobby replaces the start screen and its dialog.
      _joining = false;
      _resumingCode = null;
      _dialog = null;
      _startError = null;
    }
    if (next.phase != 'lobby') _startPending = null;
    if (next.lobbyRestores != _restoresSeen) {
      // A rejoin into the lobby: the session's log started again.
      _lobbyChatFrom = 0;
      _lobbyIntro = null;
    }
    final hostInLobby = next.status == SessionStatus.inRoom && next.phase == 'lobby' && next.isHost;
    if (hostInLobby && (!_hostInLobby || next.lobbyRestores != _restoresSeen)) _sendLobbySettings();
    _hostInLobby = hostInLobby;
    _restoresSeen = next.lobbyRestores;
    notifyListeners();
  }

  void _onMessage(ServerMessage message) {
    // Only a refusal that names the selection ends the pending start; the
    // phase leaving the lobby ends it too.
    if (message is ErrorMessage && _startPending != null && _selectionRefusals.contains(message.code)) {
      _refused = _startPending;
      _startPending = null;
      notifyListeners();
    }
  }

  void _onEnded(SessionState ended) {
    final everJoined = ended.roomCode != null;
    final finalScore = _game?.finalScore;
    _detach();
    if (ended.end == SessionEnd.left) {
      _resetRoom();
      return;
    }
    final replaced = ended.end == SessionEnd.replaced;
    if (replaced || (ended.end == SessionEnd.disconnected && everJoined)) {
      // The disconnected screen (state.js disconnected / replaced), which
      // closes the dialog.
      _resetRoom();
      _startError = null;
      _dialog = null;
      _lost = LostRoom(replaced: replaced, code: ended.roomCode, finalScore: finalScore);
      return;
    }
    final problem = StartProblem(error: ended.error, end: ended.end);
    _resetRoom();
    _startError = problem;
    // An attempt that never got in keeps its dialog, which shows the error;
    // a room that ended goes back to the start screen with it.
    if (everJoined) _dialog = null;
  }

  void _sendLobbySettings() {
    final session = _session;
    final state = _state;
    if (session == null || state == null || _selection.isReversed) return;
    if (!(state.status == SessionStatus.inRoom && state.phase == 'lobby' && state.isHost)) return;
    session.send(LobbySettingsRequest(songCount: _selection.songCountToSend, options: _selection.toOptions()));
  }

  void _unlock() {
    final unlock = _unlockSound;
    if (unlock == null || _unlocking != null) return;
    final Future<void> running;
    try {
      running = unlock();
    } catch (e) {
      _warn('sound unlock failed: $e');
      return;
    }
    _unlocking = running;
    running.then((_) {
      _soundReady = true;
      if (_disposed) return;
      // A song whose load failed while the sound was locked joins now.
      _rounds?.reloadAudio();
      notifyListeners();
    }, onError: (Object e) {
      // The next start tap or the sound prompt tries again.
      _warn('sound unlock failed: $e');
      _unlocking = null;
    });
  }
}

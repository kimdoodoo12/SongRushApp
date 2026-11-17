import 'dart:async';
import 'dart:developer' as developer;
import 'dart:ui' show Locale;

import 'package:flutter/foundation.dart';
import 'package:songrush_core/songrush_core.dart';

import '../store/prefs.dart';
import 'language.dart';
import 'lobby_selection.dart';

/// The web client's key of the last confirmed nickname (App.jsx
/// `NICKNAME_KEY`).
const String nicknameKey = 'songrush.nickname';

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

enum Screen { start, lobby }

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

/// The app's one controller (Flutter built-ins only): the screen, the
/// nickname dialog, the language, the host's lobby selection, and the room
/// [Session] with its [RoundController], made together when 확인 opens a
/// create or join (cycle 147: the round controller exists before any frame
/// arrives, on the same clock).
///
/// The first tap that opens a session (확인) also runs [unlockSound], the
/// browser's audio unlock (SoloudEngine.start on the web); 시작 runs it again
/// when the first one failed. As the web App does, the host sends its
/// selection as `lobbySettings` on entering the lobby, on every change and
/// after a rejoin into the lobby; a reversed year range is not sent.
class AppController extends ChangeNotifier {
  AppController({
    required this._socketFactory,
    required this._tokenStore,
    required this._createPlayer,
    required this._fetcher,
    required Prefs localPrefs,
    this._unlockSound,
    List<Locale> deviceLocales = const [],
    String initialCode = '',
    this.pageOrigin = '',
    int Function()? now,
    void Function(String message)? warn,
  })  : _prefs = localPrefs,
        _now = now ?? _systemNow,
        _warn = warn ?? _log,
        initialCode = initialCode.length > codeLength ? initialCode.substring(0, codeLength) : initialCode {
    _language = pickLanguage(stored: _prefs.read(languageKey), deviceLocales: deviceLocales);
    dialogName = _prefs.read(nicknameKey) ?? '';
    // A share link opens the join dialog at once (App.jsx), once per load.
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
  final Future<void> Function()? _unlockSound;
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
  StreamSubscription<SessionState>? _stateSub;
  StreamSubscription<ServerMessage>? _messageSub;
  SessionState? _state;

  // The sound unlock: running or done (null when not started or failed).
  Future<void>? _unlocking;
  bool _soundReady = false;

  // lobbySettings bookkeeping: whether the last state was host-in-lobby and
  // the lobbyRestores it saw.
  bool _hostInLobby = false;
  int _restoresSeen = 0;

  // The selection of a start not answered yet, and of the last refused one.
  LobbySelection? _startPending;
  LobbySelection? _refused;

  /// The text field's nickname (prefilled with the last confirmed one).
  String dialogName = '';

  // --- read side ---

  /// ko, en or ja.
  String get language => _language;

  Screen get screen => _state?.roomCode != null ? Screen.lobby : Screen.start;

  NicknameDialogState? get dialog => _dialog;

  /// An attempt is connecting: 확인 is disabled.
  bool get joining => _joining;

  /// The last attempt's or the last room's problem.
  StartProblem? get startError => _startError;

  /// The room as the session sees it; null on the start screen.
  SessionState? get session => _state;

  /// The room's chat, oldest first.
  List<ChatLine> get chat => _state?.chat ?? const [];

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

  /// The round controller of the room, for the game screens to come.
  RoundController? get rounds => _rounds;

  // --- start screen ---

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
    final session = _session;
    _detach();
    session?.close();
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
      now: _now,
      warn: _warn,
    );
    _session = session;
    _rounds = rounds;
    _stateSub = session.states.listen((s) {
      if (identical(_session, session)) _onState(s);
    });
    _messageSub = session.messages.listen((m) {
      if (identical(_session, session)) _onMessage(m);
    });
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
    _hostInLobby = false;
    _restoresSeen = 0;
    _startPending = null;
    _refused = null;
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
      _dialog = null;
      _startError = null;
    }
    if (next.phase != 'lobby') _startPending = null;
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
    _detach();
    if (ended.end == SessionEnd.left) {
      _resetRoom();
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
    }, onError: (Object e) {
      // The next start tap tries again.
      _warn('sound unlock failed: $e');
      _unlocking = null;
    });
  }
}

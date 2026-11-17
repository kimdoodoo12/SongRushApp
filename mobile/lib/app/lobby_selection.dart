import 'package:songrush_core/songrush_core.dart';

// The host's lobby controls, as the web's App-level selection
// (frontend/src/game/filters.js DEFAULT_SELECTION, toFields, isReversed) and
// LobbyScreen.jsx shownSelection for a guest.

/// Song type options, as the server's ids: all, OP or ED.
const List<String> typeOptions = ['all', 'OP', 'ED'];

/// The year selects' choices (filters.js YEAR_FROM_OPTIONS, YEAR_TO_OPTIONS).
const List<int> yearFromOptions = [1990, 2000, 2005, 2010, 2015, 2020, 2025];
const List<int> yearToOptions = [1999, 2004, 2009, 2014, 2019, 2024];

const List<String> tierOptions = ['easy', 'normal', 'hard'];
const List<String> answerModeOptions = ['chat', 'choice'];
const List<String> gameModeOptions = ['first', 'ranked'];

/// The cover tiles of a choice room by difficulty (FilterControls.jsx
/// CHOICE_COVERS).
const Map<String, int> choiceCovers = {'easy': 4, 'normal': 6, 'hard': 8};

/// 곡 수, the filters, 정답 형식 and 게임 모드. Immutable. A guest's view of
/// the host's selection before any `lobbySettings` is [LobbySelection.pending]
/// (every field null: nothing pressed, a dash for the count).
class LobbySelection {
  const LobbySelection({
    this.songCount = defaultSongs,
    this.type = 'all',
    this.yearFrom,
    this.yearTo,
    this.tier = 'normal',
    this.answerMode = 'chat',
    this.gameMode = 'first',
  });

  /// A guest's card before the host's first `lobbySettings`.
  const LobbySelection.pending()
      : songCount = null,
        type = null,
        yearFrom = null,
        yearTo = null,
        tier = null,
        answerMode = null,
        gameMode = null;

  /// The received `lobbySettings` as a guest's read-only card; a missing or
  /// unknown field is the default (shownSelection).
  factory LobbySelection.fromSettings(LobbySettingsMessage settings) {
    final o = settings.options;
    final types = o.types;
    final oneType = types != null && types.length == 1 && (types[0] == 'OP' || types[0] == 'ED');
    return LobbySelection(
      songCount: settings.songCount,
      type: oneType ? types[0] : 'all',
      yearFrom: o.yearFrom,
      yearTo: o.yearTo,
      tier: o.tier == 'easy' || o.tier == 'hard' ? o.tier! : 'normal',
      answerMode: o.answerMode == 'choice' ? 'choice' : 'chat',
      gameMode: o.gameMode == 'ranked' ? 'ranked' : 'first',
    );
  }

  final int? songCount;
  final String? type;
  final int? yearFrom;
  final int? yearTo;
  final String? tier;
  final String? answerMode;
  final String? gameMode;

  /// Both years set and the start after the end (filters.js isReversed).
  bool get isReversed => yearFrom != null && yearTo != null && yearFrom! > yearTo!;

  /// The count to send: the selected one, clamped to 5-100.
  int get songCountToSend => clampSongCount(songCount ?? defaultSongs);

  /// Only the non-default fields (filters.js toFields).
  GameOptions toOptions() => GameOptions(
        types: type == 'OP' || type == 'ED' ? [type!] : null,
        yearFrom: yearFrom,
        yearTo: yearTo,
        tier: tier == 'easy' || tier == 'hard' ? tier : null,
        answerMode: answerMode == 'choice' ? 'choice' : null,
        gameMode: gameMode == 'ranked' ? 'ranked' : null,
      );

  LobbySelection copyWith({
    int? songCount,
    String? type,
    Object? yearFrom = _unset,
    Object? yearTo = _unset,
    String? tier,
    String? answerMode,
    String? gameMode,
  }) =>
      LobbySelection(
        songCount: songCount ?? this.songCount,
        type: type ?? this.type,
        yearFrom: identical(yearFrom, _unset) ? this.yearFrom : yearFrom as int?,
        yearTo: identical(yearTo, _unset) ? this.yearTo : yearTo as int?,
        tier: tier ?? this.tier,
        answerMode: answerMode ?? this.answerMode,
        gameMode: gameMode ?? this.gameMode,
      );

  @override
  bool operator ==(Object other) =>
      other is LobbySelection &&
      other.songCount == songCount &&
      other.type == type &&
      other.yearFrom == yearFrom &&
      other.yearTo == yearTo &&
      other.tier == tier &&
      other.answerMode == answerMode &&
      other.gameMode == gameMode;

  @override
  int get hashCode => Object.hash(songCount, type, yearFrom, yearTo, tier, answerMode, gameMode);

  @override
  String toString() =>
      'LobbySelection($songCount, $type, $yearFrom~$yearTo, $tier, $answerMode, $gameMode)';
}

const Object _unset = Object();

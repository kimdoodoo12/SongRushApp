import 'package:songrush_core/songrush_core.dart';

import '../app/app_controller.dart';
import '../app/lobby_selection.dart';
import '../l10n/app_localizations.dart';

// Display texts built from state, as the web's errorText (game/state.js),
// settingsLine (game/filters.js) and the option labels of FilterControls.

/// The text of a server error (state.js errorText): not-enough-songs names
/// the drawable count when the server sent one; an unknown code is shown
/// with its code.
String errorText(AppLocalizations t, ErrorMessage error) {
  final available = error.available;
  if (error.code == 'not-enough-songs' && available != null) {
    return error.unit == 'series' ? t.errorAvailableSeries(available) : t.errorAvailable(available);
  }
  return switch (error.code) {
    'bad-nickname' => t.errorBadNickname,
    'bad-song-count' => t.errorBadSongCount,
    'not-enough-songs' => t.errorNotEnoughSongs,
    'too-long' => t.errorTooLong,
    'room-not-found' => t.errorRoomNotFound,
    'room-full' => t.errorRoomFull,
    'in-game' => t.errorInGame,
    'not-host' => t.errorNotHost,
    'server-full' => t.errorServerFull,
    'room-closed' => t.errorRoomClosed,
    'rejoin-failed' => t.errorRejoinFailed,
    'bad-filter' => t.errorBadFilter,
    'bad-room-theme' => t.errorBadRoomTheme,
    'rate-limited' => t.errorRateLimited,
    'bad-message' => t.errorBadMessage,
    'too-large' => t.errorTooLarge,
    'already-in-room' => t.errorAlreadyInRoom,
    'no-room' => t.errorNoRoom,
    'bad-answer-mode' => t.errorBadAnswerMode,
    'bad-game-mode' => t.errorBadGameMode,
    _ => t.errorUnknown(error.code),
  };
}

/// The start screen's or the dialog's line for a failed attempt or an ended
/// room: the server's error, else the end (session.js: a socket that closed
/// before joined is game.disconnected; a 4001 close is game.replaced).
String problemText(AppLocalizations t, StartProblem problem) {
  final error = problem.error;
  if (error != null) return errorText(t, error);
  return problem.end == SessionEnd.replaced ? t.gameReplaced : t.gameDisconnected;
}

String typeLabel(AppLocalizations t, String type) => switch (type) {
      'OP' => t.filterOp,
      'ED' => t.filterEd,
      _ => t.filterAll,
    };

String tierLabel(AppLocalizations t, String tier) => switch (tier) {
      'easy' => t.filterEasy,
      'hard' => t.filterHard,
      _ => t.filterNormal,
    };

String answerModeLabel(AppLocalizations t, String mode) => mode == 'choice' ? t.answerChoice : t.answerChat;

String gameModeLabel(AppLocalizations t, String mode) => mode == 'ranked' ? t.modeRanked : t.modeFirst;

/// The header's settings line (filters.js settingsLine), such as
/// '오프닝만 · 2010~2019 · 쉬움 · 객관식 · 순위전'; '' with every default.
String settingsLine(AppLocalizations t, GameOptions o) {
  final parts = <String>[];
  final types = o.types;
  if (types != null && types.length == 1 && (types[0] == 'OP' || types[0] == 'ED')) {
    parts.add(typeLabel(t, types[0]));
  }
  if (o.yearFrom != null || o.yearTo != null) parts.add('${o.yearFrom ?? ''}~${o.yearTo ?? ''}');
  if (o.tier == 'easy' || o.tier == 'hard') parts.add(tierLabel(t, o.tier!));
  if (o.answerMode == 'choice') parts.add(t.answerChoice);
  if (o.gameMode == 'ranked') parts.add(t.modeRanked);
  return parts.join(' · ');
}

/// The 정답 형식 hint (FilterControls.jsx answerHint), or null without a
/// mode (a guest before any lobbySettings).
String? answerHint(AppLocalizations t, LobbySelection s) {
  final mode = s.answerMode;
  if (mode == null) return null;
  if (mode == 'choice') return t.answerHintChoice(choiceCovers[s.tier] ?? choiceCovers['normal']!);
  return t.answerHintChat;
}

/// The 게임 모드 hint (FilterControls.jsx modeHint), or null without a mode.
String? modeHint(AppLocalizations t, LobbySelection s) {
  final mode = s.gameMode;
  if (mode == null) return null;
  return mode == 'ranked' ? t.modeHintRanked : t.modeHintFirst;
}

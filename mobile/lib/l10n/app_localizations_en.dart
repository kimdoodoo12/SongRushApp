// ignore: unused_import
import 'package:intl/intl.dart' as intl;

import 'app_localizations.dart';

// ignore_for_file: type=lint

/// The translations for English (`en`).
class AppLocalizationsEn extends AppLocalizations {
  AppLocalizationsEn([String locale = 'en']) : super(locale);

  @override
  String get appTitle => 'SongRush';

  @override
  String get appCredit =>
      'Data sources: AnimeThemes, AniList, anime-offline-database, AniDB, Wikidata';

  @override
  String get appReconnecting => 'Connection lost. Reconnecting...';

  @override
  String get startNickname => 'Nickname';

  @override
  String get startCreateRoom => 'Create room';

  @override
  String get startOrCode => 'or join with a code';

  @override
  String get startRoomCode => 'Room code';

  @override
  String get startJoin => 'Join';

  @override
  String startJoinTitle(Object code) {
    return 'Join room $code';
  }

  @override
  String get startConfirm => 'OK';

  @override
  String get startCancel => 'Cancel';

  @override
  String get themeSunset => 'Sunset Rooftop';

  @override
  String get moodTagSunset => 'Guess the anime OP/ED';

  @override
  String get skinSunsetTitle => 'Sunset';

  @override
  String get skinSunsetLabel => 'Room code';

  @override
  String get skinSunsetAdmit => 'ADMIT ONE';

  @override
  String get skinSunsetStart => 'Play Song';

  @override
  String get skinSunsetSeats => 'Lobby';

  @override
  String get skinSunsetReady => 'Joined';

  @override
  String get skinSunsetEmpty => 'Empty';

  @override
  String get lobbyCopy => 'Copy';

  @override
  String get lobbyWaiting => 'Waiting for the host to start the game...';

  @override
  String get lobbySongCount => 'Songs';

  @override
  String get lobbyLeave => 'Leave';

  @override
  String lobbySeats(Object seats, Object n) {
    return '$seats · Players $n / 8';
  }

  @override
  String get lobbyHostSeat => 'Host';

  @override
  String lobbyOf(Object n) {
    return '/ $n';
  }

  @override
  String get lobbySettingsTitle => 'Game settings';

  @override
  String get lobbyWhoHost => 'Only the host can change these';

  @override
  String get lobbyWhoGuest => 'Set by the host';

  @override
  String get lobbyWhoPending => 'The host is choosing the settings';

  @override
  String get lobbyShareHint =>
      'Send the link: a friend only enters a nickname to join';

  @override
  String get lobbyCopied => 'Link copied';

  @override
  String get lobbyCopyFailed =>
      'Could not copy. Press and hold the address to copy it';

  @override
  String get lobbySongCountDown => 'Fewer songs';

  @override
  String get lobbySongCountUp => 'More songs';

  @override
  String get filterType => 'Song type';

  @override
  String get filterTier => 'Difficulty';

  @override
  String get filterFrom => 'From year';

  @override
  String get filterTo => 'To year';

  @override
  String get filterFromAny => 'Earliest';

  @override
  String get filterToAny => 'Latest';

  @override
  String get filterReversed => 'The start year is later than the end year.';

  @override
  String get filterAll => 'All';

  @override
  String get filterOp => 'OP only';

  @override
  String get filterEd => 'ED only';

  @override
  String get filterEasy => 'Easy';

  @override
  String get filterNormal => 'Normal';

  @override
  String get filterHard => 'Hard';

  @override
  String get filterYears => 'Years';

  @override
  String get answerLabel => 'Answer format';

  @override
  String get answerChat => 'Chat';

  @override
  String get answerChoice => 'Multiple choice';

  @override
  String get answerHintChat => 'Guess the anime title in the chat';

  @override
  String answerHintChoice(Object k) {
    return 'Pick from $k covers (4, 6 or 8 by difficulty)';
  }

  @override
  String get modeLabel => 'Game mode';

  @override
  String get modeFirst => 'First Come';

  @override
  String get modeRanked => 'Ranked';

  @override
  String get modeHintFirst => 'Only the first to get it right scores';

  @override
  String get modeHintRanked =>
      'The song goes on until everyone answers or picks, and points follow the ranking';

  @override
  String get seatList => 'Seats';

  @override
  String get seatEmpty => 'Empty seat';

  @override
  String get seatHost => '(host)';

  @override
  String get seatMe => '(you)';

  @override
  String get chatLabel => 'Chat';

  @override
  String get chatInput => 'Your answer';

  @override
  String get chatPlaceholder => 'Type the anime title';

  @override
  String get chatSend => 'Send';

  @override
  String chatLive(Object n) {
    return '$n online';
  }

  @override
  String chatLobbyJoined(Object code) {
    return 'You joined room $code. The host is choosing the settings';
  }

  @override
  String get gameDisconnected => 'Disconnected from the server.';

  @override
  String get gameReplaced =>
      'You connected from another window, so this one was closed.';

  @override
  String get errorAlreadyInRoom => 'You are already in a room.';

  @override
  String errorAvailable(Object available) {
    return 'Songs available: $available';
  }

  @override
  String errorAvailableSeries(Object available) {
    return 'Series available: $available';
  }

  @override
  String get errorBadAnswerMode => 'Unknown answer format.';

  @override
  String get errorBadFilter => 'The conditions are not valid.';

  @override
  String get errorBadGameMode => 'Unknown game mode.';

  @override
  String get errorBadMessage => 'The request was not valid.';

  @override
  String get errorBadNickname => 'Enter a nickname of 1 to 16 characters.';

  @override
  String get errorBadRoomTheme => 'Unknown theme.';

  @override
  String get errorBadSongCount => 'A game has 5 to 30 songs.';

  @override
  String get errorInGame => 'A game is in progress. Please join after it ends.';

  @override
  String get errorNoRoom => 'You are not in a room.';

  @override
  String get errorNotEnoughSongs => 'There are not enough songs to play.';

  @override
  String get errorNotHost => 'Only the host can start the game.';

  @override
  String get errorRateLimited => 'You\'re sending messages too fast.';

  @override
  String get errorRejoinFailed =>
      'Could not reconnect. Please join the room again.';

  @override
  String get errorRoomClosed => 'The room was closed.';

  @override
  String get errorRoomFull => 'The room is full (8 players max).';

  @override
  String get errorRoomNotFound => 'Room not found.';

  @override
  String get errorServerFull => 'The server is full. Please try again shortly.';

  @override
  String get errorTooLarge => 'The message is too large.';

  @override
  String get errorTooLong => 'Chat messages can be up to 200 characters.';

  @override
  String errorUnknown(Object code) {
    return 'An error occurred ($code)';
  }
}

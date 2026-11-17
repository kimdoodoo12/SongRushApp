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
  String get lobbyAbandoned =>
      'The game was stopped, so you are back in the lobby.';

  @override
  String get stageLabel => 'Stage';

  @override
  String get stageTimeLeft => 'Time left';

  @override
  String get stageConnecting => 'Connecting to the server...';

  @override
  String get stageWaitingNext => 'Waiting for the next song...';

  @override
  String stageRound(Object index, Object total) {
    return '<lead>Song</lead><num>$index / $total</num>';
  }

  @override
  String stageScore(Object score) {
    return 'Score: $score';
  }

  @override
  String stageSeconds(Object seconds) {
    return '${seconds}s';
  }

  @override
  String get stageWatching =>
      'You are watching this song. You play from the next one.';

  @override
  String get stageLoading => 'Loading the audio...';

  @override
  String get stageLoadFailed => 'This song could not be played.';

  @override
  String get stageHint => 'Type the anime title in the chat to guess the song';

  @override
  String get stageSkip => 'Skip';

  @override
  String get stageSkipCancel => 'Unskip';

  @override
  String stageSkipCount(Object votes, Object needed) {
    return 'Skip $votes/$needed';
  }

  @override
  String stagePickCount(Object picked, Object total) {
    return 'Picked $picked/$total';
  }

  @override
  String get stageSub =>
      'Korean, English, Japanese and romaji titles all count. Spaces and punctuation are ignored.';

  @override
  String get stageSoon =>
      'When the song plays, type the anime title in the chat';

  @override
  String get stageSoonSub => 'Everyone hears the same song at the same moment.';

  @override
  String get stageChoiceSoon =>
      'When the song plays, pick its anime from the covers';

  @override
  String get stageChoiceHint =>
      'Pick the anime of the song you hear from the covers';

  @override
  String get stageChoiceSub =>
      'You can pick only once. If you miss, watch the rest of this song. Nobody else sees your pick, and the chat is just for talking.';

  @override
  String stagePickWrong(Object n) {
    return 'No. $n is wrong. Wait for the answer.';
  }

  @override
  String get revealHeading => 'Answer';

  @override
  String get revealNoWinner => 'Nobody got it';

  @override
  String revealWinner(Object name) {
    return 'Winner: $name';
  }

  @override
  String get revealRanking => 'This song\'s ranking';

  @override
  String get revealLabelKo => 'KO';

  @override
  String get revealLabelEn => 'EN';

  @override
  String get revealLabelJa => 'JA';

  @override
  String get revealLabelRomaji => 'Romaji';

  @override
  String revealSolvers(Object names) {
    return 'Solved: $names';
  }

  @override
  String get revealNext => 'The next song starts in a moment';

  @override
  String get revealNextFinal => 'The final ranking is up in a moment';

  @override
  String revealRankPlace(Object rank) {
    return '#$rank';
  }

  @override
  String revealRankPoints(Object points) {
    return '+$points';
  }

  @override
  String get revealStampHit => 'Correct';

  @override
  String get revealStampPass => 'Pass';

  @override
  String revealCoverAlt(Object title) {
    return '$title cover';
  }

  @override
  String get revealCoverAltIdle => 'Anime cover';

  @override
  String get scoreBoard => 'Scoreboard';

  @override
  String get scoreRanking => 'Final ranking';

  @override
  String scorePoints(Object score) {
    return '$score pt';
  }

  @override
  String scoreRank(Object rank) {
    return '#$rank';
  }

  @override
  String scoreFinal(Object score) {
    return 'Final score: $score';
  }

  @override
  String get gameLeave => 'Leave';

  @override
  String get gameLeaveAsk => 'Leave the game?';

  @override
  String get gameLeaveConfirm => 'Leave';

  @override
  String get gameLeaveCancel => 'Cancel';

  @override
  String get gameToLobby => 'To lobby';

  @override
  String chatCorrectLine(Object name) {
    return '$name got it!';
  }

  @override
  String chatWrongLine(Object name) {
    return '✘ $name picked wrong';
  }

  @override
  String chatCorrectRanked(Object name, Object rank) {
    return '$name got it! (#$rank)';
  }

  @override
  String get chatGameStart => 'Game starting';

  @override
  String chatSongStart(Object n) {
    return 'Song $n started';
  }

  @override
  String get chatGameOver => 'Game over';

  @override
  String get chatLobbyBack => 'Back in the lobby';

  @override
  String answerChoiceN(Object n) {
    return 'Choice $n';
  }

  @override
  String get answerMarkCorrect => '✔ Correct';

  @override
  String get answerMarkWrong => '✕ Wrong';

  @override
  String get seatCorrect => 'Got it!';

  @override
  String seatScore(Object score) {
    return '$score pt';
  }

  @override
  String get stageUnlockSound => 'Tap for sound';

  @override
  String get gameReset => 'Home';

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

  @override
  String get offsetOpen => 'Sync';

  @override
  String get offsetTitle => 'Sound sync';

  @override
  String get offsetHint =>
      'If your sound comes later than everyone else\'s, move it toward −; if earlier, toward +. It applies from the next song.';

  @override
  String offsetValue(Object ms) {
    return '${ms}ms';
  }

  @override
  String get offsetEarlier => 'Earlier';

  @override
  String get offsetLater => 'Later';

  @override
  String get offsetReset => 'Reset';

  @override
  String get offsetClose => 'Close';
}

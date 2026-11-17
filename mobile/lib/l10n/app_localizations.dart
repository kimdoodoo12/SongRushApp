import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/widgets.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:intl/intl.dart' as intl;

import 'app_localizations_en.dart';
import 'app_localizations_ja.dart';
import 'app_localizations_ko.dart';

// ignore_for_file: type=lint

/// Callers can lookup localized strings with an instance of AppLocalizations
/// returned by `AppLocalizations.of(context)`.
///
/// Applications need to include `AppLocalizations.delegate()` in their app's
/// `localizationDelegates` list, and the locales they support in the app's
/// `supportedLocales` list. For example:
///
/// ```dart
/// import 'l10n/app_localizations.dart';
///
/// return MaterialApp(
///   localizationsDelegates: AppLocalizations.localizationsDelegates,
///   supportedLocales: AppLocalizations.supportedLocales,
///   home: MyApplicationHome(),
/// );
/// ```
///
/// ## Update pubspec.yaml
///
/// Please make sure to update your pubspec.yaml to include the following
/// packages:
///
/// ```yaml
/// dependencies:
///   # Internationalization support.
///   flutter_localizations:
///     sdk: flutter
///   intl: any # Use the pinned version from flutter_localizations
///
///   # Rest of dependencies
/// ```
///
/// ## iOS Applications
///
/// iOS applications define key application metadata, including supported
/// locales, in an Info.plist file that is built into the application bundle.
/// To configure the locales supported by your app, you’ll need to edit this
/// file.
///
/// First, open your project’s ios/Runner.xcworkspace Xcode workspace file.
/// Then, in the Project Navigator, open the Info.plist file under the Runner
/// project’s Runner folder.
///
/// Next, select the Information Property List item, select Add Item from the
/// Editor menu, then select Localizations from the pop-up menu.
///
/// Select and expand the newly-created Localizations item then, for each
/// locale your application supports, add a new item and select the locale
/// you wish to add from the pop-up menu in the Value field. This list should
/// be consistent with the languages listed in the AppLocalizations.supportedLocales
/// property.
abstract class AppLocalizations {
  AppLocalizations(String locale)
    : localeName = intl.Intl.canonicalizedLocale(locale.toString());

  final String localeName;

  static AppLocalizations of(BuildContext context) {
    return Localizations.of<AppLocalizations>(context, AppLocalizations)!;
  }

  static const LocalizationsDelegate<AppLocalizations> delegate =
      _AppLocalizationsDelegate();

  /// A list of this localizations delegate along with the default localizations
  /// delegates.
  ///
  /// Returns a list of localizations delegates containing this delegate along with
  /// GlobalMaterialLocalizations.delegate, GlobalCupertinoLocalizations.delegate,
  /// and GlobalWidgetsLocalizations.delegate.
  ///
  /// Additional delegates can be added by appending to this list in
  /// MaterialApp. This list does not have to be used at all if a custom list
  /// of delegates is preferred or required.
  static const List<LocalizationsDelegate<dynamic>> localizationsDelegates =
      <LocalizationsDelegate<dynamic>>[
        delegate,
        GlobalMaterialLocalizations.delegate,
        GlobalCupertinoLocalizations.delegate,
        GlobalWidgetsLocalizations.delegate,
      ];

  /// A list of this localizations delegate's supported locales.
  static const List<Locale> supportedLocales = <Locale>[
    Locale('en'),
    Locale('ja'),
    Locale('ko'),
  ];

  /// web: app.title
  ///
  /// In ko, this message translates to:
  /// **'SongRush'**
  String get appTitle;

  /// web: app.credit
  ///
  /// In ko, this message translates to:
  /// **'데이터 출처: AnimeThemes, AniList, anime-offline-database, AniDB, Wikidata'**
  String get appCredit;

  /// web: app.reconnecting
  ///
  /// In ko, this message translates to:
  /// **'연결이 끊겨 다시 연결하는 중...'**
  String get appReconnecting;

  /// web: start.nickname
  ///
  /// In ko, this message translates to:
  /// **'닉네임'**
  String get startNickname;

  /// web: start.createRoom
  ///
  /// In ko, this message translates to:
  /// **'방 만들기'**
  String get startCreateRoom;

  /// web: start.orCode
  ///
  /// In ko, this message translates to:
  /// **'또는 코드로 참가'**
  String get startOrCode;

  /// web: start.roomCode
  ///
  /// In ko, this message translates to:
  /// **'방 코드'**
  String get startRoomCode;

  /// web: start.join
  ///
  /// In ko, this message translates to:
  /// **'참가'**
  String get startJoin;

  /// web: start.joinTitle
  ///
  /// In ko, this message translates to:
  /// **'{code} 방 참가'**
  String startJoinTitle(Object code);

  /// web: start.confirm
  ///
  /// In ko, this message translates to:
  /// **'확인'**
  String get startConfirm;

  /// web: start.cancel
  ///
  /// In ko, this message translates to:
  /// **'취소'**
  String get startCancel;

  /// web: theme.sunset
  ///
  /// In ko, this message translates to:
  /// **'노을 옥상'**
  String get themeSunset;

  /// web: mood.tag.sunset
  ///
  /// In ko, this message translates to:
  /// **'애니 OP·ED 맞히기'**
  String get moodTagSunset;

  /// web: skin.sunset.title
  ///
  /// In ko, this message translates to:
  /// **'노을'**
  String get skinSunsetTitle;

  /// web: skin.sunset.label
  ///
  /// In ko, this message translates to:
  /// **'방 코드'**
  String get skinSunsetLabel;

  /// web: skin.sunset.admit
  ///
  /// In ko, this message translates to:
  /// **'ADMIT ONE'**
  String get skinSunsetAdmit;

  /// web: skin.sunset.start
  ///
  /// In ko, this message translates to:
  /// **'Play Song'**
  String get skinSunsetStart;

  /// web: skin.sunset.seats
  ///
  /// In ko, this message translates to:
  /// **'대기실'**
  String get skinSunsetSeats;

  /// web: skin.sunset.ready
  ///
  /// In ko, this message translates to:
  /// **'참가'**
  String get skinSunsetReady;

  /// web: skin.sunset.empty
  ///
  /// In ko, this message translates to:
  /// **'빈 자리'**
  String get skinSunsetEmpty;

  /// web: lobby.copy
  ///
  /// In ko, this message translates to:
  /// **'복사'**
  String get lobbyCopy;

  /// web: lobby.waiting
  ///
  /// In ko, this message translates to:
  /// **'방장이 게임을 시작하기를 기다리는 중...'**
  String get lobbyWaiting;

  /// web: lobby.songCount
  ///
  /// In ko, this message translates to:
  /// **'곡 수'**
  String get lobbySongCount;

  /// web: lobby.leave
  ///
  /// In ko, this message translates to:
  /// **'나가기'**
  String get lobbyLeave;

  /// web: lobby.seats
  ///
  /// In ko, this message translates to:
  /// **'{seats} · 참가자 {n} / 8'**
  String lobbySeats(Object seats, Object n);

  /// web: lobby.hostSeat
  ///
  /// In ko, this message translates to:
  /// **'방장'**
  String get lobbyHostSeat;

  /// web: lobby.of
  ///
  /// In ko, this message translates to:
  /// **'/ {n}명'**
  String lobbyOf(Object n);

  /// web: lobby.settingsTitle
  ///
  /// In ko, this message translates to:
  /// **'게임 설정'**
  String get lobbySettingsTitle;

  /// web: lobby.whoHost
  ///
  /// In ko, this message translates to:
  /// **'방장만 바꿀 수 있어요'**
  String get lobbyWhoHost;

  /// web: lobby.whoGuest
  ///
  /// In ko, this message translates to:
  /// **'방장이 정한 설정이에요'**
  String get lobbyWhoGuest;

  /// web: lobby.whoPending
  ///
  /// In ko, this message translates to:
  /// **'방장이 설정을 고르는 중이에요'**
  String get lobbyWhoPending;

  /// web: lobby.shareHint
  ///
  /// In ko, this message translates to:
  /// **'링크를 보내면 친구가 닉네임만 넣고 바로 들어와요'**
  String get lobbyShareHint;

  /// web: lobby.copied
  ///
  /// In ko, this message translates to:
  /// **'링크를 복사했어요'**
  String get lobbyCopied;

  /// web: lobby.copyFailed
  ///
  /// In ko, this message translates to:
  /// **'복사하지 못했어요. 주소를 길게 눌러 복사해 주세요'**
  String get lobbyCopyFailed;

  /// web: lobby.songCountDown
  ///
  /// In ko, this message translates to:
  /// **'곡 수 줄이기'**
  String get lobbySongCountDown;

  /// web: lobby.songCountUp
  ///
  /// In ko, this message translates to:
  /// **'곡 수 늘리기'**
  String get lobbySongCountUp;

  /// web: filter.type
  ///
  /// In ko, this message translates to:
  /// **'곡 종류'**
  String get filterType;

  /// web: filter.tier
  ///
  /// In ko, this message translates to:
  /// **'난이도'**
  String get filterTier;

  /// web: filter.from
  ///
  /// In ko, this message translates to:
  /// **'연도 시작'**
  String get filterFrom;

  /// web: filter.to
  ///
  /// In ko, this message translates to:
  /// **'연도 끝'**
  String get filterTo;

  /// web: filter.fromAny
  ///
  /// In ko, this message translates to:
  /// **'처음부터'**
  String get filterFromAny;

  /// web: filter.toAny
  ///
  /// In ko, this message translates to:
  /// **'지금까지'**
  String get filterToAny;

  /// web: filter.reversed
  ///
  /// In ko, this message translates to:
  /// **'시작 연도가 끝 연도보다 늦습니다.'**
  String get filterReversed;

  /// web: filter.all
  ///
  /// In ko, this message translates to:
  /// **'전체'**
  String get filterAll;

  /// web: filter.op
  ///
  /// In ko, this message translates to:
  /// **'오프닝만'**
  String get filterOp;

  /// web: filter.ed
  ///
  /// In ko, this message translates to:
  /// **'엔딩만'**
  String get filterEd;

  /// web: filter.easy
  ///
  /// In ko, this message translates to:
  /// **'쉬움'**
  String get filterEasy;

  /// web: filter.normal
  ///
  /// In ko, this message translates to:
  /// **'보통'**
  String get filterNormal;

  /// web: filter.hard
  ///
  /// In ko, this message translates to:
  /// **'어려움'**
  String get filterHard;

  /// web: filter.years
  ///
  /// In ko, this message translates to:
  /// **'연도'**
  String get filterYears;

  /// web: answer.label
  ///
  /// In ko, this message translates to:
  /// **'정답 형식'**
  String get answerLabel;

  /// web: answer.chat
  ///
  /// In ko, this message translates to:
  /// **'채팅'**
  String get answerChat;

  /// web: answer.choice
  ///
  /// In ko, this message translates to:
  /// **'객관식'**
  String get answerChoice;

  /// web: answer.hintChat
  ///
  /// In ko, this message translates to:
  /// **'채팅으로 애니 제목을 맞혀요'**
  String get answerHintChat;

  /// web: answer.hintChoice
  ///
  /// In ko, this message translates to:
  /// **'표지 {k}장 중에서 골라요 (난이도에 따라 4·6·8장)'**
  String answerHintChoice(Object k);

  /// web: mode.label
  ///
  /// In ko, this message translates to:
  /// **'게임 모드'**
  String get modeLabel;

  /// web: mode.first
  ///
  /// In ko, this message translates to:
  /// **'선착순'**
  String get modeFirst;

  /// web: mode.ranked
  ///
  /// In ko, this message translates to:
  /// **'순위전'**
  String get modeRanked;

  /// web: mode.hintFirst
  ///
  /// In ko, this message translates to:
  /// **'가장 먼저 맞힌 한 명만 점수를 받아요'**
  String get modeHintFirst;

  /// web: mode.hintRanked
  ///
  /// In ko, this message translates to:
  /// **'모두 맞히거나 고를 때까지 곡이 이어지고 순위대로 점수를 받아요'**
  String get modeHintRanked;

  /// web: seat.list
  ///
  /// In ko, this message translates to:
  /// **'자리'**
  String get seatList;

  /// web: seat.empty
  ///
  /// In ko, this message translates to:
  /// **'빈 자리'**
  String get seatEmpty;

  /// web: seat.host
  ///
  /// In ko, this message translates to:
  /// **'(방장)'**
  String get seatHost;

  /// web: seat.me
  ///
  /// In ko, this message translates to:
  /// **'(나)'**
  String get seatMe;

  /// web: chat.label
  ///
  /// In ko, this message translates to:
  /// **'채팅'**
  String get chatLabel;

  /// web: chat.input
  ///
  /// In ko, this message translates to:
  /// **'정답 입력'**
  String get chatInput;

  /// web: chat.placeholder
  ///
  /// In ko, this message translates to:
  /// **'애니 제목을 입력하세요'**
  String get chatPlaceholder;

  /// web: chat.send
  ///
  /// In ko, this message translates to:
  /// **'보내기'**
  String get chatSend;

  /// web: chat.live
  ///
  /// In ko, this message translates to:
  /// **'{n}명 접속 중'**
  String chatLive(Object n);

  /// web: chat.lobbyJoined
  ///
  /// In ko, this message translates to:
  /// **'{code} 방에 들어왔어요. 방장이 설정을 고르는 중이에요'**
  String chatLobbyJoined(Object code);

  /// web: game.disconnected
  ///
  /// In ko, this message translates to:
  /// **'서버와 연결이 끊어졌습니다.'**
  String get gameDisconnected;

  /// web: game.replaced
  ///
  /// In ko, this message translates to:
  /// **'다른 창에서 접속해 이 연결이 끊겼습니다.'**
  String get gameReplaced;

  /// web: error.already-in-room
  ///
  /// In ko, this message translates to:
  /// **'이미 방에 참가해 있습니다.'**
  String get errorAlreadyInRoom;

  /// web: error.available
  ///
  /// In ko, this message translates to:
  /// **'출제할 수 있는 곡이 {available}곡뿐입니다.'**
  String errorAvailable(Object available);

  /// web: error.availableSeries
  ///
  /// In ko, this message translates to:
  /// **'출제할 수 있는 작품이 {available}개뿐입니다.'**
  String errorAvailableSeries(Object available);

  /// web: error.bad-answer-mode
  ///
  /// In ko, this message translates to:
  /// **'알 수 없는 정답 형식입니다.'**
  String get errorBadAnswerMode;

  /// web: error.bad-filter
  ///
  /// In ko, this message translates to:
  /// **'조건이 올바르지 않습니다.'**
  String get errorBadFilter;

  /// web: error.bad-game-mode
  ///
  /// In ko, this message translates to:
  /// **'알 수 없는 게임 모드입니다.'**
  String get errorBadGameMode;

  /// web: error.bad-message
  ///
  /// In ko, this message translates to:
  /// **'잘못된 요청입니다.'**
  String get errorBadMessage;

  /// web: error.bad-nickname
  ///
  /// In ko, this message translates to:
  /// **'닉네임은 1~16자로 입력해 주세요.'**
  String get errorBadNickname;

  /// web: error.bad-room-theme
  ///
  /// In ko, this message translates to:
  /// **'알 수 없는 분위기입니다.'**
  String get errorBadRoomTheme;

  /// web: error.bad-song-count
  ///
  /// In ko, this message translates to:
  /// **'곡 수는 5~30곡입니다.'**
  String get errorBadSongCount;

  /// web: error.in-game
  ///
  /// In ko, this message translates to:
  /// **'게임이 진행 중입니다. 끝난 뒤에 참가해 주세요.'**
  String get errorInGame;

  /// web: error.no-room
  ///
  /// In ko, this message translates to:
  /// **'참가한 방이 없습니다.'**
  String get errorNoRoom;

  /// web: error.not-enough-songs
  ///
  /// In ko, this message translates to:
  /// **'출제할 곡이 부족합니다.'**
  String get errorNotEnoughSongs;

  /// web: error.not-host
  ///
  /// In ko, this message translates to:
  /// **'방장만 시작할 수 있습니다.'**
  String get errorNotHost;

  /// web: error.rate-limited
  ///
  /// In ko, this message translates to:
  /// **'너무 빨리 보내고 있어요.'**
  String get errorRateLimited;

  /// web: error.rejoin-failed
  ///
  /// In ko, this message translates to:
  /// **'다시 연결하지 못했습니다. 방에 다시 참가해 주세요.'**
  String get errorRejoinFailed;

  /// web: error.room-closed
  ///
  /// In ko, this message translates to:
  /// **'방이 닫혔습니다.'**
  String get errorRoomClosed;

  /// web: error.room-full
  ///
  /// In ko, this message translates to:
  /// **'방이 가득 찼습니다 (최대 8명).'**
  String get errorRoomFull;

  /// web: error.room-not-found
  ///
  /// In ko, this message translates to:
  /// **'방을 찾을 수 없습니다.'**
  String get errorRoomNotFound;

  /// web: error.server-full
  ///
  /// In ko, this message translates to:
  /// **'서버가 가득 찼습니다. 잠시 후 다시 시도해 주세요.'**
  String get errorServerFull;

  /// web: error.too-large
  ///
  /// In ko, this message translates to:
  /// **'메시지가 너무 깁니다.'**
  String get errorTooLarge;

  /// web: error.too-long
  ///
  /// In ko, this message translates to:
  /// **'채팅은 200자까지 입력할 수 있습니다.'**
  String get errorTooLong;

  /// web: error.unknown
  ///
  /// In ko, this message translates to:
  /// **'오류가 발생했습니다 ({code})'**
  String errorUnknown(Object code);
}

class _AppLocalizationsDelegate
    extends LocalizationsDelegate<AppLocalizations> {
  const _AppLocalizationsDelegate();

  @override
  Future<AppLocalizations> load(Locale locale) {
    return SynchronousFuture<AppLocalizations>(lookupAppLocalizations(locale));
  }

  @override
  bool isSupported(Locale locale) =>
      <String>['en', 'ja', 'ko'].contains(locale.languageCode);

  @override
  bool shouldReload(_AppLocalizationsDelegate old) => false;
}

AppLocalizations lookupAppLocalizations(Locale locale) {
  // Lookup logic when only language code is specified.
  switch (locale.languageCode) {
    case 'en':
      return AppLocalizationsEn();
    case 'ja':
      return AppLocalizationsJa();
    case 'ko':
      return AppLocalizationsKo();
  }

  throw FlutterError(
    'AppLocalizations.delegate failed to load unsupported locale "$locale". This is likely '
    'an issue with the localizations generation tool. Please file an issue '
    'on GitHub with a reproducible sample app and the gen-l10n configuration '
    'that was used.',
  );
}

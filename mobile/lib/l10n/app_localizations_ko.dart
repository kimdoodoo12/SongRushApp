// ignore: unused_import
import 'package:intl/intl.dart' as intl;

import 'app_localizations.dart';

// ignore_for_file: type=lint

/// The translations for Korean (`ko`).
class AppLocalizationsKo extends AppLocalizations {
  AppLocalizationsKo([String locale = 'ko']) : super(locale);

  @override
  String get appTitle => 'SongRush';

  @override
  String get appCredit =>
      '데이터 출처: AnimeThemes, AniList, anime-offline-database, AniDB, Wikidata';

  @override
  String get appReconnecting => '연결이 끊겨 다시 연결하는 중...';

  @override
  String get startNickname => '닉네임';

  @override
  String get startCreateRoom => '방 만들기';

  @override
  String get startOrCode => '또는 코드로 참가';

  @override
  String get startRoomCode => '방 코드';

  @override
  String get startJoin => '참가';

  @override
  String startJoinTitle(Object code) {
    return '$code 방 참가';
  }

  @override
  String get startConfirm => '확인';

  @override
  String get startCancel => '취소';

  @override
  String get themeSunset => '노을 옥상';

  @override
  String get moodTagSunset => '애니 OP·ED 맞히기';

  @override
  String get skinSunsetTitle => '노을';

  @override
  String get skinSunsetLabel => '방 코드';

  @override
  String get skinSunsetAdmit => 'ADMIT ONE';

  @override
  String get skinSunsetStart => 'Play Song';

  @override
  String get skinSunsetSeats => '대기실';

  @override
  String get skinSunsetReady => '참가';

  @override
  String get skinSunsetEmpty => '빈 자리';

  @override
  String get lobbyCopy => '복사';

  @override
  String get lobbyWaiting => '방장이 게임을 시작하기를 기다리는 중...';

  @override
  String get lobbySongCount => '곡 수';

  @override
  String get lobbyLeave => '나가기';

  @override
  String lobbySeats(Object seats, Object n) {
    return '$seats · 참가자 $n / 8';
  }

  @override
  String get lobbyHostSeat => '방장';

  @override
  String lobbyOf(Object n) {
    return '/ $n명';
  }

  @override
  String get lobbySettingsTitle => '게임 설정';

  @override
  String get lobbyWhoHost => '방장만 바꿀 수 있어요';

  @override
  String get lobbyWhoGuest => '방장이 정한 설정이에요';

  @override
  String get lobbyWhoPending => '방장이 설정을 고르는 중이에요';

  @override
  String get lobbyShareHint => '링크를 보내면 친구가 닉네임만 넣고 바로 들어와요';

  @override
  String get lobbyCopied => '링크를 복사했어요';

  @override
  String get lobbyCopyFailed => '복사하지 못했어요. 주소를 길게 눌러 복사해 주세요';

  @override
  String get lobbySongCountDown => '곡 수 줄이기';

  @override
  String get lobbySongCountUp => '곡 수 늘리기';

  @override
  String get filterType => '곡 종류';

  @override
  String get filterTier => '난이도';

  @override
  String get filterFrom => '연도 시작';

  @override
  String get filterTo => '연도 끝';

  @override
  String get filterFromAny => '처음부터';

  @override
  String get filterToAny => '지금까지';

  @override
  String get filterReversed => '시작 연도가 끝 연도보다 늦습니다.';

  @override
  String get filterAll => '전체';

  @override
  String get filterOp => '오프닝만';

  @override
  String get filterEd => '엔딩만';

  @override
  String get filterEasy => '쉬움';

  @override
  String get filterNormal => '보통';

  @override
  String get filterHard => '어려움';

  @override
  String get filterYears => '연도';

  @override
  String get answerLabel => '정답 형식';

  @override
  String get answerChat => '채팅';

  @override
  String get answerChoice => '객관식';

  @override
  String get answerHintChat => '채팅으로 애니 제목을 맞혀요';

  @override
  String answerHintChoice(Object k) {
    return '표지 $k장 중에서 골라요 (난이도에 따라 4·6·8장)';
  }

  @override
  String get modeLabel => '게임 모드';

  @override
  String get modeFirst => '선착순';

  @override
  String get modeRanked => '순위전';

  @override
  String get modeHintFirst => '가장 먼저 맞힌 한 명만 점수를 받아요';

  @override
  String get modeHintRanked => '모두 맞히거나 고를 때까지 곡이 이어지고 순위대로 점수를 받아요';

  @override
  String get seatList => '자리';

  @override
  String get seatEmpty => '빈 자리';

  @override
  String get seatHost => '(방장)';

  @override
  String get seatMe => '(나)';

  @override
  String get chatLabel => '채팅';

  @override
  String get chatInput => '정답 입력';

  @override
  String get chatPlaceholder => '애니 제목을 입력하세요';

  @override
  String get chatSend => '보내기';

  @override
  String chatLive(Object n) {
    return '$n명 접속 중';
  }

  @override
  String chatLobbyJoined(Object code) {
    return '$code 방에 들어왔어요. 방장이 설정을 고르는 중이에요';
  }

  @override
  String get gameDisconnected => '서버와 연결이 끊어졌습니다.';

  @override
  String get gameReplaced => '다른 창에서 접속해 이 연결이 끊겼습니다.';

  @override
  String get lobbyAbandoned => '게임이 중단되어 대기실로 돌아왔습니다.';

  @override
  String get stageLabel => '무대';

  @override
  String get stageTimeLeft => '남은 시간';

  @override
  String get stageConnecting => '서버에 연결하는 중...';

  @override
  String get stageWaitingNext => '다음 곡을 기다리는 중...';

  @override
  String stageRound(Object index, Object total) {
    return '<num>$index / $total</num> 번째 곡';
  }

  @override
  String stageScore(Object score) {
    return '점수: $score';
  }

  @override
  String stageSeconds(Object seconds) {
    return '$seconds초';
  }

  @override
  String get stageWatching => '이번 곡은 구경 중입니다. 다음 곡부터 참가합니다.';

  @override
  String get stageLoading => '음원을 불러오는 중...';

  @override
  String get stageLoadFailed => '이번 곡을 재생하지 못했습니다.';

  @override
  String get stageHint => '들리는 곡의 애니 제목을 채팅으로 맞혀 보세요';

  @override
  String get stageSkip => '스킵';

  @override
  String get stageSkipCancel => '스킵 취소';

  @override
  String stageSkipCount(Object votes, Object needed) {
    return '스킵 $votes/$needed';
  }

  @override
  String stagePickCount(Object picked, Object total) {
    return '선택 $picked/$total';
  }

  @override
  String get stageSub => '한국어, 영어, 일본어, 로마자 제목 모두 정답이에요. 띄어쓰기와 문장부호는 무시해요.';

  @override
  String get stageSoon => '곡이 나오면 애니 제목을 채팅으로 맞혀 보세요';

  @override
  String get stageSoonSub => '모두가 같은 순간에 같은 곡을 들어요.';

  @override
  String get stageChoiceSoon => '곡이 나오면 표지 중에서 그 애니를 골라 보세요';

  @override
  String get stageChoiceHint => '들리는 곡의 애니를 표지에서 골라 보세요';

  @override
  String get stageChoiceSub =>
      '한 번만 고를 수 있어요. 틀리면 이번 곡은 끝까지 구경해요. 내가 고른 표지는 다른 사람에게 보이지 않고, 채팅은 대화용이에요.';

  @override
  String stagePickWrong(Object n) {
    return '$n번은 오답이에요. 정답 공개를 기다려 주세요.';
  }

  @override
  String get revealHeading => '정답 공개';

  @override
  String get revealNoWinner => '정답자 없음';

  @override
  String revealWinner(Object name) {
    return '정답: $name';
  }

  @override
  String get revealRanking => '이번 곡 순위';

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
    return '정답자: $names';
  }

  @override
  String get revealNext => '잠시 후 다음 곡이 시작돼요';

  @override
  String get revealNextFinal => '잠시 후 최종 순위를 보여 줘요';

  @override
  String revealRankPlace(Object rank) {
    return '$rank등';
  }

  @override
  String revealRankPoints(Object points) {
    return '+$points점';
  }

  @override
  String get revealStampHit => '정답';

  @override
  String get revealStampPass => '통과';

  @override
  String revealCoverAlt(Object title) {
    return '$title 표지';
  }

  @override
  String get revealCoverAltIdle => '애니 표지';

  @override
  String get scoreBoard => '점수판';

  @override
  String get scoreRanking => '최종 순위';

  @override
  String scorePoints(Object score) {
    return '$score점';
  }

  @override
  String scoreRank(Object rank) {
    return '$rank위';
  }

  @override
  String scoreFinal(Object score) {
    return '최종 점수: $score점';
  }

  @override
  String get gameLeave => '나가기';

  @override
  String get gameLeaveAsk => '게임을 나갈까요?';

  @override
  String get gameLeaveConfirm => '나가기';

  @override
  String get gameLeaveCancel => '취소';

  @override
  String get gameToLobby => '대기실로';

  @override
  String chatCorrectLine(Object name) {
    return '$name 정답!';
  }

  @override
  String chatWrongLine(Object name) {
    return '✘ $name 오답';
  }

  @override
  String chatCorrectRanked(Object name, Object rank) {
    return '$name 정답! ($rank등)';
  }

  @override
  String get chatGameStart => '게임을 시작해요';

  @override
  String chatSongStart(Object n) {
    return '$n번째 곡 시작';
  }

  @override
  String get chatGameOver => '게임 종료';

  @override
  String get chatLobbyBack => '대기실로 돌아왔어요';

  @override
  String answerChoiceN(Object n) {
    return '보기 $n';
  }

  @override
  String get answerMarkCorrect => '✔ 정답';

  @override
  String get answerMarkWrong => '✕ 오답';

  @override
  String get seatCorrect => '정답!';

  @override
  String seatScore(Object score) {
    return '$score점';
  }

  @override
  String get stageUnlockSound => '탭하여 소리 켜기';

  @override
  String get gameReset => '처음으로';

  @override
  String get errorAlreadyInRoom => '이미 방에 참가해 있습니다.';

  @override
  String errorAvailable(Object available) {
    return '출제할 수 있는 곡이 $available곡뿐입니다.';
  }

  @override
  String errorAvailableSeries(Object available) {
    return '출제할 수 있는 작품이 $available개뿐입니다.';
  }

  @override
  String get errorBadAnswerMode => '알 수 없는 정답 형식입니다.';

  @override
  String get errorBadFilter => '조건이 올바르지 않습니다.';

  @override
  String get errorBadGameMode => '알 수 없는 게임 모드입니다.';

  @override
  String get errorBadMessage => '잘못된 요청입니다.';

  @override
  String get errorBadNickname => '닉네임은 1~16자로 입력해 주세요.';

  @override
  String get errorBadRoomTheme => '알 수 없는 분위기입니다.';

  @override
  String get errorBadSongCount => '곡 수는 5~100곡입니다.';

  @override
  String get errorInGame => '게임이 진행 중입니다. 끝난 뒤에 참가해 주세요.';

  @override
  String get errorNoRoom => '참가한 방이 없습니다.';

  @override
  String get errorNotEnoughSongs => '출제할 곡이 부족합니다.';

  @override
  String get errorNotHost => '방장만 시작할 수 있습니다.';

  @override
  String get errorRateLimited => '너무 빨리 보내고 있어요.';

  @override
  String get errorRejoinFailed => '다시 연결하지 못했습니다. 방에 다시 참가해 주세요.';

  @override
  String get errorRoomClosed => '방이 닫혔습니다.';

  @override
  String get errorRoomFull => '방이 가득 찼습니다 (최대 8명).';

  @override
  String get errorRoomNotFound => '방을 찾을 수 없습니다.';

  @override
  String get errorServerFull => '서버가 가득 찼습니다. 잠시 후 다시 시도해 주세요.';

  @override
  String get errorTooLarge => '메시지가 너무 깁니다.';

  @override
  String get errorTooLong => '채팅은 200자까지 입력할 수 있습니다.';

  @override
  String errorUnknown(Object code) {
    return '오류가 발생했습니다 ($code)';
  }

  @override
  String get offsetOpen => '싱크';

  @override
  String get offsetTitle => '소리 싱크 조절';

  @override
  String get offsetHint =>
      '내 소리가 다른 사람보다 늦게 들리면 − 쪽으로, 빨리 들리면 + 쪽으로 옮겨 주세요. 다음 곡부터 적용됩니다.';

  @override
  String offsetValue(Object ms) {
    return '${ms}ms';
  }

  @override
  String get offsetEarlier => '더 빠르게';

  @override
  String get offsetLater => '더 늦게';

  @override
  String get offsetReset => '0으로';

  @override
  String get offsetClose => '닫기';

  @override
  String get appPcVersion => 'PC 버전';

  @override
  String get chatTalkPlaceholder => '채팅으로 이야기해요';

  @override
  String myRank(Object rank, Object points) {
    return '내 순위 $rank위 · $points점';
  }

  @override
  String get revealMore => '자세히';

  @override
  String get revealLess => '접기';

  @override
  String get roomLeaveAsk => '방에서 나갈까요?';

  @override
  String get exitAsk => 'SongRush를 나갈까요?';
}

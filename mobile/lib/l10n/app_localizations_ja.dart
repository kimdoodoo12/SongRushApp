// ignore: unused_import
import 'package:intl/intl.dart' as intl;

import 'app_localizations.dart';

// ignore_for_file: type=lint

/// The translations for Japanese (`ja`).
class AppLocalizationsJa extends AppLocalizations {
  AppLocalizationsJa([String locale = 'ja']) : super(locale);

  @override
  String get appTitle => 'SongRush';

  @override
  String get appCredit =>
      'データ提供: AnimeThemes, AniList, anime-offline-database, AniDB, Wikidata';

  @override
  String get appReconnecting => '接続が切れました。再接続しています...';

  @override
  String get startNickname => 'ニックネーム';

  @override
  String get startCreateRoom => 'ルームを作る';

  @override
  String get startOrCode => 'またはコードで参加';

  @override
  String get startRoomCode => 'ルームコード';

  @override
  String get startJoin => '参加';

  @override
  String startJoinTitle(Object code) {
    return 'ルーム $code に参加';
  }

  @override
  String get startConfirm => 'OK';

  @override
  String get startCancel => 'キャンセル';

  @override
  String get themeSunset => '夕焼けの屋上';

  @override
  String get moodTagSunset => 'アニメOP・ED当て';

  @override
  String get skinSunsetTitle => '夕焼け';

  @override
  String get skinSunsetLabel => 'ルームコード';

  @override
  String get skinSunsetAdmit => 'ADMIT ONE';

  @override
  String get skinSunsetStart => 'Play Song';

  @override
  String get skinSunsetSeats => 'ロビー';

  @override
  String get skinSunsetReady => '参加';

  @override
  String get skinSunsetEmpty => '空席';

  @override
  String get lobbyCopy => 'コピー';

  @override
  String get lobbyWaiting => 'ホストがゲームを始めるのを待っています...';

  @override
  String get lobbySongCount => '曲数';

  @override
  String get lobbyLeave => '退出';

  @override
  String lobbySeats(Object seats, Object n) {
    return '$seats · 参加者 $n / 8';
  }

  @override
  String get lobbyHostSeat => 'ホスト';

  @override
  String lobbyOf(Object n) {
    return '/ $n人';
  }

  @override
  String get lobbySettingsTitle => 'ゲーム設定';

  @override
  String get lobbyWhoHost => 'ホストだけが変更できます';

  @override
  String get lobbyWhoGuest => 'ホストが決めた設定です';

  @override
  String get lobbyWhoPending => 'ホストが設定を選んでいます';

  @override
  String get lobbyShareHint => 'リンクを送れば、友だちはニックネームを入れるだけで入れます';

  @override
  String get lobbyCopied => 'リンクをコピーしました';

  @override
  String get lobbyCopyFailed => 'コピーできませんでした。アドレスを長押ししてコピーしてください';

  @override
  String get lobbySongCountDown => '曲数を減らす';

  @override
  String get lobbySongCountUp => '曲数を増やす';

  @override
  String get filterType => '曲の種類';

  @override
  String get filterTier => '難易度';

  @override
  String get filterFrom => '開始年';

  @override
  String get filterTo => '終了年';

  @override
  String get filterFromAny => '最初から';

  @override
  String get filterToAny => '現在まで';

  @override
  String get filterReversed => '開始年が終了年より後になっています。';

  @override
  String get filterAll => 'すべて';

  @override
  String get filterOp => 'OPのみ';

  @override
  String get filterEd => 'EDのみ';

  @override
  String get filterEasy => 'かんたん';

  @override
  String get filterNormal => 'ふつう';

  @override
  String get filterHard => 'むずかしい';

  @override
  String get filterYears => '年代';

  @override
  String get answerLabel => '回答形式';

  @override
  String get answerChat => 'チャット';

  @override
  String get answerChoice => '選択式';

  @override
  String get answerHintChat => 'チャットでアニメのタイトルを当てます';

  @override
  String answerHintChoice(Object k) {
    return '表紙$k枚の中から選びます（難易度により4・6・8枚）';
  }

  @override
  String get modeLabel => 'ゲームモード';

  @override
  String get modeFirst => '早い者勝ち';

  @override
  String get modeRanked => '順位戦';

  @override
  String get modeHintFirst => '最初に正解した1人だけが得点します';

  @override
  String get modeHintRanked => '全員が答えるか選ぶまで曲が続き、順位どおりに得点します';

  @override
  String get seatList => '席';

  @override
  String get seatEmpty => '空席';

  @override
  String get seatHost => '（ホスト）';

  @override
  String get seatMe => '（自分）';

  @override
  String get chatLabel => 'チャット';

  @override
  String get chatInput => '答えを入力';

  @override
  String get chatPlaceholder => 'アニメのタイトルを入力';

  @override
  String get chatSend => '送信';

  @override
  String chatLive(Object n) {
    return '$n人接続中';
  }

  @override
  String chatLobbyJoined(Object code) {
    return 'ルーム $code に入りました。ホストが設定を選んでいます';
  }

  @override
  String get gameDisconnected => 'サーバーとの接続が切れました。';

  @override
  String get gameReplaced => '別のウィンドウから接続したため、この接続は切れました。';

  @override
  String get lobbyAbandoned => 'ゲームが中断されたため、ロビーに戻りました。';

  @override
  String get stageLabel => 'ステージ';

  @override
  String get stageTimeLeft => '残り時間';

  @override
  String get stageConnecting => 'サーバーに接続しています...';

  @override
  String get stageWaitingNext => '次の曲を待っています...';

  @override
  String stageRound(Object index, Object total) {
    return '<num>$index / $total</num> 曲目';
  }

  @override
  String stageScore(Object score) {
    return '得点: $score';
  }

  @override
  String stageSeconds(Object seconds) {
    return '$seconds秒';
  }

  @override
  String get stageWatching => 'この曲は観戦中です。次の曲から参加します。';

  @override
  String get stageLoading => '音声を読み込んでいます...';

  @override
  String get stageLoadFailed => 'この曲を再生できませんでした。';

  @override
  String get stageHint => '流れている曲のアニメのタイトルをチャットで当てよう';

  @override
  String get stageSkip => 'スキップ';

  @override
  String get stageSkipCancel => '取り消し';

  @override
  String stageSkipCount(Object votes, Object needed) {
    return 'スキップ$votes/$needed';
  }

  @override
  String stagePickCount(Object picked, Object total) {
    return '選択 $picked/$total';
  }

  @override
  String get stageSub => '韓国語・英語・日本語・ローマ字のタイトル、どれでも正解です。スペースと記号は無視します。';

  @override
  String get stageSoon => '曲が流れたら、アニメのタイトルをチャットで当てよう';

  @override
  String get stageSoonSub => '全員が同じ瞬間に同じ曲を聴きます。';

  @override
  String get stageChoiceSoon => '曲が流れたら、表紙の中からそのアニメを選ぼう';

  @override
  String get stageChoiceHint => '流れている曲のアニメを表紙から選ぼう';

  @override
  String get stageChoiceSub =>
      '選べるのは一度だけです。外れたらこの曲は最後まで見学です。自分が選んだ表紙は他の人には見えず、チャットは会話用です。';

  @override
  String stagePickWrong(Object n) {
    return '$n番は不正解です。正解発表を待ってください。';
  }

  @override
  String get revealHeading => '正解発表';

  @override
  String get revealNoWinner => '正解者なし';

  @override
  String revealWinner(Object name) {
    return '正解: $name';
  }

  @override
  String get revealRanking => 'この曲の順位';

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
    return '正解者: $names';
  }

  @override
  String get revealNext => 'まもなく次の曲が始まります';

  @override
  String get revealNextFinal => 'まもなく最終順位を発表します';

  @override
  String revealRankPlace(Object rank) {
    return '$rank位';
  }

  @override
  String revealRankPoints(Object points) {
    return '+$points点';
  }

  @override
  String get revealStampHit => '正解';

  @override
  String get revealStampPass => 'パス';

  @override
  String revealCoverAlt(Object title) {
    return '$title 表紙';
  }

  @override
  String get revealCoverAltIdle => 'アニメの表紙';

  @override
  String get scoreBoard => 'スコアボード';

  @override
  String get scoreRanking => '最終順位';

  @override
  String scorePoints(Object score) {
    return '$score点';
  }

  @override
  String scoreRank(Object rank) {
    return '$rank位';
  }

  @override
  String scoreFinal(Object score) {
    return '最終得点: $score点';
  }

  @override
  String get gameLeave => '退出';

  @override
  String get gameLeaveAsk => 'ゲームを退出しますか？';

  @override
  String get gameLeaveConfirm => '退出';

  @override
  String get gameLeaveCancel => 'キャンセル';

  @override
  String get gameToLobby => 'ロビーへ';

  @override
  String chatCorrectLine(Object name) {
    return '$name 正解！';
  }

  @override
  String chatWrongLine(Object name) {
    return '✘ $name 不正解';
  }

  @override
  String chatCorrectRanked(Object name, Object rank) {
    return '$name 正解！（$rank位）';
  }

  @override
  String get chatGameStart => 'ゲームを始めます';

  @override
  String chatSongStart(Object n) {
    return '$n曲目スタート';
  }

  @override
  String get chatGameOver => 'ゲーム終了';

  @override
  String get chatLobbyBack => 'ロビーに戻りました';

  @override
  String answerChoiceN(Object n) {
    return '選択肢$n';
  }

  @override
  String get answerMarkCorrect => '✔ 正解';

  @override
  String get answerMarkWrong => '✕ 不正解';

  @override
  String get seatCorrect => '正解！';

  @override
  String seatScore(Object score) {
    return '$score点';
  }

  @override
  String get stageUnlockSound => 'タップして音を出す';

  @override
  String get gameReset => 'トップへ';

  @override
  String get errorAlreadyInRoom => 'すでにルームに参加しています。';

  @override
  String errorAvailable(Object available) {
    return '出題できる曲は$available曲だけです。';
  }

  @override
  String errorAvailableSeries(Object available) {
    return '出題できる作品は$available作品だけです。';
  }

  @override
  String get errorBadAnswerMode => '不明な回答形式です。';

  @override
  String get errorBadFilter => '条件が正しくありません。';

  @override
  String get errorBadGameMode => '不明なゲームモードです。';

  @override
  String get errorBadMessage => '不正なリクエストです。';

  @override
  String get errorBadNickname => 'ニックネームは1〜16文字で入力してください。';

  @override
  String get errorBadRoomTheme => '不明なテーマです。';

  @override
  String get errorBadSongCount => '曲数は5〜30曲です。';

  @override
  String get errorInGame => 'ゲーム中です。終わってから参加してください。';

  @override
  String get errorNoRoom => 'ルームに参加していません。';

  @override
  String get errorNotEnoughSongs => '出題できる曲が足りません。';

  @override
  String get errorNotHost => 'ホストだけがゲームを開始できます。';

  @override
  String get errorRateLimited => '送信が速すぎます。';

  @override
  String get errorRejoinFailed => '再接続できませんでした。もう一度ルームに参加してください。';

  @override
  String get errorRoomClosed => 'ルームが閉じられました。';

  @override
  String get errorRoomFull => 'ルームが満員です（最大8人）。';

  @override
  String get errorRoomNotFound => 'ルームが見つかりません。';

  @override
  String get errorServerFull => 'サーバーが混み合っています。しばらくしてからもう一度お試しください。';

  @override
  String get errorTooLarge => 'メッセージが大きすぎます。';

  @override
  String get errorTooLong => 'チャットは200文字まで入力できます。';

  @override
  String errorUnknown(Object code) {
    return 'エラーが発生しました（$code）';
  }

  @override
  String get offsetOpen => '同期';

  @override
  String get offsetTitle => '音ズレ調整';

  @override
  String get offsetHint =>
      '自分の音がほかの人より遅れて聞こえるなら − に、早く聞こえるなら + に動かしてください。次の曲から反映されます。';

  @override
  String offsetValue(Object ms) {
    return '${ms}ms';
  }

  @override
  String get offsetEarlier => '早く';

  @override
  String get offsetLater => '遅く';

  @override
  String get offsetReset => 'リセット';

  @override
  String get offsetClose => '閉じる';
}

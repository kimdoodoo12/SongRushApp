import 'dart:async';
import 'dart:math' as math;

import 'package:flutter/services.dart';
import 'package:material_ui/material_ui.dart';
import 'package:songrush_core/songrush_core.dart';

import '../app/app_controller.dart';
import '../app/game_model.dart';
import '../l10n/app_localizations.dart';
import 'chat_panel.dart';
import 'palette.dart';
import 'texts.dart';
import 'widgets.dart';

// The clock turns low at this many seconds or less (GameScreen.jsx
// LOW_SECONDS).
const int _lowSeconds = 10;

// The reveal card's title lines, in card order (GameScreen.jsx TITLE_LANGS).
const List<String> _titleLangs = ['ko', 'en', 'ja', 'romaji'];

// Hangul letters: the KO line takes the frame's reveal text when it is
// Korean (GameScreen.jsx lineTitle).
final RegExp _hangul = RegExp('[ㄱ-ㆎ가-힣]');

// stage.round's markup: <lead>...</lead> and <num>...</num>.
final RegExp _roundTag = RegExp(r'<(lead|num)>(.*?)</\1>');

// The em dash before the artists (GameScreen.jsx ThemeLine, U+2014).
final String _dash = String.fromCharCode(0x2014);

/// How long the full reveal card shows before it folds into its strip
/// (D-145: "3-4 s").
const Duration revealFoldDelay = Duration(milliseconds: 3500);

// The share of the middle the full reveal card may take above the chat
// log, and above a choice room's tiles; past it the card scrolls inside.
const double _revealShareChat = 0.6;
const double _revealShareChoice = 0.55;

// After the game, the share of the middle the final ranking may take above
// a chat room's log.
const double _finalShareChat = 0.55;

/// How long a choice room's chat sheet takes to slide up or down (D-146).
const Duration sheetDuration = Duration(milliseconds: 200);

// The dimmed strip of the game screen left above the open chat sheet
// (V3ChatSheet).
const double _sheetGap = 40;

// A swipe down on the sheet's handle or header closes it past this speed
// (logical px/s), or when it is let go below this share of its height.
const double _sheetFlingSpeed = 400;
const double _sheetKeepShare = 0.7;

// The mark before a solver's line (U+2714).
final String _check = String.fromCharCode(0x2714);

// The side padding of the screen's parts.
const double _side = 12;

// The round 채팅 button of a choice room.
const double _chatButton = 56;

// The floating chat's inset from the covers' left edge, so a bubble's
// rounded left end lies over the covers whole (cycle 164).
const double floatInset = 6;

/// The game and game-over screens (GameScreen.jsx, the sunset mood) on one
/// phone screen with no page scroll, in the v2 layout (D-145). Top: the
/// small spinning disc, song n / N, the time bar and the seconds left; then
/// 내 순위 N위 · P점 ▾ (it opens the scores), a choice room's pick count,
/// 스킵 with the vote count, and 나가기 (D-138, with a confirm dialog); the
/// connection, sound and load lines under them.
///
/// A chat room: the hint, the reveal, and the chat log filling the rest
/// (the own lines on the right in the accent colour), with the input fixed
/// at the bottom and lifted by the on-screen keyboard. A choice room: the
/// hint, the reveal, and the covers filling the rest; the latest chat lines
/// float over their bottom left and fade ([FloatingChat]); a round 채팅
/// button with the unread count opens the chat sheet (D-146), and the
/// screen itself has no input. The sheet slides up over the whole screen
/// (a dimmed strip of it left at the top): 채팅 and 닫기 ▾, a thin line with
/// the small disc, song n / N, the time bar, the seconds and the pick
/// count, the whole log, and the input (focused) above the keyboard; none
/// after the game. The game runs on under it, and the covers cannot be
/// picked while it is open. 닫기, a back or a swipe down on its handle or
/// header closes it. A reveal while it is open adds one line to its log;
/// the card shows in full once it closes.
///
/// The reveal shows as the full card for [revealFoldDelay], then folds into
/// a one-line strip (small cover, title, OP1 · song — artists, the winner)
/// with 자세히 ▾, which opens the card again (접기 folds it). After the
/// game the final ranking fills the middle, over the chat log in a chat
/// room; 대기실로 at the bottom once the room is back in its lobby.
class GameScreen extends StatelessWidget {
  const GameScreen({super.key, required this.controller});

  final AppController controller;

  @override
  Widget build(BuildContext context) {
    final game = controller.game;
    if (game == null) return const SizedBox.shrink();
    return ListenableBuilder(
      listenable: game,
      builder: (context, _) => _GameBody(controller: controller, game: game),
    );
  }
}

class _GameBody extends StatefulWidget {
  const _GameBody({required this.controller, required this.game});

  final AppController controller;
  final GameModel game;

  @override
  State<_GameBody> createState() => _GameBodyState();
}

// The scores, the chat sheet and the reveal's fold live in this screen, so
// they go with the screen when it ends; a back closes the scores, then the
// chat sheet ([AppController.registerScreenBack]). The leave dialog (as
// LeaveDialog inside GameScreen on the web) is the controller's, which a
// back opens too.
class _GameBodyState extends State<_GameBody> with SingleTickerProviderStateMixin {
  bool _scoresOpen = false;
  final FocusNode _leaveButton = FocusNode();
  final FocusNode _rankButton = FocusNode();

  // A choice room's chat sheet: open or not, and its slide (0 down, 1 up);
  // it stays built while it slides down, and goes in the frame it reaches
  // the bottom.
  bool _chatOpen = false;
  late final AnimationController _sheet = AnimationController(vsync: this, duration: sheetDuration)
    ..addListener(() {
      if (!_chatOpen && _sheet.value == 0 && mounted) setState(() {});
    });

  // The reveal on screen, whether its card has folded into the strip, and
  // whether the strip was opened again (자세히).
  RevealMessage? _revealOf;
  bool _folded = false;
  bool _detailed = false;
  Timer? _fold;

  // A reveal that came while the chat sheet was open: its line in the
  // sheet's log, after the first [at] lines. Its card shows in full (and
  // then folds) once the sheet closes.
  ({RevealMessage reveal, int at})? _sheetReveal;

  // A choice room's lines read in the chat sheet (the unread count is the
  // rest), and the lines no longer floating over the covers.
  final Set<LogLine> _read = Set.identity();
  final Set<LogLine> _gone = Set.identity();

  @override
  void initState() {
    super.initState();
    _syncReveal();
    widget.controller.registerScreenBack(_back);
  }

  @override
  void didUpdateWidget(_GameBody old) {
    super.didUpdateWidget(old);
    // Every change of the game rebuilds this widget.
    _syncReveal();
  }

  @override
  void dispose() {
    widget.controller.unregisterScreenBack(_back);
    _fold?.cancel();
    _sheet.dispose();
    _leaveButton.dispose();
    _rankButton.dispose();
    super.dispose();
  }

  // A new reveal shows in full, then folds (with the chat sheet open, it
  // gets the sheet's line and waits for the sheet to close); a cleared one
  // (the next song's prepare) takes the strip and the line with it.
  void _syncReveal() {
    final reveal = widget.game.reveal;
    if (identical(reveal, _revealOf)) return;
    _revealOf = reveal;
    _fold?.cancel();
    _fold = null;
    _folded = false;
    _detailed = false;
    _sheetReveal = null;
    if (reveal == null) return;
    if (_chatOpen) {
      _sheetReveal = (reveal: reveal, at: widget.game.log.length);
    } else {
      _startFold();
    }
  }

  void _startFold() {
    _fold?.cancel();
    _fold = Timer(revealFoldDelay, () {
      if (mounted) setState(() => _folded = true);
    });
  }

  void _foldNow() => setState(() {
        _fold?.cancel();
        _folded = true;
        _detailed = false;
      });

  void _cancelLeave() {
    widget.controller.cancelLeave();
    // No 나가기 after the game.
    if (_leaveButton.context != null) _leaveButton.requestFocus();
  }

  // A back: the scores close first, then the chat sheet.
  bool _back() {
    if (_scoresOpen) {
      _closeScores();
      return true;
    }
    if (_chatOpen) {
      _closeChat();
      return true;
    }
    return false;
  }

  void _closeScores() {
    setState(() => _scoresOpen = false);
    _rankButton.requestFocus();
  }

  // Opening or closing the chat sheet reads every line so far, and ends
  // their floating.
  void _readAll() {
    _read.addAll(widget.game.log);
    _gone.addAll(widget.game.log);
  }

  void _openChat() {
    setState(() {
      _readAll();
      _chatOpen = true;
    });
    _sheet.forward();
  }

  void _closeChat() {
    if (!_chatOpen) return;
    setState(() {
      _readAll();
      _chatOpen = false;
      // The reveal that came while it was open: its card now, then the fold.
      final pending = _sheetReveal;
      _sheetReveal = null;
      if (pending != null && identical(pending.reveal, widget.game.reveal)) {
        _folded = false;
        _detailed = false;
        _startFold();
      }
    });
    _sheet.reverse();
  }

  // A drag on the sheet's handle or header moves it with the finger; let
  // go fast downwards or low, it closes, else it slides back up.
  void _dragSheet(double delta, double height) {
    if (!_chatOpen || height <= 0) return;
    _sheet.value -= delta / height;
  }

  void _dragSheetEnd(double velocity) {
    if (!_chatOpen) return;
    if (velocity > _sheetFlingSpeed || _sheet.value < _sheetKeepShare) {
      _closeChat();
    } else {
      _sheet.forward();
    }
  }

  void _floatGone(LogLine line) {
    if (mounted) setState(() => _gone.add(line));
  }

  @override
  Widget build(BuildContext context) {
    final t = AppLocalizations.of(context);
    final c = widget.controller;
    final game = widget.game;
    final state = c.session!;
    final me = state.myName;
    final over = game.view == GameView.over;
    final settings = game.settings;
    final choiceRoom = game.choices != null || settings?.answerMode == 'choice';
    final scores = scoreList(state.players, game);
    final mine = scores.where((s) => s.name == me).firstOrNull;
    final unread = game.log.where((l) => !_read.contains(l)).length;
    final sheetReveal = _sheetReveal;

    final Widget middle = LayoutBuilder(
      builder: (context, box) => choiceRoom ? _choiceMiddle(game, me, over, unread) : _chatMiddle(game, me, box, over),
    );

    final page = Scaffold(
      backgroundColor: Palette.bg,
      // A choice room's screen has no input: the chat sheet over it takes
      // the keyboard.
      resizeToAvoidBottomInset: !choiceRoom,
      body: SafeArea(
        child: Center(
          child: ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: contentMaxWidth),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                _TopBar(
                  game: game,
                  standing: mine == null ? null : (rank: rankIn(scores, mine.score), points: mine.score),
                  rankFocus: _rankButton,
                  onRank: () => setState(() => _scoresOpen = true),
                  trailing: over
                      ? null
                      : RoomButton(
                          key: const ValueKey('game-leave'),
                          label: t.gameLeave,
                          compact: true,
                          focusNode: _leaveButton,
                          onPressed: c.askLeave,
                        ),
                ),
                _Notices(controller: c, game: game, over: over),
                Expanded(child: middle),
                if (!over && !choiceRoom)
                  Container(
                    padding: const EdgeInsets.fromLTRB(_side, 10, _side, 12),
                    decoration: const BoxDecoration(
                      color: Palette.bg,
                      border: Border(top: BorderSide(color: Palette.line)),
                    ),
                    child: ChatForm(key: const ValueKey('chat-form'), onSend: c.sendChat),
                  )
                else if (over && game.lobbyOpen)
                  Padding(
                    padding: const EdgeInsets.fromLTRB(_side, 8, _side, 10),
                    child: Align(
                      alignment: Alignment.centerLeft,
                      child: RoomButton(
                        key: const ValueKey('to-lobby'),
                        label: t.gameToLobby,
                        kind: ButtonKind.primary,
                        onPressed: c.toLobby,
                      ),
                    ),
                  )
                else
                  const SizedBox(height: 8),
              ],
            ),
          ),
        ),
      ),
    );
    return Stack(
      children: [
        page,
        if (choiceRoom && (_chatOpen || _sheet.value > 0))
          Positioned.fill(
            child: _ChatSheet(
              game: game,
              me: me,
              slide: _sheet,
              open: _chatOpen,
              // After the game the sheet only reads (the game-over screen
              // has no input either).
              onSend: over ? null : c.sendChat,
              revealLine: sheetReveal == null
                  ? null
                  : (
                      at: sheetReveal.at,
                      text: sheetRevealText(t, sheetReveal.reveal),
                      solved: revealWho(t, sheetReveal.reveal) != null,
                    ),
              onClose: _closeChat,
              onDrag: _dragSheet,
              onDragEnd: _dragSheetEnd,
            ),
          ),
        if (_scoresOpen)
          Positioned.fill(
            child: _ScoresSheet(scores: scores, me: me, solvers: _solvers(game), onClose: _closeScores),
          ),
        // The game's 나가기 or a back; after the game (a back only) it asks
        // about the room.
        if (c.leaveAsked)
          Positioned.fill(
            child: LeaveDialog(onConfirm: c.leave, onCancel: _cancelLeave, ask: over ? t.roomLeaveAsk : null),
          ),
      ],
    );
  }

  // A chat room: the hint (before the reveal), the reveal, and the log
  // filling the rest; after the game the final ranking over the log.
  Widget _chatMiddle(GameModel game, String? me, BoxConstraints box, bool over) {
    final reveal = game.reveal;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        if (over)
          Padding(
            padding: const EdgeInsets.fromLTRB(_side, 8, _side, 0),
            child: ConstrainedBox(
              constraints: BoxConstraints(maxHeight: box.maxHeight * _finalShareChat),
              child: _FinalCard(game: game, me: me),
            ),
          )
        else if (reveal != null)
          Padding(
            padding: const EdgeInsets.fromLTRB(_side, 8, _side, 0),
            child: _revealArea(game, box.maxHeight * _revealShareChat),
          )
        else
          Padding(
            padding: const EdgeInsets.fromLTRB(_side, 6, _side, 0),
            child: Text(
              hintText(AppLocalizations.of(context), game),
              key: const ValueKey('hint'),
              textAlign: TextAlign.center,
              maxLines: 2,
              overflow: TextOverflow.ellipsis,
              style: Fonts.bodyStyle(size: 12.5, color: Palette.dim),
            ),
          ),
        const SizedBox(height: 8),
        Expanded(
          child: ColoredBox(
            color: Palette.panel,
            child: Semantics(
              label: AppLocalizations.of(context).chatLabel,
              container: true,
              child: ChatLog(lines: game.log, me: me, padding: const EdgeInsets.fromLTRB(16, 10, 16, 10)),
            ),
          ),
        ),
      ],
    );
  }

  // A choice room: the hint, the reveal and the covers filling the rest
  // (after the game the final ranking), the latest lines floating over
  // their bottom left, and the round 채팅 button that opens the chat sheet.
  Widget _choiceMiddle(GameModel game, String? me, bool over, int unread) {
    final reveal = game.reveal;
    final choices = game.choices;
    return LayoutBuilder(
      builder: (context, box) => Stack(
        fit: StackFit.expand,
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(_side, 6, _side, 8),
            child: over
                ? Align(alignment: Alignment.topCenter, child: _FinalCard(game: game, me: me))
                : Column(
                    crossAxisAlignment: CrossAxisAlignment.stretch,
                    children: [
                      if (reveal != null)
                        _revealArea(game, box.maxHeight * _revealShareChoice)
                      else
                        _ChoiceHint(game: game),
                      const SizedBox(height: 8),
                      Expanded(
                        child: choices == null
                            ? const SizedBox.shrink()
                            : ChoiceGrid(game: game, cover: (url) => widget.controller.rounds?.cover(url)),
                      ),
                    ],
                  ),
          ),
          Positioned(
            left: _side + floatInset,
            right: _side + _chatButton + 12,
            bottom: 12,
            child: Align(
              alignment: Alignment.bottomLeft,
              child: FloatingChat(lines: game.log, me: me, gone: _gone, onGone: _floatGone),
            ),
          ),
          Positioned(
            right: _side,
            bottom: 12,
            child: _ChatButton(unread: unread, onTap: _openChat),
          ),
        ],
      ),
    );
  }

  // The reveal: the full card (at most [maxCard] tall, scrolling inside)
  // until it folds, then the strip; 자세히 opens the card again.
  Widget _revealArea(GameModel game, double maxCard) {
    final reveal = game.reveal!;
    final language = widget.controller.language;
    if (_folded && !_detailed) {
      return _RevealStrip(
        reveal: reveal,
        cover: game.revealCover,
        language: language,
        onMore: () => setState(() => _detailed = true),
      );
    }
    return ConstrainedBox(
      constraints: BoxConstraints(maxHeight: maxCard),
      child: SingleChildScrollView(
        key: const ValueKey('reveal-scroll'),
        child: RevealCard(
          reveal: reveal,
          cover: game.revealCover,
          last: game.lastSong,
          language: language,
          onLess: _foldNow,
        ),
      ),
    );
  }

  // This song's solvers: every ranked name, the first-come winner, and the
  // reveal's.
  static Set<String> _solvers(GameModel game) => {
        ...game.correctNames,
        ?game.correct,
        if (game.view != GameView.over) ...game.solvers,
      };
}

/// A player's line of the scores: the name, the points, connected or not.
typedef ScoreLine = ({String name, int score, bool connected});

/// The scores of the game (D-145), highest first, the roster's order on a
/// tie: the final ranking after the game; else every player of the roster
/// with the last `scores` row's points (else the roster's).
List<ScoreLine> scoreList(List<Player> players, GameModel game) {
  final connected = {for (final p in players) p.name: p.connected};
  final ranking = game.ranking;
  final List<ScoreLine> lines;
  if (game.view == GameView.over && ranking != null) {
    lines = [for (final r in ranking) (name: r.name, score: r.score, connected: connected[r.name] ?? false)];
  } else {
    final board = {for (final row in game.scoreboard ?? const <ScoreRow>[]) row.name: row.score};
    lines = [for (final p in players) (name: p.name, score: board[p.name] ?? p.score, connected: p.connected)];
  }
  final order = [for (var i = 0; i < lines.length; i++) i]
    ..sort((a, b) {
      final by = lines[b].score.compareTo(lines[a].score);
      return by != 0 ? by : a.compareTo(b);
    });
  return [for (final i in order) lines[i]];
}

/// The rank of [score] among [scores]: one more than the players ahead of
/// it, so a tie shares its rank.
int rankIn(List<ScoreLine> scores, int score) => 1 + scores.where((s) => s.score > score).length;

/// The top bar (D-145). First line: the small disc (spinning while the
/// song is audible), song n / N, the time bar and the seconds left. Second
/// line: 내 순위 ▾ ([standing]; [onRank] opens
/// the scores), a choice song's pick count, 스킵 (or 스킵 취소) with the
/// vote count, and [trailing] (나가기, none at game over).
class _TopBar extends StatelessWidget {
  const _TopBar({
    required this.game,
    required this.standing,
    required this.rankFocus,
    required this.onRank,
    this.trailing,
  });

  final GameModel game;
  final ({int rank, int points})? standing;
  final FocusNode rankFocus;
  final VoidCallback onRank;
  final Widget? trailing;

  @override
  Widget build(BuildContext context) {
    final t = AppLocalizations.of(context);
    final round = game.round;
    final timer = game.timer;
    final seconds = timer == null ? null : (timer.remainingMs / 1000).ceil();
    final count = game.skipCount;
    final picks = game.pickCount;
    final own = standing;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Container(
          decoration: const BoxDecoration(border: Border(bottom: BorderSide(color: Palette.line))),
          padding: const EdgeInsets.fromLTRB(_side + 2, 10, _side + 2, 8),
          child: SizedBox(
            height: 32,
            child: Row(
              children: [
                Container(
                  padding: const EdgeInsets.all(1.5),
                  decoration: const BoxDecoration(color: Palette.a1, shape: BoxShape.circle),
                  child: Disc(size: 27, spinning: game.audible),
                ),
                const SizedBox(width: 10),
                if (round != null) ...[
                  Text.rich(
                    _roundSpans(t.stageRound(round.index + 1, round.total)),
                    key: const ValueKey('round'),
                    maxLines: 1,
                  ),
                  const SizedBox(width: 10),
                ],
                Expanded(child: _TimeBar(timer: timer)),
                const SizedBox(width: 10),
                Text(
                  seconds == null ? '' : t.stageSeconds(seconds),
                  key: const ValueKey('clock'),
                  style: Fonts.displayStyle(size: 18, height: 1, color: seconds != null && seconds <= _lowSeconds ? Palette.a3 : Palette.ink),
                ),
              ],
            ),
          ),
        ),
        Padding(
          padding: const EdgeInsets.fromLTRB(_side, 8, _side, 4),
          child: Row(
            children: [
              Expanded(
                child: Align(
                  alignment: Alignment.centerLeft,
                  child: FittedBox(
                    fit: BoxFit.scaleDown,
                    child: Row(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        if (own != null)
                          RoomButton(
                            key: const ValueKey('my-rank'),
                            label: '${t.myRank(own.rank, own.points)} ▾',
                            compact: true,
                            focusNode: rankFocus,
                            onPressed: onRank,
                          ),
                        if (game.pickShown && picks != null) ...[
                          const SizedBox(width: 8),
                          Text(
                            t.stagePickCount(picks.picked, picks.total),
                            key: const ValueKey('pick-count'),
                            style: Fonts.bodyStyle(size: 13, color: Palette.dim),
                          ),
                        ],
                      ],
                    ),
                  ),
                ),
              ),
              if (game.canSkip) ...[
                const SizedBox(width: 6),
                RoomButton(
                  key: const ValueKey('skip'),
                  label: game.skipVoted ? t.stageSkipCancel : t.stageSkip,
                  kind: game.skipVoted ? ButtonKind.primary : ButtonKind.plain,
                  compact: true,
                  onPressed: game.skipVote,
                ),
                if (count != null) ...[
                  const SizedBox(width: 4),
                  Text(
                    t.stageSkipCount(count.votes, count.needed),
                    key: const ValueKey('skip-count'),
                    style: Fonts.monoStyle(size: 11, letterSpacingEm: 0),
                  ),
                ],
              ],
              if (trailing != null) ...[
                const SizedBox(width: 6),
                trailing!,
              ],
            ],
          ),
        ),
      ],
    );
  }

  // stage.round with its <lead> and <num> parts styled (the Trans of
  // GameScreen.jsx).
  static TextSpan _roundSpans(String text) {
    final spans = <InlineSpan>[];
    var at = 0;
    for (final m in _roundTag.allMatches(text)) {
      if (m.start > at) spans.add(TextSpan(text: text.substring(at, m.start)));
      final num = m.group(1) == 'num';
      spans.add(TextSpan(
        text: m.group(2),
        style: num ? Fonts.displayStyle(size: 18, height: 1.2) : null,
      ));
      if (!num) spans.add(const TextSpan(text: ' '));
      at = m.end;
    }
    if (at < text.length) spans.add(TextSpan(text: text.substring(at)));
    return TextSpan(style: Fonts.bodyStyle(size: 12, color: Palette.dim), children: spans);
  }
}

/// `.timebar`: the fill shrinks from the right as the song's time runs out;
/// a progress bar only while the song has a clock. [height] thin in the chat
/// sheet's line, whose fill has its own [fillKey].
class _TimeBar extends StatelessWidget {
  const _TimeBar({required this.timer, this.height = 6, this.fillKey = const ValueKey('timebar-fill')});

  final GameTimer? timer;
  final double height;
  final Key fillKey;

  @override
  Widget build(BuildContext context) {
    final t = AppLocalizations.of(context);
    final timer = this.timer;
    final ratio = timer == null || timer.maxMs <= 0 ? 0.0 : (timer.remainingMs / timer.maxMs).clamp(0.0, 1.0);
    final bar = Container(
      height: height,
      decoration: BoxDecoration(color: Palette.panel2, borderRadius: BorderRadius.circular(height / 2)),
      clipBehavior: Clip.antiAlias,
      alignment: Alignment.centerLeft,
      child: FractionallySizedBox(
        key: fillKey,
        widthFactor: timer == null ? 1 : ratio,
        heightFactor: 1,
        child: const DecoratedBox(
          decoration: BoxDecoration(gradient: LinearGradient(colors: [Palette.a1, Palette.a2])),
        ),
      ),
    );
    if (timer == null) return bar;
    return Semantics(
      label: t.stageTimeLeft,
      value: t.stageSeconds((timer.remainingMs / 1000).ceil()),
      child: bar,
    );
  }
}

/// The lines under the top bar, so they never push the form down: the
/// reconnecting line, the watching line, the song's load state, an error,
/// and the sound prompt (no tap yet, as after a reload rejoin: one tap
/// unlocks it and the song joins where it is).
class _Notices extends StatelessWidget {
  const _Notices({required this.controller, required this.game, required this.over});

  final AppController controller;
  final GameModel game;
  final bool over;

  @override
  Widget build(BuildContext context) {
    final t = AppLocalizations.of(context);
    final c = controller;
    final state = c.session!;
    final error = state.error;
    final live = !over && game.round != null && game.reveal == null;
    final lines = <Widget>[
      if (state.status == SessionStatus.reconnecting) _line(WarnText(t.appReconnecting)),
      if (!over && game.watching && (game.round != null || game.waitingNext)) _line(OptHint(t.stageWatching)),
      if (live && game.load == LoadState.loading)
        _line(Text(t.stageLoading, key: const ValueKey('load-line'), style: Fonts.bodyStyle(size: 12.5, color: Palette.dim))),
      if (live && game.load == LoadState.failed) _line(WarnText(t.stageLoadFailed)),
      if (!over && error != null) _line(WarnText(errorText(t, error))),
      if (!over && c.soundBlocked)
        Align(
          alignment: Alignment.centerLeft,
          child: RoomButton(
            key: const ValueKey('unlock-sound'),
            label: t.stageUnlockSound,
            kind: ButtonKind.primary,
            onPressed: c.unlockSound,
          ),
        ),
    ];
    if (lines.isEmpty) return const SizedBox.shrink();
    return Padding(
      padding: const EdgeInsets.fromLTRB(_side, 4, _side, 0),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          for (final (i, line) in lines.indexed) ...[
            if (i > 0) const SizedBox(height: 4),
            line,
          ],
        ],
      ),
    );
  }

  // One line of text, at most two on a narrow phone.
  static Widget _line(Widget text) => DefaultTextStyle.merge(maxLines: 2, overflow: TextOverflow.ellipsis, child: text);
}

/// The hint before the reveal (GameScreen.jsx): the winner, the wait, or
/// what to do.
String hintText(AppLocalizations t, GameModel game) {
  final playing = game.playing;
  if (game.correct != null) return t.chatCorrectLine(game.correct!);
  if (game.round == null) {
    return game.waitingNext
        ? t.stageWaitingNext
        : game.watching
            ? t.stageWatching
            : t.stageConnecting;
  }
  if (game.choices != null) return playing ? t.stageChoiceHint : t.stageChoiceSoon;
  return playing ? t.stageHint : t.stageSoon;
}

/// A choice room's hint above the covers, in one line that shrinks to fit,
/// and under it the own wrong pick.
class _ChoiceHint extends StatelessWidget {
  const _ChoiceHint({required this.game});

  final GameModel game;

  @override
  Widget build(BuildContext context) {
    final t = AppLocalizations.of(context);
    final picked = game.picked;
    final wrong = game.choices != null && game.round != null && game.pickWrong && picked != null;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Center(
          child: FittedBox(
            fit: BoxFit.scaleDown,
            child: Text(hintText(t, game), key: const ValueKey('hint'), maxLines: 1, style: Fonts.displayStyle(size: 15, height: 1.3)),
          ),
        ),
        if (wrong)
          Padding(
            padding: const EdgeInsets.only(top: 2),
            child: Text(
              t.stagePickWrong(picked + 1),
              key: const ValueKey('pick-wrong'),
              textAlign: TextAlign.center,
              maxLines: 2,
              overflow: TextOverflow.ellipsis,
              style: Fonts.bodyStyle(size: 13, color: Palette.a3, weight: FontWeight.w700),
            ),
          ),
      ],
    );
  }
}

/// A small text button (접기, 닫기); [dense] with less padding above and
/// below.
class _SmallButton extends StatelessWidget {
  const _SmallButton({super.key, required this.label, required this.onTap, this.dense = false});

  final String label;
  final VoidCallback onTap;
  final bool dense;

  @override
  Widget build(BuildContext context) => Semantics(
        button: true,
        child: InkWell(
          onTap: onTap,
          borderRadius: BorderRadius.circular(8),
          child: Padding(
            padding: EdgeInsets.symmetric(horizontal: 8, vertical: dense ? 2 : 6),
            child: Text(label, style: Fonts.bodyStyle(size: 12.5, color: Palette.dim)),
          ),
        ),
      );
}

/// The folded reveal (D-145): the small cover, the winner, the title in the
/// screen's language and OP1 · song — artists, with 자세히 ▾ when [onMore]
/// opens the full card. Shown only after the reveal.
class _RevealStrip extends StatelessWidget {
  const _RevealStrip({required this.reveal, required this.cover, required this.language, this.onMore});

  final RevealMessage reveal;
  final Uint8List? cover;
  final String language;
  final VoidCallback? onMore;

  @override
  Widget build(BuildContext context) {
    final t = AppLocalizations.of(context);
    final who = revealWho(t, reveal);
    final title = revealTitle(reveal, language);
    final more = onMore;
    return Material(
      type: MaterialType.transparency,
      child: InkWell(
        key: const ValueKey('reveal-strip'),
        onTap: more,
        borderRadius: BorderRadius.circular(14),
        child: Ink(
          padding: const EdgeInsets.fromLTRB(10, 8, 10, 8),
          decoration: BoxDecoration(
            color: Palette.panel,
            border: Border.all(color: who == null ? Palette.line : Palette.ok),
            borderRadius: BorderRadius.circular(14),
          ),
          child: Row(
            children: [
              _RevealCover(cover: cover, title: title, missed: who == null, width: 40, stamp: false),
              const SizedBox(width: 10),
              Expanded(
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      who ?? t.revealNoWinner,
                      key: const ValueKey('strip-who'),
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: Fonts.bodyStyle(size: 12, color: who == null ? Palette.dim : Palette.ok),
                    ),
                    Text(
                      title,
                      key: const ValueKey('strip-title'),
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: Fonts.displayStyle(size: 17, color: Palette.a2, height: 1.25),
                    ),
                    Text.rich(
                      themeSpan(reveal, 12),
                      key: const ValueKey('strip-theme'),
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                    ),
                  ],
                ),
              ),
              if (more != null) ...[
                const SizedBox(width: 8),
                Text('${t.revealMore} ▾', key: const ValueKey('reveal-more'), style: Fonts.bodyStyle(size: 12, color: Palette.dim)),
              ],
            ],
          ),
        ),
      ),
    );
  }
}

/// A choice room's round 채팅 button (D-145) with the count of lines since
/// the chat sheet was last closed; it opens the sheet (D-146).
class _ChatButton extends StatelessWidget {
  const _ChatButton({required this.unread, required this.onTap});

  final int unread;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final t = AppLocalizations.of(context);
    final style = Fonts.displayStyle(size: 13, color: Palette.bg, height: 1.15);
    return Semantics(
      button: true,
      label: unread > 0 ? '${t.chatLabel} $unread' : t.chatLabel,
      excludeSemantics: true,
      child: Material(
        color: Palette.a1,
        shape: const CircleBorder(),
        elevation: 6,
        shadowColor: const Color(0x66000000),
        child: InkWell(
          key: const ValueKey('chat-open'),
          customBorder: const CircleBorder(),
          onTap: onTap,
          child: SizedBox.square(
            dimension: _chatButton,
            child: Padding(
              padding: const EdgeInsets.all(6),
              child: FittedBox(
                fit: BoxFit.scaleDown,
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Text(t.chatLabel, style: style),
                    if (unread > 0) Text('$unread', key: const ValueKey('chat-unread'), style: style),
                  ],
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }
}

/// The chat sheet's line for a reveal that came while it was open (D-146):
/// ✔ and the solvers (every ranked one, or the first-come winner) with 정답!,
/// else 정답 공개; then that closing the sheet shows the answer card.
String sheetRevealText(AppLocalizations t, RevealMessage reveal) {
  final ranking = reveal.ranking;
  final names = ranking != null ? [for (final row in ranking) row.name] : [?reveal.winner];
  return names.isEmpty ? t.chatSheetReveal : '$_check ${t.chatSheetRevealSolved(names.join(', '))}';
}

/// A choice room's chat sheet (D-146, V3ChatSheet) over the whole game
/// screen, sliding up from the bottom with [slide]: a dimmed strip of the
/// screen above it (a tap closes it), the handle, 채팅 and 닫기 ▾ (a swipe
/// down on the strip, the handle or the header moves it: [onDrag], then
/// [onDragEnd] with the speed), the thin status line, the whole log with
/// [revealLine] after its first `at` lines, and the input above the
/// keyboard ([onSend]; none after the game). Escape closes it. It covers
/// the screen, so nothing under it takes a tap; while it slides down
/// ([open] false) it takes no tap and nothing in it keeps the focus.
class _ChatSheet extends StatelessWidget {
  const _ChatSheet({
    required this.game,
    required this.me,
    required this.slide,
    required this.open,
    required this.onSend,
    required this.revealLine,
    required this.onClose,
    required this.onDrag,
    required this.onDragEnd,
  });

  final GameModel game;
  final String? me;
  final Animation<double> slide;
  final bool open;
  final ValueChanged<String>? onSend;
  final ({int at, String text, bool solved})? revealLine;
  final VoidCallback onClose;
  final void Function(double delta, double height) onDrag;
  final ValueChanged<double> onDragEnd;

  @override
  Widget build(BuildContext context) {
    final t = AppLocalizations.of(context);
    final media = MediaQuery.of(context);
    final keyboard = media.viewInsets.bottom;
    final top = media.padding.top + _sheetGap;
    final send = onSend;
    final note = revealLine;
    return LayoutBuilder(
      builder: (context, box) {
        final height = math.max(0.0, box.maxHeight - top);
        Widget dragArea({required Widget child, VoidCallback? onTap}) => GestureDetector(
              behavior: HitTestBehavior.opaque,
              onTap: onTap,
              onVerticalDragUpdate: (d) => onDrag(d.primaryDelta ?? 0, height),
              onVerticalDragEnd: (d) => onDragEnd(d.primaryVelocity ?? 0),
              child: child,
            );
        final sheet = Material(
          key: const ValueKey('chat-sheet'),
          color: Palette.panel,
          shape: const RoundedRectangleBorder(
            borderRadius: BorderRadius.vertical(top: Radius.circular(22)),
            side: BorderSide(color: Palette.line),
          ),
          clipBehavior: Clip.antiAlias,
          child: Semantics(
            scopesRoute: true,
            namesRoute: true,
            explicitChildNodes: true,
            label: t.chatLabel,
            child: Padding(
              padding: EdgeInsets.only(bottom: keyboard > 0 ? keyboard : media.padding.bottom),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  dragArea(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.stretch,
                      children: [
                        Padding(
                          padding: const EdgeInsets.only(top: 8),
                          child: Center(
                            child: Container(
                              key: const ValueKey('chat-sheet-handle'),
                              width: 40,
                              height: 4,
                              decoration: BoxDecoration(color: Palette.line, borderRadius: BorderRadius.circular(2)),
                            ),
                          ),
                        ),
                        Padding(
                          padding: const EdgeInsets.fromLTRB(16, 8, 12, 8),
                          child: Row(
                            children: [
                              Expanded(
                                child: Semantics(
                                  header: true,
                                  child: Text(t.chatLabel, key: const ValueKey('chat-sheet-title'), style: Fonts.displayStyle(size: 20)),
                                ),
                              ),
                              RoomButton(
                                key: const ValueKey('chat-close'),
                                label: '${t.offsetClose} ▾',
                                compact: true,
                                onPressed: onClose,
                              ),
                            ],
                          ),
                        ),
                      ],
                    ),
                  ),
                  _SheetStatus(game: game),
                  Expanded(
                    child: ChatLog(
                      lines: game.log,
                      me: me,
                      padding: const EdgeInsets.fromLTRB(16, 12, 16, 12),
                      extra: note == null
                          ? null
                          : (
                              at: note.at,
                              child: KeyedSubtree(
                                key: const ValueKey('sheet-reveal'),
                                child: noteLine(note.text, solved: note.solved),
                              ),
                            ),
                    ),
                  ),
                  if (send != null)
                    Container(
                      padding: const EdgeInsets.fromLTRB(16, 10, 16, 12),
                      decoration: const BoxDecoration(border: Border(top: BorderSide(color: Palette.line))),
                      child: ChatForm(
                        key: const ValueKey('chat-form'),
                        onSend: send,
                        hint: t.chatTalkPlaceholder,
                        autofocus: true,
                      ),
                    ),
                ],
              ),
            ),
          ),
        );
        return CallbackShortcuts(
          bindings: {const SingleActivator(LogicalKeyboardKey.escape): onClose},
          child: ExcludeFocus(
            excluding: !open,
            child: IgnorePointer(
              ignoring: !open,
              child: BlockSemantics(
                child: Stack(
                  children: [
                    Positioned.fill(
                      child: FadeTransition(
                        opacity: slide,
                        child: dragArea(
                          onTap: onClose,
                          child: const ColoredBox(key: ValueKey('chat-sheet-dim'), color: Color(0x99000000)),
                        ),
                      ),
                    ),
                    Positioned(
                      left: 0,
                      right: 0,
                      top: top,
                      bottom: 0,
                      child: SlideTransition(
                        position: Tween(begin: const Offset(0, 1), end: Offset.zero).animate(slide),
                        child: Center(
                          child: SizedBox(width: math.min(box.maxWidth, contentMaxWidth), height: height, child: sheet),
                        ),
                      ),
                    ),
                  ],
                ),
              ),
            ),
          ),
        );
      },
    );
  }
}

/// The chat sheet's thin status line (V3ChatSheet): the small disc
/// (spinning while the song is audible), song n / N, a thin time bar, the
/// seconds left and · the pick count.
class _SheetStatus extends StatelessWidget {
  const _SheetStatus({required this.game});

  final GameModel game;

  @override
  Widget build(BuildContext context) {
    final t = AppLocalizations.of(context);
    final round = game.round;
    final timer = game.timer;
    final seconds = timer == null ? null : (timer.remainingMs / 1000).ceil();
    final picks = game.pickCount;
    final style = Fonts.bodyStyle(size: 12, color: Palette.dim);
    return Container(
      key: const ValueKey('sheet-status'),
      padding: const EdgeInsets.fromLTRB(16, 0, 16, 10),
      decoration: const BoxDecoration(border: Border(bottom: BorderSide(color: Palette.line))),
      child: SizedBox(
        height: 24,
        child: Row(
          children: [
            Container(
              key: const ValueKey('sheet-disc'),
              padding: const EdgeInsets.all(1.5),
              decoration: const BoxDecoration(color: Palette.a1, shape: BoxShape.circle),
              child: Disc(size: 19, spinning: game.audible),
            ),
            const SizedBox(width: 10),
            if (round != null) ...[
              Text('${round.index + 1} / ${round.total}', key: const ValueKey('sheet-round'), style: style),
              const SizedBox(width: 10),
            ],
            Expanded(child: _TimeBar(timer: timer, height: 4, fillKey: const ValueKey('sheet-timebar-fill'))),
            const SizedBox(width: 10),
            Text(
              seconds == null ? '' : t.stageSeconds(seconds),
              key: const ValueKey('sheet-clock'),
              style: seconds != null && seconds <= _lowSeconds ? style.copyWith(color: Palette.a3) : style,
            ),
            if (game.pickShown && picks != null) ...[
              const SizedBox(width: 6),
              Text('· ${t.stagePickCount(picks.picked, picks.total)}', key: const ValueKey('sheet-picks'), style: style),
            ],
          ],
        ),
      ),
    );
  }
}

/// The scores over the screen (D-145, from 내 순위 ▾): every player highest
/// first with the place (a tie shares it), the name with (나), this song's
/// solvers marked 정답!, and the points; 닫기 (also Escape or a tap
/// outside) closes it.
class _ScoresSheet extends StatelessWidget {
  const _ScoresSheet({required this.scores, required this.me, required this.solvers, required this.onClose});

  final List<ScoreLine> scores;
  final String? me;
  final Set<String> solvers;
  final VoidCallback onClose;

  @override
  Widget build(BuildContext context) {
    final t = AppLocalizations.of(context);
    return CallbackShortcuts(
      bindings: {const SingleActivator(LogicalKeyboardKey.escape): onClose},
      child: FocusScope(
        autofocus: true,
        child: Stack(
          children: [
            ModalBarrier(
              key: const ValueKey('scores-barrier'),
              color: const Color(0x99000000),
              onDismiss: onClose,
              semanticsLabel: t.offsetClose,
            ),
            SafeArea(
              child: Center(
                child: Padding(
                  padding: const EdgeInsets.all(16),
                  child: ConstrainedBox(
                    constraints: const BoxConstraints(maxWidth: 384),
                    child: Material(
                      key: const ValueKey('scores'),
                      color: Palette.panel,
                      shape: RoundedRectangleBorder(
                        borderRadius: BorderRadius.circular(Palette.radius),
                        side: const BorderSide(color: Palette.line),
                      ),
                      child: Semantics(
                        scopesRoute: true,
                        namesRoute: true,
                        explicitChildNodes: true,
                        label: t.scoreBoard,
                        child: Padding(
                          padding: const EdgeInsets.fromLTRB(18, 16, 18, 14),
                          child: Column(
                            mainAxisSize: MainAxisSize.min,
                            crossAxisAlignment: CrossAxisAlignment.stretch,
                            children: [
                              Semantics(header: true, child: Text(t.scoreBoard, style: Fonts.displayStyle(size: 19))),
                              const SizedBox(height: 10),
                              Flexible(
                                child: ListView(
                                  key: const ValueKey('scores-list'),
                                  shrinkWrap: true,
                                  padding: EdgeInsets.zero,
                                  children: [
                                    for (final s in scores)
                                      Opacity(
                                        opacity: s.connected ? 1 : 0.5,
                                        child: _RankRow(
                                          key: ValueKey('score-${s.name}'),
                                          place: t.scoreRank(rankIn(scores, s.score)),
                                          name: s.name == me ? '${s.name} ${t.seatMe}' : s.name,
                                          points: t.scorePoints(s.score),
                                          mine: solvers.contains(s.name) ? ' ${t.seatCorrect}' : '',
                                        ),
                                      ),
                                  ],
                                ),
                              ),
                              const SizedBox(height: 8),
                              Align(
                                alignment: Alignment.centerRight,
                                child: RoomButton(
                                  key: const ValueKey('scores-close'),
                                  label: t.offsetClose,
                                  autofocus: true,
                                  onPressed: onClose,
                                ),
                              ),
                            ],
                          ),
                        ),
                      ),
                    ),
                  ),
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

/// The deck's disc in small (Deck.jsx `.disc` and `.mark`, drawPlayer's
/// sunset LP): a black record with faint grooves, a sheen and an orange
/// label, and a ? over it (from 40 px up); one turn in 7 s while
/// [spinning]. The game's top bar shows it at 30 px (D-145).
class Disc extends StatefulWidget {
  const Disc({super.key, required this.size, required this.spinning});

  final double size;
  final bool spinning;

  @override
  State<Disc> createState() => _DiscState();
}

class _DiscState extends State<Disc> with SingleTickerProviderStateMixin {
  late final AnimationController _turn = AnimationController(vsync: this, duration: const Duration(seconds: 7));

  @override
  void initState() {
    super.initState();
    if (widget.spinning) _turn.repeat();
  }

  @override
  void didUpdateWidget(Disc old) {
    super.didUpdateWidget(old);
    if (widget.spinning && !_turn.isAnimating) {
      _turn.repeat();
    } else if (!widget.spinning && _turn.isAnimating) {
      _turn.stop();
    }
  }

  @override
  void dispose() {
    _turn.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final size = widget.size;
    return ExcludeSemantics(
      child: SizedBox(
        key: const ValueKey('disc'),
        width: size,
        height: size,
        child: Stack(
          alignment: Alignment.center,
          children: [
            DecoratedBox(
              decoration: BoxDecoration(
                shape: BoxShape.circle,
                boxShadow: [
                  BoxShadow(color: const Color(0x8C000000), blurRadius: size * 0.21, offset: Offset(0, size * 0.07)),
                ],
              ),
              child: RotationTransition(
                turns: _turn,
                child: CustomPaint(size: Size.square(size), painter: const _DiscPainter()),
              ),
            ),
            if (size >= 40) Text('?', style: Fonts.displayStyle(size: size * 0.28, color: Palette.bg, height: 1)),
          ],
        ),
      ),
    );
  }
}

/// drawPlayer's sunset disc, scaled from its 520 px canvas.
class _DiscPainter extends CustomPainter {
  const _DiscPainter();

  @override
  void paint(Canvas canvas, Size size) {
    final w = size.width;
    final c = Offset(w / 2, size.height / 2);
    canvas.drawCircle(c, w / 2, Paint()..color = const Color(0xFF121012));
    final groove = Paint()
      ..style = PaintingStyle.stroke
      ..strokeWidth = math.max(0.5, w * 1.5 / 520)
      ..color = const Color(0x0FFFFFFF);
    for (var r = w * 0.2; r < w * 0.49; r += w * 0.012) {
      canvas.drawCircle(c, r, groove);
    }
    canvas.drawArc(
      Rect.fromCircle(center: c, radius: w * 0.38),
      -0.9,
      0.5,
      false,
      Paint()
        ..style = PaintingStyle.stroke
        ..strokeWidth = w * 0.03
        ..color = const Color(0x2EFFDCB4),
    );
    final label = Rect.fromCircle(center: c, radius: w * 0.18);
    canvas.drawCircle(
      c,
      w * 0.18,
      Paint()..shader = const RadialGradient(colors: [Palette.a2, Palette.a1]).createShader(label),
    );
    canvas.drawCircle(c, w * 0.015, Paint()..color = const Color(0xFF121012));
  }

  @override
  bool shouldRepaint(_DiscPainter old) => false;
}

/// The first accepted title of [lang], or null (GameScreen.jsx firstTitle).
String? _firstTitle(Titles titles, String lang) {
  final list = switch (lang) {
    'ko' => titles.ko,
    'en' => titles.en,
    'ja' => titles.ja,
    _ => titles.romaji,
  };
  if (list.isEmpty || list.first.trim().isEmpty) return null;
  return list.first;
}

/// A language's title line: the KO line is the frame's reveal text when
/// that is Korean (GameScreen.jsx lineTitle).
String? lineTitle(RevealMessage reveal, String lang) {
  final own = reveal.reveal;
  if (lang == 'ko' && own != null && _hangul.hasMatch(own)) return own;
  return _firstTitle(reveal.titles, lang);
}

/// The reveal's winner line: every ranked solver, or the first-come
/// winner; null when nobody solved it.
String? revealWho(AppLocalizations t, RevealMessage reveal) {
  final ranking = reveal.ranking;
  if (ranking != null) {
    return ranking.isEmpty ? null : t.revealSolvers([for (final row in ranking) row.name].join(', '));
  }
  final winner = reveal.winner;
  return winner == null ? null : t.revealWinner(winner);
}

/// The reveal's title in the screen's [language] (else the Korean line,
/// else the frame's own text).
String revealTitle(RevealMessage reveal, String language) =>
    lineTitle(reveal, language) ?? lineTitle(reveal, 'ko') ?? reveal.reveal ?? '';

/// `OP1 · song — artists` (GameScreen.jsx ThemeLine) at [size].
TextSpan themeSpan(RevealMessage reveal, double size) {
  final song = reveal.song;
  final artists = reveal.artists;
  return TextSpan(
    style: Fonts.bodyStyle(size: size, color: Palette.dim),
    children: [
      TextSpan(text: reveal.themeSlug),
      if (song != null && song.isNotEmpty) ...[
        const TextSpan(text: ' · '),
        TextSpan(text: song, style: Fonts.bodyStyle(size: size, color: Palette.ink, weight: FontWeight.w500)),
      ],
      if (artists.isNotEmpty) TextSpan(text: ' $_dash ${artists.join(', ')}'),
    ],
  );
}

/// The reveal card (GameScreen.jsx RevealCard with the deck's cover), in
/// its compact phone form (D-142): the cover thumbnail with its stamp
/// (정답, or 통과 when nobody solved it) beside 정답 공개, the winner (or
/// every ranked solver, or 정답자 없음), the title in the screen's language
/// and the other titles in small text; a ranked song's ranking with its
/// points; the slug · song — artists line; and what comes next. 접기 beside
/// 정답 공개 when [onLess] is given (it folds the card into its strip,
/// D-145).
class RevealCard extends StatelessWidget {
  const RevealCard({
    super.key,
    required this.reveal,
    required this.cover,
    required this.last,
    required this.language,
    this.onLess,
  });

  final RevealMessage reveal;
  final Uint8List? cover;
  final bool last;
  final String language;
  final VoidCallback? onLess;

  @override
  Widget build(BuildContext context) {
    final t = AppLocalizations.of(context);
    final found = [
      for (final lang in _titleLangs)
        if (lineTitle(reveal, lang) case final title?) (lang: lang, title: title),
    ];
    // The screen's language first, the others in card order.
    final lines = [
      ...found.where((l) => l.lang == language),
      ...found.where((l) => l.lang != language),
    ];
    final ranking = reveal.ranking;
    final who = revealWho(t, reveal);
    final missed = who == null;
    final coverTitle = revealTitle(reveal, language);
    final less = onLess;
    return Container(
      key: const ValueKey('reveal'),
      padding: const EdgeInsets.fromLTRB(12, 10, 12, 10),
      decoration: BoxDecoration(
        color: Palette.panel,
        border: Border.all(color: Palette.line),
        borderRadius: BorderRadius.circular(Palette.radius),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              _RevealCover(cover: cover, title: coverTitle, missed: missed),
              const SizedBox(width: 12),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    Row(
                      children: [
                        Expanded(
                          child: Text(
                            t.revealHeading.toUpperCase(),
                            style: Fonts.monoStyle(size: 10.5, semiBold: true, letterSpacingEm: 0.16),
                          ),
                        ),
                        if (less != null)
                          _SmallButton(
                            key: const ValueKey('reveal-less'),
                            label: '${t.revealLess} ▴',
                            onTap: less,
                            dense: true,
                          ),
                      ],
                    ),
                    Text(
                      who ?? t.revealNoWinner,
                      key: const ValueKey('reveal-who'),
                      maxLines: 2,
                      overflow: TextOverflow.ellipsis,
                      style: Fonts.displayStyle(size: 18, height: 1.25, color: who == null ? Palette.dim : Palette.ink),
                    ),
                    const SizedBox(height: 4),
                    if (lines.isEmpty)
                      Text(reveal.reveal ?? '', style: _mainStyle)
                    else
                      for (final (i, line) in lines.indexed)
                        _TitleLine(label: _label(t, line.lang), lang: line.lang, title: line.title, main: i == 0),
                  ],
                ),
              ),
            ],
          ),
          if (ranking != null && ranking.isNotEmpty) ...[
            const SizedBox(height: 8),
            Semantics(
              label: t.revealRanking,
              container: true,
              child: Wrap(
                spacing: 6,
                runSpacing: 6,
                children: [
                  for (final row in ranking)
                    _RankChip(
                      key: ValueKey('song-rank-${row.name}'),
                      place: t.revealRankPlace(row.rank),
                      name: row.name,
                      points: t.revealRankPoints(row.points),
                    ),
                ],
              ),
            ),
          ],
          const SizedBox(height: 8),
          Container(
            width: double.infinity,
            padding: const EdgeInsets.only(top: 6),
            decoration: const BoxDecoration(border: Border(top: BorderSide(color: Palette.line))),
            child: Text.rich(
              key: const ValueKey('theme-line'),
              maxLines: 2,
              overflow: TextOverflow.ellipsis,
              themeSpan(reveal, 12.5),
            ),
          ),
          Text(last ? t.revealNextFinal : t.revealNext, style: Fonts.monoStyle(size: 10.5, letterSpacingEm: 0)),
        ],
      ),
    );
  }

  static final TextStyle _mainStyle = Fonts.bodyStyle(size: 16, color: Palette.a2, weight: FontWeight.w700);

  static String _label(AppLocalizations t, String lang) => switch (lang) {
        'ko' => t.revealLabelKo,
        'en' => t.revealLabelEn,
        'ja' => t.revealLabelJa,
        _ => t.revealLabelRomaji,
      };
}

/// A title line of the reveal card: its language label, then the title,
/// large for the screen's language ([main]) and small for the others.
class _TitleLine extends StatelessWidget {
  const _TitleLine({required this.label, required this.lang, required this.title, required this.main});

  final String label;
  final String lang;
  final String title;
  final bool main;

  @override
  Widget build(BuildContext context) => Padding(
        padding: EdgeInsets.only(bottom: main ? 2 : 0),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            SizedBox(
              width: 46,
              child: Padding(
                padding: EdgeInsets.only(top: main ? 4 : 2),
                child: Text(label, style: Fonts.monoStyle(size: 10, letterSpacingEm: 0)),
              ),
            ),
            Expanded(
              child: Text(
                title,
                key: ValueKey('title-$lang'),
                maxLines: main ? 2 : 1,
                overflow: TextOverflow.ellipsis,
                style: main ? RevealCard._mainStyle : Fonts.bodyStyle(size: 12.5, color: Palette.dim).copyWith(height: 1.35),
              ),
            ),
          ],
        ),
      );
}

/// The deck's cover as a thumbnail: the reveal's cover (or an empty
/// frame) and its stamp.
class _RevealCover extends StatelessWidget {
  const _RevealCover({required this.cover, required this.title, required this.missed, this.width = 66, this.stamp = true});

  final Uint8List? cover;
  final String title;
  final bool missed;
  final double width;

  /// The 정답 / 통과 stamp (not on the strip's small cover).
  final bool stamp;

  @override
  Widget build(BuildContext context) {
    final t = AppLocalizations.of(context);
    final bytes = cover;
    return SizedBox(
      width: width,
      child: AspectRatio(
        aspectRatio: 3 / 4,
        child: Stack(
          fit: StackFit.expand,
          clipBehavior: Clip.none,
          children: [
            ClipRRect(
              borderRadius: BorderRadius.circular(6),
              child: ColoredBox(
                color: Palette.panel2,
                child: bytes == null
                    ? null
                    : Image.memory(
                        bytes,
                        key: ValueKey(stamp ? 'reveal-cover' : 'strip-cover'),
                        fit: BoxFit.cover,
                        semanticLabel: t.revealCoverAlt(title),
                        gaplessPlayback: true,
                        errorBuilder: (_, _, _) => const SizedBox.shrink(),
                      ),
              ),
            ),
            if (stamp)
              Positioned(
              right: -6,
              bottom: 4,
              child: Transform.rotate(
                angle: -0.2,
                child: Container(
                  key: const ValueKey('reveal-stamp'),
                  padding: const EdgeInsets.symmetric(horizontal: 5, vertical: 1),
                  decoration: BoxDecoration(
                    color: Palette.bg.withValues(alpha: 0.85),
                    border: Border.all(color: missed ? Palette.dim : Palette.a3, width: 1.5),
                    borderRadius: BorderRadius.circular(5),
                  ),
                  child: Text(
                    missed ? t.revealStampPass : t.revealStampHit,
                    style: Fonts.displayStyle(size: 11.5, color: missed ? Palette.dim : Palette.a3, height: 1.2),
                  ),
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

/// A ranked song's row on the reveal card in short: the place, the name,
/// the points.
class _RankChip extends StatelessWidget {
  const _RankChip({super.key, required this.place, required this.name, required this.points});

  final String place;
  final String name;
  final String points;

  @override
  Widget build(BuildContext context) => Container(
        padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
        decoration: BoxDecoration(color: Palette.panel2, borderRadius: BorderRadius.circular(Palette.radius / 2)),
        child: Semantics(
          label: '$place $name $points',
          excludeSemantics: true,
          child: Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              Text(place, style: Fonts.displayStyle(size: 13, color: Palette.a2)),
              const SizedBox(width: 5),
              Flexible(
                child: Text(name, maxLines: 1, overflow: TextOverflow.ellipsis, style: Fonts.bodyStyle(size: 12.5)),
              ),
              const SizedBox(width: 5),
              Text(points, style: Fonts.bodyStyle(size: 12.5, color: Palette.ok, weight: FontWeight.w700)),
            ],
          ),
        ),
      );
}

/// `ol.rank li`: the place, the name, the points.
class _RankRow extends StatelessWidget {
  const _RankRow({super.key, required this.place, required this.name, required this.points, this.mine = ''});

  final String place;
  final String name;
  final String points;
  final String mine;

  @override
  Widget build(BuildContext context) => Container(
        margin: const EdgeInsets.only(bottom: 6),
        padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
        decoration: BoxDecoration(color: Palette.panel2, borderRadius: BorderRadius.circular(Palette.radius / 2)),
        child: Semantics(
          label: '$place $name $points$mine',
          excludeSemantics: true,
          child: Row(
            children: [
              SizedBox(width: 44, child: Text(place, style: Fonts.displayStyle(size: 16, color: Palette.a2))),
              const SizedBox(width: 8),
              Expanded(
                child: Text(name, maxLines: 1, overflow: TextOverflow.ellipsis, style: Fonts.bodyStyle(size: 14.5)),
              ),
              const SizedBox(width: 8),
              Text('$points$mine', style: Fonts.bodyStyle(size: 14, color: Palette.ok, weight: FontWeight.w700)),
            ],
          ),
        ),
      );
}

/// The final ranking (GameScreen.jsx FinalCard) in the middle: rows `N위
/// name N점`, with (나) after the own points (the rows scroll inside when
/// they do not fit), and the own final score. 대기실로 sits at the bottom
/// of the screen once the server has returned the room to the lobby
/// (D-062).
class _FinalCard extends StatelessWidget {
  const _FinalCard({required this.game, required this.me});

  final GameModel game;
  final String? me;

  @override
  Widget build(BuildContext context) {
    final t = AppLocalizations.of(context);
    final ranking = game.ranking;
    final finalScore = game.finalScore;
    return Container(
      key: const ValueKey('final'),
      padding: const EdgeInsets.fromLTRB(16, 14, 16, 12),
      decoration: BoxDecoration(
        color: Palette.panel,
        border: Border.all(color: Palette.line),
        borderRadius: BorderRadius.circular(Palette.radius),
      ),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Text(t.scoreRanking.toUpperCase(), style: Fonts.monoStyle(size: 11.5, semiBold: true, letterSpacingEm: 0.16)),
          const SizedBox(height: 10),
          if (ranking != null)
            Flexible(
              child: Semantics(
                label: t.scoreRanking,
                container: true,
                child: ListView(
                  key: const ValueKey('final-list'),
                  shrinkWrap: true,
                  padding: EdgeInsets.zero,
                  children: [
                    for (final row in ranking)
                      _RankRow(
                        key: ValueKey('final-${row.name}'),
                        place: t.scoreRank(row.rank),
                        name: row.name,
                        points: t.scorePoints(row.score),
                        mine: row.name == me ? ' ${t.seatMe}' : '',
                      ),
                  ],
                ),
              ),
            ),
          if (finalScore != null) ...[
            const SizedBox(height: 4),
            Text(t.scoreFinal(finalScore), key: const ValueKey('final-score'), style: Fonts.bodyStyle(size: 14, color: Palette.dim)),
          ],
        ],
      ),
    );
  }
}

/// The tile's mark (ChoiceGrid.jsx tileState).
enum TileMark { none, correct, wrong, dim, locked }

/// A tile's mark and whether it takes a tap: the reveal first, then the own
/// pick (wrong, or awaiting its result), then whether picking is allowed.
({TileMark mark, bool enabled}) tileState(int position, GameModel game) {
  final picked = game.picked;
  if (game.reveal != null) {
    if (position == game.correctChoice) return (mark: TileMark.correct, enabled: false);
    return (mark: position == picked ? TileMark.wrong : TileMark.dim, enabled: false);
  }
  if (picked != null) {
    if (position != picked) return (mark: TileMark.locked, enabled: false);
    return (mark: game.pickWrong ? TileMark.wrong : TileMark.none, enabled: false);
  }
  return (mark: TileMark.none, enabled: game.canPick);
}

// The gap between tiles, and the polaroid frame around a cover (5 on the
// sides and the top, 14 below).
const double _tileGap = 8;
const double _frameWidth = 10;
const double _frameHeight = 19;

/// The columns and the cover width that fit [count] tiles in [width] x
/// [height] (D-142, D-145): 2, 3 or 4 columns (no more than the tiles),
/// whichever gives the biggest covers, the fewest columns on a tie. The
/// covers are 3:4 in their polaroid frames. The width is never negative
/// (the grid scales down past that).
({int columns, double coverWidth}) tileLayout(int count, double width, double height) {
  final options = _columnOptions(count);
  var best = (columns: options.first, coverWidth: -1.0);
  for (final cols in options) {
    final rows = (count / cols).ceil();
    final byWidth = (width - _tileGap * (cols - 1)) / cols - _frameWidth;
    final byHeight = ((height - _tileGap * (rows - 1)) / rows - _frameHeight) * 3 / 4;
    final cover = math.min(byWidth, byHeight);
    if (cover > best.coverWidth) best = (columns: cols, coverWidth: cover);
  }
  return (columns: best.columns, coverWidth: math.max(0, best.coverWidth).floorToDouble());
}

// The column counts [tileLayout] picks from for [count] tiles.
Set<int> _columnOptions(int count) => {
      math.min(2, math.max(1, count)),
      for (final columns in const [3, 4])
        if (columns <= count) columns,
    };

/// A choice room's cover tiles (ChoiceGrid.jsx, D-111) filling the space
/// they are given (D-142, D-145, [tileLayout]); each tile its cover (from the round controller's bytes; an
/// empty frame until loaded), its number, and after the reveal or the own
/// pick its translated mark (cycle 138). Nothing marks the correct tile
/// before the reveal (A9).
class ChoiceGrid extends StatelessWidget {
  const ChoiceGrid({super.key, required this.game, required this.cover});

  final GameModel game;
  final Uint8List? Function(String url) cover;

  @override
  Widget build(BuildContext context) {
    final choices = game.choices!;
    return LayoutBuilder(
      builder: (context, box) {
        final layout = tileLayout(choices.length, box.maxWidth, box.maxHeight);
        final cols = layout.columns;
        final tileWidth = layout.coverWidth + _frameWidth;
        final tileHeight = (layout.coverWidth * 4 / 3).floorToDouble() + _frameHeight;
        final rows = <Widget>[];
        for (var start = 0; start < choices.length; start += cols) {
          if (start > 0) rows.add(const SizedBox(height: _tileGap));
          rows.add(Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              for (var i = start; i < start + cols && i < choices.length; i++) ...[
                if (i > start) const SizedBox(width: _tileGap),
                SizedBox(
                  width: tileWidth,
                  height: tileHeight,
                  child: _Tile(position: i, url: choices[i], game: game, cover: cover),
                ),
              ],
            ],
          ));
        }
        return Container(
          key: const ValueKey('choices'),
          alignment: Alignment.center,
          child: FittedBox(
            fit: BoxFit.scaleDown,
            child: Column(mainAxisSize: MainAxisSize.min, children: rows),
          ),
        );
      },
    );
  }
}

class _Tile extends StatelessWidget {
  const _Tile({required this.position, required this.url, required this.game, required this.cover});

  final int position;
  final String? url;
  final GameModel game;
  final Uint8List? Function(String url) cover;

  @override
  Widget build(BuildContext context) {
    final t = AppLocalizations.of(context);
    final state = tileState(position, game);
    final mark = state.mark;
    final link = url;
    final bytes = link == null ? null : cover(link);
    final markText = switch (mark) {
      TileMark.correct => t.answerMarkCorrect,
      TileMark.wrong => t.answerMarkWrong,
      _ => '',
    };
    final opacity = switch (mark) {
      TileMark.dim => 0.35,
      TileMark.locked => 0.45,
      _ => 1.0,
    };
    final frame = Container(
      decoration: BoxDecoration(
        color: Palette.panel2,
        borderRadius: BorderRadius.circular(4),
        boxShadow: mark == TileMark.correct ? const [BoxShadow(color: Palette.ok, spreadRadius: 3)] : null,
      ),
      clipBehavior: Clip.antiAlias,
      child: Stack(
        fit: StackFit.expand,
        children: [
          if (bytes != null)
            mark == TileMark.wrong
                ? ColorFiltered(colorFilter: const ColorFilter.matrix(_greyDark), child: _cover(bytes))
                : _cover(bytes),
          Positioned(
            left: 4,
            top: 4,
            child: Container(
              padding: const EdgeInsets.symmetric(horizontal: 6),
              decoration: BoxDecoration(color: Palette.bg.withValues(alpha: 0.8), borderRadius: BorderRadius.circular(8)),
              child: Text('${position + 1}', style: Fonts.monoStyle(size: 11, color: Palette.ink, semiBold: true, letterSpacingEm: 0)),
            ),
          ),
          if (markText.isNotEmpty)
            Container(
              alignment: mark == TileMark.correct ? const Alignment(0, 0.8) : Alignment.center,
              decoration: BoxDecoration(
                color: mark == TileMark.wrong ? const Color(0x59C8283C) : null,
                gradient: mark == TileMark.correct
                    ? LinearGradient(
                        begin: Alignment.topCenter,
                        end: Alignment.bottomCenter,
                        stops: const [0.55, 1],
                        colors: [const Color(0x00000000), Palette.ok.withValues(alpha: 0.6)],
                      )
                    : null,
              ),
              child: Text(
                markText,
                key: ValueKey('tile-mark-$position'),
                textAlign: TextAlign.center,
                style: Fonts.displayStyle(size: 15, height: 1.1).copyWith(
                  shadows: const [Shadow(color: Color(0x99000000), blurRadius: 8, offset: Offset(0, 2))],
                ),
              ),
            ),
        ],
      ),
    );
    return Semantics(
      button: true,
      enabled: state.enabled,
      label: t.answerChoiceN(position + 1),
      excludeSemantics: true,
      child: Opacity(
        opacity: opacity,
        child: Container(
          // The sunset mood's polaroid frame.
          padding: const EdgeInsets.fromLTRB(5, 5, 5, 14),
          decoration: BoxDecoration(
            color: const Color(0xFFFBF5EC),
            borderRadius: BorderRadius.circular(2),
            boxShadow: const [BoxShadow(color: Color(0x59000000), blurRadius: 18, offset: Offset(0, 8))],
          ),
          child: InkWell(
            key: ValueKey('tile-$position'),
            onTap: state.enabled ? () => game.pick(position) : null,
            child: frame,
          ),
        ),
      ),
    );
  }

  static Widget _cover(Uint8List bytes) => Image.memory(
        bytes,
        key: const ValueKey('tile-cover'),
        fit: BoxFit.cover,
        gaplessPlayback: true,
        excludeFromSemantics: true,
        errorBuilder: (_, _, _) => const SizedBox.shrink(),
      );

  // grayscale(1) brightness(.6).
  static const List<double> _greyDark = [
    0.1278, 0.4292, 0.0430, 0, 0, //
    0.1278, 0.4292, 0.0430, 0, 0, //
    0.1278, 0.4292, 0.0430, 0, 0, //
    0, 0, 0, 1, 0,
  ];
}

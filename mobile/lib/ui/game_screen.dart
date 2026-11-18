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

// The share of the middle the reveal card may take above the chat log, and
// above a choice room's tiles; past it the card scrolls inside.
const double _revealShareChat = 0.6;
const double _revealShareChoice = 0.55;

// The chat room's spinning disc beside the hint, and the least middle
// height that shows it (below it, the hint line alone).
const double _discSize = 84;
const double _discRowMin = 160;

// A choice room's small disc beside the hint, and the least middle height
// that shows it.
const double _smallDisc = 40;
const double _smallDiscMin = 140;

// A choice room's tiles take at most this share of the middle; the chat
// box below them takes the rest. After the game the ranking takes at most
// [_finalShare] and the chat box the rest.
const double _tileShare = 0.45;
const double _finalShare = 0.6;

// The least height kept for a choice room's tiles under the reveal card.
const double _tileMin = 40;

// A choice room's reveal card: at most its share of the middle, and less
// when the space leaves too little for the tiles and two chat lines, or
// with the keyboard up (no chat box then, [chat] false) for the tiles
// alone (the card scrolls inside).
double _choiceCardMax(double middle, {bool chat = true}) => math.max(
    0, math.min(middle * _revealShareChoice, middle - 8 - (chat ? 8 + chatBoxMinHeight : 0) - _tileMin));

// The least cover width of a choice room's tiles with the keyboard up
// (cycle 159): below it the disc and the hint give their row to the tiles.
const double _coverReadable = 48;

// The side padding of the screen's parts.
const double _side = 12;

/// The game and game-over screens (GameScreen.jsx, the sunset mood) on one
/// phone screen with no page scroll (D-142). Top: a compact bar with song
/// n / N, the room code and settings, the sound offset and 나가기 (during a
/// game, D-138, with a confirm dialog), then the time bar, the seconds left
/// and the skip vote; one sideways line of players with their points; the
/// connection, sound and load lines. Middle: the spinning disc with the
/// hint beside it (84 px in a chat room, 40 px in a choice room; the hint
/// alone when the space is short) or the compact reveal card, over the
/// chat log in a chat room; the final ranking after the game. Bottom,
/// lifted by the on-screen keyboard: the
/// chat form. In a choice room the tiles take a box of 45 % of the middle
/// under the small disc and the hint, and a box of the latest chat lines
/// with 전체 ▲ takes the rest (after the game, under the ranking); it opens
/// the full chat over the middle. With the keyboard up a choice room shows
/// no chat box, and its tiles keep their size as far as the space allows
/// (cycle 159): they shrink only to a readable size, then the disc and the
/// hint make way, then they shrink again.
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

// The leave dialog and the full chat live in this screen (as LeaveDialog
// inside GameScreen on the web), so they go with the screen when it ends.
class _GameBodyState extends State<_GameBody> {
  bool _leaveOpen = false;
  bool _chatOpen = false;
  final FocusNode _leaveButton = FocusNode();

  @override
  void dispose() {
    _leaveButton.dispose();
    super.dispose();
  }

  void _cancelLeave() {
    setState(() => _leaveOpen = false);
    _leaveButton.requestFocus();
  }

  void _toggleChat() => setState(() => _chatOpen = !_chatOpen);

  @override
  Widget build(BuildContext context) {
    final t = AppLocalizations.of(context);
    final c = widget.controller;
    final game = widget.game;
    final state = c.session!;
    final me = state.myName;
    final over = game.view == GameView.over;
    final online = state.players.where((p) => p.connected).length;
    final settings = game.settings;
    final choiceRoom = game.choices != null || settings?.answerMode == 'choice';
    final codeLine = [
      ?state.roomCode,
      if (settings != null && settingsLine(t, settings).isNotEmpty) settingsLine(t, settings),
    ].join(' · ');

    final preview = ChatPreview(lines: game.log, me: me, onOpen: _toggleChat);
    final Widget middle;
    if (over) {
      // The ranking on top (its rows scroll inside past 60 %), the latest
      // chat lines in the rest.
      middle = LayoutBuilder(
        builder: (context, box) => Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            ConstrainedBox(
              constraints: BoxConstraints(maxHeight: box.maxHeight * _finalShare),
              child: _FinalCard(game: game, me: me),
            ),
            const SizedBox(height: 8),
            Expanded(child: preview),
          ],
        ),
      );
    } else {
      // A choice room with the on-screen keyboard up (cycle 159): no chat
      // box, the tiles at their size with the keyboard down as far as the
      // space allows. The Scaffold below takes the keyboard out of its
      // body's MediaQuery, so it is read here. [lifted] is how much lower
      // the middle is than with the keyboard down: the keyboard's height,
      // less the bottom safe-area padding it covers.
      final inset = MediaQuery.viewInsetsOf(context).bottom;
      final typing = choiceRoom && inset > 0;
      final lifted = typing ? inset - math.min(inset, MediaQuery.viewPaddingOf(context).bottom) : 0.0;
      final textScaler = MediaQuery.textScalerOf(context);
      middle = LayoutBuilder(
        builder: (context, box) {
          final reveal = game.reveal;
          final share = choiceRoom ? _revealShareChoice : _revealShareChat;
          final choices = game.choices;
          final count = choices?.length ?? 0;
          // The middle's height with the keyboard down, and the tiles' box
          // there at most: 45 % of it.
          final middleDown = box.maxHeight + lifted;
          final tilesDown = middleDown * _tileShare;
          // With the keyboard up the disc and the hint keep their row only
          // while the tiles under it stay readable (or at their size with
          // the keyboard down, when that is smaller).
          final headerShown = !typing ||
              choices == null ||
              box.maxHeight - _hintRowHeight(game, textScaler, disc: box.maxHeight >= _smallDiscMin) - 8 >=
                  math.min(gridHeight(count, box.maxWidth, tilesDown), gridHeightAt(count, box.maxWidth, _coverReadable));
          return Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              if (reveal != null)
                ConstrainedBox(
                  constraints: BoxConstraints(
                      maxHeight: choiceRoom ? _choiceCardMax(box.maxHeight, chat: !typing) : box.maxHeight * share),
                  child: SingleChildScrollView(
                    key: const ValueKey('reveal-scroll'),
                    child: RevealCard(reveal: reveal, cover: game.revealCover, last: game.lastSong, language: c.language),
                  ),
                )
              else if (!choiceRoom && box.maxHeight >= _discRowMin)
                _DiscRow(game: game, size: math.min(_discSize, box.maxHeight * 0.4))
              else if (!headerShown)
                const SizedBox.shrink()
              else if (choiceRoom && box.maxHeight >= _smallDiscMin)
                Row(
                  children: [
                    Disc(size: _smallDisc, spinning: game.audible),
                    const SizedBox(width: 10),
                    Expanded(child: _Hint(game: game)),
                  ],
                )
              else
                _Hint(game: game),
              if (headerShown) const SizedBox(height: 8),
              Expanded(
                child: !choiceRoom
                    ? _LogBox(child: ChatLog(lines: game.log, me: me, padding: const EdgeInsets.fromLTRB(12, 8, 12, 8)))
                    : LayoutBuilder(
                        // The tiles in a box of 45 % of the middle (less when
                        // the space leaves no room for two chat lines); the
                        // latest chat lines take all the rest. With the
                        // keyboard up, the same box as with it down, cut to
                        // the space there is, and no chat lines.
                        builder: (context, rest) {
                          final room = math.min(tilesDown, rest.maxHeight + lifted - 8 - chatBoxMinHeight);
                          final fit = typing ? math.min(room, rest.maxHeight) : room;
                          final grid = choices == null ? 0.0 : gridHeight(count, rest.maxWidth, math.max(0, fit));
                          final tiles = choices != null && grid >= _frameHeight;
                          return Column(
                            crossAxisAlignment: CrossAxisAlignment.stretch,
                            children: [
                              if (tiles)
                                SizedBox(height: grid, child: ChoiceGrid(game: game, cover: (url) => c.rounds?.cover(url))),
                              if (!typing) ...[
                                if (tiles) const SizedBox(height: 8),
                                Expanded(child: preview),
                              ],
                            ],
                          );
                        },
                      ),
              ),
            ],
          );
        },
      );
    }
    final showPreview = choiceRoom || over;

    final page = Scaffold(
      backgroundColor: Palette.bg,
      resizeToAvoidBottomInset: true,
      body: SafeArea(
        child: Center(
          child: ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: contentMaxWidth),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                _TopBar(
                  game: game,
                  codeLine: codeLine,
                  trailing: over
                      ? null
                      : RoomButton(
                          key: const ValueKey('game-leave'),
                          label: t.gameLeave,
                          compact: true,
                          focusNode: _leaveButton,
                          onPressed: () => setState(() => _leaveOpen = true),
                        ),
                ),
                _PlayersStrip(players: state.players, me: me, scoreboard: game.scoreboard, correct: _winners(game)),
                _Notices(controller: c, game: game, over: over),
                Expanded(
                  child: Padding(
                    padding: const EdgeInsets.fromLTRB(_side, 8, _side, 0),
                    child: Stack(
                      fit: StackFit.expand,
                      children: [
                        middle,
                        if (showPreview && _chatOpen)
                          Positioned.fill(
                            child: _FullChat(lines: game.log, me: me, online: online, onClose: _toggleChat),
                          ),
                      ],
                    ),
                  ),
                ),
                Padding(
                  padding: const EdgeInsets.fromLTRB(_side, 8, _side, 10),
                  child: Column(
                    mainAxisSize: MainAxisSize.min,
                    crossAxisAlignment: CrossAxisAlignment.stretch,
                    children: [
                      if (!over)
                        ChatForm(
                          key: const ValueKey('chat-form'),
                          onSend: c.sendChat,
                          hint: choiceRoom ? t.chatTalkPlaceholder : null,
                        )
                      else if (game.lobbyOpen)
                        Align(
                          alignment: Alignment.centerLeft,
                          child: RoomButton(
                            key: const ValueKey('to-lobby'),
                            label: t.gameToLobby,
                            kind: ButtonKind.primary,
                            onPressed: c.toLobby,
                          ),
                        ),
                    ],
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
    if (!_leaveOpen || over) return page;
    return Stack(
      children: [
        page,
        Positioned.fill(
          child: LeaveDialog(onConfirm: c.leave, onCancel: _cancelLeave),
        ),
      ],
    );
  }

  // The players' correct marks: every ranked name, or the first-come winner.
  static List<String> _winners(GameModel game) {
    if (game.correctNames.isNotEmpty) return game.correctNames;
    final winner = game.correct;
    return winner == null ? const [] : [winner];
  }
}

/// The compact top bar (the header and `.stage-top`): song n / N, the room
/// code with the game's settings, [trailing] (나가기, none at game over);
/// then the time bar, the seconds left, and 스킵 (or 스킵 취소) with the
/// vote count.
class _TopBar extends StatelessWidget {
  const _TopBar({required this.game, required this.codeLine, this.trailing});

  final GameModel game;
  final String codeLine;
  final Widget? trailing;

  @override
  Widget build(BuildContext context) {
    final t = AppLocalizations.of(context);
    final round = game.round;
    final timer = game.timer;
    final seconds = timer == null ? null : (timer.remainingMs / 1000).ceil();
    final count = game.skipCount;
    return Container(
      decoration: BoxDecoration(
        color: Palette.bg.withValues(alpha: 0.78),
        border: const Border(bottom: BorderSide(color: Palette.line)),
      ),
      padding: const EdgeInsets.fromLTRB(_side, 6, _side, 8),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Row(
            children: [
              if (round != null) ...[
                Text.rich(
                  _roundSpans(t.stageRound(round.index + 1, round.total)),
                  key: const ValueKey('round'),
                  maxLines: 1,
                ),
                const SizedBox(width: 10),
              ],
              Expanded(
                child: Text(
                  codeLine,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: Fonts.monoStyle(size: 11.5, letterSpacingEm: 0.04),
                ),
              ),
              if (trailing != null) ...[
                const SizedBox(width: 6),
                trailing!,
              ],
            ],
          ),
          const SizedBox(height: 6),
          SizedBox(
            height: 32,
            child: Row(
              children: [
                Expanded(child: _TimeBar(timer: timer)),
                const SizedBox(width: 10),
                Text(
                  seconds == null ? '' : t.stageSeconds(seconds),
                  key: const ValueKey('clock'),
                  style: Fonts.displayStyle(size: 22, height: 1, color: seconds != null && seconds <= _lowSeconds ? Palette.a3 : Palette.ink),
                ),
                if (game.canSkip) ...[
                  const SizedBox(width: 8),
                  RoomButton(
                    key: const ValueKey('skip'),
                    label: game.skipVoted ? t.stageSkipCancel : t.stageSkip,
                    kind: game.skipVoted ? ButtonKind.primary : ButtonKind.plain,
                    compact: true,
                    onPressed: game.skipVote,
                  ),
                  if (count != null) ...[
                    const SizedBox(width: 6),
                    Text(
                      t.stageSkipCount(count.votes, count.needed),
                      key: const ValueKey('skip-count'),
                      style: Fonts.monoStyle(size: 11.5, letterSpacingEm: 0),
                    ),
                  ],
                ],
              ],
            ),
          ),
        ],
      ),
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
        style: num ? Fonts.displayStyle(size: 20, height: 1.2) : null,
      ));
      if (!num) spans.add(const TextSpan(text: ' '));
      at = m.end;
    }
    if (at < text.length) spans.add(TextSpan(text: text.substring(at)));
    return TextSpan(style: Fonts.bodyStyle(size: 12.5, color: Palette.dim), children: spans);
  }
}

/// `.timebar`: the fill shrinks from the right as the song's time runs out;
/// a progress bar only while the song has a clock.
class _TimeBar extends StatelessWidget {
  const _TimeBar({required this.timer});

  final GameTimer? timer;

  @override
  Widget build(BuildContext context) {
    final t = AppLocalizations.of(context);
    final timer = this.timer;
    final ratio = timer == null || timer.maxMs <= 0 ? 0.0 : (timer.remainingMs / timer.maxMs).clamp(0.0, 1.0);
    final bar = Container(
      height: 6,
      decoration: BoxDecoration(color: Palette.line, borderRadius: BorderRadius.circular(Palette.radius)),
      clipBehavior: Clip.antiAlias,
      alignment: Alignment.centerLeft,
      child: FractionallySizedBox(
        key: const ValueKey('timebar-fill'),
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

/// The players in one sideways line (the game's seats, D-142): each the
/// initial on the name colour, the name with (나), and its points from the
/// last scores (else the roster's), with 정답! and a green border for this
/// song's correct answers. It scrolls sideways when it does not fit.
class _PlayersStrip extends StatelessWidget {
  const _PlayersStrip({required this.players, required this.me, required this.scoreboard, required this.correct});

  final List<Player> players;
  final String? me;
  final List<ScoreRow>? scoreboard;
  final List<String> correct;

  int _scoreOf(Player p) {
    for (final row in scoreboard ?? const <ScoreRow>[]) {
      if (row.name == p.name) return row.score;
    }
    return p.score;
  }

  Widget _chip(AppLocalizations t, Player p) {
    final won = correct.contains(p.name);
    final name = p.name == me ? '${p.name} ${t.seatMe}' : p.name;
    final initial = p.name.isEmpty ? '' : String.fromCharCode(p.name.runes.first);
    return Opacity(
      opacity: p.connected ? 1 : 0.5,
      child: Container(
        key: ValueKey('game-seat-${p.name}'),
        padding: const EdgeInsets.fromLTRB(3, 2, 10, 2),
        decoration: BoxDecoration(
          color: Palette.panelAlpha(0.8),
          border: Border.all(color: won ? Palette.ok : Palette.line),
          borderRadius: BorderRadius.circular(Palette.radius),
        ),
        child: Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            Container(
              width: 22,
              height: 22,
              decoration: BoxDecoration(color: nameColour(p.name), shape: BoxShape.circle),
              alignment: Alignment.center,
              child: ExcludeSemantics(
                child: Text(
                  initial,
                  style: Fonts.bodyStyle(size: 11, color: const Color(0xFF1A1020), weight: FontWeight.w700).copyWith(height: 1.2),
                ),
              ),
            ),
            const SizedBox(width: 6),
            Text(
              name,
              maxLines: 1,
              style: Fonts.bodyStyle(size: 12.5, weight: p.name == me ? FontWeight.w700 : FontWeight.w500).copyWith(height: 1.3),
            ),
            const SizedBox(width: 6),
            Text.rich(
              key: ValueKey('seat-score-${p.name}'),
              maxLines: 1,
              TextSpan(
                style: Fonts.monoStyle(size: 11, color: Palette.a2, letterSpacingEm: 0),
                children: [
                  TextSpan(text: t.seatScore(_scoreOf(p))),
                  if (won)
                    TextSpan(
                      text: ' ${t.seatCorrect}',
                      style: Fonts.bodyStyle(size: 11.5, color: Palette.ok, weight: FontWeight.w700),
                    ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final t = AppLocalizations.of(context);
    return Padding(
      padding: const EdgeInsets.only(top: 8),
      child: SizedBox(
        height: 30,
        child: Semantics(
          label: t.scoreBoard,
          container: true,
          child: SingleChildScrollView(
            key: const ValueKey('players'),
            scrollDirection: Axis.horizontal,
            padding: const EdgeInsets.symmetric(horizontal: _side),
            child: Row(
              children: [
                for (final (i, p) in players.indexed) ...[
                  if (i > 0) const SizedBox(width: 6),
                  _chip(t, p),
                ],
              ],
            ),
          ),
        ),
      ),
    );
  }
}

/// The lines under the players, in the top area so they never push the
/// form down: the reconnecting line, the watching line, the song's load
/// state, an error, and the sound prompt (no tap yet, as after a reload
/// rejoin: one tap unlocks it and the song joins where it is).
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
      padding: const EdgeInsets.fromLTRB(_side, 6, _side, 0),
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

/// The height of a choice room's row of [_Hint] (beside the small disc when
/// [disc]), from [_Hint]'s text styles: the hint line, and the line of the
/// wrong pick and the pick count when it shows.
double _hintRowHeight(GameModel game, TextScaler scaler, {required bool disc}) {
  final second = game.choices != null &&
      ((game.round != null && game.pickWrong && game.picked != null) || (game.pickShown && game.pickCount != null));
  final hint = scaler.scale(18) * 1.3 + (second ? 2 + scaler.scale(13) * 1.5 : 0);
  return disc ? math.max(_smallDisc, hint) : hint;
}

/// The hint before the reveal (GameScreen.jsx), in one line that shrinks
/// to fit; under it in a choice room the own wrong pick and the pick count.
class _Hint extends StatelessWidget {
  const _Hint({required this.game});

  final GameModel game;

  @override
  Widget build(BuildContext context) {
    final t = AppLocalizations.of(context);
    final round = game.round;
    final grid = game.choices != null;
    final hint = hintText(t, game);
    final picked = game.picked;
    final wrong = grid && round != null && game.pickWrong && picked != null;
    final picks = game.pickCount;
    final counted = game.pickShown && picks != null;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        _OneLine(Text(hint, key: const ValueKey('hint'), maxLines: 1, style: Fonts.displayStyle(size: 18, height: 1.3))),
        if (wrong || counted)
          Padding(
            padding: const EdgeInsets.only(top: 2),
            child: Row(
              children: [
                Expanded(
                  child: wrong
                      ? _OneLine(Text(
                          t.stagePickWrong(picked + 1),
                          key: const ValueKey('pick-wrong'),
                          maxLines: 1,
                          style: Fonts.bodyStyle(size: 13, color: Palette.a3, weight: FontWeight.w700),
                        ))
                      : const SizedBox.shrink(),
                ),
                if (counted) ...[
                  const SizedBox(width: 8),
                  Text(
                    t.stagePickCount(picks.picked, picks.total),
                    key: const ValueKey('pick-count'),
                    style: Fonts.monoStyle(size: 12, letterSpacingEm: 0),
                  ),
                ],
              ],
            ),
          ),
      ],
    );
  }
}

/// A chat room's row before the reveal (D-142): the spinning disc (the
/// web's sunset LP with its ?, turning while the song is audible) and the
/// hint beside it. Nothing in it tells the song.
class _DiscRow extends StatelessWidget {
  const _DiscRow({required this.game, required this.size});

  final GameModel game;
  final double size;

  @override
  Widget build(BuildContext context) => SizedBox(
        height: size,
        child: Row(
          children: [
            Disc(size: size, spinning: game.audible),
            const SizedBox(width: 12),
            Expanded(
              child: Align(
                alignment: Alignment.centerLeft,
                child: Text(
                  hintText(AppLocalizations.of(context), game),
                  key: const ValueKey('hint'),
                  maxLines: 3,
                  overflow: TextOverflow.ellipsis,
                  style: Fonts.displayStyle(size: 16, height: 1.25),
                ),
              ),
            ),
          ],
        ),
      );
}

/// The deck's disc in small (Deck.jsx `.disc` and `.mark`, drawPlayer's
/// sunset LP): a black record with faint grooves, a sheen and an orange
/// label, and a ? over it; one turn in 7 s while [spinning].
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
              decoration: const BoxDecoration(
                shape: BoxShape.circle,
                boxShadow: [BoxShadow(color: Color(0x8C000000), blurRadius: 18, offset: Offset(0, 6))],
              ),
              child: RotationTransition(
                turns: _turn,
                child: CustomPaint(size: Size.square(size), painter: const _DiscPainter()),
              ),
            ),
            Text('?', style: Fonts.displayStyle(size: size * 0.28, color: Palette.bg, height: 1)),
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

/// [text] on one line, scaled down when it is wider than the space.
class _OneLine extends StatelessWidget {
  const _OneLine(this.text);

  final Widget text;

  @override
  Widget build(BuildContext context) => Align(
        alignment: Alignment.centerLeft,
        child: FittedBox(fit: BoxFit.scaleDown, child: text),
      );
}

/// The chat log's frame in a chat room's middle.
class _LogBox extends StatelessWidget {
  const _LogBox({required this.child});

  final Widget child;

  @override
  Widget build(BuildContext context) => Container(
        decoration: BoxDecoration(
          color: Palette.panelAlpha(0.9),
          border: Border.all(color: Palette.line),
          borderRadius: BorderRadius.circular(Palette.radius),
        ),
        clipBehavior: Clip.antiAlias,
        child: Semantics(label: AppLocalizations.of(context).chatLabel, container: true, child: child),
      );
}

/// The full chat over the middle (a choice room, and after the game): 채팅
/// with the connected count and 닫기 (also a second tap on the latest
/// lines), and the log. The form stays below it.
class _FullChat extends StatelessWidget {
  const _FullChat({required this.lines, required this.me, required this.online, required this.onClose});

  final List<LogLine> lines;
  final String? me;
  final int online;
  final VoidCallback onClose;

  @override
  Widget build(BuildContext context) {
    final t = AppLocalizations.of(context);
    return Container(
      key: const ValueKey('chat-full'),
      decoration: BoxDecoration(
        color: Palette.panel,
        border: Border.all(color: Palette.line),
        borderRadius: BorderRadius.circular(Palette.radius),
        boxShadow: const [BoxShadow(color: Color(0x99000000), blurRadius: 18, offset: Offset(0, 4))],
      ),
      clipBehavior: Clip.antiAlias,
      child: Semantics(
        label: t.chatLabel,
        container: true,
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Container(
              padding: const EdgeInsets.fromLTRB(14, 6, 8, 6),
              decoration: const BoxDecoration(border: Border(bottom: BorderSide(color: Palette.line))),
              child: Row(
                children: [
                  Text(t.chatLabel, style: Fonts.displayStyle(size: 16)),
                  const SizedBox(width: 10),
                  const LiveDot(),
                  const SizedBox(width: 6),
                  Expanded(
                    child: Text(
                      t.chatLive(online),
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: Fonts.bodyStyle(size: 12, color: Palette.dim),
                    ),
                  ),
                  RoomButton(key: const ValueKey('chat-close'), label: t.offsetClose, compact: true, onPressed: onClose),
                ],
              ),
            ),
            Expanded(child: ChatLog(lines: lines, me: me, padding: const EdgeInsets.fromLTRB(12, 8, 12, 8))),
          ],
        ),
      ),
    );
  }
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

/// The reveal card (GameScreen.jsx RevealCard with the deck's cover), in
/// its compact phone form (D-142): the cover thumbnail with its stamp
/// (정답, or 통과 when nobody solved it) beside 정답 공개, the winner (or
/// every ranked solver, or 정답자 없음), the title in the screen's language
/// and the other titles in small text; a ranked song's ranking with its
/// points; the slug · song — artists line; and what comes next.
class RevealCard extends StatelessWidget {
  const RevealCard({super.key, required this.reveal, required this.cover, required this.last, required this.language});

  final RevealMessage reveal;
  final Uint8List? cover;
  final bool last;
  final String language;

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
    final ranked = ranking != null;
    final solvers = ranked ? [for (final row in ranking) row.name] : const <String>[];
    final String? who;
    if (ranked) {
      who = solvers.isEmpty ? null : t.revealSolvers(solvers.join(', '));
    } else {
      who = reveal.winner == null ? null : t.revealWinner(reveal.winner!);
    }
    final missed = ranked ? solvers.isEmpty : reveal.winner == null;
    final coverTitle = lineTitle(reveal, language) ?? lineTitle(reveal, 'ko') ?? reveal.reveal ?? '';
    final song = reveal.song;
    final artists = reveal.artists;
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
                    Text(t.revealHeading.toUpperCase(), style: Fonts.monoStyle(size: 10.5, semiBold: true, letterSpacingEm: 0.16)),
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
              TextSpan(
                style: Fonts.bodyStyle(size: 12.5, color: Palette.dim),
                children: [
                  TextSpan(text: reveal.themeSlug),
                  if (song != null && song.isNotEmpty) ...[
                    const TextSpan(text: ' · '),
                    TextSpan(text: song, style: Fonts.bodyStyle(size: 12.5, weight: FontWeight.w500)),
                  ],
                  if (artists.isNotEmpty) TextSpan(text: ' $_dash ${artists.join(', ')}'),
                ],
              ),
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
  const _RevealCover({required this.cover, required this.title, required this.missed});

  final Uint8List? cover;
  final String title;
  final bool missed;

  @override
  Widget build(BuildContext context) {
    final t = AppLocalizations.of(context);
    final bytes = cover;
    return SizedBox(
      width: 66,
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
                        key: const ValueKey('reveal-cover'),
                        fit: BoxFit.cover,
                        semanticLabel: t.revealCoverAlt(title),
                        gaplessPlayback: true,
                        errorBuilder: (_, _, _) => const SizedBox.shrink(),
                      ),
              ),
            ),
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
/// [height] (D-142): 2 or 4 columns for 4 and 8 tiles, 2 or 3 for 6,
/// whichever gives the bigger tiles; the covers are 3:4 in their polaroid
/// frames. The width is never negative (the grid scales down past that).
({int columns, double coverWidth}) tileLayout(int count, double width, double height) {
  final options = _columnOptions(count);
  var best = (columns: options.first, coverWidth: -1.0);
  for (final columns in options) {
    final rows = (count / columns).ceil();
    final byWidth = (width - _tileGap * (columns - 1)) / columns - _frameWidth;
    final byHeight = ((height - _tileGap * (rows - 1)) / rows - _frameHeight) * 3 / 4;
    final cover = math.min(byWidth, byHeight);
    if (cover > best.coverWidth) best = (columns: columns, coverWidth: cover);
  }
  return (columns: best.columns, coverWidth: math.max(0, best.coverWidth).floorToDouble());
}

// The column counts [tileLayout] picks from for [count] tiles.
Set<int> _columnOptions(int count) => {
      math.min(2, math.max(1, count)),
      if (count == 4 || count == 8) 4,
      if (count == 6) 3,
    };

/// The least height in which [tileLayout] gives [count] tiles in [width]
/// covers at least [cover] wide; infinite when [width] is too narrow.
double gridHeightAt(int count, double width, double cover) {
  var least = double.infinity;
  for (final columns in _columnOptions(count)) {
    if ((width - _tileGap * (columns - 1)) / columns - _frameWidth < cover) continue;
    final rows = (count / columns).ceil();
    // Rounded up so the layout's own rounding down still reaches [cover].
    final tile = (cover * 4 / 3).ceilToDouble() + _frameHeight;
    least = math.min(least, rows * tile + _tileGap * (rows - 1));
  }
  return least;
}

/// The height of [count] tiles laid out by [tileLayout] in [width] x
/// [height]: at most [height] (past that the grid scales down).
double gridHeight(int count, double width, double height) {
  final layout = tileLayout(count, width, height);
  final rows = (count / layout.columns).ceil();
  final tile = (layout.coverWidth * 4 / 3).floorToDouble() + _frameHeight;
  return math.min(height, rows * tile + _tileGap * (rows - 1));
}

/// A choice room's cover tiles (ChoiceGrid.jsx, D-111) filling the space
/// they are given (D-142, [tileLayout]); each tile its cover (from the
/// round controller's bytes; an empty frame until loaded), its number, and
/// after the reveal or the own pick its translated mark (cycle 138).
/// Nothing marks the correct tile before the reveal (A9).
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

/// The leave confirmation (LeaveDialog.jsx, D-138): 게임을 나갈까요? with
/// 취소 (focused on open; also Escape or a tap outside the card) and
/// 나가기, over a modal barrier.
class LeaveDialog extends StatelessWidget {
  const LeaveDialog({super.key, required this.onConfirm, required this.onCancel});

  final VoidCallback onConfirm;
  final VoidCallback onCancel;

  @override
  Widget build(BuildContext context) {
    final t = AppLocalizations.of(context);
    return CallbackShortcuts(
      bindings: {const SingleActivator(LogicalKeyboardKey.escape): onCancel},
      child: FocusScope(
        autofocus: true,
        child: Stack(
          children: [
            ModalBarrier(
              key: const ValueKey('leave-barrier'),
              color: const Color(0x99000000),
              onDismiss: onCancel,
              semanticsLabel: t.gameLeaveCancel,
            ),
            Center(
              child: Padding(
                padding: const EdgeInsets.all(16),
                child: ConstrainedBox(
                  constraints: const BoxConstraints(maxWidth: 384),
                  child: Material(
                    key: const ValueKey('leave-dialog'),
                    color: Palette.panel,
                    shape: RoundedRectangleBorder(
                      borderRadius: BorderRadius.circular(Palette.radius),
                      side: const BorderSide(color: Palette.line),
                    ),
                    child: Semantics(
                      scopesRoute: true,
                      namesRoute: true,
                      explicitChildNodes: true,
                      label: t.gameLeaveAsk,
                      child: Padding(
                        padding: const EdgeInsets.fromLTRB(22, 20, 22, 18),
                        child: Column(
                          mainAxisSize: MainAxisSize.min,
                          crossAxisAlignment: CrossAxisAlignment.stretch,
                          children: [
                            Semantics(header: true, child: Text(t.gameLeaveAsk, style: Fonts.displayStyle(size: 21))),
                            const SizedBox(height: 18),
                            Row(
                              mainAxisAlignment: MainAxisAlignment.end,
                              children: [
                                RoomButton(
                                  key: const ValueKey('leave-cancel'),
                                  label: t.gameLeaveCancel,
                                  autofocus: true,
                                  onPressed: onCancel,
                                ),
                                const SizedBox(width: 8),
                                RoomButton(
                                  key: const ValueKey('leave-confirm'),
                                  label: t.gameLeaveConfirm,
                                  kind: ButtonKind.primary,
                                  onPressed: onConfirm,
                                ),
                              ],
                            ),
                          ],
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

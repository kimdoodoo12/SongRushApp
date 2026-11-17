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

/// The game and game-over screens (GameScreen.jsx, the sunset mood), in one
/// phone column: the header with the code, the game's settings line and
/// 나가기 (during a game, D-138, with a confirm dialog); the stage (the mood
/// tag, song n / N, the clock and the time bar; the hint, the reveal card,
/// or the final ranking over the last reveal); the skip vote and the pick
/// count; a choice room's tiles; the seats with their points; the chat
/// (no form after the game); the footer.
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

// The leave dialog lives in this screen (as LeaveDialog inside GameScreen
// on the web), so it goes with the screen when the game ends.
class _GameBodyState extends State<_GameBody> {
  bool _leaveOpen = false;
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
    final error = state.error;
    final choices = game.choices;
    final page = PageFrame(
      header: RoomHeader(
        code: state.roomCode,
        line: settings == null ? '' : settingsLine(t, settings),
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
      children: [
        if (state.status == SessionStatus.reconnecting) ...[
          WarnText(t.appReconnecting),
          const SizedBox(height: 12),
        ],
        Panel(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              _StageTop(game: game),
              const SizedBox(height: 10),
              _TimeBar(timer: game.timer),
              const SizedBox(height: 18),
              if (over) ...[
                _FinalCard(game: game, me: me, onToLobby: c.toLobby),
                if (game.reveal != null) ...[
                  const SizedBox(height: 14),
                  RevealCard(reveal: game.reveal!, cover: game.revealCover, last: game.lastSong, language: c.language),
                ],
              ] else if (game.reveal != null)
                RevealCard(reveal: game.reveal!, cover: game.revealCover, last: game.lastSong, language: c.language)
              else
                _Hint(game: game),
              if (!over && game.watching && (game.round != null || game.waitingNext)) ...[
                const SizedBox(height: 8),
                OptHint(t.stageWatching),
              ],
              if (!over && (game.canSkip || game.pickShown)) ...[
                const SizedBox(height: 14),
                _SkipRow(game: game),
              ],
              if (!over && error != null) ...[
                const SizedBox(height: 10),
                WarnText(errorText(t, error)),
              ],
            ],
          ),
        ),
        if (!over && choices != null) ...[
          const SizedBox(height: 16),
          ChoiceGrid(game: game, cover: (url) => c.rounds?.cover(url)),
        ],
        const SizedBox(height: 24),
        _GameSeats(players: state.players, me: me, scoreboard: game.scoreboard, correct: _winners(game)),
        const SizedBox(height: 24),
        ChatPanel(lines: game.log, me: me, online: online, onSend: over ? null : c.sendChat),
        Footer(language: c.language, onLanguage: c.setLanguage),
      ],
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

  // The seats' correct marks: every ranked name, or the first-come winner.
  static List<String> _winners(GameModel game) {
    if (game.correctNames.isNotEmpty) return game.correctNames;
    final winner = game.correct;
    return winner == null ? const [] : [winner];
  }
}

/// `.stage-top`: the mood's tag, song n / N, and the clock.
class _StageTop extends StatelessWidget {
  const _StageTop({required this.game});

  final GameModel game;

  @override
  Widget build(BuildContext context) {
    final t = AppLocalizations.of(context);
    final round = game.round;
    final timer = game.timer;
    final seconds = timer == null ? null : (timer.remainingMs / 1000).ceil();
    return Row(
      crossAxisAlignment: CrossAxisAlignment.end,
      children: [
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text('${t.themeSunset} · ${t.moodTagSunset}', style: Fonts.bodyStyle(size: 12.5, color: Palette.a2)),
              if (round != null)
                Text.rich(
                  _roundSpans(t.stageRound(round.index + 1, round.total)),
                  key: const ValueKey('round'),
                ),
            ],
          ),
        ),
        Text(
          seconds == null ? '' : t.stageSeconds(seconds),
          key: const ValueKey('clock'),
          style: Fonts.displayStyle(size: 40, height: 1, color: seconds != null && seconds <= _lowSeconds ? Palette.a3 : Palette.ink),
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
        style: num ? Fonts.displayStyle(size: 26, height: 1.2) : null,
      ));
      if (!num) spans.add(const TextSpan(text: ' '));
      at = m.end;
    }
    if (at < text.length) spans.add(TextSpan(text: text.substring(at)));
    return TextSpan(style: Fonts.bodyStyle(size: 14, color: Palette.dim), children: spans);
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

/// The panel before the reveal (GameScreen.jsx): the hint, the load state
/// and the sub line, and the own wrong pick.
class _Hint extends StatelessWidget {
  const _Hint({required this.game});

  final GameModel game;

  @override
  Widget build(BuildContext context) {
    final t = AppLocalizations.of(context);
    final round = game.round;
    final grid = game.choices != null;
    final playing = game.playing;
    final String hint;
    if (game.correct != null) {
      hint = t.chatCorrectLine(game.correct!);
    } else if (round == null) {
      hint = game.waitingNext
          ? t.stageWaitingNext
          : game.watching
              ? t.stageWatching
              : t.stageConnecting;
    } else if (grid) {
      hint = playing ? t.stageChoiceHint : t.stageChoiceSoon;
    } else {
      hint = playing ? t.stageHint : t.stageSoon;
    }
    final subs = <String>[
      if (round != null && game.load == LoadState.loading) t.stageLoading,
      if (round != null && game.load == LoadState.failed) t.stageLoadFailed,
      if (round != null && game.correct == null)
        playing
            ? (grid ? t.stageChoiceSub : t.stageSub)
            : t.stageSoonSub,
    ];
    final picked = game.picked;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(hint, key: const ValueKey('hint'), style: Fonts.displayStyle(size: 22, height: 1.3)),
        for (final sub in subs) ...[
          const SizedBox(height: 6),
          Text(sub, style: Fonts.bodyStyle(size: 13.5, color: Palette.dim)),
        ],
        if (grid && round != null && game.pickWrong && picked != null) ...[
          const SizedBox(height: 6),
          Text(
            t.stagePickWrong(picked + 1),
            key: const ValueKey('pick-wrong'),
            style: Fonts.bodyStyle(size: 13.5, color: Palette.a3, weight: FontWeight.w700),
          ),
        ],
      ],
    );
  }
}

/// `.row`: 스킵 (or 스킵 취소) with the vote count, and the pick count.
class _SkipRow extends StatelessWidget {
  const _SkipRow({required this.game});

  final GameModel game;

  @override
  Widget build(BuildContext context) {
    final t = AppLocalizations.of(context);
    final count = game.skipCount;
    final picks = game.pickCount;
    return Wrap(
      spacing: 12,
      runSpacing: 8,
      crossAxisAlignment: WrapCrossAlignment.center,
      children: [
        if (game.canSkip)
          RoomButton(
            key: const ValueKey('skip'),
            label: game.skipVoted ? t.stageSkipCancel : t.stageSkip,
            kind: game.skipVoted ? ButtonKind.primary : ButtonKind.plain,
            compact: true,
            onPressed: game.skipVote,
          ),
        if (game.canSkip && count != null)
          Text(
            t.stageSkipCount(count.votes, count.needed),
            key: const ValueKey('skip-count'),
            style: Fonts.monoStyle(size: 12.5, letterSpacingEm: 0),
          ),
        if (game.pickShown && picks != null)
          Text(
            t.stagePickCount(picks.picked, picks.total),
            key: const ValueKey('pick-count'),
            style: Fonts.monoStyle(size: 12.5, letterSpacingEm: 0),
          ),
      ],
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

/// The reveal card (GameScreen.jsx RevealCard with the deck's cover): 정답
/// 공개, the winner (or every ranked solver, or 정답자 없음), the cover with
/// its stamp (정답, or 통과 when nobody solved it) and the title in the
/// screen's language, the KO/EN/JA/Romaji titles, a ranked song's ranking
/// with its points, the slug · song — artists line, and what comes next.
class RevealCard extends StatelessWidget {
  const RevealCard({super.key, required this.reveal, required this.cover, required this.last, required this.language});

  final RevealMessage reveal;
  final Uint8List? cover;
  final bool last;
  final String language;

  @override
  Widget build(BuildContext context) {
    final t = AppLocalizations.of(context);
    final lines = [
      for (final lang in _titleLangs)
        if (lineTitle(reveal, lang) case final title?) (lang: lang, title: title),
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
      padding: const EdgeInsets.fromLTRB(18, 16, 18, 16),
      decoration: BoxDecoration(
        color: Palette.panel,
        border: Border.all(color: Palette.line),
        borderRadius: BorderRadius.circular(Palette.radius),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(t.revealHeading.toUpperCase(), style: Fonts.monoStyle(size: 11.5, semiBold: true, letterSpacingEm: 0.16)),
          const SizedBox(height: 4),
          Text(
            who ?? t.revealNoWinner,
            key: const ValueKey('reveal-who'),
            style: Fonts.displayStyle(size: 25, height: 1.25, color: who == null ? Palette.dim : Palette.ink),
          ),
          const SizedBox(height: 12),
          Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              _RevealCover(cover: cover, title: coverTitle, missed: missed),
              const SizedBox(width: 14),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    if (lines.isEmpty)
                      Text(reveal.reveal ?? '', style: _koStyle)
                    else
                      for (final line in lines)
                        Padding(
                          padding: const EdgeInsets.only(bottom: 4),
                          child: Row(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                              SizedBox(
                                width: 52,
                                child: Padding(
                                  padding: const EdgeInsets.only(top: 3),
                                  child: Text(_label(t, line.lang), style: Fonts.monoStyle(size: 11, letterSpacingEm: 0)),
                                ),
                              ),
                              Expanded(
                                child: Text(
                                  line.title,
                                  key: ValueKey('title-${line.lang}'),
                                  style: line.lang == 'ko' ? _koStyle : Fonts.bodyStyle(size: 14.5, weight: FontWeight.w500),
                                ),
                              ),
                            ],
                          ),
                        ),
                  ],
                ),
              ),
            ],
          ),
          if (ranking != null && ranking.isNotEmpty) ...[
            const SizedBox(height: 10),
            Semantics(
              label: t.revealRanking,
              container: true,
              child: Column(
                children: [
                  for (final row in ranking)
                    _RankRow(
                      key: ValueKey('song-rank-${row.name}'),
                      place: t.revealRankPlace(row.rank),
                      name: row.name,
                      points: t.revealRankPoints(row.points),
                    ),
                ],
              ),
            ),
          ],
          const SizedBox(height: 10),
          Container(
            width: double.infinity,
            padding: const EdgeInsets.only(top: 10),
            decoration: const BoxDecoration(border: Border(top: BorderSide(color: Palette.line))),
            child: Text.rich(
              key: const ValueKey('theme-line'),
              TextSpan(
                style: Fonts.bodyStyle(size: 13.5, color: Palette.dim),
                children: [
                  TextSpan(text: reveal.themeSlug),
                  if (song != null && song.isNotEmpty) ...[
                    const TextSpan(text: ' · '),
                    TextSpan(text: song, style: Fonts.bodyStyle(size: 13.5, weight: FontWeight.w500)),
                  ],
                  if (artists.isNotEmpty) TextSpan(text: ' $_dash ${artists.join(', ')}'),
                ],
              ),
            ),
          ),
          const SizedBox(height: 10),
          Text(last ? t.revealNextFinal : t.revealNext, style: Fonts.monoStyle(size: 11.5, letterSpacingEm: 0)),
        ],
      ),
    );
  }

  static final TextStyle _koStyle = Fonts.bodyStyle(size: 17, color: Palette.a2, weight: FontWeight.w700);

  static String _label(AppLocalizations t, String lang) => switch (lang) {
        'ko' => t.revealLabelKo,
        'en' => t.revealLabelEn,
        'ja' => t.revealLabelJa,
        _ => t.revealLabelRomaji,
      };
}

/// The deck's cover in small: the reveal's cover (or an empty frame), its
/// stamp, and the title in the screen's language.
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
      width: 96,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          AspectRatio(
            aspectRatio: 3 / 4,
            child: Stack(
              fit: StackFit.expand,
              children: [
                ClipRRect(
                  borderRadius: BorderRadius.circular(8),
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
                  right: -4,
                  bottom: 6,
                  child: Transform.rotate(
                    angle: -0.2,
                    child: Container(
                      key: const ValueKey('reveal-stamp'),
                      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
                      decoration: BoxDecoration(
                        color: Palette.bg.withValues(alpha: 0.85),
                        border: Border.all(color: missed ? Palette.dim : Palette.a3, width: 2),
                        borderRadius: BorderRadius.circular(6),
                      ),
                      child: Text(
                        missed ? t.revealStampPass : t.revealStampHit,
                        style: Fonts.displayStyle(size: 15, color: missed ? Palette.dim : Palette.a3, height: 1.2),
                      ),
                    ),
                  ),
                ),
              ],
            ),
          ),
          const SizedBox(height: 6),
          Text(
            title,
            maxLines: 2,
            overflow: TextOverflow.ellipsis,
            textAlign: TextAlign.center,
            style: Fonts.bodyStyle(size: 12, color: Palette.dim),
          ),
        ],
      ),
    );
  }
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

/// The final ranking (GameScreen.jsx FinalCard): rows `N위 name N점`, with
/// (나) after the own points, the own final score, and 대기실로 once the
/// server has returned the room to the lobby (D-062).
class _FinalCard extends StatelessWidget {
  const _FinalCard({required this.game, required this.me, required this.onToLobby});

  final GameModel game;
  final String? me;
  final VoidCallback onToLobby;

  @override
  Widget build(BuildContext context) {
    final t = AppLocalizations.of(context);
    final ranking = game.ranking;
    final finalScore = game.finalScore;
    return Container(
      key: const ValueKey('final'),
      padding: const EdgeInsets.fromLTRB(18, 16, 18, 16),
      decoration: BoxDecoration(
        color: Palette.panel,
        border: Border.all(color: Palette.line),
        borderRadius: BorderRadius.circular(Palette.radius),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Text(t.scoreRanking.toUpperCase(), style: Fonts.monoStyle(size: 11.5, semiBold: true, letterSpacingEm: 0.16)),
          const SizedBox(height: 10),
          if (ranking != null)
            Semantics(
              label: t.scoreRanking,
              container: true,
              child: Column(
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
          if (finalScore != null) ...[
            const SizedBox(height: 4),
            Text(t.scoreFinal(finalScore), key: const ValueKey('final-score'), style: Fonts.bodyStyle(size: 14, color: Palette.dim)),
          ],
          if (game.lobbyOpen) ...[
            const SizedBox(height: 12),
            Align(
              alignment: Alignment.centerLeft,
              child: RoomButton(
                key: const ValueKey('to-lobby'),
                label: t.gameToLobby,
                kind: ButtonKind.primary,
                onPressed: onToLobby,
              ),
            ),
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

/// A choice room's cover tiles (ChoiceGrid.jsx, D-111): 4 and 8 in four
/// columns, 6 in three; each tile its cover (from the round controller's
/// bytes; an empty frame until loaded), its number, and after the reveal
/// or the own pick its translated mark (cycle 138). Nothing marks the
/// correct tile before the reveal (A9).
class ChoiceGrid extends StatelessWidget {
  const ChoiceGrid({super.key, required this.game, required this.cover});

  final GameModel game;
  final Uint8List? Function(String url) cover;

  @override
  Widget build(BuildContext context) {
    final choices = game.choices!;
    final cols = choices.length == 6 ? 3 : 4;
    final rows = <Widget>[];
    for (var start = 0; start < choices.length; start += cols) {
      if (start > 0) rows.add(const SizedBox(height: 8));
      rows.add(Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          for (var i = start; i < start + cols; i++) ...[
            if (i > start) const SizedBox(width: 8),
            Expanded(
              child: i < choices.length ? _Tile(position: i, url: choices[i], game: game, cover: cover) : const SizedBox(),
            ),
          ],
        ],
      ));
    }
    return Container(
      key: const ValueKey('choices'),
      padding: const EdgeInsets.only(top: 14),
      decoration: const BoxDecoration(border: Border(top: BorderSide(color: Palette.line))),
      child: Column(children: rows),
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
    final frame = AspectRatio(
      aspectRatio: 3 / 4,
      child: Container(
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

/// The seats during a game (Seats.jsx without the lobby skin): two columns
/// of eight, each the initial on the name colour, the name with (방장) and
/// (나), and its points from the last scores (else the roster's), with
/// 정답! and a green border for this song's correct answers.
class _GameSeats extends StatelessWidget {
  const _GameSeats({required this.players, required this.me, required this.scoreboard, required this.correct});

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

  Widget _seat(AppLocalizations t, int i) {
    if (i >= players.length) {
      return Container(
        alignment: Alignment.centerLeft,
        padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 14),
        decoration: BoxDecoration(
          border: Border.all(color: Palette.line),
          borderRadius: BorderRadius.circular(Palette.radius),
        ),
        child: Text(t.seatEmpty, style: Fonts.bodyStyle(size: 13, color: Palette.dim)),
      );
    }
    final p = players[i];
    final won = correct.contains(p.name);
    var name = p.name;
    if (p.host) name += ' ${t.seatHost}';
    if (p.name == me) name += ' ${t.seatMe}';
    final initial = p.name.isEmpty ? '' : String.fromCharCode(p.name.runes.first);
    return Opacity(
      opacity: p.connected ? 1 : 0.5,
      child: Container(
        key: ValueKey('game-seat-${p.name}'),
        padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
        decoration: BoxDecoration(
          color: Palette.panelAlpha(0.8),
          border: Border.all(color: won ? Palette.ok : Palette.line),
          borderRadius: BorderRadius.circular(Palette.radius),
        ),
        child: Row(
          children: [
            Container(
              width: 30,
              height: 30,
              decoration: BoxDecoration(color: nameColour(p.name), shape: BoxShape.circle),
              alignment: Alignment.center,
              child: ExcludeSemantics(
                child: Text(
                  initial,
                  style: Fonts.bodyStyle(size: 12.5, color: const Color(0xFF1A1020), weight: FontWeight.w700),
                ),
              ),
            ),
            const SizedBox(width: 10),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    name,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: Fonts.bodyStyle(size: 13.5, weight: FontWeight.w700).copyWith(height: 1.3),
                  ),
                  Text.rich(
                    key: ValueKey('seat-score-${p.name}'),
                    TextSpan(
                      style: Fonts.monoStyle(size: 11, letterSpacingEm: 0),
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
          ],
        ),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final t = AppLocalizations.of(context);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Text(t.seatList.toUpperCase(), style: Fonts.monoStyle(size: 12, semiBold: true, letterSpacingEm: 0.14)),
        const SizedBox(height: 10),
        Semantics(
          label: t.scoreBoard,
          container: true,
          child: Column(
            children: [
              for (var row = 0; row < seatCount; row += 2) ...[
                if (row > 0) const SizedBox(height: 8),
                IntrinsicHeight(
                  child: Row(
                    crossAxisAlignment: CrossAxisAlignment.stretch,
                    children: [
                      Expanded(child: _seat(t, row)),
                      const SizedBox(width: 8),
                      Expanded(child: _seat(t, row + 1)),
                    ],
                  ),
                ),
              ],
            ],
          ),
        ),
      ],
    );
  }
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

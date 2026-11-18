import 'dart:math' as math;

import 'package:flutter/services.dart';
import 'package:material_ui/material_ui.dart';
import '../app/app_controller.dart';
import '../app/game_model.dart';
import '../l10n/app_localizations.dart';
import 'palette.dart';
import 'widgets.dart';

/// The lobby log's height on a phone; it scrolls inside.
const double _logHeight = 260;

// The mark before a correct line (GameScreen.jsx CHECK, U+2714).
final String _check = String.fromCharCode(0x2714);

// choice.css `.msg.wrong`.
const Color _wrongRed = Color(0xFFFF6B7A);

/// A notice's text in the current language.
String noticeText(AppLocalizations t, NoticeLine line) => switch (line.kind) {
      NoticeKind.lobbyJoined => t.chatLobbyJoined(line.value),
      NoticeKind.lobbyBack => t.chatLobbyBack,
      NoticeKind.gameStart => t.chatGameStart,
      NoticeKind.songStart => t.chatSongStart(line.value),
      NoticeKind.gameOver => t.chatGameOver,
      NoticeKind.wrongPick => t.chatWrongLine(line.value),
    };

/// The lobby chat (GameScreen.jsx Frame's aside.chat with ChatLog and
/// ChatForm, the sunset look): 채팅 with the connected count, the log in a
/// fixed height, and the form, absent without [onSend]. The game screen
/// places [ChatLog], [FloatingChat] and [ChatForm] itself (D-142, D-145).
class ChatPanel extends StatelessWidget {
  const ChatPanel({
    super.key,
    required this.lines,
    required this.me,
    required this.online,
    required this.onSend,
  });

  final List<LogLine> lines;
  final String? me;
  final int online;
  final ValueChanged<String>? onSend;

  @override
  Widget build(BuildContext context) {
    final t = AppLocalizations.of(context);
    final send = onSend;
    return Container(
      decoration: BoxDecoration(
        color: Palette.panelAlpha(0.9),
        border: Border.all(color: Palette.line),
        borderRadius: BorderRadius.circular(Palette.radius),
      ),
      child: Semantics(
        label: t.chatLabel,
        container: true,
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Container(
              padding: const EdgeInsets.symmetric(horizontal: 18, vertical: 14),
              decoration: const BoxDecoration(border: Border(bottom: BorderSide(color: Palette.line))),
              child: Row(
                children: [
                  Text(t.chatLabel, style: Fonts.displayStyle(size: 17.5)),
                  const Spacer(),
                  const LiveDot(),
                  const SizedBox(width: 8),
                  Text(t.chatLive(online), style: Fonts.bodyStyle(size: 12.5, color: Palette.dim)),
                ],
              ),
            ),
            SizedBox(height: _logHeight, child: ChatLog(lines: lines, me: me)),
            if (send != null) ChatForm(onSend: send, padding: const EdgeInsets.fromLTRB(18, 10, 18, 16)),
          ],
        ),
      ),
    );
  }
}

/// The red dot before the connected count.
class LiveDot extends StatelessWidget {
  const LiveDot({super.key});

  @override
  Widget build(BuildContext context) => Container(
        width: 8,
        height: 8,
        decoration: const BoxDecoration(color: Color(0xFFFF4D6D), shape: BoxShape.circle),
      );
}

/// The chat log: each said line as the name colour's dot, the name and a
/// bubble, the own ones on the right in the accent colour (D-145); notices
/// as centred system lines; a correct answer prominent, as
/// `✔ <name> 정답!`; a wrong pick in red. It fills the height it is
/// given and scrolls inside; the lines stack from its bottom, next to the
/// form, so a few lines sit at the bottom (cycle 153). It opens on the
/// newest line, follows new lines while it is at the bottom (and always
/// after an own line), and keeps the newest line in view when its height
/// changes (the on-screen keyboard).
class ChatLog extends StatefulWidget {
  const ChatLog({
    super.key,
    required this.lines,
    required this.me,
    this.padding = const EdgeInsets.symmetric(horizontal: 18, vertical: 12),
  });

  final List<LogLine> lines;
  final String? me;
  final EdgeInsets padding;

  @override
  State<ChatLog> createState() => _ChatLogState();
}

class _ChatLogState extends State<ChatLog> {
  final ScrollController _scroll = ScrollController();

  // At the bottom (within 40 px) after the last scroll.
  bool _atEnd = true;

  @override
  void initState() {
    super.initState();
    _toEnd();
  }

  @override
  void didUpdateWidget(ChatLog old) {
    super.didUpdateWidget(old);
    if (widget.lines.length == old.lines.length) return;
    final last = widget.lines.isEmpty ? null : widget.lines.last;
    final mine = last is SaidLine && last.name == widget.me;
    if (_atEnd || mine) _toEnd();
  }

  @override
  void dispose() {
    _scroll.dispose();
    super.dispose();
  }

  void _toEnd() {
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted || !_scroll.hasClients) return;
      final end = _scroll.position.maxScrollExtent;
      if (_scroll.offset != end) _scroll.jumpTo(end);
    });
  }

  @override
  Widget build(BuildContext context) {
    final t = AppLocalizations.of(context);
    return NotificationListener<ScrollMetricsNotification>(
      // The viewport or the content changed size: stay on the newest line.
      onNotification: (_) {
        if (_atEnd) _toEnd();
        return false;
      },
      child: NotificationListener<ScrollUpdateNotification>(
        onNotification: (n) {
          _atEnd = n.metrics.extentAfter < 40;
          return false;
        },
        child: LayoutBuilder(
          builder: (context, box) => SingleChildScrollView(
            key: const ValueKey('chat-log'),
            controller: _scroll,
            padding: widget.padding,
            child: ConstrainedBox(
              // At least the viewport's height, so the lines sit at its bottom.
              constraints: BoxConstraints(minHeight: math.max(0, box.maxHeight - widget.padding.vertical)),
              child: Column(
                mainAxisAlignment: MainAxisAlignment.end,
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  for (final line in widget.lines) logLine(t, line, me: widget.me),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }
}

/// A log line's widget.
Widget logLine(AppLocalizations t, LogLine line, {required String? me}) => switch (line) {
      SaidLine() => _Line(name: line.name, text: line.text, mine: line.name == me),
      CorrectLine(:final winner, :final rank) =>
        _CorrectLine(text: '$_check ${rank == null ? t.chatCorrectLine(winner) : t.chatCorrectRanked(winner, rank)}'),
      NoticeLine(kind: NoticeKind.wrongPick) => _SystemLine(text: t.chatWrongLine(line.value), color: _wrongRed),
      NoticeLine() => _SystemLine(text: noticeText(t, line)),
    };

/// The chat form: the field (200 characters; a blank line is not sent) and
/// 보내기. Sending keeps the focus in the field. [hint] replaces the field's
/// 애니 제목을 입력하세요 (a choice room, where chat is only talk, D-135).
/// [autofocus]: the field takes the focus when it shows (a choice room's
/// chat panel, D-145).
class ChatForm extends StatefulWidget {
  const ChatForm({super.key, required this.onSend, this.padding = EdgeInsets.zero, this.hint, this.autofocus = false});

  final ValueChanged<String> onSend;
  final EdgeInsets padding;
  final String? hint;
  final bool autofocus;

  @override
  State<ChatForm> createState() => _ChatFormState();
}

class _ChatFormState extends State<ChatForm> {
  final TextEditingController _text = TextEditingController();
  final FocusNode _focus = FocusNode();

  @override
  void dispose() {
    _text.dispose();
    _focus.dispose();
    super.dispose();
  }

  void _send() {
    final text = _text.text;
    if (text.trim().isEmpty) return;
    widget.onSend(text);
    _text.clear();
    _focus.requestFocus();
  }

  @override
  Widget build(BuildContext context) {
    final t = AppLocalizations.of(context);
    return Padding(
      padding: widget.padding,
      child: Row(
        children: [
          Expanded(
            child: TextField(
              key: const ValueKey('chat-input'),
              controller: _text,
              focusNode: _focus,
              autofocus: widget.autofocus,
              autocorrect: false,
              inputFormatters: [LengthLimitingTextInputFormatter(chatMax)],
              textInputAction: TextInputAction.send,
              style: Fonts.bodyStyle(size: 14.5),
              decoration: InputDecoration(hintText: widget.hint ?? t.chatPlaceholder),
              onSubmitted: (_) => _send(),
            ),
          ),
          const SizedBox(width: 8),
          // 보내기 counts as part of the field (cycle 163): on the web a
          // touch outside a TextField unfocuses it on pointer down, which
          // closed the text connection until the send refocused it; the
          // keyboard's resize in that gap set Flutter's view to the
          // keyboard-open height, and it stayed there once the keyboard
          // went down (the screen squeezed to the top, the rest empty).
          TextFieldTapRegion(
            child: RoomButton(
              key: const ValueKey('chat-send'),
              label: t.chatSend,
              kind: ButtonKind.primary,
              compact: true,
              onPressed: _send,
            ),
          ),
        ],
      ),
    );
  }
}

/// How long a floating line stays over the covers (D-145): fully shown,
/// then faded out over its last second.
const Duration floatLife = Duration(seconds: 6);

/// How many of the latest lines float over the covers at most.
const int floatMax = 3;

/// A choice room's latest chat over its covers (D-145): the last
/// [floatMax] lines not yet [gone], oldest on top, as translucent bubbles
/// (the newer, the more opaque). Each fades out [floatLife] after it first
/// shows and is then reported to [onGone]. Taps pass through to the tiles.
class FloatingChat extends StatelessWidget {
  const FloatingChat({super.key, required this.lines, required this.me, required this.gone, required this.onGone});

  final List<LogLine> lines;
  final String? me;
  final Set<LogLine> gone;
  final ValueChanged<LogLine> onGone;

  @override
  Widget build(BuildContext context) {
    final t = AppLocalizations.of(context);
    final latest = lines.length <= floatMax ? lines : lines.sublist(lines.length - floatMax);
    final shown = [
      for (final line in latest)
        if (!gone.contains(line)) line,
    ];
    return IgnorePointer(
      child: Column(
        key: const ValueKey('chat-float'),
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          for (final (i, line) in shown.indexed)
            _FloatLine(
              key: ObjectKey(line),
              text: _floatSpan(t, line),
              depth: shown.length - 1 - i,
              onGone: () => onGone(line),
            ),
        ],
      ),
    );
  }

  // A line in short: the name in its colour and what was said, or the
  // system line in its colour.
  TextSpan _floatSpan(AppLocalizations t, LogLine line) => switch (line) {
        SaidLine(:final name, :final text) => TextSpan(children: [
            TextSpan(
              text: name,
              style: Fonts.bodyStyle(size: 13, color: nameColour(name), weight: FontWeight.w700),
            ),
            TextSpan(text: ' $text'),
          ]),
        CorrectLine(:final winner, :final rank) => TextSpan(
            text: '$_check ${rank == null ? t.chatCorrectLine(winner) : t.chatCorrectRanked(winner, rank)}',
            style: Fonts.bodyStyle(size: 13, color: Palette.ok, weight: FontWeight.w700),
          ),
        NoticeLine(kind: NoticeKind.wrongPick) => TextSpan(
            text: t.chatWrongLine(line.value),
            style: Fonts.bodyStyle(size: 13, color: _wrongRed, weight: FontWeight.w700),
          ),
        NoticeLine() => TextSpan(text: noticeText(t, line), style: Fonts.bodyStyle(size: 13, color: Palette.dim)),
      };
}

// The floating bubbles' background and text opacity, newest ([depth] 0)
// first.
const List<double> _floatBack = [0.85, 0.72, 0.55];
const List<double> _floatInk = [1, 0.85, 0.6];

class _FloatLine extends StatefulWidget {
  const _FloatLine({super.key, required this.text, required this.depth, required this.onGone});

  final TextSpan text;
  final int depth;
  final VoidCallback onGone;

  @override
  State<_FloatLine> createState() => _FloatLineState();
}

class _FloatLineState extends State<_FloatLine> with SingleTickerProviderStateMixin {
  late final AnimationController _life = AnimationController(vsync: this, duration: floatLife)
    ..addStatusListener((status) {
      if (status == AnimationStatus.completed) widget.onGone();
    })
    ..forward();

  // Shown in full for the first five sixths, then faded out.
  late final Animation<double> _fade = ReverseAnimation(
    CurvedAnimation(parent: _life, curve: const Interval(5 / 6, 1)),
  );

  @override
  void dispose() {
    _life.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final depth = math.min(widget.depth, _floatBack.length - 1);
    return FadeTransition(
      opacity: _fade,
      child: Padding(
        padding: const EdgeInsets.only(top: 6),
        child: Container(
          padding: const EdgeInsets.symmetric(horizontal: 11, vertical: 4),
          decoration: BoxDecoration(
            color: Palette.bg.withValues(alpha: _floatBack[depth]),
            borderRadius: BorderRadius.circular(14),
          ),
          child: Opacity(
            opacity: _floatInk[depth],
            child: Text.rich(
              widget.text,
              maxLines: 2,
              overflow: TextOverflow.ellipsis,
              style: Fonts.bodyStyle(size: 13),
            ),
          ),
        ),
      ),
    );
  }
}

/// `.msg.sys`: a centred dim line between dashed rules; a wrong pick
/// (`.msg.wrong`) in its colour.
class _SystemLine extends StatelessWidget {
  const _SystemLine({required this.text, this.color = Palette.dim});

  final String text;
  final Color color;

  @override
  Widget build(BuildContext context) => Container(
        margin: const EdgeInsets.symmetric(vertical: 4),
        padding: const EdgeInsets.symmetric(vertical: 5),
        decoration: const BoxDecoration(
          border: Border.symmetric(horizontal: BorderSide(color: Palette.line)),
        ),
        child: Text(
          text,
          textAlign: TextAlign.center,
          style: Fonts.bodyStyle(
            size: 12.5,
            color: color,
            weight: color == Palette.dim ? FontWeight.w400 : FontWeight.w700,
          ),
        ),
      );
}

/// A correct answer's line (D-094), prominent (D-145): centred on a green
/// tint, in the display face.
class _CorrectLine extends StatelessWidget {
  const _CorrectLine({required this.text});

  final String text;

  @override
  Widget build(BuildContext context) => Container(
        margin: const EdgeInsets.only(top: 2, bottom: 8),
        padding: const EdgeInsets.all(8),
        decoration: BoxDecoration(
          color: Palette.ok.withValues(alpha: 0.12),
          borderRadius: BorderRadius.circular(12),
        ),
        child: Text(text, textAlign: TextAlign.center, style: Fonts.displayStyle(size: 16, color: Palette.ok, height: 1.25)),
      );
}

/// `.msg` (D-145): another player's line as the name colour's dot, the name
/// (key `chat-name`) and a bubble; the own line alone on the right, in the
/// accent colour with dark text.
class _Line extends StatelessWidget {
  const _Line({required this.name, required this.text, required this.mine});

  final String name;
  final String text;
  final bool mine;

  @override
  Widget build(BuildContext context) {
    final bubble = Flexible(
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 5),
        decoration: BoxDecoration(
          color: mine ? Palette.a1 : Palette.panel2,
          borderRadius: BorderRadius.circular(14),
        ),
        child: Semantics(
          label: '$name: $text',
          excludeSemantics: true,
          child: Text(text, style: Fonts.bodyStyle(size: 14.5, color: mine ? Palette.bg : Palette.ink)),
        ),
      ),
    );
    return Padding(
      padding: const EdgeInsets.only(bottom: 8),
      child: Row(
        mainAxisAlignment: mine ? MainAxisAlignment.end : MainAxisAlignment.start,
        crossAxisAlignment: CrossAxisAlignment.center,
        children: mine
            ? [bubble]
            : [
                Container(
                  width: 8,
                  height: 8,
                  decoration: BoxDecoration(color: nameColour(name), shape: BoxShape.circle),
                ),
                const SizedBox(width: 8),
                ExcludeSemantics(
                  child: ConstrainedBox(
                    constraints: const BoxConstraints(maxWidth: 110),
                    child: Text(
                      name,
                      key: const ValueKey('chat-name'),
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: Fonts.bodyStyle(size: 12, color: Palette.dim),
                    ),
                  ),
                ),
                const SizedBox(width: 8),
                bubble,
              ],
      ),
    );
  }
}

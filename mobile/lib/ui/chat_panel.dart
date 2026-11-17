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
/// places [ChatLog], [ChatPreview] and [ChatForm] itself (D-142).
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

/// The chat log: each said line as the name colour's dot and a bubble, the
/// own ones on the right; notices as centred system lines; a correct answer
/// as `✔ <name> 정답!`, a wrong pick in red. It fills the height it is
/// given and scrolls inside. It opens on the newest line, follows new lines
/// while it is at the bottom (and always after an own line), and keeps the
/// newest line in view when its height changes (the on-screen keyboard).
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
        child: ListView(
          key: const ValueKey('chat-log'),
          controller: _scroll,
          padding: widget.padding,
          children: [
            for (final line in widget.lines) logLine(t, line, me: widget.me),
          ],
        ),
      ),
    );
  }
}

/// A log line's widget; [compact] keeps it to one line (the preview).
Widget logLine(AppLocalizations t, LogLine line, {required String? me, bool compact = false}) => switch (line) {
      SaidLine() => _Line(name: line.name, text: line.text, mine: line.name == me, compact: compact),
      CorrectLine(:final winner, :final rank) => _SystemLine(
          text: '$_check ${rank == null ? t.chatCorrectLine(winner) : t.chatCorrectRanked(winner, rank)}',
          color: Palette.ok,
          compact: compact,
        ),
      NoticeLine(kind: NoticeKind.wrongPick) =>
        _SystemLine(text: t.chatWrongLine(line.value), color: _wrongRed, compact: compact),
      NoticeLine() => _SystemLine(text: noticeText(t, line), compact: compact),
    };

/// The chat form: the field (200 characters; a blank line is not sent) and
/// 보내기. Sending keeps the focus in the field.
class ChatForm extends StatefulWidget {
  const ChatForm({super.key, required this.onSend, this.padding = EdgeInsets.zero});

  final ValueChanged<String> onSend;
  final EdgeInsets padding;

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
              autocorrect: false,
              inputFormatters: [LengthLimitingTextInputFormatter(chatMax)],
              textInputAction: TextInputAction.send,
              style: Fonts.bodyStyle(size: 14.5),
              decoration: InputDecoration(hintText: t.chatPlaceholder),
              onSubmitted: (_) => _send(),
            ),
          ),
          const SizedBox(width: 8),
          RoomButton(
            key: const ValueKey('chat-send'),
            label: t.chatSend,
            kind: ButtonKind.primary,
            compact: true,
            onPressed: _send,
          ),
        ],
      ),
    );
  }
}

// The up-pointing triangle after 전체 (U+25B2).
final String _up = String.fromCharCode(0x25B2);

/// One compact line's height in [ChatPreview] (the bubble's 13 px text at
/// 1.5, rounded up, and its 2 px gap).
const double previewLineHeight = 22;

/// The least height of [ChatPreview]: two lines and its frame.
const double chatBoxMinHeight = 2 * previewLineHeight + 10;

/// The chat in short (D-142, a choice room and the game over): a box that
/// fills the height it is given with as many of the latest lines as fit,
/// the newest at the bottom, and 전체 ▲; a tap anywhere on it opens the
/// full chat.
class ChatPreview extends StatelessWidget {
  const ChatPreview({super.key, required this.lines, required this.me, required this.onOpen});

  final List<LogLine> lines;
  final String? me;
  final VoidCallback onOpen;

  @override
  Widget build(BuildContext context) {
    final t = AppLocalizations.of(context);
    return Semantics(
      button: true,
      label: t.chatLabel,
      child: Material(
        type: MaterialType.transparency,
        child: InkWell(
          key: const ValueKey('chat-preview'),
          onTap: onOpen,
          borderRadius: BorderRadius.circular(Palette.radius / 2),
          child: Ink(
            padding: const EdgeInsets.fromLTRB(10, 4, 6, 4),
            decoration: BoxDecoration(
              color: Palette.panelAlpha(0.9),
              border: Border.all(color: Palette.line),
              borderRadius: BorderRadius.circular(Palette.radius / 2),
            ),
            child: Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Expanded(
                  child: LayoutBuilder(
                    builder: (context, box) {
                      // Each compact line is at most [previewLineHeight] tall.
                      final fit = math.max(0, (box.maxHeight / previewLineHeight).floor());
                      final latest = lines.length <= fit ? lines : lines.sublist(lines.length - fit);
                      return SizedBox(
                        key: const ValueKey('chat-preview-lines'),
                        height: box.maxHeight,
                        child: ClipRect(
                          child: OverflowBox(
                            maxHeight: double.infinity,
                            alignment: Alignment.bottomCenter,
                            child: latest.isEmpty
                                ? Align(
                                    alignment: Alignment.bottomLeft,
                                    child: Text(t.chatLabel, style: Fonts.bodyStyle(size: 12.5, color: Palette.dim)),
                                  )
                                : Column(
                                    mainAxisSize: MainAxisSize.min,
                                    crossAxisAlignment: CrossAxisAlignment.stretch,
                                    children: [for (final line in latest) logLine(t, line, me: me, compact: true)],
                                  ),
                          ),
                        ),
                      );
                    },
                  ),
                ),
                const SizedBox(width: 6),
                Container(
                  key: const ValueKey('chat-all'),
                  padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
                  decoration: BoxDecoration(
                    border: Border.all(color: Palette.line),
                    borderRadius: BorderRadius.circular(Palette.radius),
                  ),
                  child: Text('${t.filterAll} $_up', style: Fonts.bodyStyle(size: 12, color: Palette.dim)),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

/// `.msg.sys`: a centred dim line between dashed rules; a correct line
/// (`.msg.sys.correct`) and a wrong pick (`.msg.wrong`) in their colours.
/// [compact]: one line without the rules.
class _SystemLine extends StatelessWidget {
  const _SystemLine({required this.text, this.color = Palette.dim, this.compact = false});

  final String text;
  final Color color;
  final bool compact;

  @override
  Widget build(BuildContext context) => Container(
        margin: EdgeInsets.symmetric(vertical: compact ? 1 : 4),
        padding: EdgeInsets.symmetric(vertical: compact ? 0 : 5),
        decoration: compact
            ? null
            : const BoxDecoration(
                border: Border.symmetric(horizontal: BorderSide(color: Palette.line)),
              ),
        child: Text(
          text,
          maxLines: compact ? 1 : null,
          overflow: compact ? TextOverflow.ellipsis : null,
          textAlign: TextAlign.center,
          style: Fonts.bodyStyle(
            size: 12.5,
            color: color,
            weight: color == Palette.dim ? FontWeight.w400 : FontWeight.w700,
          ),
        ),
      );
}

/// `.msg`: the name colour's dot and the line in a bubble (sunset), the own
/// ones reversed and tinted. [compact]: one smaller line.
class _Line extends StatelessWidget {
  const _Line({required this.name, required this.text, required this.mine, this.compact = false});

  final String name;
  final String text;
  final bool mine;
  final bool compact;

  @override
  Widget build(BuildContext context) {
    final dot = Container(
      width: 8,
      height: 8,
      decoration: BoxDecoration(color: nameColour(name), shape: BoxShape.circle),
    );
    final bubble = Flexible(
      child: Container(
        padding: EdgeInsets.symmetric(horizontal: compact ? 10 : 12, vertical: compact ? 0 : 5),
        decoration: BoxDecoration(
          color: mine ? Color.lerp(Palette.panel2, Palette.a1, 0.3) : Palette.panel2,
          borderRadius: mine
              ? const BorderRadius.only(
                  topLeft: Radius.circular(14),
                  topRight: Radius.circular(14),
                  bottomLeft: Radius.circular(14),
                  bottomRight: Radius.circular(4),
                )
              : const BorderRadius.only(
                  topLeft: Radius.circular(14),
                  topRight: Radius.circular(14),
                  bottomLeft: Radius.circular(4),
                  bottomRight: Radius.circular(14),
                ),
        ),
        child: Semantics(
          label: '$name: $text',
          excludeSemantics: true,
          child: compact
              ? Text(text, maxLines: 1, overflow: TextOverflow.ellipsis, style: Fonts.bodyStyle(size: 13))
              : Text(text, style: Fonts.bodyStyle(size: 14.5)),
        ),
      ),
    );
    return Padding(
      padding: EdgeInsets.only(bottom: compact ? 2 : 6),
      child: Row(
        mainAxisAlignment: mine ? MainAxisAlignment.end : MainAxisAlignment.start,
        crossAxisAlignment: CrossAxisAlignment.center,
        children: mine ? [bubble, const SizedBox(width: 8), dot] : [dot, const SizedBox(width: 8), bubble],
      ),
    );
  }
}

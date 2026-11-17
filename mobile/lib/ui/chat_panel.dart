import 'package:flutter/services.dart';
import 'package:material_ui/material_ui.dart';
import '../app/app_controller.dart';
import '../app/game_model.dart';
import '../l10n/app_localizations.dart';
import 'palette.dart';
import 'widgets.dart';

/// The log's height on a phone; it scrolls inside.
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

/// The lobby and game chat (GameScreen.jsx Frame's aside.chat with ChatLog
/// and ChatForm, the sunset look): 채팅 with the connected count, the log
/// (each said line as the name colour's dot and a bubble, the own ones on
/// the right; notices as centred system lines; a correct answer as
/// `✔ <name> 정답!`, a wrong pick in red), and the form, absent without
/// [onSend] (the game-over screen). The log follows new lines while it is
/// scrolled to the bottom, and always after an own line. A blank line is
/// not sent; the field takes 200 characters.
class ChatPanel extends StatefulWidget {
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
  State<ChatPanel> createState() => _ChatPanelState();
}

class _ChatPanelState extends State<ChatPanel> {
  final TextEditingController _text = TextEditingController();
  final ScrollController _scroll = ScrollController();
  final FocusNode _focus = FocusNode();

  @override
  void didUpdateWidget(ChatPanel old) {
    super.didUpdateWidget(old);
    if (widget.lines.length == old.lines.length) return;
    final last = widget.lines.isEmpty ? null : widget.lines.last;
    final mine = last is SaidLine && last.name == widget.me;
    final atEnd = !_scroll.hasClients || _scroll.position.extentAfter < 40;
    if (atEnd || mine) {
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (_scroll.hasClients) _scroll.jumpTo(_scroll.position.maxScrollExtent);
      });
    }
  }

  @override
  void dispose() {
    _text.dispose();
    _scroll.dispose();
    _focus.dispose();
    super.dispose();
  }

  void _send() {
    final text = _text.text;
    final send = widget.onSend;
    if (send == null || text.trim().isEmpty) return;
    send(text);
    _text.clear();
    _focus.requestFocus();
  }

  Widget _logLine(AppLocalizations t, LogLine line) => switch (line) {
        SaidLine() => _Line(name: line.name, text: line.text, mine: line.name == widget.me),
        CorrectLine(:final winner, :final rank) => _SystemLine(
            text: '$_check ${rank == null ? t.chatCorrectLine(winner) : t.chatCorrectRanked(winner, rank)}',
            color: Palette.ok,
          ),
        NoticeLine(kind: NoticeKind.wrongPick) => _SystemLine(text: t.chatWrongLine(line.value), color: _wrongRed),
        NoticeLine() => _SystemLine(text: noticeText(t, line)),
      };

  @override
  Widget build(BuildContext context) {
    final t = AppLocalizations.of(context);
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
                  Container(
                    width: 8,
                    height: 8,
                    decoration: const BoxDecoration(color: Color(0xFFFF4D6D), shape: BoxShape.circle),
                  ),
                  const SizedBox(width: 8),
                  Text(t.chatLive(widget.online), style: Fonts.bodyStyle(size: 12.5, color: Palette.dim)),
                ],
              ),
            ),
            SizedBox(
              height: _logHeight,
              child: ListView(
                key: const ValueKey('chat-log'),
                controller: _scroll,
                padding: const EdgeInsets.symmetric(horizontal: 18, vertical: 12),
                children: [
                  for (final line in widget.lines) _logLine(t, line),
                ],
              ),
            ),
            if (widget.onSend != null)
              Padding(
                padding: const EdgeInsets.fromLTRB(18, 10, 18, 16),
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
                    RoomButton(key: const ValueKey('chat-send'), label: t.chatSend, kind: ButtonKind.primary, compact: true, onPressed: _send),
                  ],
                ),
              ),
          ],
        ),
      ),
    );
  }
}

/// `.msg.sys`: a centred dim line between dashed rules; a correct line
/// (`.msg.sys.correct`) and a wrong pick (`.msg.wrong`) in their colours.
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

/// `.msg`: the name colour's dot and the line in a bubble (sunset), the own
/// ones reversed and tinted.
class _Line extends StatelessWidget {
  const _Line({required this.name, required this.text, required this.mine});

  final String name;
  final String text;
  final bool mine;

  @override
  Widget build(BuildContext context) {
    final dot = Container(
      width: 8,
      height: 8,
      decoration: BoxDecoration(color: nameColour(name), shape: BoxShape.circle),
    );
    final bubble = Flexible(
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 5),
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
          child: Text(text, style: Fonts.bodyStyle(size: 14.5)),
        ),
      ),
    );
    return Padding(
      padding: const EdgeInsets.only(bottom: 6),
      child: Row(
        mainAxisAlignment: mine ? MainAxisAlignment.end : MainAxisAlignment.start,
        crossAxisAlignment: CrossAxisAlignment.center,
        children: mine ? [bubble, const SizedBox(width: 8), dot] : [dot, const SizedBox(width: 8), bubble],
      ),
    );
  }
}

import 'package:flutter/services.dart';
import 'package:material_ui/material_ui.dart';
import 'package:songrush_core/songrush_core.dart';

import '../app/app_controller.dart';
import '../l10n/app_localizations.dart';
import 'palette.dart';
import 'widgets.dart';

/// The log's height on a phone; it scrolls inside.
const double _logHeight = 260;

/// The lobby chat (GameScreen.jsx Frame's aside.chat with ChatLog and
/// ChatForm, the sunset look): 채팅 with the connected count, the log (the
/// join line, then each line as the name colour's dot and a bubble, the
/// own ones on the right), and the form. The log follows new lines while it
/// is scrolled to the bottom. A blank line is not sent; the field takes 200
/// characters.
class ChatPanel extends StatefulWidget {
  const ChatPanel({
    super.key,
    required this.lines,
    required this.me,
    required this.roomCode,
    required this.online,
    required this.onSend,
  });

  final List<ChatLine> lines;
  final String? me;
  final String roomCode;
  final int online;
  final ValueChanged<String> onSend;

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
    final mine = last != null && last.name == widget.me;
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
    if (text.trim().isEmpty) return;
    widget.onSend(text);
    _text.clear();
    _focus.requestFocus();
  }

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
                  _SystemLine(text: t.chatLobbyJoined(widget.roomCode)),
                  for (final line in widget.lines) _Line(line: line, mine: line.name == widget.me),
                ],
              ),
            ),
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

/// `.msg.sys`: a centred dim line between dashed rules.
class _SystemLine extends StatelessWidget {
  const _SystemLine({required this.text});

  final String text;

  @override
  Widget build(BuildContext context) => Container(
        margin: const EdgeInsets.symmetric(vertical: 4),
        padding: const EdgeInsets.symmetric(vertical: 5),
        decoration: const BoxDecoration(
          border: Border.symmetric(horizontal: BorderSide(color: Palette.line)),
        ),
        child: Text(text, textAlign: TextAlign.center, style: Fonts.bodyStyle(size: 12.5, color: Palette.dim)),
      );
}

/// `.msg`: the name colour's dot and the line in a bubble (sunset), the own
/// ones reversed and tinted.
class _Line extends StatelessWidget {
  const _Line({required this.line, required this.mine});

  final ChatLine line;
  final bool mine;

  @override
  Widget build(BuildContext context) {
    final dot = Container(
      width: 8,
      height: 8,
      decoration: BoxDecoration(color: nameColour(line.name), shape: BoxShape.circle),
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
          label: '${line.name}: ${line.text}',
          excludeSemantics: true,
          child: Text(line.text, style: Fonts.bodyStyle(size: 14.5)),
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

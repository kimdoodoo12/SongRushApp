import 'package:flutter/services.dart';
import 'package:material_ui/material_ui.dart';

import '../app/app_controller.dart';
import '../l10n/app_localizations.dart';
import 'nickname_dialog.dart';
import 'palette.dart';
import 'texts.dart';
import 'widgets.dart';

/// The start screen (StartScreen.jsx): the marquee, one card with 방 만들기,
/// then 방 코드 with 참가 (a full 5-character code; Enter in the field does
/// what 참가 does), the last problem, and the footer. 방 만들기 and 참가 open
/// the nickname dialog; a `/r/<CODE>` link prefills the code and opens it.
class StartScreen extends StatefulWidget {
  const StartScreen({super.key, required this.controller});

  final AppController controller;

  @override
  State<StartScreen> createState() => _StartScreenState();
}

class _StartScreenState extends State<StartScreen> {
  late final TextEditingController _code = TextEditingController(text: widget.controller.initialCode);

  @override
  void initState() {
    super.initState();
    _code.addListener(() => setState(() {}));
  }

  @override
  void dispose() {
    _code.dispose();
    super.dispose();
  }

  bool get _canJoin => _code.text.length == codeLength;

  void _join() {
    if (_canJoin) widget.controller.openJoinDialog(_code.text);
  }

  @override
  Widget build(BuildContext context) {
    final t = AppLocalizations.of(context);
    final c = widget.controller;
    final problem = c.startError;
    return Stack(
      children: [
        PageFrame(
          header: const RoomHeader(),
          children: [
            Marquee(t.appTitle),
            const SizedBox(height: 20),
            Panel(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  RoomButton(
                    key: const ValueKey('create'),
                    label: t.startCreateRoom,
                    kind: ButtonKind.go,
                    onPressed: c.openCreateDialog,
                  ),
                  const SizedBox(height: 16),
                  OptHint(t.startOrCode),
                  const SizedBox(height: 16),
                  Row(
                    crossAxisAlignment: CrossAxisAlignment.end,
                    children: [
                      Expanded(
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            OptLabel(t.startRoomCode),
                            const SizedBox(height: 6),
                            TextField(
                              key: const ValueKey('code'),
                              controller: _code,
                              autocorrect: false,
                              enableSuggestions: false,
                              textCapitalization: TextCapitalization.characters,
                              textInputAction: TextInputAction.go,
                              inputFormatters: [_UpperCaseFormatter(), LengthLimitingTextInputFormatter(codeLength)],
                              style: Fonts.monoStyle(size: 16, color: Palette.ink, letterSpacingEm: 0.25),
                              onSubmitted: (_) => _join(),
                            ),
                          ],
                        ),
                      ),
                      const SizedBox(width: 8),
                      RoomButton(key: const ValueKey('join'), label: t.startJoin, onPressed: _canJoin ? _join : null),
                    ],
                  ),
                  if (c.dialog == null && problem != null) ...[
                    const SizedBox(height: 12),
                    WarnText(problemText(t, problem)),
                  ],
                ],
              ),
            ),
            Footer(language: c.language, onLanguage: c.setLanguage),
          ],
        ),
        if (c.dialog != null) NicknameDialog(controller: c),
      ],
    );
  }
}

/// The code field upper-cases what is typed, as the web's onChange does.
class _UpperCaseFormatter extends TextInputFormatter {
  @override
  TextEditingValue formatEditUpdate(TextEditingValue oldValue, TextEditingValue newValue) =>
      newValue.copyWith(text: newValue.text.toUpperCase());
}

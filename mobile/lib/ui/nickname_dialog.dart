import 'package:flutter/services.dart';
import 'package:material_ui/material_ui.dart';

import '../app/app_controller.dart';
import '../l10n/app_localizations.dart';
import 'palette.dart';
import 'texts.dart';
import 'widgets.dart';

/// The nickname dialog (NicknameDialog.jsx, D-123, D-127): 방 만들기 and
/// 참가 open it. A modal card over a barrier; the field has the focus on
/// open. Escape, 취소 or a tap on the barrier cancel; Enter or 확인 confirm
/// with the trimmed name. A blank name, or an attempt connecting, keeps 확인
/// disabled. The attempt's error shows under the field.
class NicknameDialog extends StatefulWidget {
  const NicknameDialog({super.key, required this.controller});

  final AppController controller;

  @override
  State<NicknameDialog> createState() => _NicknameDialogState();
}

class _NicknameDialogState extends State<NicknameDialog> {
  late final TextEditingController _name = TextEditingController(text: widget.controller.dialogName);

  @override
  void initState() {
    super.initState();
    _name.addListener(() {
      widget.controller.setDialogName(_name.text);
      setState(() {});
    });
  }

  @override
  void dispose() {
    _name.dispose();
    super.dispose();
  }

  bool get _canConfirm => !widget.controller.joining && _name.text.trim().isNotEmpty;

  void _confirm() {
    if (_canConfirm) widget.controller.confirmNickname(_name.text);
  }

  @override
  Widget build(BuildContext context) {
    final t = AppLocalizations.of(context);
    final c = widget.controller;
    final dialog = c.dialog!;
    final problem = c.startError;
    final sub = dialog.mode == DialogMode.join ? t.startJoinTitle(dialog.code) : t.startCreateRoom;
    return CallbackShortcuts(
      bindings: {const SingleActivator(LogicalKeyboardKey.escape): c.cancelDialog},
      child: Stack(
        children: [
          Positioned.fill(
            child: GestureDetector(
              key: const ValueKey('nick-backdrop'),
              behavior: HitTestBehavior.opaque,
              onTap: c.cancelDialog,
              child: ColoredBox(color: Palette.bg.withValues(alpha: 0.72)),
            ),
          ),
          Center(
            child: SingleChildScrollView(
              padding: const EdgeInsets.all(12),
              child: ConstrainedBox(
                constraints: const BoxConstraints(maxWidth: 384),
                child: Material(
                  type: MaterialType.transparency,
                  child: Semantics(
                    scopesRoute: true,
                    namesRoute: true,
                    explicitChildNodes: true,
                    label: t.startNickname,
                    child: Panel(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.stretch,
                        mainAxisSize: MainAxisSize.min,
                        children: [
                          Text(t.startNickname, style: Fonts.displayStyle(size: 21)),
                          Text(sub, key: const ValueKey('nick-sub'), style: Fonts.bodyStyle(size: 13, color: Palette.dim)),
                          const SizedBox(height: 16),
                          OptLabel(t.startNickname),
                          const SizedBox(height: 6),
                          TextField(
                            key: const ValueKey('nickname'),
                            controller: _name,
                            autofocus: true,
                            autofillHints: const [AutofillHints.nickname],
                            inputFormatters: [LengthLimitingTextInputFormatter(nicknameMax)],
                            textInputAction: TextInputAction.done,
                            style: Fonts.bodyStyle(size: 15),
                            onSubmitted: (_) => _confirm(),
                          ),
                          if (dialog.attempted && problem != null) ...[
                            const SizedBox(height: 12),
                            WarnText(problemText(t, problem)),
                          ],
                          const SizedBox(height: 16),
                          Row(
                            mainAxisAlignment: MainAxisAlignment.end,
                            children: [
                              RoomButton(key: const ValueKey('nick-cancel'), label: t.startCancel, onPressed: c.cancelDialog),
                              const SizedBox(width: 8),
                              RoomButton(
                                key: const ValueKey('nick-confirm'),
                                label: t.startConfirm,
                                kind: ButtonKind.primary,
                                onPressed: _canConfirm ? _confirm : null,
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
    );
  }
}

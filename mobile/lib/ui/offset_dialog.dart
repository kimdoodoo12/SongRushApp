import 'package:flutter/services.dart';
import 'package:material_ui/material_ui.dart';
import 'package:songrush_core/songrush_core.dart';

import '../app/app_controller.dart';
import '../l10n/app_localizations.dart';
import 'palette.dart';
import 'widgets.dart';

/// The offset moves in steps of this many ms.
const int offsetStepMs = 10;

// The minus sign of a negative offset (U+2212), as the lobby's stepper.
final String _minus = String.fromCharCode(0x2212);

/// [ms] as the setting shows it: +20ms, 0ms, −30ms.
String offsetLabel(AppLocalizations t, int ms) =>
    t.offsetValue(ms > 0 ? '+$ms' : ms < 0 ? '$_minus${-ms}' : '0');

/// The sound offset button: a compact `.btn` with the tune icon, named 싱크
/// by its tooltip and semantics, so it fits beside 나가기 on a 360 px phone.
/// Opens [SoundOffsetDialog]. Not placed in any header for now (cycle 152).
class OffsetButton extends StatelessWidget {
  const OffsetButton({super.key, required this.controller});

  final AppController controller;

  @override
  Widget build(BuildContext context) {
    final label = AppLocalizations.of(context).offsetOpen;
    return Tooltip(
      message: label,
      child: Semantics(
        label: label,
        button: true,
        excludeSemantics: true,
        onTap: controller.openOffset,
        child: TextButton(
          key: const ValueKey('offset-open'),
          onPressed: controller.openOffset,
          style: ButtonStyle(
            padding: const WidgetStatePropertyAll(EdgeInsets.symmetric(horizontal: 6, vertical: 6)),
            minimumSize: const WidgetStatePropertyAll(Size(0, 0)),
            tapTargetSize: MaterialTapTargetSize.shrinkWrap,
            shape: WidgetStatePropertyAll(RoundedRectangleBorder(borderRadius: BorderRadius.circular(Palette.radius))),
            side: const WidgetStatePropertyAll(BorderSide(color: Palette.line)),
            backgroundColor: WidgetStatePropertyAll(Palette.panelAlpha(0.8)),
            foregroundColor: const WidgetStatePropertyAll(Palette.ink),
            overlayColor: WidgetStatePropertyAll(Palette.a2.withValues(alpha: 0.18)),
          ),
          child: const Icon(Icons.tune, size: 18),
        ),
      ),
    );
  }
}

/// The manual sound offset (cycle 142's `adj`; the web has no such
/// setting): −500..+500 ms in steps of 10 with a slider and −/+ buttons,
/// back to 0, and 닫기 (also Escape or a tap outside). Each change is stored
/// at once and used from the next song.
class SoundOffsetDialog extends StatelessWidget {
  const SoundOffsetDialog({super.key, required this.controller});

  final AppController controller;

  @override
  Widget build(BuildContext context) {
    final t = AppLocalizations.of(context);
    final c = controller;
    final ms = c.manualOffsetMs;
    void set(int value) => c.setManualOffset((value / offsetStepMs).round() * offsetStepMs);
    return CallbackShortcuts(
      bindings: {const SingleActivator(LogicalKeyboardKey.escape): c.closeOffset},
      child: FocusScope(
        autofocus: true,
        child: Stack(
          children: [
            ModalBarrier(
              key: const ValueKey('offset-barrier'),
              color: const Color(0x99000000),
              onDismiss: c.closeOffset,
              semanticsLabel: t.offsetClose,
            ),
            Center(
              child: Padding(
                padding: const EdgeInsets.all(16),
                child: ConstrainedBox(
                  constraints: const BoxConstraints(maxWidth: 384),
                  child: Material(
                    key: const ValueKey('offset-dialog'),
                    color: Palette.panel,
                    shape: RoundedRectangleBorder(
                      borderRadius: BorderRadius.circular(Palette.radius),
                      side: const BorderSide(color: Palette.line),
                    ),
                    child: Semantics(
                      scopesRoute: true,
                      namesRoute: true,
                      explicitChildNodes: true,
                      label: t.offsetTitle,
                      child: Padding(
                        padding: const EdgeInsets.fromLTRB(22, 20, 22, 18),
                        child: Column(
                          mainAxisSize: MainAxisSize.min,
                          crossAxisAlignment: CrossAxisAlignment.stretch,
                          children: [
                            Semantics(header: true, child: Text(t.offsetTitle, style: Fonts.displayStyle(size: 21))),
                            const SizedBox(height: 8),
                            OptHint(t.offsetHint),
                            const SizedBox(height: 14),
                            Row(
                              children: [
                                Semantics(
                                  label: t.offsetEarlier,
                                  child: RoomButton(
                                    key: const ValueKey('offset-earlier'),
                                    label: _minus,
                                    compact: true,
                                    onPressed: ms > manualOffsetMinMs ? () => set(ms - offsetStepMs) : null,
                                  ),
                                ),
                                Expanded(
                                  child: Text(
                                    offsetLabel(t, ms),
                                    key: const ValueKey('offset-value'),
                                    textAlign: TextAlign.center,
                                    style: Fonts.monoStyle(size: 18, color: Palette.ink, semiBold: true),
                                  ),
                                ),
                                Semantics(
                                  label: t.offsetLater,
                                  child: RoomButton(
                                    key: const ValueKey('offset-later'),
                                    label: '+',
                                    compact: true,
                                    onPressed: ms < manualOffsetMaxMs ? () => set(ms + offsetStepMs) : null,
                                  ),
                                ),
                              ],
                            ),
                            Slider(
                              key: const ValueKey('offset-slider'),
                              value: ms.toDouble(),
                              min: manualOffsetMinMs.toDouble(),
                              max: manualOffsetMaxMs.toDouble(),
                              divisions: (manualOffsetMaxMs - manualOffsetMinMs) ~/ offsetStepMs,
                              label: offsetLabel(t, ms),
                              semanticFormatterCallback: (v) => offsetLabel(t, v.round()),
                              activeColor: Palette.a1,
                              inactiveColor: Palette.line,
                              onChanged: (v) => set(v.round()),
                            ),
                            const SizedBox(height: 8),
                            Row(
                              mainAxisAlignment: MainAxisAlignment.end,
                              children: [
                                RoomButton(
                                  key: const ValueKey('offset-reset'),
                                  label: t.offsetReset,
                                  onPressed: ms == 0 ? null : () => set(0),
                                ),
                                const SizedBox(width: 8),
                                RoomButton(
                                  key: const ValueKey('offset-close'),
                                  label: t.offsetClose,
                                  kind: ButtonKind.primary,
                                  autofocus: true,
                                  onPressed: c.closeOffset,
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

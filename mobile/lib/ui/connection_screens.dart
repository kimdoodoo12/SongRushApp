import 'package:material_ui/material_ui.dart';

import '../app/app_controller.dart';
import '../l10n/app_localizations.dart';
import 'palette.dart';
import 'widgets.dart';

/// A reload rejoin before its `joined` (state.js `connecting` with
/// `rejoin`): the game frame with the room code, the reconnecting line and
/// the connecting hint. `rejoin-failed` sends the player to the start
/// screen with its text; `joined` and the snapshot to the lobby or the game.
class RejoiningScreen extends StatelessWidget {
  const RejoiningScreen({super.key, required this.controller});

  final AppController controller;

  @override
  Widget build(BuildContext context) {
    final t = AppLocalizations.of(context);
    final c = controller;
    return PageFrame(
      header: RoomHeader(code: c.rejoiningCode),
      children: [
        WarnText(t.appReconnecting),
        const SizedBox(height: 12),
        Panel(
          child: Text(t.stageConnecting, key: const ValueKey('hint'), style: Fonts.displayStyle(size: 22, height: 1.3)),
        ),
        Footer(language: c.language, onLanguage: c.setLanguage),
      ],
    );
  }
}

/// The disconnected screen (GameScreen.jsx `screen === 'disconnected'`):
/// the own final score when the game had ended, 서버와 연결이 끊어졌습니다.
/// (or the replaced text after a 4001), and 처음으로 back to the start.
class DisconnectedScreen extends StatelessWidget {
  const DisconnectedScreen({super.key, required this.controller});

  final AppController controller;

  @override
  Widget build(BuildContext context) {
    final t = AppLocalizations.of(context);
    final c = controller;
    final lost = c.lostRoom;
    if (lost == null) return const SizedBox.shrink();
    final score = lost.finalScore;
    return PageFrame(
      header: RoomHeader(code: lost.code),
      children: [
        Panel(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              if (score != null) ...[
                Text(t.scoreFinal(score), style: Fonts.bodyStyle(size: 13.5, color: Palette.dim)),
                const SizedBox(height: 6),
              ],
              Semantics(
                liveRegion: true,
                child: Text(
                  lost.replaced ? t.gameReplaced : t.gameDisconnected,
                  key: const ValueKey('lost'),
                  style: Fonts.displayStyle(size: 22, height: 1.3, color: Palette.dim),
                ),
              ),
              const SizedBox(height: 16),
              RoomButton(
                key: const ValueKey('reset'),
                label: t.gameReset,
                kind: ButtonKind.primary,
                onPressed: c.reset,
              ),
            ],
          ),
        ),
        Footer(language: c.language, onLanguage: c.setLanguage),
      ],
    );
  }
}

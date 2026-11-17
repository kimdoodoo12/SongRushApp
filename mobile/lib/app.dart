import 'package:cupertino_ui/cupertino_ui.dart' show GlobalCupertinoLocalizations;
import 'package:flutter_localizations/flutter_localizations.dart' show GlobalWidgetsLocalizations;
import 'package:material_ui/material_ui.dart';

import 'app/app_controller.dart';
import 'app/language.dart';
import 'l10n/app_localizations.dart';
import 'ui/connection_screens.dart';
import 'ui/game_screen.dart';
import 'ui/lobby_screen.dart';
import 'ui/offset_dialog.dart';
import 'ui/palette.dart';
import 'ui/start_screen.dart';

/// The app: the [AppController]'s language and screen. The material and
/// cupertino delegates are material_ui's and cupertino_ui's (the generated
/// `AppLocalizations.localizationsDelegates` names the SDK's deprecated
/// material ones, which the material_ui widgets do not read).
///
/// The page's visibility reaches the controller through an
/// [AppLifecycleListener]: on the web Flutter reports the document's
/// `visibilitychange` as the app's lifecycle (hidden, then shown), and the
/// same listener serves the Android app later, so there is one path for
/// both. Going hidden does nothing on purpose (the socket stays while the
/// browser allows and the audio may stop); [AppController.pageShown] runs
/// on the return.
class SongRushApp extends StatefulWidget {
  const SongRushApp({super.key, required this.controller});

  final AppController controller;

  @override
  State<SongRushApp> createState() => _SongRushAppState();
}

class _SongRushAppState extends State<SongRushApp> {
  late final AppLifecycleListener _lifecycle;

  @override
  void initState() {
    super.initState();
    _lifecycle = AppLifecycleListener(onShow: () => widget.controller.pageShown());
  }

  @override
  void dispose() {
    _lifecycle.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final controller = widget.controller;
    return ListenableBuilder(
      listenable: controller,
      builder: (context, _) => MaterialApp(
        onGenerateTitle: (context) => AppLocalizations.of(context).appTitle,
        debugShowCheckedModeBanner: false,
        locale: Locale(controller.language),
        supportedLocales: [for (final code in languages) Locale(code)],
        localizationsDelegates: const [
          AppLocalizations.delegate,
          GlobalMaterialLocalizations.delegate,
          GlobalCupertinoLocalizations.delegate,
          GlobalWidgetsLocalizations.delegate,
        ],
        theme: songRushTheme(),
        home: _home(controller),
      ),
    );
  }

  // The screen, and over a room's screen the sound offset setting.
  static Widget _home(AppController controller) {
    final screen = switch (controller.screen) {
      Screen.start => StartScreen(controller: controller),
      Screen.rejoining => RejoiningScreen(controller: controller),
      Screen.lobby => LobbyScreen(controller: controller),
      Screen.game || Screen.over => GameScreen(controller: controller),
      Screen.disconnected => DisconnectedScreen(controller: controller),
    };
    final room = switch (controller.screen) {
      Screen.lobby || Screen.game || Screen.over => true,
      _ => false,
    };
    // Always a Stack, so opening the setting keeps the screen's state.
    return Stack(
      children: [
        Positioned.fill(child: screen),
        if (room && controller.offsetOpen) Positioned.fill(child: SoundOffsetDialog(controller: controller)),
      ],
    );
  }
}

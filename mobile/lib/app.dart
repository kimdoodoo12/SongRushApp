import 'package:cupertino_ui/cupertino_ui.dart' show GlobalCupertinoLocalizations;
import 'package:flutter/foundation.dart';
import 'package:flutter_localizations/flutter_localizations.dart' show GlobalWidgetsLocalizations;
import 'package:material_ui/material_ui.dart';

import 'app/app_controller.dart';
import 'app/language.dart';
import 'l10n/app_localizations.dart';
import 'platform/keyboard_inset.dart';
import 'ui/connection_screens.dart';
import 'ui/game_screen.dart';
import 'ui/lobby_screen.dart';
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
///
/// On the web the on-screen keyboard's height comes from the browser
/// ([browserKeyboardInset], cycle 161) and replaces the bottom view inset
/// of every screen and dialog: Chrome on Android overlays the keyboard
/// without resizing the page, and Flutter's own inset stayed up after the
/// keyboard was hidden. Every Scaffold's resizeToAvoidBottomInset and the
/// game screen's keyboard rules read it. Off the web Flutter's own inset
/// stays. [keyboardInset] replaces the browser's (the tests).
///
/// The back button (cycle 164) goes to [AppController.handleBack] through a
/// PopScope on the one route. On the web the engine keeps an entry of its
/// own on top of the page's history (flutter_web_sdk navigation/history.dart
/// SingleEntryBrowserHistory, chosen by the Navigator of MaterialApp): the
/// browser's back lands on the entry below, the engine pushes its entry
/// again (the URL stays) and sends `popRoute`, which the Navigator offers
/// to this PopScope. Leaving the site is [SystemNavigator.pop] (main.dart),
/// which the engine turns into a history back past both entries. A route
/// pushed over the screen (a dropdown's menu) is popped first by the
/// Navigator itself.
class SongRushApp extends StatefulWidget {
  const SongRushApp({super.key, required this.controller, this.keyboardInset});

  final AppController controller;
  final ValueListenable<double>? keyboardInset;

  @override
  State<SongRushApp> createState() => _SongRushAppState();
}

class _SongRushAppState extends State<SongRushApp> {
  late final AppLifecycleListener _lifecycle;
  late final ValueListenable<double>? _keyboard = widget.keyboardInset ?? browserKeyboardInset();

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
        builder: _withKeyboard,
        home: PopScope(
          canPop: false,
          onPopInvokedWithResult: (didPop, _) {
            if (!didPop) controller.handleBack();
          },
          child: _home(controller),
        ),
      ),
    );
  }

  Widget _withKeyboard(BuildContext context, Widget? child) {
    final keyboard = _keyboard;
    final app = child ?? const SizedBox.shrink();
    if (keyboard == null) return app;
    return ValueListenableBuilder<double>(
      valueListenable: keyboard,
      child: app,
      builder: (context, inset, app) {
        // Cycle 162: the applied inset, for the ?debug=1 overlay (web only),
        // recorded after the frame so the build stays pure.
        WidgetsBinding.instance.addPostFrameCallback((_) => reportAppliedKeyboardInset(inset));
        return MediaQuery(
          data: MediaQuery.of(context).copyWith(viewInsets: EdgeInsets.only(bottom: inset)),
          child: app!,
        );
      },
    );
  }

  // The screen. The sound offset setting (ui/offset_dialog.dart) is not
  // shown for now (cycle 152): nothing opens it, and a stored offset still
  // applies.
  static Widget _home(AppController controller) => switch (controller.screen) {
        Screen.start => StartScreen(controller: controller),
        Screen.rejoining => RejoiningScreen(controller: controller),
        Screen.lobby => LobbyScreen(controller: controller),
        Screen.game || Screen.over => GameScreen(controller: controller),
        Screen.disconnected => DisconnectedScreen(controller: controller),
      };
}

import 'package:cupertino_ui/cupertino_ui.dart' show GlobalCupertinoLocalizations;
import 'package:flutter_localizations/flutter_localizations.dart' show GlobalWidgetsLocalizations;
import 'package:material_ui/material_ui.dart';

import 'app/app_controller.dart';
import 'app/language.dart';
import 'l10n/app_localizations.dart';
import 'ui/lobby_screen.dart';
import 'ui/palette.dart';
import 'ui/start_screen.dart';

/// The app: the [AppController]'s language and screen. The material and
/// cupertino delegates are material_ui's and cupertino_ui's (the generated
/// `AppLocalizations.localizationsDelegates` names the SDK's deprecated
/// material ones, which the material_ui widgets do not read).
class SongRushApp extends StatelessWidget {
  const SongRushApp({super.key, required this.controller});

  final AppController controller;

  @override
  Widget build(BuildContext context) {
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
        home: switch (controller.screen) {
          Screen.start => StartScreen(controller: controller),
          Screen.lobby => LobbyScreen(controller: controller),
        },
      ),
    );
  }
}

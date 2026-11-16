import 'package:material_ui/material_ui.dart';
import 'package:songrush_core/songrush_core.dart';

void main() {
  runApp(const SongRushApp());
}

class SongRushApp extends StatelessWidget {
  const SongRushApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'SongRush',
      theme: ThemeData(
        colorScheme: ColorScheme.fromSeed(seedColor: Colors.deepPurple),
      ),
      home: const StartScreen(),
    );
  }
}

/// Placeholder start screen; the real screens follow the owner's mockup (F-M4a).
class StartScreen extends StatelessWidget {
  const StartScreen({super.key});

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: Center(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Text('SongRush', style: Theme.of(context).textTheme.headlineMedium),
            const SizedBox(height: 8),
            Text('$minSongs-$maxSongs songs'),
          ],
        ),
      ),
    );
  }
}

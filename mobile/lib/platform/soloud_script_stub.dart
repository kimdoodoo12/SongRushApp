/// No script to load here.
Future<void> loadSoloudScript() async {}

/// No page here: nothing to preload.
void preloadSoloudScriptOnFirstTouch() {}

/// No browser AudioContext here: the sound is never held off.
Future<bool> soundContextRunning() async => true;

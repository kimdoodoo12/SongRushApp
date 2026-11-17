# 150 · F-M5 mobile reconnect and lifecycle (web first)

## Goal
The Flutter client survives what phones do to a web page: dropped connections, a reload, going to the background and coming back, and a page opened before the user has tapped (audio locked), following design 141's lifecycle rule; plus the manual sound offset setting from cycle 142 in the UI.

## Acceptance criteria
1. Reconnect UI: while the session is `reconnecting` the lobby and game screens show the web's reconnecting banner; when the core gives up or the server refuses (4001 replaced, room closed, rejoin failed) the app shows the web's disconnected/replaced screen with a button back to the start (mirror the React client's texts and behaviour).
2. Reload rejoin: on start, a stored `songrush.rejoin` token (sessionStorage, the web's format) resumes the room with `Session.resume`, as the web does on reload; the snapshot puts the player back in the lobby or the game, mid-song late start and reveal window included (the core already handles the audio; the screens must follow the snapshot).
3. Background and return (web: `visibilitychange`; app lifecycle via `AppLifecycleListener`): going hidden lets audio stop; on return, if the socket is gone, the session rejoins with the token and late-starts from the snapshot; if it is still open nothing changes except audio resuming at the right position (the drift check corrects it). Decide by the core's state, not by timers.
4. Sound unlock (web): if audio is still locked when a song starts (no tap yet, e.g. after a reload rejoin), show the web's "tap to turn on sound" prompt; one tap unlocks and the current song joins at the right position.
5. Screen awake during a game (web: Screen Wake Lock API where supported, released on game over or leaving; no-op elsewhere).
6. Manual sound offset: a small setting (−500..+500 ms, step 10, default 0) reachable from the lobby and game header, stored in localStorage through `PlaybackSettingsStore`, applied from the next song (core behaviour), with ko/en/ja texts (new keys, written in the same style as the web's; the web has no such setting).
7. Tests on fakes for 1-6 (app widget tests in a copy without flutter_soloud, as in cycles 148-149; core tests if core changes); `flutter analyze` clean, `flutter build web` succeeds, core `dart test` passes.
8. Manual run (lead): on the local server, (a) kill the backend connection mid-song (restart is not needed: close the socket from the page) and see the rejoin and late start; (b) reload the app page mid-song and see it rejoin into the game; (c) hide and show the page.

## Out of scope
Android-only parts (audio focus on calls, native wake lock) until packaging (I-011); moods; the live site (I-012).

## Result
- Core: `Session.reconnectNow()` (opens the waiting attempt at once; the 30 s limit still counts from the drop) and `RoundController.reloadAudio()` (after the sound unlock, loads a song whose load failed and late-starts it; never a second `ready`, never before the start).
- App: rejoining and disconnected/replaced screens, reload rejoin from the web's `songrush.rejoin` entry only on a `reload` navigation, `AppLifecycleListener` return → `reconnectNow` when reconnecting, the `탭하여 소리 켜기` prompt, Screen Wake Lock during a game, the sound-offset dialog (-500..+500 ms, step 10, localStorage `songrush.soundOffset`; 8 new texts in ko/en/ja written for the app).
- Verifier: core `dart analyze` no issues, `dart test` +176; `flutter analyze` no issues; app widget tests +69 all passed in a copy without flutter_soloud; frontend untouched.
- Manual run (lead), React web host and app guest, 5 songs, song 1:
  - (a) the app's socket closed from the page: rejoined at once, same song (67 s → 65 s left), one open socket.
  - (b) page reload: back in the game at song 1 (60 s left) with the `탭하여 소리 켜기` prompt; the web still lists the app player (`docs/cycles/150/b-after-reload.png`).
  - (c) visibilitychange hidden then visible: same song, socket kept.
  - No console errors. Not checked here: real audio after the unlock tap and the wake lock on a phone (headless Chromium); left for the phone check.

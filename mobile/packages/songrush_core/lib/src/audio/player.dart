import 'dart:typed_data';

// The audio engine and the network as the round controller sees them (F-M3,
// design 141 option A). The app implements them with flutter_soloud and
// package:http; tests use FakePlayer and FakeFetcher (testing.dart).

/// One song's sound in an audio engine: the round controller makes one per
/// round ([SongPlayerFactory]), so a reveal window can play on while the next
/// song loads. Times are in the player's own clock, in seconds ([nowSec]).
abstract interface class SongPlayer {
  /// Decodes [bytes] (Ogg Opus) into this player. Throws when they cannot be
  /// loaded; may be called again after a failure.
  Future<void> load(Uint8List bytes);

  /// Plays the loaded sound from [offset] seconds into it at player time
  /// [when] and stops it at [stopAt]. A [when] already past starts at once.
  /// Called again after [stop] to reschedule. Throws before a [load].
  void play({required double when, required double offset, required double stopAt});

  /// Stops the sound, playing or scheduled. Does nothing when there is none.
  void stop();

  /// Moves the stop of the sound being played to player time [stopAt].
  void setStopAt(double stopAt);

  /// Jumps the sound being played to [offsetSec] into the song (the in-song
  /// drift correction).
  void seek(double offsetSec);

  /// Where the sound is now, in seconds into the song, as the engine reports
  /// it; null when no sound plays.
  double? get positionSec;

  /// The player's clock, in seconds.
  double get nowSec;

  /// How long a sample takes from the engine to the speaker, in seconds, as
  /// the engine reports it (0 when unknown).
  double get outputLatencySec;

  /// Stops and frees the sound; the player is not used again. A load still
  /// running frees what it loads.
  void dispose();
}

/// Makes the [SongPlayer] of one round.
typedef SongPlayerFactory = SongPlayer Function();

/// Downloads one of the server's paths (`/audio/...`, `/cover/...`).
abstract interface class Fetcher {
  /// The body of [url]; throws on a network failure or a status other than
  /// 200.
  Future<Uint8List> fetch(String url);
}

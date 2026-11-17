// The player's own playback setting (cycle 142's `adj`): a manual offset in
// ms that moves every song later (positive) or earlier (negative), for a
// device whose reported output latency is off.

/// The manual offset's range, in ms.
const int manualOffsetMinMs = -500;
const int manualOffsetMaxMs = 500;

/// [ms] inside [manualOffsetMinMs]..[manualOffsetMaxMs].
int clampManualOffset(int ms) => ms.clamp(manualOffsetMinMs, manualOffsetMaxMs);

/// Where the manual offset is kept. Synchronous like the TokenStore; an app
/// adapter over async storage keeps a cached copy and reports its own write
/// failures.
abstract interface class PlaybackSettingsStore {
  /// The stored offset in ms, or null when none is stored.
  int? readManualOffsetMs();

  void writeManualOffsetMs(int ms);
}

/// A [PlaybackSettingsStore] in memory: lost with the process.
class MemoryPlaybackSettingsStore implements PlaybackSettingsStore {
  MemoryPlaybackSettingsStore([this._manualOffsetMs]);

  int? _manualOffsetMs;

  @override
  int? readManualOffsetMs() => _manualOffsetMs;

  @override
  void writeManualOffsetMs(int ms) => _manualOffsetMs = ms;
}

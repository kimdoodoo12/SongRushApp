import 'clock_sync.dart';

// Playback planning against the server clock, as pure functions over numbers:
// the plan of frontend/src/audio/playback.js and the start offset, its
// correction and the reveal window of frontend/src/game/session.js, plus the
// in-song drift check of cycle 142. The audio engine that uses them is F-M3.

/// A start's receipt offset that differs from the estimate by more than its
/// rtt plus this re-syncs (F-004).
const int driftMarginMs = 50;

/// A provisional start moves only for a lower bound this far above its
/// offset (spec 051).
const int correctionMarginMs = 20;

/// The in-song drift check corrects only a player this far off (cycle 142).
const double driftThresholdMs = 40;

/// ... and at most once in this long.
const int driftMinIntervalMs = 2000;

/// When and how to play one song, in the player's clock (seconds): start at
/// [when] from [offset] seconds into the song and stop at [stopAt].
class PlaybackPlan {
  const PlaybackPlan({required this.when, required this.offset, required this.stopAt});

  final double when;
  final double offset;
  final double stopAt;

  @override
  String toString() => 'PlaybackPlan(when: $when, offset: $offset, stopAt: $stopAt)';
}

/// Converts the server's [startAt]/[deadlineAt] (epoch ms) into player
/// seconds. [offsetMs] is server time minus local time; [nowMs] and
/// [ctxTime] are read at the same moment. [outputLatency] (seconds) is
/// subtracted so the sound leaves the speaker at startAt. [manualOffsetMs] is
/// the player's own setting: positive plays later. Null when the song is
/// already over.
PlaybackPlan? planPlayback({
  required num startAt,
  required num deadlineAt,
  required num offsetMs,
  required num nowMs,
  required num ctxTime,
  num outputLatency = 0,
  num manualOffsetMs = 0,
}) {
  final lead = outputLatency - manualOffsetMs / 1000;
  final delaySec = (startAt - offsetMs - nowMs) / 1000 - lead;
  final when = ctxTime + (delaySec > 0 ? delaySec : 0);
  // A late start plays from where the song is now.
  final offset = -delaySec > 0 ? -delaySec : 0.0;
  final stopAt = ctxTime + (deadlineAt - offsetMs - nowMs) / 1000 - lead;
  if (stopAt <= when) return null;
  return PlaybackPlan(when: when.toDouble(), offset: offset.toDouble(), stopAt: stopAt.toDouble());
}

/// The offset a `start` uses, and whether the clock must re-sync.
class StartOffset {
  const StartOffset({required this.offsetMs, required this.provisional, required this.resync});

  final double offsetMs;

  /// The start used a drifted receipt: fresh pongs may still move it earlier
  /// ([correctedStartOffset]).
  final bool provisional;

  /// The receipt drifted from the estimate: clear the samples and burst.
  final bool resync;

  @override
  String toString() => 'StartOffset($offsetMs, provisional: $provisional, resync: $resync)';
}

/// The offset used at start: the synced [estimate] when there is one, else
/// the receipt offset (`serverTime - nowMs`). A receipt that drifts from the
/// estimate by more than its rtt + [driftMarginMs] re-syncs, and the song
/// uses the smaller of the two offsets, which is never early (cycle 047
/// review M1); a smaller receipt is provisional (cycle 051).
StartOffset startOffset({required num serverTime, required num nowMs, ClockEstimate? estimate}) {
  final receipt = (serverTime - nowMs).toDouble();
  if (estimate == null) return StartOffset(offsetMs: receipt, provisional: false, resync: false);
  if ((receipt - estimate.offsetMs).abs() <= estimate.rttMs + driftMarginMs) {
    return StartOffset(offsetMs: estimate.offsetMs, provisional: false, resync: false);
  }
  if (receipt < estimate.offsetMs) return StartOffset(offsetMs: receipt, provisional: true, resync: true);
  return StartOffset(offsetMs: estimate.offsetMs, provisional: false, resync: true);
}

/// A provisional start's new offset: the clock's [lowerBound] when it is
/// more than [correctionMarginMs] above [offsetMs]; null to keep it. It never
/// moves later, so it is never early (spec 051).
double? correctedStartOffset({required num offsetMs, required num? lowerBound}) {
  if (lowerBound == null || lowerBound <= offsetMs + correctionMarginMs) return null;
  return lowerBound.toDouble();
}

/// The local time (epoch ms) a reveal window ends, `min(playUntil,
/// deadlineAt)`, or null when the reveal opens none: [playUntil] is missing
/// or not later than now (cycle 129).
double? revealWindowEnd({
  required num? playUntil,
  required num deadlineAt,
  required num offsetMs,
  required num nowMs,
}) {
  if (playUntil == null || !playUntil.isFinite) return null;
  if (playUntil - offsetMs <= nowMs) return null;
  final end = (playUntil < deadlineAt ? playUntil : deadlineAt) - offsetMs;
  return end > nowMs ? end.toDouble() : null;
}

/// Where the player should report the song to be now, in ms: the server's
/// song position plus [outputLatency] (seconds; the sound leaves the speaker
/// that much later), minus [manualOffsetMs] (positive plays later). The same
/// timeline as [planPlayback]. Negative before the start.
double expectedPositionMs({
  required num startAt,
  required num offsetMs,
  required num nowMs,
  num outputLatency = 0,
  num manualOffsetMs = 0,
}) =>
    ((nowMs + offsetMs - startAt) + outputLatency * 1000 - manualOffsetMs).toDouble();

/// The drift check's answer: seek to [seekToMs], or null to leave the player.
class DriftCorrection {
  const DriftCorrection({required this.driftMs, required this.seekToMs});

  /// The reported position minus the expected one: positive is ahead.
  final double driftMs;

  /// Where to seek, in ms into the song; null when no correction is due.
  final double? seekToMs;

  bool get correct => seekToMs != null;

  @override
  String toString() => 'DriftCorrection(drift: $driftMs, seekTo: $seekToMs)';
}

/// The in-song drift check (cycle 142: an engine's latency report and its
/// clock can both be off). Seeks to [expectedMs] when the [reportedMs]
/// position is more than [thresholdMs] off it, either way, and the last
/// correction ([lastCorrectionAtMs], local epoch ms; null for none) was at
/// least [minIntervalMs] before [nowMs]. Never before the song's start.
DriftCorrection checkDrift({
  required num expectedMs,
  required num reportedMs,
  required num nowMs,
  num? lastCorrectionAtMs,
  num thresholdMs = driftThresholdMs,
  num minIntervalMs = driftMinIntervalMs,
}) {
  final drift = (reportedMs - expectedMs).toDouble();
  final due = expectedMs >= 0 &&
      drift.abs() > thresholdMs &&
      (lastCorrectionAtMs == null || nowMs - lastCorrectionAtMs >= minIntervalMs);
  return DriftCorrection(driftMs: drift, seekToMs: due ? expectedMs.toDouble() : null);
}

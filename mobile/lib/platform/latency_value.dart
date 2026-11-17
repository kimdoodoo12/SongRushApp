/// A latency read from the browser, in seconds, when it is a finite number
/// (session.js `outputLatency`: `Number.isFinite(ctx.outputLatency)`);
/// otherwise null (missing, not a number, NaN or infinite).
double? finiteLatency(Object? raw) {
  if (raw is! num) return null;
  final value = raw.toDouble();
  return value.isFinite ? value : null;
}

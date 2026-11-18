/// What a client keeps to take its seat back: the room's `token` and code
/// (the web client's `songrush.rejoin` sessionStorage entry).
class RejoinEntry {
  const RejoinEntry({required this.token, required this.code});

  /// The secret from the server's `token` frame. Never log it.
  final String token;

  /// The room code from `joined`.
  final String code;

  @override
  bool operator ==(Object other) =>
      other is RejoinEntry && other.token == token && other.code == code;

  @override
  int get hashCode => Object.hash(token, code);

  @override
  String toString() => 'RejoinEntry(<hidden>, $code)';
}

/// Where the session keeps its [RejoinEntry]. Synchronous, like the web
/// client's sessionStorage; an app adapter over async storage keeps a cached
/// copy. A store that fails to write should report it itself: the session
/// goes on with the token it holds in memory.
abstract interface class TokenStore {
  RejoinEntry? read();

  void write(RejoinEntry entry);

  void clear();
}

/// A [TokenStore] in memory: lost with the process.
class MemoryTokenStore implements TokenStore {
  MemoryTokenStore([this._entry]);

  RejoinEntry? _entry;

  @override
  RejoinEntry? read() => _entry;

  @override
  void write(RejoinEntry entry) => _entry = entry;

  @override
  void clear() => _entry = null;
}

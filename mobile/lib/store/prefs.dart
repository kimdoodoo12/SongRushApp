/// A small string store, synchronous like the browser's Web Storage: the
/// app keeps the nickname, the language and the rejoin entry in it under
/// the web client's keys. A store that cannot read answers null; one that
/// cannot write reports it itself and the app goes on with what it holds.
abstract interface class Prefs {
  String? read(String key);

  void write(String key, String value);

  void remove(String key);
}

/// [Prefs] in memory: lost with the process. The fallback off the web and in
/// tests.
class MemoryPrefs implements Prefs {
  MemoryPrefs([Map<String, String>? values]) : values = values ?? {};

  final Map<String, String> values;

  @override
  String? read(String key) => values[key];

  @override
  void write(String key, String value) => values[key] = value;

  @override
  void remove(String key) => values.remove(key);
}

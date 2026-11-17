/// Typed reads from a decoded JSON object. Every wrong shape throws a
/// [FormatException] naming the frame type and the key, so a malformed frame
/// is refused whole instead of half-read.
class JsonReader {
  JsonReader(this.json, this.context);

  final Map<String, Object?> json;

  /// The frame type (or nested object) the errors name.
  final String context;

  FormatException _bad(String key, String expected) =>
      FormatException('$context.$key: expected $expected, got ${json[key]}');

  bool has(String key) => json.containsKey(key);

  int integer(String key) {
    final v = json[key];
    if (v is int) return v;
    throw _bad(key, 'an integer');
  }

  int? optInteger(String key) => json[key] == null ? null : integer(key);

  String string(String key) {
    final v = json[key];
    if (v is String) return v;
    throw _bad(key, 'a string');
  }

  String? optString(String key) => json[key] == null ? null : string(key);

  bool boolean(String key) {
    final v = json[key];
    if (v is bool) return v;
    throw _bad(key, 'a boolean');
  }

  bool? optBoolean(String key) => json[key] == null ? null : boolean(key);

  List<String> strings(String key) {
    final v = json[key];
    if (v is List && v.every((e) => e is String)) return List.unmodifiable(v);
    throw _bad(key, 'an array of strings');
  }

  List<String>? optStrings(String key) => json[key] == null ? null : strings(key);

  Map<String, Object?> object(String key) {
    final v = json[key];
    if (v is Map<String, Object?>) return v;
    throw _bad(key, 'an object');
  }

  Map<String, Object?>? optObject(String key) => json[key] == null ? null : object(key);

  /// A list of objects, each read by [read].
  List<T> objects<T>(String key, T Function(JsonReader r) read) {
    final v = json[key];
    if (v is! List) throw _bad(key, 'an array of objects');
    return List.unmodifiable([
      for (final (i, e) in v.indexed)
        if (e is Map<String, Object?>)
          read(JsonReader(e, '$context.$key[$i]'))
        else
          throw _bad(key, 'an array of objects'),
    ]);
  }

  List<T>? optObjects<T>(String key, T Function(JsonReader r) read) =>
      json[key] == null ? null : objects(key, read);
}

/// Adds [value] under [key] unless it is null (an absent optional field).
void putOpt(Map<String, Object?> json, String key, Object? value) {
  if (value != null) json[key] = value;
}

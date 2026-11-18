import 'json.dart';

/// The game options shared by `start`, `lobbySettings` and `settings`
/// (contracts/protocol.md, client: start). A null field is absent on the
/// wire. The string fields keep the server's ids (`tier`: easy, normal, hard;
/// `answerMode`: chat, choice; `gameMode`: first, ranked).
class GameOptions {
  const GameOptions({
    this.types,
    this.yearFrom,
    this.yearTo,
    this.tier,
    this.answerMode,
    this.gameMode,
  });

  factory GameOptions.read(JsonReader r) => GameOptions(
        types: r.optStrings('types'),
        yearFrom: r.optInteger('yearFrom'),
        yearTo: r.optInteger('yearTo'),
        tier: r.optString('tier'),
        answerMode: r.optString('answerMode'),
        gameMode: r.optString('gameMode'),
      );

  /// `"OP"` and/or `"ED"`.
  final List<String>? types;
  final int? yearFrom;
  final int? yearTo;
  final String? tier;
  final String? answerMode;
  final String? gameMode;

  /// The set fields only.
  Map<String, Object?> toJson() {
    final m = <String, Object?>{};
    putOpt(m, 'types', types);
    putOpt(m, 'yearFrom', yearFrom);
    putOpt(m, 'yearTo', yearTo);
    putOpt(m, 'tier', tier);
    putOpt(m, 'answerMode', answerMode);
    putOpt(m, 'gameMode', gameMode);
    return m;
  }
}

/// A `roster` or `snapshot` player.
class Player {
  const Player({
    required this.name,
    required this.score,
    required this.host,
    required this.connected,
  });

  factory Player.read(JsonReader r) => Player(
        name: r.string('name'),
        score: r.integer('score'),
        host: r.boolean('host'),
        connected: r.boolean('connected'),
      );

  final String name;
  final int score;
  final bool host;

  /// False while in grace, or after leaving during a game.
  final bool connected;

  Map<String, Object?> toJson() =>
      {'name': name, 'score': score, 'host': host, 'connected': connected};
}

/// A `scores` (or `snapshot.scores`) row.
class ScoreRow {
  const ScoreRow({required this.name, required this.score});

  factory ScoreRow.read(JsonReader r) =>
      ScoreRow(name: r.string('name'), score: r.integer('score'));

  final String name;
  final int score;

  Map<String, Object?> toJson() => {'name': name, 'score': score};
}

/// One cover tile of a choice room's song.
class Choice {
  const Choice({required this.coverUrl});

  factory Choice.read(JsonReader r) => Choice(coverUrl: r.string('coverUrl'));

  /// `/cover/<id>`.
  final String coverUrl;

  Map<String, Object?> toJson() => {'coverUrl': coverUrl};
}

/// A ranked correct answer already sent (`prepare.correct`,
/// `snapshot.song.correct`).
class CorrectEntry {
  const CorrectEntry({required this.name, required this.rank});

  factory CorrectEntry.read(JsonReader r) =>
      CorrectEntry(name: r.string('name'), rank: r.integer('rank'));

  final String name;
  final int rank;

  Map<String, Object?> toJson() => {'name': name, 'rank': rank};
}

/// A ranked `reveal.ranking` row.
class SongRank {
  const SongRank({required this.name, required this.rank, required this.points});

  factory SongRank.read(JsonReader r) => SongRank(
        name: r.string('name'),
        rank: r.integer('rank'),
        points: r.integer('points'),
      );

  final String name;
  final int rank;
  final int points;

  Map<String, Object?> toJson() => {'name': name, 'rank': rank, 'points': points};
}

/// A `gameOver.ranking` row; ties share a rank.
class FinalRank {
  const FinalRank({required this.rank, required this.name, required this.score});

  factory FinalRank.read(JsonReader r) => FinalRank(
        rank: r.integer('rank'),
        name: r.string('name'),
        score: r.integer('score'),
      );

  final int rank;
  final String name;
  final int score;

  Map<String, Object?> toJson() => {'rank': rank, 'name': name, 'score': score};
}

/// A reveal's accepted titles per language (each may be empty).
class Titles {
  const Titles({
    required this.ko,
    required this.en,
    required this.ja,
    required this.romaji,
  });

  factory Titles.read(JsonReader r) => Titles(
        ko: r.strings('ko'),
        en: r.strings('en'),
        ja: r.strings('ja'),
        romaji: r.strings('romaji'),
      );

  final List<String> ko;
  final List<String> en;
  final List<String> ja;
  final List<String> romaji;

  Map<String, Object?> toJson() => {'ko': ko, 'en': en, 'ja': ja, 'romaji': romaji};
}

/// `{picked, total}`: connected players who picked, and connected players.
class PickCount {
  const PickCount({required this.picked, required this.total});

  factory PickCount.read(JsonReader r) =>
      PickCount(picked: r.integer('picked'), total: r.integer('total'));

  final int picked;
  final int total;

  Map<String, Object?> toJson() => {'picked': picked, 'total': total};
}

/// `{votes, needed}` of a skip vote.
class SkipCount {
  const SkipCount({required this.votes, required this.needed});

  factory SkipCount.read(JsonReader r) =>
      SkipCount(votes: r.integer('votes'), needed: r.integer('needed'));

  final int votes;
  final int needed;

  Map<String, Object?> toJson() => {'votes': votes, 'needed': needed};
}

import 'dart:convert';

import 'json.dart';
import 'models.dart';

/// A frame from the server (contracts/protocol.md, "Server to client").
///
/// [decode] refuses text that is not a JSON object with a string `type`, and a
/// known type with a field of the wrong shape, with a [FormatException]. An
/// unknown `type` is an [UnknownMessage], never an error. Unknown keys are
/// ignored by the typed fields and kept in [raw].
sealed class ServerMessage {
  const ServerMessage({this.raw = const {}});

  /// Parses one text frame.
  factory ServerMessage.decode(String text) {
    final Object? json;
    try {
      json = jsonDecode(text);
    } on FormatException catch (e) {
      throw FormatException('not JSON: ${e.message}');
    }
    if (json is! Map<String, Object?>) {
      throw const FormatException('not a JSON object');
    }
    return ServerMessage.fromJson(json);
  }

  /// Reads a decoded frame.
  factory ServerMessage.fromJson(Map<String, Object?> json) {
    final type = json['type'];
    if (type is! String) throw const FormatException('no string type');
    final r = JsonReader(json, type);
    return switch (type) {
      'pong' => PongMessage._read(r),
      'token' => TokenMessage._read(r),
      'joined' => JoinedMessage._read(r),
      'roster' => RosterMessage._read(r),
      'snapshot' => SnapshotMessage._read(r),
      'settings' => SettingsMessage._read(r),
      'lobbySettings' => LobbySettingsMessage._read(r),
      'roomTheme' => RoomThemeMessage._read(r),
      'prepare' => PrepareMessage._read(r),
      'start' => StartMessage._read(r),
      'chat' => ChatMessage._read(r),
      'correct' => CorrectMessage._read(r),
      'wrong' => WrongMessage._read(r),
      'pick-result' => PickResultMessage._read(r),
      'pickState' => PickStateMessage._read(r),
      'skipState' => SkipStateMessage._read(r),
      'reveal' => RevealMessage._read(r),
      'scores' => ScoresMessage._read(r),
      'gameOver' => GameOverMessage._read(r),
      'error' => ErrorMessage._read(r),
      _ => UnknownMessage(json),
    };
  }

  /// The decoded frame as received (empty for a message built in code).
  final Map<String, Object?> raw;

  /// The wire `type`.
  String get type;

  /// The frame's fields other than `type`.
  Map<String, Object?> _body();

  /// The frame from the typed fields only (unknown keys are not written).
  Map<String, Object?> toJson() => {'type': type, ..._body()};

  String encode() => jsonEncode(toJson());
}

/// A frame of a type this client does not know.
final class UnknownMessage extends ServerMessage {
  const UnknownMessage(Map<String, Object?> raw) : super(raw: raw);

  @override
  String get type => raw['type'] as String;

  @override
  Map<String, Object?> _body() => Map.of(raw)..remove('type');

  @override
  Map<String, Object?> toJson() => Map.of(raw);
}

/// The answer to `ping`.
final class PongMessage extends ServerMessage {
  const PongMessage({this.id, required this.serverTime, super.raw});

  PongMessage._read(JsonReader r)
      : id = r.json['id'],
        serverTime = r.integer('serverTime'),
        super(raw: r.json);

  /// The ping's `id`; null when it had none (absent on the wire).
  final Object? id;
  final int serverTime;

  @override
  String get type => 'pong';

  @override
  Map<String, Object?> _body() {
    final m = <String, Object?>{};
    putOpt(m, 'id', id);
    m['serverTime'] = serverTime;
    return m;
  }
}

/// The rejoin secret, after `joined` on create or join. Keep it private.
final class TokenMessage extends ServerMessage {
  const TokenMessage({required this.token, super.raw});

  TokenMessage._read(JsonReader r)
      : token = r.string('token'),
        super(raw: r.json);

  final String token;

  @override
  String get type => 'token';

  @override
  Map<String, Object?> _body() => {'token': token};

  @override
  String toString() => 'TokenMessage(<hidden>)';
}

/// The room code and the sender's assigned name (with ` (2)`... on a
/// duplicate nickname).
final class JoinedMessage extends ServerMessage {
  const JoinedMessage({required this.code, required this.name, super.raw});

  JoinedMessage._read(JsonReader r)
      : code = r.string('code'),
        name = r.string('name'),
        super(raw: r.json);

  final String code;
  final String name;

  @override
  String get type => 'joined';

  @override
  Map<String, Object?> _body() => {'code': code, 'name': name};
}

/// Every membership change. [phase] is `lobby` or `playing`.
final class RosterMessage extends ServerMessage {
  const RosterMessage({required this.phase, required this.players, super.raw});

  RosterMessage._read(JsonReader r)
      : phase = r.string('phase'),
        players = r.objects('players', Player.read),
        super(raw: r.json);

  final String phase;
  final List<Player> players;

  @override
  String get type => 'roster';

  @override
  Map<String, Object?> _body() =>
      {'phase': phase, 'players': [for (final p in players) p.toJson()]};
}

/// The current song of a `snapshot`, with only values already sent.
class SnapshotSong {
  const SnapshotSong({
    required this.index,
    required this.total,
    required this.audioUrl,
    this.startAt,
    this.deadlineAt,
    this.choices,
    this.picked,
    this.pickState,
    this.answered,
    this.correct,
    this.reveal,
    this.skipState,
  });

  factory SnapshotSong.read(JsonReader r) => SnapshotSong(
        index: r.integer('index'),
        total: r.integer('total'),
        audioUrl: r.string('audioUrl'),
        startAt: r.optInteger('startAt'),
        deadlineAt: r.optInteger('deadlineAt'),
        choices: r.optObjects('choices', Choice.read),
        picked: r.optInteger('picked'),
        pickState: r.json['pickState'] == null
            ? null
            : PickCount.read(JsonReader(r.object('pickState'), '${r.context}.pickState')),
        answered: r.optBoolean('answered'),
        correct: r.optObjects('correct', CorrectEntry.read),
        reveal: r.json['reveal'] == null
            ? null
            : RevealMessage._read(JsonReader(r.object('reveal'), '${r.context}.reveal')),
        skipState: r.json['skipState'] == null
            ? null
            : SkipCount.read(JsonReader(r.object('skipState'), '${r.context}.skipState')),
      );

  final int index;
  final int total;
  final String audioUrl;

  /// PLAYING, or in a reveal window.
  final int? startAt;
  final int? deadlineAt;

  /// Choice room only.
  final List<Choice>? choices;

  /// Choice room only: the receiver's own picked position, or null.
  final int? picked;
  final PickCount? pickState;

  /// Ranked only.
  final bool? answered;
  final List<CorrectEntry>? correct;

  /// In a reveal window only: the reveal frame.
  final RevealMessage? reveal;
  final SkipCount? skipState;

  Map<String, Object?> toJson() {
    final m = <String, Object?>{'index': index, 'total': total, 'audioUrl': audioUrl};
    putOpt(m, 'startAt', startAt);
    putOpt(m, 'deadlineAt', deadlineAt);
    if (choices != null) {
      m['choices'] = [for (final c in choices!) c.toJson()];
      // Present (possibly null) exactly in a choice room.
      m['picked'] = picked;
    }
    putOpt(m, 'pickState', pickState?.toJson());
    putOpt(m, 'answered', answered);
    putOpt(m, 'correct', correct?.map((c) => c.toJson()).toList());
    putOpt(m, 'reveal', reveal?._body());
    putOpt(m, 'skipState', skipState?.toJson());
    return m;
  }
}

/// The room as it is, after `joined` on a join during a game and on every
/// `rejoin`.
final class SnapshotMessage extends ServerMessage {
  const SnapshotMessage({
    required this.phase,
    required this.players,
    required this.song,
    required this.scores,
    super.raw,
  });

  SnapshotMessage._read(JsonReader r)
      : phase = r.string('phase'),
        players = r.objects('players', Player.read),
        song = r.json['song'] == null
            ? null
            : SnapshotSong.read(JsonReader(r.object('song'), 'snapshot.song')),
        scores = r.objects('scores', ScoreRow.read),
        super(raw: r.json);

  final String phase;
  final List<Player> players;

  /// Null in the lobby, while paused, and between songs outside a reveal
  /// window.
  final SnapshotSong? song;

  /// Empty in the lobby.
  final List<ScoreRow> scores;

  @override
  String get type => 'snapshot';

  @override
  Map<String, Object?> _body() => {
        'phase': phase,
        'players': [for (final p in players) p.toJson()],
        'song': song?.toJson(),
        'scores': [for (final s in scores) s.toJson()],
      };
}

/// The game's filters, choice and ranked flags (only the set ones).
final class SettingsMessage extends ServerMessage {
  const SettingsMessage({required this.options, super.raw});

  SettingsMessage._read(JsonReader r)
      : options = GameOptions.read(r),
        super(raw: r.json);

  final GameOptions options;

  @override
  String get type => 'settings';

  @override
  Map<String, Object?> _body() => options.toJson();
}

/// The host's latest lobby selection, display-only for guests.
final class LobbySettingsMessage extends ServerMessage {
  const LobbySettingsMessage({required this.songCount, required this.options, super.raw});

  LobbySettingsMessage._read(JsonReader r)
      : songCount = r.integer('songCount'),
        options = GameOptions.read(r),
        super(raw: r.json);

  final int songCount;
  final GameOptions options;

  @override
  String get type => 'lobbySettings';

  @override
  Map<String, Object?> _body() => {'songCount': songCount, ...options.toJson()};
}

/// Legacy (D-136): the room's look id.
final class RoomThemeMessage extends ServerMessage {
  const RoomThemeMessage({required this.theme, super.raw});

  RoomThemeMessage._read(JsonReader r)
      : theme = r.string('theme'),
        super(raw: r.json);

  final String theme;

  @override
  String get type => 'roomTheme';

  @override
  Map<String, Object?> _body() => {'theme': theme};
}

/// A song to load; answer with `ready`.
final class PrepareMessage extends ServerMessage {
  const PrepareMessage({
    required this.index,
    required this.total,
    required this.audioUrl,
    this.choices,
    this.correct,
    this.holdMs,
    super.raw,
  });

  PrepareMessage._read(JsonReader r)
      : index = r.integer('index'),
        total = r.integer('total'),
        audioUrl = r.string('audioUrl'),
        choices = r.optObjects('choices', Choice.read),
        correct = r.optObjects('correct', CorrectEntry.read),
        holdMs = r.optInteger('holdMs'),
        super(raw: r.json);

  final int index;
  final int total;

  /// `/audio/<opaque token>`.
  final String audioUrl;

  /// Choice room only; shuffled, nothing marks the correct one.
  final List<Choice>? choices;

  /// Ranked re-prepare only.
  final List<CorrectEntry>? correct;

  /// Prefetch on reveal only.
  final int? holdMs;

  @override
  String get type => 'prepare';

  @override
  Map<String, Object?> _body() {
    final m = <String, Object?>{'index': index, 'total': total, 'audioUrl': audioUrl};
    putOpt(m, 'choices', choices?.map((c) => c.toJson()).toList());
    putOpt(m, 'correct', correct?.map((c) => c.toJson()).toList());
    putOpt(m, 'holdMs', holdMs);
    return m;
  }
}

/// The song starts at [startAt] (server clock) and ends at [deadlineAt].
final class StartMessage extends ServerMessage {
  const StartMessage({
    required this.index,
    required this.startAt,
    required this.deadlineAt,
    required this.serverTime,
    super.raw,
  });

  StartMessage._read(JsonReader r)
      : index = r.integer('index'),
        startAt = r.integer('startAt'),
        deadlineAt = r.integer('deadlineAt'),
        serverTime = r.integer('serverTime'),
        super(raw: r.json);

  final int index;
  final int startAt;
  final int deadlineAt;
  final int serverTime;

  @override
  String get type => 'start';

  @override
  Map<String, Object?> _body() => {
        'index': index,
        'startAt': startAt,
        'deadlineAt': deadlineAt,
        'serverTime': serverTime,
      };
}

/// A chat line, lobby or game.
final class ChatMessage extends ServerMessage {
  const ChatMessage({required this.name, required this.text, super.raw});

  ChatMessage._read(JsonReader r)
      : name = r.string('name'),
        text = r.string('text'),
        super(raw: r.json);

  final String name;
  final String text;

  @override
  String get type => 'chat';

  @override
  Map<String, Object?> _body() => {'name': name, 'text': text};
}

/// A correct answer; [rank] only in ranked.
final class CorrectMessage extends ServerMessage {
  const CorrectMessage({required this.index, required this.name, this.rank, super.raw});

  CorrectMessage._read(JsonReader r)
      : index = r.integer('index'),
        name = r.string('name'),
        rank = r.optInteger('rank'),
        super(raw: r.json);

  final int index;
  final String name;
  final int? rank;

  @override
  String get type => 'correct';

  @override
  Map<String, Object?> _body() {
    final m = <String, Object?>{'index': index, 'name': name};
    putOpt(m, 'rank', rank);
    return m;
  }
}

/// Choice room: who picked wrong, never the position.
final class WrongMessage extends ServerMessage {
  const WrongMessage({required this.index, required this.name, super.raw});

  WrongMessage._read(JsonReader r)
      : index = r.integer('index'),
        name = r.string('name'),
        super(raw: r.json);

  final int index;
  final String name;

  @override
  String get type => 'wrong';

  @override
  Map<String, Object?> _body() => {'index': index, 'name': name};
}

/// Choice room: the sender's own wrong position.
final class PickResultMessage extends ServerMessage {
  const PickResultMessage({required this.index, required this.choice, super.raw});

  PickResultMessage._read(JsonReader r)
      : index = r.integer('index'),
        choice = r.integer('choice'),
        super(raw: r.json);

  final int index;
  final int choice;

  @override
  String get type => 'pick-result';

  @override
  Map<String, Object?> _body() => {'index': index, 'choice': choice};
}

/// Choice room: connected players who picked, and connected players.
final class PickStateMessage extends ServerMessage {
  const PickStateMessage({required this.index, required this.picked, required this.total, super.raw});

  PickStateMessage._read(JsonReader r)
      : index = r.integer('index'),
        picked = r.integer('picked'),
        total = r.integer('total'),
        super(raw: r.json);

  final int index;
  final int picked;
  final int total;

  @override
  String get type => 'pickState';

  @override
  Map<String, Object?> _body() => {'index': index, 'picked': picked, 'total': total};
}

/// The skip votes of song [index].
final class SkipStateMessage extends ServerMessage {
  const SkipStateMessage({required this.index, required this.votes, required this.needed, super.raw});

  SkipStateMessage._read(JsonReader r)
      : index = r.integer('index'),
        votes = r.integer('votes'),
        needed = r.integer('needed'),
        super(raw: r.json);

  final int index;
  final int votes;
  final int needed;

  @override
  String get type => 'skipState';

  @override
  Map<String, Object?> _body() => {'index': index, 'votes': votes, 'needed': needed};
}

/// The song's answer, at its end.
final class RevealMessage extends ServerMessage {
  const RevealMessage({
    required this.index,
    required this.reveal,
    required this.titles,
    required this.song,
    required this.artists,
    required this.themeType,
    required this.themeSlug,
    required this.winner,
    required this.coverUrl,
    this.correctChoice,
    this.nextChoices,
    this.ranking,
    this.playUntil,
    super.raw,
  });

  RevealMessage._read(JsonReader r)
      : index = r.integer('index'),
        reveal = r.optString('reveal'),
        titles = Titles.read(JsonReader(r.object('titles'), '${r.context}.titles')),
        song = r.optString('song'),
        artists = r.strings('artists'),
        themeType = r.string('themeType'),
        themeSlug = r.string('themeSlug'),
        winner = r.optString('winner'),
        coverUrl = r.optString('coverUrl'),
        correctChoice = r.optInteger('correctChoice'),
        nextChoices = r.optObjects('nextChoices', Choice.read),
        ranking = r.optObjects('ranking', SongRank.read),
        playUntil = r.optInteger('playUntil'),
        super(raw: r.json);

  final int index;

  /// The entry's own display title.
  final String? reveal;
  final Titles titles;

  /// The song name.
  final String? song;
  final List<String> artists;

  /// `OP` or `ED`.
  final String themeType;

  /// For example `OP1`.
  final String themeSlug;

  /// The winner's name; in ranked the rank-1 name.
  final String? winner;
  final String? coverUrl;

  /// Choice room only.
  final int? correctChoice;

  /// Choice room, not the last song.
  final List<Choice>? nextChoices;

  /// Ranked only.
  final List<SongRank>? ranking;

  /// Absent when the server runs with play to limit off.
  final int? playUntil;

  @override
  String get type => 'reveal';

  @override
  Map<String, Object?> _body() {
    final m = <String, Object?>{
      'index': index,
      'reveal': reveal,
      'titles': titles.toJson(),
      'song': song,
      'artists': artists,
      'themeType': themeType,
      'themeSlug': themeSlug,
      'winner': winner,
      'coverUrl': coverUrl,
    };
    putOpt(m, 'correctChoice', correctChoice);
    putOpt(m, 'nextChoices', nextChoices?.map((c) => c.toJson()).toList());
    putOpt(m, 'ranking', ranking?.map((r) => r.toJson()).toList());
    putOpt(m, 'playUntil', playUntil);
    return m;
  }
}

/// Every player's score, in player order, after each reveal.
final class ScoresMessage extends ServerMessage {
  const ScoresMessage({required this.scores, super.raw});

  ScoresMessage._read(JsonReader r)
      : scores = r.objects('scores', ScoreRow.read),
        super(raw: r.json);

  final List<ScoreRow> scores;

  @override
  String get type => 'scores';

  @override
  Map<String, Object?> _body() => {'scores': [for (final s in scores) s.toJson()]};
}

/// The final ranking; the room then returns to the lobby.
final class GameOverMessage extends ServerMessage {
  const GameOverMessage({required this.ranking, super.raw});

  GameOverMessage._read(JsonReader r)
      : ranking = r.objects('ranking', FinalRank.read),
        super(raw: r.json);

  final List<FinalRank> ranking;

  @override
  String get type => 'gameOver';

  @override
  Map<String, Object?> _body() => {'ranking': [for (final f in ranking) f.toJson()]};
}

/// A refused request, or a room-wide failure (`not-enough-songs`,
/// `room-closed`).
final class ErrorMessage extends ServerMessage {
  const ErrorMessage({required this.code, this.forRequest, this.available, this.unit, super.raw});

  ErrorMessage._read(JsonReader r)
      : code = r.string('code'),
        forRequest = r.optString('for'),
        available = r.optInteger('available'),
        unit = r.optString('unit'),
        super(raw: r.json);

  /// The error code, for example `room-not-found`.
  final String code;

  /// The wire `for`: `skipVote` or `pick` on a refused vote or pick.
  final String? forRequest;

  /// `not-enough-songs`: the drawable count.
  final int? available;

  /// `not-enough-songs`: `series`, or null when counting songs.
  final String? unit;

  @override
  String get type => 'error';

  @override
  Map<String, Object?> _body() {
    final m = <String, Object?>{'code': code};
    putOpt(m, 'for', forRequest);
    putOpt(m, 'available', available);
    putOpt(m, 'unit', unit);
    return m;
  }
}

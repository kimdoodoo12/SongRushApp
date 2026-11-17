import 'dart:convert';

import 'json.dart';
import 'models.dart';

/// A frame to the server (contracts/protocol.md, "Client to server").
///
/// [decode] exists for tests and fakes that read what a client sent; it
/// throws a [FormatException] for anything that is not a known client frame.
sealed class ClientMessage {
  const ClientMessage();

  factory ClientMessage.decode(String text) {
    final json = jsonDecode(text);
    if (json is! Map<String, Object?>) {
      throw const FormatException('not a JSON object');
    }
    return ClientMessage.fromJson(json);
  }

  factory ClientMessage.fromJson(Map<String, Object?> json) {
    final type = json['type'];
    if (type is! String) throw const FormatException('no string type');
    final r = JsonReader(json, 'client $type');
    return switch (type) {
      'ping' => PingRequest(id: json['id']),
      'create' => CreateRequest(
          nickname: r.string('nickname'),
          songCount: r.optInteger('songCount'),
          types: r.optStrings('types'),
          yearFrom: r.optInteger('yearFrom'),
          yearTo: r.optInteger('yearTo'),
          tier: r.optString('tier'),
        ),
      'join' => JoinRequest(code: r.string('code'), nickname: r.string('nickname')),
      'rejoin' => RejoinRequest(token: r.string('token')),
      'start' => StartRequest(songCount: r.integer('songCount'), options: GameOptions.read(r)),
      'lobbySettings' =>
        LobbySettingsRequest(songCount: r.integer('songCount'), options: GameOptions.read(r)),
      'ready' => ReadyRequest(index: r.integer('index')),
      'skipVote' => SkipVoteRequest(index: r.integer('index')),
      'pick' => PickRequest(index: r.integer('index'), choice: r.integer('choice')),
      'chat' => ChatRequest(text: r.string('text')),
      'roomTheme' => RoomThemeRequest(theme: r.string('theme')),
      'leave' => const LeaveRequest(),
      _ => throw FormatException('unknown client type $type'),
    };
  }

  /// The wire `type`.
  String get type;

  Map<String, Object?> _body();

  Map<String, Object?> toJson() => {'type': type, ..._body()};

  String encode() => jsonEncode(toJson());
}

/// Clock sync probe; [id] (any JSON value) is echoed in `pong`.
final class PingRequest extends ClientMessage {
  const PingRequest({this.id});

  /// Null leaves `id` out.
  final Object? id;

  @override
  String get type => 'ping';

  @override
  Map<String, Object?> _body() => id == null ? {} : {'id': id};
}

/// Makes a lobby with the sender as host. With [songCount] it is the solo
/// path (the client has no solo mode, D-093, but the frame allows it).
final class CreateRequest extends ClientMessage {
  const CreateRequest({
    required this.nickname,
    this.songCount,
    this.types,
    this.yearFrom,
    this.yearTo,
    this.tier,
  });

  final String nickname;
  final int? songCount;
  final List<String>? types;
  final int? yearFrom;
  final int? yearTo;
  final String? tier;

  @override
  String get type => 'create';

  @override
  Map<String, Object?> _body() {
    final m = <String, Object?>{'nickname': nickname};
    putOpt(m, 'songCount', songCount);
    putOpt(m, 'types', types);
    putOpt(m, 'yearFrom', yearFrom);
    putOpt(m, 'yearTo', yearTo);
    putOpt(m, 'tier', tier);
    return m;
  }
}

final class JoinRequest extends ClientMessage {
  const JoinRequest({required this.code, required this.nickname});

  final String code;
  final String nickname;

  @override
  String get type => 'join';

  @override
  Map<String, Object?> _body() => {'code': code, 'nickname': nickname};
}

/// Takes the seat back with an earlier `token`.
final class RejoinRequest extends ClientMessage {
  const RejoinRequest({required this.token});

  final String token;

  @override
  String get type => 'rejoin';

  @override
  Map<String, Object?> _body() => {'token': token};

  @override
  String toString() => 'RejoinRequest(<hidden>)';
}

/// Host only, lobby phase only.
final class StartRequest extends ClientMessage {
  const StartRequest({required this.songCount, this.options = const GameOptions()});

  final int songCount;
  final GameOptions options;

  @override
  String get type => 'start';

  @override
  Map<String, Object?> _body() => {'songCount': songCount, ...options.toJson()};
}

/// Host only, lobby phase only: the selection shown to guests.
final class LobbySettingsRequest extends ClientMessage {
  const LobbySettingsRequest({required this.songCount, this.options = const GameOptions()});

  final int songCount;
  final GameOptions options;

  @override
  String get type => 'lobbySettings';

  @override
  Map<String, Object?> _body() => {'songCount': songCount, ...options.toJson()};
}

/// The audio of song [index] is loaded.
final class ReadyRequest extends ClientMessage {
  const ReadyRequest({required this.index});

  final int index;

  @override
  String get type => 'ready';

  @override
  Map<String, Object?> _body() => {'index': index};
}

/// Toggles the own skip vote for song [index].
final class SkipVoteRequest extends ClientMessage {
  const SkipVoteRequest({required this.index});

  final int index;

  @override
  String get type => 'skipVote';

  @override
  Map<String, Object?> _body() => {'index': index};
}

/// Choice room: the position [choice] in song [index]'s choices.
final class PickRequest extends ClientMessage {
  const PickRequest({required this.index, required this.choice});

  final int index;
  final int choice;

  @override
  String get type => 'pick';

  @override
  Map<String, Object?> _body() => {'index': index, 'choice': choice};
}

final class ChatRequest extends ClientMessage {
  const ChatRequest({required this.text});

  final String text;

  @override
  String get type => 'chat';

  @override
  Map<String, Object?> _body() => {'text': text};
}

/// Legacy (D-136); new clients should not send it.
final class RoomThemeRequest extends ClientMessage {
  const RoomThemeRequest({required this.theme});

  final String theme;

  @override
  String get type => 'roomTheme';

  @override
  Map<String, Object?> _body() => {'theme': theme};
}

/// Leaves the room at once; the token is dropped.
final class LeaveRequest extends ClientMessage {
  const LeaveRequest();

  @override
  String get type => 'leave';

  @override
  Map<String, Object?> _body() => {};
}

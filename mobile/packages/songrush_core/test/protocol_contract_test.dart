import 'dart:convert';
import 'dart:io';

import 'package:songrush_core/songrush_core.dart';
import 'package:test/test.dart';

/// The repository's contracts/ folder, found upwards from the package.
Directory contractsDir() {
  var dir = Directory.current.absolute;
  while (true) {
    final candidate = Directory('${dir.path}${Platform.pathSeparator}contracts');
    if (File('${candidate.path}${Platform.pathSeparator}protocol.md').existsSync()) return candidate;
    final parent = dir.parent;
    if (parent.path == dir.path) throw StateError('contracts/ not found above ${Directory.current.path}');
    dir = parent;
  }
}

Map<String, Object?> readJson(File f) => jsonDecode(f.readAsStringSync()) as Map<String, Object?>;

void main() {
  final contracts = contractsDir();
  final examples = Directory('${contracts.path}${Platform.pathSeparator}messages')
      .listSync()
      .whereType<File>()
      .where((f) => f.path.endsWith('.json'))
      .toList()
    ..sort((a, b) => a.path.compareTo(b.path));
  String nameOf(File f) => f.uri.pathSegments.last.replaceAll('.json', '');

  test('the examples are there', () {
    expect(examples.length, greaterThanOrEqualTo(32));
  });

  group('server examples', () {
    for (final file in examples.where((f) => !nameOf(f).startsWith('client-'))) {
      final type = nameOf(file);
      test('$type decodes to its type and re-encodes every field', () {
        final text = file.readAsStringSync();
        final msg = ServerMessage.decode(text);
        expect(msg, isNot(isA<UnknownMessage>()));
        expect(msg.type, type);
        expect(msg.toJson(), equals(jsonDecode(text)));
        // And once more from its own encoding.
        expect(ServerMessage.decode(msg.encode()).toJson(), equals(jsonDecode(text)));
      });
    }
  });

  group('client examples', () {
    for (final file in examples.where((f) => nameOf(f).startsWith('client-'))) {
      final type = nameOf(file).substring('client-'.length);
      test('$type decodes to its type and re-encodes every field', () {
        final json = readJson(file);
        final msg = ClientMessage.fromJson(json);
        expect(msg.type, type);
        expect(msg.toJson(), equals(json));
      });
    }
  });

  test('every message heading in protocol.md has an example decoded above', () {
    final md = File('${contracts.path}${Platform.pathSeparator}protocol.md').readAsStringSync();
    final headings = RegExp(r'^### (server|client): (\S+)', multiLine: true)
        .allMatches(md)
        .map((m) => m[1] == 'client' ? 'client-${m[2]}' : m[2]!)
        .toSet();
    expect(headings, hasLength(32));
    expect(examples.map(nameOf).toSet(), containsAll(headings));
  });

  group('snapshot song', () {
    // snapshot.json is a chat room in PREPARE; the other keys of the
    // contract's table are only documented, so they are checked here.
    final reveal = (readJson(File('${contracts.path}/messages/reveal.json'))..remove('type'));
    final full = <String, Object?>{
      'type': 'snapshot',
      'phase': 'playing',
      'players': [
        {'name': 'A', 'score': 1, 'host': true, 'connected': true},
      ],
      'song': {
        'index': 2,
        'total': 10,
        'audioUrl': '/audio/x',
        'startAt': 10,
        'deadlineAt': 20,
        'choices': [
          {'coverUrl': '/cover/a'},
          {'coverUrl': '/cover/b'},
        ],
        'picked': null,
        'pickState': {'picked': 1, 'total': 2},
        'answered': false,
        'correct': [
          {'name': 'A', 'rank': 1},
        ],
        'reveal': reveal,
        'skipState': {'votes': 0, 'needed': 2},
      },
      'scores': [
        {'name': 'A', 'score': 1},
      ],
    };

    test('keeps every key of the table', () {
      final msg = ServerMessage.fromJson(full) as SnapshotMessage;
      expect(msg.song!.reveal!.ranking!.first.points, 3);
      expect(msg.song!.picked, isNull);
      expect(msg.toJson(), equals(full));
    });

    test('a lobby snapshot keeps song null', () {
      final lobby = {...full, 'phase': 'lobby', 'song': null, 'scores': <Object?>[]};
      final msg = ServerMessage.fromJson(lobby) as SnapshotMessage;
      expect(msg.song, isNull);
      expect(msg.toJson(), equals(lobby));
    });
  });

  group('decode', () {
    test('an unknown type is an UnknownMessage', () {
      final msg = ServerMessage.decode('{"type":"futureThing","x":[1]}');
      expect(msg, isA<UnknownMessage>());
      expect(msg.type, 'futureThing');
      expect(msg.toJson(), {'type': 'futureThing', 'x': [1]});
    });

    test('unknown keys are ignored and kept in raw', () {
      final msg = ServerMessage.decode('{"type":"chat","name":"A","text":"hi","extra":true}') as ChatMessage;
      expect(msg.text, 'hi');
      expect(msg.raw['extra'], true);
      expect(msg.toJson(), {'type': 'chat', 'name': 'A', 'text': 'hi'});
    });

    test('a pong without id leaves it out', () {
      final msg = ServerMessage.decode('{"type":"pong","serverTime":5}') as PongMessage;
      expect(msg.id, isNull);
      expect(msg.toJson(), {'type': 'pong', 'serverTime': 5});
    });

    for (final bad in [
      'not json',
      '[1,2]',
      '{"name":"A"}',
      '{"type":7}',
      '{"type":"chat","name":"A"}',
      '{"type":"joined","code":"ABCDE","name":5}',
      '{"type":"roster","phase":"lobby","players":[1]}',
    ]) {
      test('refuses $bad', () {
        expect(() => ServerMessage.decode(bad), throwsFormatException);
      });
    }
  });

  test('a token never shows in toString', () {
    expect(const TokenMessage(token: 'secret').toString(), isNot(contains('secret')));
    expect(const RejoinRequest(token: 'secret').toString(), isNot(contains('secret')));
    expect(const RejoinEntry(token: 'secret', code: 'ABCDE').toString(), isNot(contains('secret')));
  });
}

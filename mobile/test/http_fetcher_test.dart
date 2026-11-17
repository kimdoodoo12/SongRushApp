import 'dart:async';

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';

import 'package:songrush_mobile/net/http_fetcher.dart';

void main() {
  final base = Uri.parse('https://songrush.example/room/K7XQM');

  test('fetches a server path against the base origin', () async {
    final seen = <Uri>[];
    final fetcher = HttpFetcher(
      base: base,
      client: MockClient((request) async {
        seen.add(request.url);
        return http.Response.bytes([1, 2, 3], 200);
      }),
    );
    expect(await fetcher.fetch('/audio/abc'), [1, 2, 3]);
    expect(seen.single, Uri.parse('https://songrush.example/audio/abc'));
    expect(seen.single.hasQuery, isFalse);
  });

  test('a status other than 200 fails', () async {
    final fetcher = HttpFetcher(
      base: base,
      client: MockClient((request) async => http.Response('gone', 404)),
    );
    await expectLater(
      fetcher.fetch('/cover/x'),
      throwsA(isA<HttpFetchException>().having((e) => e.statusCode, 'statusCode', 404)),
    );
  });

  test('a fetch with no answer times out', () async {
    final never = Completer<http.Response>();
    final fetcher = HttpFetcher(
      base: base,
      timeout: const Duration(milliseconds: 10),
      client: MockClient((request) => never.future),
    );
    await expectLater(fetcher.fetch('/audio/abc'), throwsA(isA<TimeoutException>()));
  });
}

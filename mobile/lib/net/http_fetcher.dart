import 'dart:typed_data';

import 'package:http/http.dart' as http;
import 'package:songrush_core/songrush_core.dart';

/// A [fetch] answered with a status other than 200.
class HttpFetchException implements Exception {
  const HttpFetchException(this.url, this.statusCode);

  final Uri url;
  final int statusCode;

  @override
  String toString() => 'HttpFetchException($statusCode for $url)';
}

/// The app's [Fetcher]: a GET of the server path resolved against [base]
/// (the page's origin on the web). Redirects are followed (the browser's
/// fetch does it on the web, the client on a device), so an `/audio/` path
/// that redirects to S3 works. Nothing is cached here: the server answers
/// `/audio/` and `/cover/` with no-store, and no request header is added, so
/// a cross-origin redirect needs no CORS preflight. A fetch with no answer
/// in [timeout] fails.
class HttpFetcher implements Fetcher {
  HttpFetcher({required this.base, http.Client? client, this.timeout = const Duration(seconds: 60)})
      : _client = client ?? http.Client();

  final Uri base;
  final Duration timeout;
  final http.Client _client;

  @override
  Future<Uint8List> fetch(String url) async {
    final uri = base.resolve(url);
    final response = await _client.get(uri).timeout(timeout);
    if (response.statusCode != 200) throw HttpFetchException(uri, response.statusCode);
    return response.bodyBytes;
  }

  void close() => _client.close();
}

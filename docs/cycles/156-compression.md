# 156 · Compress static files (mobile first load)

The owner saw the mobile app's "불러오는 중" for a long time on a phone. Measured on the live site with a Pixel 7 profile: about 13 MB before the first frame (canvaskit.wasm 5.3 MB, main.dart.js 3.1 MB, the soloud wasm 1.9 MB, fonts about 2.7 MB), none of it compressed (no Content-Encoding even when asked for gzip/br/zstd; the React bundle neither). Revalidation already works (`If-Modified-Since` gives 304), so a second visit took 1.2 s.

Change: Spring Boot response compression (`server.compression.*` in application.properties): text/html, css, plain, javascript, json, wasm, ttf/otf, svg, manifest; responses of 2 KB and more; never audio.

Evidence: `CompressionTest` (5): a JS, a wasm and a ttf come back gzip-encoded and identical after gunzip; a 100-byte file not compressed; `/audio` (audio/ogg) never compressed, exact bytes. With compression off, the three gzip tests failed (implementer's negative check). Lead re-ran `cleanTest` for CompressionTest 5/5, MobilePageTest 4/4 and the audio tests 48/48; implementer's full backend run 1315 tests, 0 failures.

To check after deploy: `curl -sI -H 'Accept-Encoding: gzip' https://animeopguessr.com/m/main.dart.js` shows `Content-Encoding: gzip`.

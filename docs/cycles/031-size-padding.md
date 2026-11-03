# 031 size-padding (C-016)

## Goal
Change the size of every cached audio file by a random amount without changing its audio, so a client cannot identify the song from the public AnimeThemes file sizes (D-055, design 029).

## Interface: `data/songrush_data/pad.py`
- **`ogg_crc(data: bytes) -> int`**: the Ogg page checksum: CRC-32 with polynomial `0x04C11DB7`, initial value 0, no bit reflection, no final XOR, computed over the page with its CRC field set to zero. Check value: `ogg_crc(b"123456789") == 0x89A1897F`.
- **`pad_opus(data: bytes, pad_len: int) -> bytes`** (pure):
  - Parses the pages of the `OpusHead` stream (as `audio.ogg_info` does) and reassembles the second packet, which must start with `OpusTags`; it may span pages.
  - Rewrites `OpusTags`: the user-comment count goes up by one and the comment `SONGRUSH_PAD=` followed by `pad_len` bytes of `0x41` (`A`) is appended. Vendor string and existing comments are unchanged.
  - Repaginates the new `OpusTags` packet into as many pages as needed (at most 255 lacing values per page; continued-packet flag on continuation pages; granule position 0 on header pages, as Ogg Opus requires), keeping the stream's serial number.
  - Every later page keeps its header type, granule and payload byte for byte; its page sequence number is shifted by the change in the number of header pages, and its CRC is recomputed. Pages of other streams, if any, are copied unchanged.
  - Every page in the result has a valid `ogg_crc`.
  - Raises `ValueError` if the data is not Ogg Opus, if `OpusTags` is missing, if it already has a `SONGRUSH_PAD=` comment, or if `pad_len` is not between 1 and 65,536.
- **`pad_cache(cache_dir, *, rng=None, log=print) -> dict`**
  - Takes the same lock as the prefetch (`cache_dir/.prefetch.lock`, `audio.PrefetchLocked`), so padding never runs during a prefetch.
  - For every entry of `cache_dir/index.json` whose file is not padded yet (its size equals the entry's `size`): `pad_len = rng.randint(1024, 65536)` (`rng` defaults to `secrets.SystemRandom()`), `pad_opus`, write to `<basename>.part`, `os.replace` into place (with the prefetch's PermissionError retry), then set the entry's `paddedSize` and rewrite the index. A file whose size already equals `paddedSize` is skipped.
  - Returns and logs `pad done=<n> skipped=<n> failed=<n>`; a file that fails `pad_opus` is logged and left unchanged.
- **`main(argv=None) -> int`**: `python -m songrush_data.pad [--cache DIR]`, default `data/audio-cache`; exit 0, 3 when locked, 2 for usage errors, 1 on other errors; UTF-8 line-buffered console.

## Change: `data/songrush_data/audio.py`
- A cached file is also complete when its size equals the index entry's `paddedSize` and it is Ogg Opus with a positive duration; a padded file is never refetched. When the index entry is rebuilt from a local padded file (index lost), `paddedSize` is set to the file size and `size` to the catalog size.

## Acceptance criteria (offline; tiny Ogg Opus files built in the test with valid CRCs)
1. `ogg_crc(b"123456789") == 0x89A1897F`; a built page's stored CRC equals `ogg_crc` of the page with the field zeroed.
2. **Small padding:** `pad_opus(file, 1000)` grows the file by 1,000 + 13 + 4 bytes (comment length field and text, plus any extra lacing byte the test computes); `audio.ogg_info` gives the same `preSkip`, `lastGranule` and `durationMs`; the `OpusTags` comment count is one higher and the last comment is `SONGRUSH_PAD=` + 1,000 × `A`; every page CRC is valid; the audio pages' payloads, granules and header types are byte-identical to the original's.
3. **Large padding:** `pad_opus(file, 65536)` produces a multi-page `OpusTags` (continuation flag on the second page), shifts the later pages' sequence numbers by the added page count, and keeps every page CRC valid and every audio payload identical.
4. **Rejects:** a Vorbis file, a file without `OpusTags`, an already padded file, and `pad_len` 0 or 65,537 raise `ValueError`.
5. **`pad_cache`:** on a cache of three files and their index, with a seeded `random.Random`, every file grows by its own random length (the three lengths differ), the index gains `paddedSize` equal to the new sizes, `durationMs` stays; a second run pads nothing and leaves the bytes unchanged; a held `.prefetch.lock` raises `PrefetchLocked` and changes nothing.
6. **Prefetch after padding:** `audio.prefetch` on a padded cache makes no request and reports `skipped`; with `index.json` deleted it rebuilds the entries (with `paddedSize`) without a request.
7. **CLI:** `main(["stray"])` returns 2; with `pad_cache` patched to raise `PrefetchLocked`, 3.
8. Every cycle 030 test still passes; `.venv\Scripts\python.exe -m pytest` exits 0 from `data/`.

## Test list
- `data/tests/test_pad.py`: criteria 1-7.

## File ownership
- Test writer: `data/tests/test_pad.py`.
- Implementer: `data/songrush_data/pad.py`, `data/songrush_data/audio.py`.

## After the cycle (lead)
When the prefetch has finished, run `python -u -m songrush_data.pad` and check offline that every padded file still parses with `ogg_info` to its recorded duration with all page CRCs valid, and count how many padded sizes still equal some public AnimeThemes size in the catalog.

## Out of scope
Matching by decoded audio content (needs a reference audio database; accepted in D-055). The backend.

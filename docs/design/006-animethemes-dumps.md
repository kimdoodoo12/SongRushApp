# 006 AnimeThemes dumps and bulk export (S-006)

Stack updated for D-029 (2026-11-02); decisions unchanged.

Researched 2026-11-02 by `explorer`. Sources were the public docs, the AnimeThemes GitHub source, and 7 direct requests sent with the SongRushBot UA, at least 3 s apart. No dump was downloaded.

## Questions (BACKLOG S-006)
1. Does AnimeThemes publish database dumps or a bulk export, and where?
2. What is the format, and how often is it updated?
3. What do the license and terms say?
4. Does the export include audio links, theme slugs and AniList ids?
5. Could it replace C-002's paginated crawl?

## Findings

### F1. Dumps exist in the server code, but no public download route was reachable
- `animethemes-server` has a dump feature.
  - `config/dump.php` defines `DUMP_DISK`, `DUMP_URL` (e.g. a `dump.` subdomain) and `DUMP_PATH`.
  - `DumpCommand.php` validates mysqldump options (`default-character-set`, `set-gtid-purged`). The format is therefore SQL (inferred).
  - Sources:
    - raw.githubusercontent.com/AnimeThemes/animethemes-server/main/config/dump.php
    - raw.githubusercontent.com/AnimeThemes/animethemes-server/main/app/Console/Commands/Storage/Admin/DumpCommand.php
- Probes:

  | Request | Result |
  |---|---|
  | `dump.animethemes.moe/latest/wiki` (HEAD) | 404 |
  | `dump.animethemes.moe/` | 404 |
  | `api.animethemes.moe/dump` (HEAD and GET) | 404 |
  | `api.animethemes.moe/dump/latest` | 404 |
  | `animethemes.moe/dump` | timed out at 25 s |

- Third-party docs (a search snippet only; the page itself returned 410) gave conflicting URLs. They were not verified.
- **Size, update cadence and data license were not found.** The API docs' "Released under the MIT License" covers the docs and code, not the data.

### F2. The JSON:API we planned on is marked deprecated
- `api-docs.animethemes.moe/reference.html` warns that the JSON:API is deprecated and will be removed. The warning itself does not name GraphQL. The site nav has a GraphQL section, and design 007 cites a "Migrating from JSON:API" page.
- **This needs to be verified with a quote and any timeline.** C-001 and C-002, and every saved fixture, are JSON:API shapes.

## Options
- **A. Keep the paginated crawl (C-002) on JSON:API.** It works today, and fixtures exist. If JSON:API is removed, we would need a rewrite.
- **B. Switch the crawl to GraphQL before C-001 and C-002 are implemented.** It is the documented future, and it fetches exactly the needed fields. It needs new fixtures, and GraphQL's limits and pagination are not yet known.
- **C. Dump loader.** No public dump was reachable. It would need a SQL import and tracking of an unpublished schema. Rejected for now.

## Decision
- **Do not use dumps** (option C rejected for now).
- **A or B is decided by S-007**, a short investigation of the GraphQL API that runs before C-001 and C-002 are implemented. Until then, C-001 and C-002 stay in Ready unchanged.
  - If S-007 confirms a near removal date, or shows that GraphQL covers every field, S-007's resulting items revise C-001 and C-002 and their fixtures.
  - C-002 keeps the fetcher behind a small interface (fetch page -> raw JSON). This **does not** isolate C-001: the transform depends on the raw shape, so a source switch rewrites it too. Design 007 confirms this.

## Resulting items
- **S-007** design: AnimeThemes GraphQL vs JSON:API.
  - Questions:
    - Quote the deprecation notice and any removal date.
    - Can a single GraphQL query return, per anime: name, year, season, synonyms, themes (type, slug), song (title, artists), entries (version), videos -> audio (link, size), resources (site, external_id) and series?
    - What are the pagination and rate limits?
    - What is the response size for a page of 100 anime?
  - Save one GraphQL response as a fixture.
  - Output: keep or switch, and updated C-001 and C-002 criteria if switching.

## Review (combined with design 007, round 1)
| Finding | Change |
|---|---|
| M3: the reference page does not name GraphQL | Reworded; the GraphQL successor evidence comes from design 007 |
| M3: "interface isolates C-001" | Corrected: a source switch rewrites C-001 |

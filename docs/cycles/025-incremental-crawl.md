# 025 incremental-crawl (C-012)

## Goal
Stop the AnimeThemes crawl from clearing its whole cache and refetching all ~51 pages in the common case where AnimeThemes only added anime at the end (cycle 023 review F1, F2), while still restarting whenever earlier pages really changed.

## Background
AnimeThemes pages are ordered by anime id, and new anime get larger ids, so additions land at the end. Pages hold `PER_PAGE` = 100 items. Cycle 023 accepted an append only when the new ids fit on the cached last page and the `total` grew by exactly that number, and it restarted on any `total` change during a pass. With real 100-item pages, an addition that spills onto a new page, or any addition during a 9-minute first or resumed crawl, therefore cleared the cache and restarted.

## Interface changes: `data/songrush_data/animethemes.py`
Same `crawl(client, cache, *, refresh_all=False, log=print) -> CrawlResult`.

1. **Total grew during a pass.** The checks below run before the duplicate check, so a shifted id leads to a restart, not a `ValueError` (spec review M1).
   - **Freshly fetched page n** (fetched from the source in this pass and not already a verified refetch, such as the resume check) reporting a larger `total` than remembered: the crawl refetches page n-1 with `refresh=True` (when n > 1). If page n-1's ids are unchanged, the earlier pages still hold, so it logs `crawl: total grew on page <n> (<old> -> <new>), continuing`, remembers the new total and that it grew, and continues without clearing anything. If page n-1's ids changed, it clears the prefix and restarts (spec review H1: an earlier deletion combined with additions keeps the item count right while dropping an anime, so the count alone cannot be trusted; the backlog's "every page already read keeps its ids").
   - **Page read from the cache** reporting a larger `total`: accepted silently with no request (it was validated when it was stored, for example after an earlier append), and the larger total is remembered. This keeps the run after an append from clearing the cache (spec review M2). No `total grew` line is logged for cached pages (N2).
   - **A smaller total** on any page still logs `crawl: total changed on page <n> (<old> -> <new>)`, clears the prefix and restarts (a deletion shifts later pages).
2. **Freshness with spill-over.** When the last page was read from the cache and its fresh copy keeps the cached ids as a prefix, the change is an append when either
   - (a) the new ids fit on the page: `total` grew by exactly the number of new ids on the page (cycle 023), or
   - (b) the fresh page is full (`PER_PAGE` items), now has `hasMorePages` true, and the new total is larger than the old one by at least the number of new ids on the page (which may be zero, when the cached last page was already full). A page that only flips `hasMorePages` with an unchanged total is not an append (N1).
   In both cases the fresh page replaces the cached entry, the new items are added, the remembered total becomes the fresh total, the crawl logs `crawl: last page <n> gained <k> anime` (k may be 0 in case b), and when `hasMorePages` is true it continues with the next pages as usual. Any other change still clears and restarts.
3. **End-of-pass check** (a cheap extra guard, not the main one). If the total grew during the pass (rule 1 or 2), the pass must end with exactly `total` items; otherwise the crawl logs `crawl: item count <n> does not match total <t> after the source grew` and clears and restarts. When the total did not change during the pass, a mismatch stays a warning, as before (cycle 017).
4. The restart cap of two (cycle 023), the resume-shift check, validation, the duplicate check and the order warning are unchanged.

## Acceptance criteria (offline, fake transport; synthetic pages of 100 items unless stated)
1. **Spill onto a new page:** three cached pages of 100, 100 and 100 items (total 300, last page `hasMorePages` false); the source now has 301 anime (page 3 full with `hasMorePages` true, page 4 with 1 item, every page reporting total 301). The crawl makes exactly the requests `[3, 4]`, clears nothing, logs `gained 0 anime`, and returns 301 items in id order. **A second crawl with the same cache against the unchanged source makes exactly `[4]` and logs no `cleared` line** (M2).
2. **Spill from a partly filled page:** cached pages of 100, 100 and 99 items (total 299); the source adds 2 (page 3 full, page 4 with 1 item, total 301). Requests `[3, 4]`, nothing cleared, `gained 1 anime`, 301 items; a second crawl against the unchanged source makes exactly `[4]` with no `cleared` line.
3. **Addition during a resumed crawl:** pages 1 and 2 cached from a source with total 250; the source now reports total 251 on every page and page 3 holds 51 items. The crawl requests `[2, 3]` (the resume check of page 2, then page 3), logs `total grew`, clears nothing, and returns 251 items.
4. **Addition during a first crawl:** no cache; page 1 reports total 250 and pages 2 and 3 report 251, with 251 items overall, and page 1's ids do not change. Requests `[1, 2, 1, 3]` (page 1 is refetched once to confirm it still holds), `total grew` logged once, nothing cleared, 251 items.
5. **Deletion still restarts:** with a `SequenceTransport`, page 1 first reports total 300 and then 299; pages 2 and 3 report 299 and hold the shifted ids. The crawl logs `total changed` once, clears and restarts, makes exactly `[1, 2, 1, 2, 3]`, and returns 299 items.
5b. **Deletion plus additions during a first crawl** (the H1 case): after page 1 is read, the source deletes one anime from page 1's range and adds two at the end, so pages 2-4 report total 301 and page 1's fresh ids differ. The crawl refetches page 1 when page 2 reports the larger total, sees changed ids, clears and restarts, and the final result holds exactly the source's current ids (none lost, none stale).
6. **Growth that does not add up restarts:** the total grows to 301 during the pass but the pages hold only 300 items in total. The crawl logs `does not match total`, clears and restarts; a source that keeps doing this raises `ValueError` after two restarts.
7. **Changed id in an earlier page still restarts:** a resumed crawl whose cached page 2 differs from the fresh page 2 clears and restarts, as in cycle 017.
8. **Replaced cycle 023 tests:** `test_crawl_total_changed_mid_pass_clears_and_restarts` and `test_crawl_total_keeps_changing_raises_after_two_restarts` pinned "any total change restarts". They are replaced by criteria 4, 5 and 6 (a growing total now continues; a shrinking total, or growth that does not add up, restarts). Every other existing crawl test passes unchanged, including `test_crawl_summary_and_total_warning` (a static mismatch stays a warning).
9. `.venv\Scripts\python.exe -m pytest` exits 0 from `data/`.

## Test list
- `data/tests/test_animethemes.py`: criteria 1-7 and 5b added; the two tests named in criterion 8 replaced. No other existing test changes.

## File ownership
- Test writer: `data/tests/test_animethemes.py`.
- Implementer: `data/songrush_data/animethemes.py`.

## Review changes (spec round 1)
- **H1.** The end-of-pass count check could not catch a deletion in an earlier page combined with additions: with offset pagination the collected count always equals the new total, so an anime was silently lost and the damage survived later runs (the critic's simulation). Now a freshly fetched page with a larger total triggers a refetch of the page before it, and the pass continues only if that page's ids are unchanged. The count check stays as a second guard.
- **M1.** The growth check runs before the duplicate check, so an id shifted into a later page restarts the crawl instead of stopping the build with `ValueError`.
- **M2.** The run after a successful append must not clear the cache because cached earlier pages carry the old total; criteria 1 and 2 now pin a second run that only checks the last page.
- **N1, N2.** Case (b) requires a strictly larger total; `total grew` is logged for fetched pages only.
- Criterion 5 names the transport and the expected outcome after the restart.

## Out of scope
A change with no net effect on the total (one anime deleted and one added in different pages) is not detected; this limit existed in cycles 017 and 023 too. `refresh_all` covers it.
Line buffering of the build log and the gap/timeout test (cycle 023 follow-ups). Any change to `build.py`.

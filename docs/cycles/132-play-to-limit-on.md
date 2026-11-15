# 132 play-to-limit-on (B-037, from S-008 design 125, D-129, issue #44 request 1)

## Goal
Turn the play-to-limit rule on by default, so every room plays a song to its time limit after it is decided (D-129), and state the rule in CLAUDE.md and the deploy guide.

## Background
- Cycles 126 to 131 built the rule behind `songrush.game.play-to-limit` (env `SONGRUSH_GAME_PLAYTOLIMIT`), default off: the reveal window and `playUntil` (126), skips in it (127), the snapshot in it (128), the music, clock and disc going on (129), the skip button in it (130), reload and late join in it (131).
- `GameProperties` (backend/src/main/java/com/songrush/GameProperties.java) is `record GameProperties(boolean prefetchOnReveal, boolean playToLimit)`; an absent property binds false.
- An experiment by the lead (the default flipped in the working tree, `gradlew test`, then reverted): of 106 test classes only `GamePropertiesBindingTest` failed (2 of 9 cases, the ones that read an absent property as off). No socket test depends on the old default.
- D-129 is a rule for every room, not a host option.

## Design
- `playToLimit` defaults to true when the property is absent (`@DefaultValue("true")` on the record component). `false` still turns it off, through the property or `SONGRUSH_GAME_PLAYTOLIMIT=false`. `prefetchOnReveal` is unchanged (default off).
- CLAUDE.md "Product decisions", the bullet "Each song plays from its start, at most 90 s": the sentence "A song ends at the first correct answer, a passed skip vote, or the end of the song" is replaced by D-129's rule: the music plays to the 90 s limit or the end of the song, and a passed skip vote moves on early. In 선착순 the answer is revealed at the first correct answer; in 순위전 it is never revealed at a correct answer, only once every connected player has answered or picked. After the reveal the music plays on; in 선착순 chat is ordinary chat and nothing more scores, and in a choice room no further pick is accepted. The other sentences stay.
- CLAUDE.md "Multiplayer rules", the prefetch exception: with the window, the next song's file is prefetched 5 s before the window ends (one reveal pause before the music stops), not from the start of the reveal; reword that clause only.
- `docs/deploy-guide.md`, in the current-settings note at its top (lines 6-8, beside `SONGRUSH_GAME_PREFETCHONREVEAL`, with the note's count of settings updated): `SONGRUSH_GAME_PLAYTOLIMIT`, on unless set to `false`; `false` brings back the 5 s reveal.
- The comments that say "absent or false keeps every reveal at the reveal pause" are updated.

## Acceptance criteria
1. `GamePropertiesBindingTest`: an absent `songrush.game` gives `playToLimit` true; `songrush.game.play-to-limit=false` and `SONGRUSH_GAME_PLAYTOLIMIT=false` give false; `true` gives true; `prefetchOnReveal` absent is still false.
2. `PlayToLimitSocketTest` runs with the default configuration (its `songrush.game.play-to-limit=true` property is removed): its existing checks still pass, so a song answered early gets a `reveal` with `playUntil` in the future through the real socket with no property set.
3. CLAUDE.md's song bullet and prefetch clause state the new rule as in Design; the deploy guide's settings note lists the variable.
5. Browser check (lead), with the local server started like production: `SONGRUSH_GAME_PREFETCHONREVEAL=true` and no play-to-limit variable. Two players, a song answered early: the music plays on with the card, the next song starts when the music ends (held prepare in the last 5 s), and a passed skip in the window moves on at once.
4. Every other test passes unchanged; `./gradlew test` passes from `backend/`; `npm test` passes from `frontend/` (unchanged).

## Test list
- `backend/src/test/java/com/songrush/ws/GamePropertiesBindingTest.java`: the play-to-limit cases for criterion 1 (the two cases that read an absent property as off are changed to the new default).
- `backend/src/test/java/com/songrush/ws/PlayToLimitSocketTest.java`: the property line removed and its comments (the header, "with the switch off" at :190) updated to the default; criterion 2. Its `thePropertyBindsTrue` case keeps asserting `playToLimit()` true, now from the default.

## File ownership
- Test writer: the two test files (and their header comments).
- Implementer: `GameProperties.java` (and its comment), `CLAUDE.md` (the song bullet and the prefetch clause), `docs/deploy-guide.md` (the settings note).

## Out of scope
- The follow-ups of cycles 128 to 131 (the skip vote race, the reveal card's "잠시 후" wording, the announce line after a late decode), any client change.

## Spec review
One round. No Blocker; two Medium and three Nits, all taken.
- Medium: the CLAUDE.md wording left out parts of D-129 (no reveal at a correct answer in 순위전; chat ordinary and no more scoring after the reveal in 선착순; no more picks in a choice room) and said "decided" where D-129 says "revealed". The bullet now carries D-129's sentences.
- Medium: a new default-config socket test would duplicate `PlayToLimitSocketTest`; removing that test's property meets the item. The production setup (prefetch on with the window) has no socket test; the lead's browser check runs exactly that setup (criterion 5), and the Room-level tests of cycles 126-127 cover it with prefetch on.
- Nits: the CLAUDE.md prefetch clause ("from the start of the reveal") is reworded; stale "default false" and "switch off" comments in the two tests are updated; the deploy guide's line goes into its current-settings note, not the past record.
- Checked by the critic: `@DefaultValue("true")` on one record component binds with the prefix absent; every production `Room` gets the value; turning it on at deploy is what D-129 and design 125 intend.

## Result
- Tests first: commit `1e24604`. `GamePropertiesBindingTest` (absent means on, `false` by property and by env, the two switches independent) and `PlayToLimitSocketTest` (its property removed). Red, run by the lead: 4 failures, all assertions (`playToLimit()` false by default, no `playUntil` in the socket test's `reveal`).
- Implementation: commit `26803b8`, first attempt: `@DefaultValue("true")` on `playToLimit`; CLAUDE.md's song bullet carries D-129; the deploy guide's settings note lists the variable. After the review, commit `6785cf1` rewords the CLAUDE.md prefetch clause and the deploy note.
- Green, run by the verifier with `--rerun-tasks` (one earlier run hit the known `NoSuchFileException` of overlapping Gradle runs and was rerun): 1249 tests, 0 failures, 0 errors, 1 skipped; `GamePropertiesBindingTest` 10/10, `PlayToLimitSocketTest` 2/2; `npm test` 56 files, 860 tests passed. The locked tests are unchanged since `1e24604`. Criteria 1 to 4 confirmed.
- The Stop hook ran its own `gradlew test` while the verifier's run was going and failed with `EOFException` (the overlapping-Gradle pitfall in CLAUDE.md), counted as attempt 1/3. Not a test failure: the verifier's rerun passed.
- Review of the diff: no Blocker or High. One Medium, fixed in `6785cf1`: "5 s before the reveal's music ends" was wrong for a song decided in its last 5 s and for a skip or deadline reveal; the clause now says one reveal pause before the next song starts. One Nit fixed: the deploy note says the server needs no setting for this.
- Criterion 5, by the lead in Chromium against the local server started like production (`SONGRUSH_GAME_PREFETCHONREVEAL=true`, no play-to-limit variable), two players: song 1 answered about 5 s in; both clocks agreed through the window (60.99 s left 4 s after the reveal); 60.1 s after the reveal, with 5 s of music left, the held `prepare` hid the skip button while the music and the card went on; song 2 played 68.1 s after the reveal. Song 2 answered early, both voted skip in the window, and song 3 played 3.0 s after the passing vote. Console: only the placeholder covers' 404s.
- The rule is now on by default: a deploy of `dev` turns it on in production.

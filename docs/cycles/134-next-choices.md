# 134 next-choices (B-039, from D-133)

## Goal
In a choice room, the server sends the next song's choice cover URLs when a song is revealed, so clients can load the covers during the reveal and the next song does not wait for them.

## Background
- Today the next song's covers reach clients only in its `prepare` (Room.java:950), which with play-to-limit and prefetch goes out one reveal pause before the music ends (cycle 126). A client reports `ready` only after its audio and its covers have loaded, so slow covers delay the next song for everyone.
- D-133 (owner, 2026-11-15): covers may load from the reveal; the audio stays at the last reveal pause (D-119). Covers alone do not say which choice is correct, and A9 (CLAUDE.md "Multiplayer rules") already sends them, shuffled, in `prepare`.
- The choices of every song are drawn at the game's start (`choices`, Room.java:149, 426), so the next song's set and order are known at the reveal.
- `endSong` (Room.java ~986-1045) sends `reveal`, then `scores`; then, with prefetch and no window, `prepareEarly()` in the same turn; with a window, `skipState` and the delayed `prepareEarly`; without prefetch, `skipState` (when a window) and `afterReveal` after the pause.

## Design
In a choice room, every `reveal` of a song that has a next song carries one more key, `nextChoices`: the next song's choices exactly as its `prepare` will carry them (`choices.get(songIndex + 1).wire()`, the same shuffled, opaque `/cover/` URLs). Nothing else of the next song.
- Not added: in a chat room; on the last song's reveal.
- Added on every such reveal (by answers, skip or deadline; with or without prefetch or play-to-limit). When the `prepare` follows in the same turn it is redundant but harmless; one rule is simpler than a condition.
- A separate frame was tried first (an experiment by the lead): it broke 99 existing cases in 5 classes that pin the exact frame list after a choice reveal. A key in `reveal` broke 26 cases, all through one helper (`RoomPlayToLimitTest.assertRevealOn`, which pins the reveal's exact keys).
- The snapshot in a reveal window (cycle 128) copies the broadcast `reveal` (`lastReveal`, Room.java:1029), so a reload in the window gets `nextChoices` too, provided the key is put in the map before that copy (next to `correctChoice`, Room.java:1012).
- CLAUDE.md "Multiplayer rules", the A9 sentence: the next song's choice cover URLs also go out in the `reveal` of the song before it (D-133), shuffled as in its `prepare`, with nothing marking the correct one. Accepted property, unchanged by this cycle: cover ids are fixed per anime, and a game uses each series once, so a choice whose cover was an earlier answer is certainly wrong; the earlier list gives everyone more time to see that, not new information.
- What it adds: lead time only. The list is the one `prepare` carries (drawn at the game's start); everyone gets it at once; the audio stays gated (D-119).

## Acceptance criteria (JUnit)
1. Choice room, play-to-limit and prefetch on, a song decided at 10 s: the `reveal` has `nextChoices` equal to the `choices` of the later `prepare` of song 1.
2. The same with prefetch off, with play-to-limit off, after a skip reveal and after a deadline reveal.
3. No `nextChoices` key: a chat room's reveal; the last song's reveal in a choice room.
4. `nextChoices` is a list of opaque `/cover/` URLs, as in `prepare`; the reveal gains no other key; no theme or anime id of the next song appears in the frame.
5. A snapshot taken in a window (cycle 128) carries `reveal.nextChoices` too.
6. `RoomPlayToLimitTest.assertRevealOn` (:401) and `assertRevealOff` (:407) accept `nextChoices` exactly when the room is a choice room and a next song exists (the reveal's `index` + 1 below the song count); nothing else in that class changes. Every other test passes unchanged.
7. `./gradlew test` passes from `backend/`.

## Test list
- New `backend/src/test/java/com/songrush/game/RoomNextChoicesTest.java`: criteria 1 to 5.
- `RoomPlayToLimitTest.java`: only the reveal-key helper(s) of criterion 6.

## File ownership
- Test writer: the new test file and the declared helper change.
- Implementer: `backend/src/main/java/com/songrush/game/Room.java`, `CLAUDE.md` (the A9 sentence).

## Out of scope
- The client (F-038). Any change to how a snapshot is built (it already copies the reveal, criterion 5).

## Spec review
One round. No Blocker; one High, one Medium, one Nit, all taken.
- High: the out-of-scope line said a snapshot would not carry `nextChoices`, against criterion 5. Removed; the design says the key goes in before the snapshot copy.
- Medium: the design states the accepted property (a cover that was an earlier answer marks a wrong choice) and that only lead time is added.
- Nit: criterion 6 names `assertRevealOff` and how the helpers know a next song exists. The redundant copy when `prepare` follows in the same turn is kept on purpose (one rule).
- Checked by the critic: the list equals the later `prepare`'s (drawn once at the start); a skip, a resume or the early prepare send the same list; watchers promoted at `prepare` are as today.

## Result
- Tests first: commit `527cb3d`. `RoomNextChoicesTest` (new, 35 cases) and the declared helper change in `RoomPlayToLimitTest` (`revealKeysOf`, used by `assertRevealOn` and `assertRevealOff`). Red, run by the lead: 47 failures in the two classes, every one naming the missing `nextChoices` key.
- Implementation: commit `a1a0170`, first attempt: `nextChoices` put in the reveal map in the choice branch, before the snapshot copy (+4); CLAUDE.md's A9 sentence names it.
- Green, run by the verifier with `--rerun-tasks`: 1294 tests, 0 failures, 0 errors, 1 skipped. The locked tests are unchanged since `527cb3d`; against `dev` only the new test and the helper changed. Criteria 1 to 7 confirmed.
- Review of the diff: no Blocker, High or Medium. Checked: `choices` has one entry per song for the whole game, so the index is safe; `prepare` and the reveal send the same list; the client ignores the unknown key until F-038. One Nit to Follow-ups: two comments in `Room.java` (the class header and the `covers` field) still say cover ids leave only in `prepare` and the snapshot.
- No client change yet: F-038 makes the client use the key.

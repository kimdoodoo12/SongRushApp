# 125 play-to-limit (S-008, from D-129, issue #44 request 1)

## Questions
1. What does the server do when a song is decided before its time limit, now that the music must go on (D-129)?
2. When is the answer revealed in 선착순 and in 순위전, and what are chat and picks between the reveal and the end of the music?
3. How does a skip vote work before and after the song is decided?
4. What happens to the next-song prefetch (D-119) and its spoiler guard (D-120)?
5. What does a player who reloads or joins during the longer reveal see?
6. Which tests pin the early end today, and how large is the migration?

## Findings
- **The reveal already is a waiting period.** `endSong` sends `reveal` and `scores`, sets `Phase.REVEAL` and schedules `afterReveal` after `REVEAL_PAUSE` = 5 s (`Room.java:908-953`). In that phase chat is broadcast and never answer-checked (`:703-706`) and a pick is ignored (`:623`). So "nothing more scores, chat is ordinary, no further pick" (D-129) is what `REVEAL` does today.
- **The client stops the music by hand at the reveal.** `onReveal` calls `stopTimer()` and `stopSource()` (`session.js:670-695`); the reducer sets `timer: null, started: false` (`state.js:457-465`), which hides the time bar and the skip button and stops the disc (`isAudible`, `state.js:129`). Without that, the audio would stop by itself at `deadlineAt`: `planPlayback` schedules `source.stop` there (`session.js:~296`).
- **The client is told no reveal length.** The next `prepare` simply arrives (`start` carries `startAt` and `deadlineAt`; nothing else has a time).
- **A skip is only accepted in `PLAYING`** (`Room.java:597-609`); votes are cleared in `endSong` (`:910`). The client also refuses to send one once `round.revealed` (`session.js:987-989`).
- **Prefetch mode is on in production** (cycle 107: `SONGRUSH_GAME_PREFETCHONREVEAL=true`; `docs/deploy-guide.md:8`). In that mode `endSong` goes straight to `enterPrepare(songIndex + 1)`: the phase is `PREPARE` for the next song during the reveal, the next `prepare` carries `holdMs` = 5000, `revealing` is true, and a chat line matching the next song's titles goes to its sender only (D-120) (`Room.java:940-952`, `:683-702`). The accepted exposure is that a modified client can hear the next song about 5 s early (D-119).
- **A reload during a reveal shows an empty waiting screen.** `currentSong()` is null outside `PREPARE` and `PLAYING` (`Room.java:288-298`), so the snapshot has `song: null` and the client waits for the next `prepare` (`state.js:322-338`). That is at most 5 s today. In prefetch mode the snapshot is already the next song's prepare.
- **A resume from a pause in `REVEAL` moves on at once** (`Room.java:517-528`).
- **Tests.** About 45 backend test files and 21 frontend test files pass through a reveal. The frame sequence at the decision (`correct`, `chat`, `reveal`, `scores`) is asserted in many of them (`RoomTest.java:360`, `RoomRankedTest.java:253`, `RoomChoiceTest.java:315`). Most helpers then advance 5 s to reach the next song.

## Options
**A. Stretch the reveal to the end of the music.** The decision still sends `reveal` and `scores` and enters `REVEAL`, exactly as today. Only the length of the reveal changes: it lasts until the music ends, and a skip vote can end it early. The client keeps the audio, the clock and the disc going during the reveal.
- For: `REVEAL` already has the chat and pick rules D-129 asks for; the frame sequence at the decision does not change; the phase names keep their meaning. The server change is the reveal's end time, a skip during the reveal, and the snapshot.
- Against: every test that reaches the next song by advancing 5 s after a decision must advance to the end of the music or pass a skip.

**B. Stay in `PLAYING` with a "decided" flag.** The reveal is sent, the phase does not change.
- Against: every `PLAYING` check (chat scoring, picks, ranked answers, pick count, skip, deadline, snapshot, disconnect) needs a second condition, and the reveal data must be kept for snapshots anyway. More code paths change for the same behaviour.

**C. Client only.** The server ends the song as today; each client keeps playing the audio.
- Against: the next song would start while the old one plays, or clients would differ in when they move on. The server stops being the authority on timing. Rejected.

## Decision
**Option A.** Decided by the lead: it is the smallest change that gives D-129.

### Server
1. **The reveal window.** When a song is revealed, the server computes `playUntil`:
   - revealed because it was **decided by answers** (선착순: the first correct answer; 순위전: every connected player has answered or picked): `playUntil = deadlineAt`;
   - revealed by a **passed skip vote** or at the **deadline**: `playUntil = now` (the music stops, as today).
   The window ends at `windowEnd = max(playUntil, now + REVEAL_PAUSE)`. So a reveal at the deadline or by a skip lasts 5 s as today, a song decided 2 s before its end still shows its reveal for 5 s, and a song decided early shows it until the music ends.
2. **`reveal` gains `playUntil`** (server epoch milliseconds). Nothing else in `reveal` or `scores` changes.
3. **At `windowEnd`** the room moves on as `afterReveal` does today (next `prepare`, or `gameOver` after the last song).
4. **Skip during the window** (new). While the music is still playing in `REVEAL` (`now < playUntil`) and the next `prepare` has not been sent, a connected Room player can vote to skip with the same message and the same majority rule. Votes start from zero at the reveal: a vote cast before the decision meant "I give up", not "move on". When the vote passes the server sends `over{index}` to everyone and moves on at once.
5. **Prefetch mode.** The next `prepare` is sent at `windowEnd - REVEAL_PAUSE` with `holdMs` = 5000, not at the reveal. From then on the room is in the existing `revealing` state (phase `PREPARE` for the next song, spoiler guard on). So the next song is downloaded during the last 5 s of the window, and a modified client hears it at most as early as today (D-119 is unchanged). A skip is not accepted in those last 5 s. When a skip passes earlier, the `prepare` goes out at once with no hold, as in the mode without prefetch.
6. **Snapshot in `REVEAL`** (new). While the window is open the snapshot's `song` carries what a playing song carries (`index`, `total`, `audioUrl`, `startAt`, `deadlineAt`, and the choice or ranked fields) plus `reveal`: the same object that was broadcast, `playUntil` included. A reloading player and a late joiner see the reveal and hear the rest of the music. This leaks nothing: the answer is already public.
7. **Unchanged:** scoring; the frames at the decision and their order; chat and pick rules in `REVEAL`; pause and resume (a resume in `REVEAL` moves on at once); `pickState` (not sent in `REVEAL`); the audio token lifetime (it already covers a full song).

### Client
1. On `reveal` with `playUntil` later than now the audio is not stopped and the clock keeps counting down to `playUntil`; the disc keeps spinning. The reveal card shows as today. With `playUntil` at or before now (or absent, for an older server) the client behaves as today.
2. The skip button stays during the window, with its vote count, and disappears when the music ends or the next `prepare` is held.
3. `over{index}` stops the audio and the clock.
4. A `restore` or `watching` snapshot whose song has `reveal` shows the card and plays the rest of the music late.

### Smaller choices made here
- Votes are cleared at the reveal (point 4). The alternative, keeping them, would move the room on by itself right after the first correct answer whenever a majority had voted to skip earlier.
- No skip in the last 5 s (point 5). The next song's `prepare` is already out then; cancelling its hold would save at most 5 s and add a third path.
- A skip that passes after the decision moves on at once, with no minimum reveal time: the players asked for it.

### Deploy order
Server and client ship in one image. A tab still running the old client against the new server stops its audio at the reveal as it does today and ignores `over`; nothing breaks.

## Failure modes
- **Everyone leaves during the window:** the room pauses; a resume in `REVEAL` moves on at once (unchanged).
- **The last unanswered player disconnects in 순위전:** the song is decided by the existing rule (`endIfAllAnswered`), so the window opens with `playUntil = deadlineAt`.
- **A reveal in the last seconds:** `windowEnd` is 5 s after the reveal; the music has stopped by itself at `deadlineAt`; no skip is offered (`now >= playUntil`, or the last 5 s).
- **A skip vote and a disconnect together:** the disconnect lowers the majority and can pass the vote, in the window as in `PLAYING`.
- **A slow client that never became ready for the next song:** unchanged (`PREPARE_TIMEOUT`).
- **Longer games:** ten songs take up to 15 minutes plus loading when nobody skips. Accepted by D-129.

## Resulting items
- **B-034** backend: the reveal window and `playUntil` (server points 1 to 3, 5, 7).
- **B-035** backend: skip during the window and `over` (server point 4).
- **B-036** backend: the snapshot during the window (server point 6).
- **F-035** frontend: the music, the clock and the disc go on during the reveal; `over` (client points 1 and 3).
- **F-036** frontend: the skip button during the window (client point 2).
- **F-037** frontend: reload and late join during the window (client point 4).
- Docs: the rule in `CLAUDE.md` "Product decisions" is updated with B-034.

## Requests made
None. No external source was contacted and no fixture was saved.

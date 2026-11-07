package com.songrush.feedback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

// Cycle 083 backend item 2 (D-097): FeedbackStore, one JSON file per store-dir.
//
// Production API this test relies on (package com.songrush.feedback):
//   public record CreatedIssue(int number, String url)
//   public record FeedbackRecord(String id, Instant createdAt, String category, String title, String body,
//       String lang, String page, String status, Integer issueNumber, String issueUrl, Instant updatedAt)
//     - status is "new", "sent" or "hidden"; issueNumber/issueUrl are null until sent;
//     - id is 12 random URL-safe characters [A-Za-z0-9_-];
//     - createdAt and updatedAt come from the injected Clock; a new record has updatedAt == createdAt,
//       and every status change sets updatedAt to clock.instant().
//   public class FeedbackStore {
//     public FeedbackStore(Path dir, Clock clock);
//       - dir == null: storage disabled, no file I/O;
//       - otherwise: creates dir if missing, deletes leftover "feedback.json.*.tmp" files (and nothing
//         else), probes the folder by writing and deleting a temp file, then loads <dir>/feedback.json
//         (missing file = empty list). If creating or probing the folder fails, it logs at ERROR a line
//         containing "feedback: store-dir <dir> not writable" and the store is disabled.
//       - An unparsable feedback.json is renamed to "feedback.json.broken-" + clock.millis(), an ERROR
//         line containing "broken" is logged, and the store starts empty and enabled.
//     public boolean enabled();
//     public FeedbackRecord add(String category, String title, String body, String lang, String page)
//         throws IOException, FeedbackStore.FullException;  // stores a "new" record, values as given
//     public List<FeedbackRecord> list(String status);  // "new"|"sent"|"hidden"|"all"; newest first
//         (createdAt descending; equal createdAt in reverse insertion order)
//     public Optional<FeedbackRecord> get(String id);
//     public FeedbackRecord hide(String id) throws IOException;          // new -> hidden
//     public FeedbackRecord unhide(String id) throws IOException;        // hidden -> new
//     public FeedbackRecord markSent(String id, CreatedIssue issue) throws IOException;  // -> sent
//     public static final class FullException extends Exception  // add() with 1000 "new" records
//   }
//   - Every write rewrites the whole file atomically (temp file in the same folder + Files.move
//     ATOMIC_MOVE), so no temp file is left behind. Every access is serialised.
//   - Caps: with 1000 "new" records, add() throws FullException and changes nothing. When an add makes
//     the total exceed 2000, the oldest (by createdAt) "sent" or "hidden" records are removed until the
//     total is 2000; "new" records are never pruned.
//   The file format is in FeedbackFiles.
@ExtendWith(OutputCaptureExtension.class)
class FeedbackStoreTest {

	private static final String URL_SAFE_12 = "[A-Za-z0-9_-]{12}";

	@TempDir
	Path tmp;

	private final MutableClock clock = new MutableClock();

	private Path dir() {
		return tmp.resolve("store");
	}

	private FeedbackStore open() {
		return new FeedbackStore(dir(), clock);
	}

	private static FeedbackRecord add(FeedbackStore store, String title) throws Exception {
		return store.add("bug", title, "body of " + title, "ko", "start");
	}

	private static List<String> ids(List<FeedbackRecord> records) {
		return records.stream().map(FeedbackRecord::id).toList();
	}

	private List<String> filesInDir() throws Exception {
		try (Stream<Path> files = Files.list(dir())) {
			return files.map(p -> p.getFileName().toString()).sorted().toList();
		}
	}

	private static String errorLine(CapturedOutput output, String needle) {
		return output.getAll().lines().filter(l -> l.contains(needle)).findFirst().orElse("");
	}

	@Test
	void nullDirIsDisabled() {
		assertThat(new FeedbackStore(null, clock).enabled()).isFalse();
	}

	@Test
	void missingFolderIsCreatedAndStartsEmpty() throws Exception {
		Path nested = tmp.resolve("a").resolve("b");
		FeedbackStore store = new FeedbackStore(nested, clock);
		assertThat(store.enabled()).isTrue();
		assertThat(Files.isDirectory(nested)).isTrue();
		assertThat(store.list("all")).isEmpty();
	}

	@Test
	void addThenReloadKeepsEveryField() throws Exception {
		FeedbackStore store = open();
		Instant now = clock.instant();
		String body = "line1\n\tline2 <b>x</b> ```` @owner";
		FeedbackRecord added = store.add("song", "Title @x", body, "ja", "lobby");

		assertThat(added.id()).matches(URL_SAFE_12);
		assertThat(added.createdAt()).isEqualTo(now);
		assertThat(added.updatedAt()).isEqualTo(now);
		assertThat(added.category()).isEqualTo("song");
		assertThat(added.title()).isEqualTo("Title @x");
		assertThat(added.body()).isEqualTo(body);
		assertThat(added.lang()).isEqualTo("ja");
		assertThat(added.page()).isEqualTo("lobby");
		assertThat(added.status()).isEqualTo("new");
		assertThat(added.issueNumber()).isNull();
		assertThat(added.issueUrl()).isNull();

		FeedbackStore reloaded = open();
		assertThat(reloaded.get(added.id())).contains(added);
		assertThat(reloaded.list("all")).containsExactly(added);
	}

	@Test
	void idsAreRandomAndDistinct() throws Exception {
		FeedbackStore store = open();
		Set<String> ids = new HashSet<>();
		for (int i = 0; i < 50; i++) {
			String id = add(store, "t" + i).id();
			assertThat(id).matches(URL_SAFE_12);
			ids.add(id);
		}
		assertThat(ids).hasSize(50);
	}

	@Test
	void atomicRewriteLeavesNoTempFiles() throws Exception {
		FeedbackStore store = open();
		FeedbackRecord a = add(store, "a");
		FeedbackRecord b = add(store, "b");
		store.hide(a.id());
		store.unhide(a.id());
		store.markSent(b.id(), new CreatedIssue(5, "https://github.com/kimdoodoo12/SongRushApp/issues/5"));
		assertThat(filesInDir()).containsExactly(FeedbackFiles.FILE);
	}

	@Test
	void statusChangesPersist() throws Exception {
		FeedbackStore store = open();
		Instant created = clock.instant();
		FeedbackRecord a = add(store, "a");
		FeedbackRecord b = add(store, "b");
		FeedbackRecord c = add(store, "c");
		clock.advance(Duration.ofMinutes(1));
		Instant changed = clock.instant();
		FeedbackRecord hidden = store.hide(a.id());
		String url = "https://github.com/kimdoodoo12/SongRushApp/issues/12";
		FeedbackRecord sent = store.markSent(b.id(), new CreatedIssue(12, url));
		store.hide(c.id());
		FeedbackRecord restored = store.unhide(c.id());

		assertThat(hidden.status()).isEqualTo("hidden");
		assertThat(hidden.updatedAt()).isEqualTo(changed);
		assertThat(hidden.createdAt()).isEqualTo(created);
		assertThat(sent.status()).isEqualTo("sent");
		assertThat(sent.issueNumber()).isEqualTo(12);
		assertThat(sent.issueUrl()).isEqualTo(url);
		assertThat(restored.status()).isEqualTo("new");

		FeedbackStore reloaded = open();
		assertThat(reloaded.get(a.id())).contains(hidden);
		assertThat(reloaded.get(b.id())).contains(sent);
		assertThat(reloaded.get(c.id())).contains(restored);
	}

	@Test
	void listFiltersByStatusNewestFirst() throws Exception {
		FeedbackStore store = open();
		FeedbackRecord a = add(store, "a");
		clock.advance(Duration.ofSeconds(1));
		FeedbackRecord b = add(store, "b");
		clock.advance(Duration.ofSeconds(1));
		FeedbackRecord c = add(store, "c");
		clock.advance(Duration.ofSeconds(1));
		FeedbackRecord d = add(store, "d");
		store.hide(b.id());
		store.markSent(c.id(), new CreatedIssue(3, "https://github.com/kimdoodoo12/SongRushApp/issues/3"));

		assertThat(ids(store.list("all"))).containsExactly(d.id(), c.id(), b.id(), a.id());
		assertThat(ids(store.list("new"))).containsExactly(d.id(), a.id());
		assertThat(ids(store.list("hidden"))).containsExactly(b.id());
		assertThat(ids(store.list("sent"))).containsExactly(c.id());
	}

	@Test
	void newCapGivesFull() throws Exception {
		Instant base = clock.instant().minus(Duration.ofDays(30));
		List<Map<String, Object>> seed = new ArrayList<>(FeedbackFiles.seed(999, base, i -> "new"));
		seed.add(FeedbackFiles.record("hiddenRec001", base.plusSeconds(5000), "hidden"));
		FeedbackFiles.writeFile(dir(), seed);
		FeedbackStore store = open();
		assertThat(store.list("new")).hasSize(999);

		add(store, "the 1000th");
		assertThat(store.list("new")).hasSize(1000);
		assertThrows(FeedbackStore.FullException.class, () -> add(store, "one too many"));
		assertThat(store.list("new")).hasSize(1000);
		assertThat(store.list("all")).hasSize(1001);
		assertThat(open().list("all")).hasSize(1001);

		// Hiding one frees a place.
		store.hide(FeedbackFiles.seedId(0));
		add(store, "fits again");
		assertThat(store.list("new")).hasSize(1000);
	}

	@Test
	void totalCapPrunesOldestSentOrHidden() throws Exception {
		Instant base = clock.instant().minus(Duration.ofDays(30));
		// index 0 is the oldest and "new"; 1 is "hidden"; 2 is "sent"; then every third one is "new".
		List<Map<String, Object>> seed = FeedbackFiles.seed(2000, base, i -> {
			if (i == 0) {
				return "new";
			}
			if (i == 1) {
				return "hidden";
			}
			if (i == 2) {
				return "sent";
			}
			return i % 3 == 0 ? "new" : (i % 2 == 0 ? "sent" : "hidden");
		});
		FeedbackFiles.writeFile(dir(), seed);
		FeedbackStore store = open();
		assertThat(store.list("all")).hasSize(2000);

		FeedbackRecord added = add(store, "number 2001");
		assertThat(store.list("all")).hasSize(2000);
		assertThat(store.get(FeedbackFiles.seedId(0))).as("an old new record is never pruned").isPresent();
		assertThat(store.get(FeedbackFiles.seedId(1))).as("the oldest hidden/sent record is pruned").isEmpty();
		assertThat(store.get(FeedbackFiles.seedId(2))).isPresent();
		assertThat(store.get(added.id())).isPresent();

		FeedbackStore reloaded = open();
		assertThat(reloaded.list("all")).hasSize(2000);
		assertThat(reloaded.get(FeedbackFiles.seedId(1))).isEmpty();

		add(reloaded, "number 2002");
		assertThat(reloaded.list("all")).hasSize(2000);
		assertThat(reloaded.get(FeedbackFiles.seedId(0))).isPresent();
		assertThat(reloaded.get(FeedbackFiles.seedId(2))).as("next oldest sent record is pruned").isEmpty();
		assertThat(reloaded.get(FeedbackFiles.seedId(3))).isPresent();
	}

	@Test
	void corruptFileIsRenamedLoggedAndStoreStartsEmpty(CapturedOutput output) throws Exception {
		Files.createDirectories(dir());
		String garbage = "{\"items\":[ not json";
		Files.writeString(dir().resolve(FeedbackFiles.FILE), garbage, StandardCharsets.UTF_8);

		FeedbackStore store = open();
		assertThat(store.enabled()).isTrue();
		assertThat(store.list("all")).isEmpty();
		Path broken = dir().resolve(FeedbackFiles.FILE + ".broken-" + clock.millis());
		assertThat(broken).exists();
		assertThat(Files.readString(broken, StandardCharsets.UTF_8)).isEqualTo(garbage);
		assertThat(errorLine(output, "broken")).contains("ERROR");

		// A later write creates a fresh file and leaves the broken copy alone.
		FeedbackRecord added = add(store, "after corruption");
		assertThat(Files.readString(broken, StandardCharsets.UTF_8)).isEqualTo(garbage);
		assertThat(open().list("all")).containsExactly(added);
	}

	@Test
	void leftoverTmpFilesAreRemovedAtStartup() throws Exception {
		FeedbackStore first = open();
		FeedbackRecord kept = add(first, "kept");
		Files.writeString(dir().resolve("feedback.json.123456.tmp"), "half", StandardCharsets.UTF_8);
		Files.writeString(dir().resolve("feedback.json.abc.tmp"), "", StandardCharsets.UTF_8);
		Files.writeString(dir().resolve("notes.txt"), "owner's file", StandardCharsets.UTF_8);

		FeedbackStore second = open();
		assertThat(filesInDir()).containsExactly(FeedbackFiles.FILE, "notes.txt");
		assertThat(second.list("all")).containsExactly(kept);
	}

	@Test
	void folderThatCannotBeCreatedDisablesStorage(CapturedOutput output) throws Exception {
		Path file = Files.writeString(tmp.resolve("store-is-a-file"), "x", StandardCharsets.UTF_8);
		FeedbackStore store = new FeedbackStore(file, clock);
		assertThat(store.enabled()).isFalse();
		String line = errorLine(output, "not writable");
		assertThat(line).contains("ERROR").contains("feedback: store-dir").contains("store-is-a-file");
	}

	// Skipped where the OS cannot make a folder read-only for the test user (Windows, or running as root).
	@Test
	void readOnlyFolderDisablesStorage(CapturedOutput output) throws Exception {
		assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"),
				"POSIX permissions not supported");
		Path ro = Files.createDirectories(tmp.resolve("read-only"));
		Files.setPosixFilePermissions(ro, PosixFilePermissions.fromString("r-xr-xr-x"));
		try {
			boolean canStillWrite;
			try {
				Files.delete(Files.createTempFile(ro, "probe", ".tmp"));
				canStillWrite = true;
			}
			catch (java.io.IOException e) {
				canStillWrite = false;
			}
			assumeTrue(!canStillWrite, "the test user can write to a read-only folder (root?)");

			FeedbackStore store = new FeedbackStore(ro, clock);
			assertThat(store.enabled()).isFalse();
			assertThat(errorLine(output, "not writable")).contains("ERROR").contains("feedback: store-dir");
		}
		finally {
			Files.setPosixFilePermissions(ro, PosixFilePermissions.fromString("rwxr-xr-x"));
		}
	}

	@Test
	void concurrentAddsAllPersist() throws Exception {
		FeedbackStore store = open();
		int threads = 8;
		int each = 25;
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		CountDownLatch start = new CountDownLatch(1);
		ConcurrentLinkedQueue<String> ids = new ConcurrentLinkedQueue<>();
		try {
			List<Future<?>> futures = new ArrayList<>();
			for (int t = 0; t < threads; t++) {
				int thread = t;
				futures.add(pool.submit(() -> {
					start.await();
					for (int i = 0; i < each; i++) {
						ids.add(store.add("idea", "t" + thread + "-" + i, "body", "en", "game").id());
					}
					return null;
				}));
			}
			start.countDown();
			assertTimeoutPreemptively(Duration.ofSeconds(120), () -> {
				for (Future<?> f : futures) {
					f.get();
				}
			});
		}
		finally {
			pool.shutdownNow();
		}
		assertThat(new HashSet<>(ids)).hasSize(threads * each);
		assertThat(store.list("all")).hasSize(threads * each);
		assertThat(ids(open().list("all"))).containsExactlyInAnyOrderElementsOf(ids);
		assertThat(filesInDir()).containsExactly(FeedbackFiles.FILE);
	}

}

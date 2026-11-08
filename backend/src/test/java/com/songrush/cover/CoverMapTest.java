package com.songrush.cover;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;

import com.songrush.BackendApplication;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;

// Cycle 093 (B-020) criterion 6: the cover map's loading rules for both formats
// (docs/cycles/093-reveal-cover.md).
//
// Pinned production API (the implementer builds exactly this; tests in cover/ and game/ use it):
//
//   package com.songrush.cover;
//   public final class CoverMap {
//     public static CoverMap empty();
//     // Disk mode: reads coverCacheDir/index.json ({"version": 1, "covers": {"<animeId>": {"id", "file",
//     // "size"}}}); an entry counts only when coverCacheDir/<id>.jpg exists with that size.
//     public static CoverMap loadDisk(Path coverCacheDir);
//     // S3 mode: reads the served map file (data-dir/cover-cache/s3-covers.json,
//     // {"<animeId>": {"id", "key", "size"}}) and keeps id -> key; no file is checked.
//     public static CoverMap loadS3(Path servedMapFile);
//     public Optional<String> idFor(int animeId);
//     public Optional<String> keyFor(String id);  // S3 maps only ("c/<id>"); empty for a disk map
//     public Optional<Path> fileFor(String id);   // disk maps only; empty for an S3 map
//     public int size();                          // entries kept
//     public String toString();                   // counts only, never an id, key or path of an entry
//   }
//   Lookups by id are exact (case-sensitive). Loading never throws: a missing, unreadable or
//   wrong-shape file logs one WARN and gives an empty map; a bad or case-insensitively duplicate
//   entry is skipped with a WARN that names no id.
//
//   One Spring bean of type CoverMap, built from SongrushProperties.dataDir().resolve("cover-cache"):
//   loadS3(<that>/s3-covers.json) when AudioProperties.s3Mode(), else loadDisk(<that>).
//
//   com.songrush.game.Room gains
//     Room(Catalog, AudioTokens, GameScheduler, Clock, RandomGenerator, Outbox, List<PlayerRef>, CoverMap)
//   and com.songrush.game.Lobby gains
//     Lobby(String, Catalog, AudioTokens, GameScheduler, Clock, RandomGenerator, Outbox, Runnable, CoverMap);
//   the existing constructors keep working with CoverMap.empty(). The Lobby passes its map to every Room.
@ExtendWith(OutputCaptureExtension.class)
class CoverMapTest {

	// Ids in the shape of token_urlsafe(16); every one has lower-case letters and a distinct
	// 6-character prefix, so a log line naming one is found.
	static final String A = "Qm7Zx2Lp9sVb4nWk1rTy0A";

	static final String B = "Hj3K8dFq5wEr6tYu2iOp9Z";

	static final String C = "Mn1B4vC7xZ0lK3jH6gF9dS";

	static final String D = "Xc-V_bN3mQ8wE2rT5yU7iO";

	static final List<String> BAD_IDS = List.of("Bad/Id9Zk", "Dot.Id7Wq", "Spc Id4Rt", "L".repeat(65));

	// Valid ids used only by entries that are bad for another reason.
	static final List<String> F = List.of("Fa1qwertyuiop", "Fb2asdfghjkl", "Fc3zxcvbnmqw", "Fd4poiuytrew",
			"Fe5lkjhgfdsa", "Ff6mnbvcxzqa", "Fg7wsxedcrfv", "Fh8tgbyhnujm", "Fi9ikolpqazx");

	static final String G = "Gz0okmijnuhb";

	@TempDir
	Path dir;

	// ---- fixture helpers ----

	// JPEG-looking bytes, distinct per seed.
	static byte[] jpeg(int seed, int size) {
		byte[] bytes = new byte[size];
		for (int i = 0; i < size; i++) {
			bytes[i] = (byte) (i * 13 + seed * 29);
		}
		if (size >= 3) {
			bytes[0] = (byte) 0xFF;
			bytes[1] = (byte) 0xD8;
			bytes[2] = (byte) 0xFF;
		}
		return bytes;
	}

	private void writeFile(String name, int size) {
		try {
			Files.write(dir.resolve(name), jpeg(name.hashCode(), size));
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private void writeText(String name, String text) {
		try {
			Files.writeString(dir.resolve(name), text, StandardCharsets.UTF_8);
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	// One disk index entry: "<animeId>": {"id": "<id>", "file": "<id>.jpg", "size": <size>}.
	static String disk(int animeId, String id, int size) {
		return "\"%d\": {\"id\": \"%s\", \"file\": \"%s.jpg\", \"size\": %d}".formatted(animeId, id, id, size);
	}

	// One served-map entry: "<animeId>": {"id": "<id>", "key": "c/<id>", "size": <size>}.
	static String s3(int animeId, String id, int size) {
		return "\"%d\": {\"id\": \"%s\", \"key\": \"c/%s\", \"size\": %d}".formatted(animeId, id, id, size);
	}

	private void writeIndex(String... entries) {
		writeText("index.json", "{\"version\": 1, \"covers\": {" + String.join(", ", entries) + "}}");
	}

	private Path writeServed(String... entries) {
		writeText("s3-covers.json", "{" + String.join(", ", entries) + "}");
		return dir.resolve("s3-covers.json");
	}

	private CoverMap loadDisk() {
		return CoverMap.loadDisk(dir);
	}

	private static byte[] read(Path file) {
		try {
			return Files.readAllBytes(file);
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	// No id (nor its first 6 characters) appears in the captured output.
	private static void assertNamesNoId(CapturedOutput output, List<String> ids) {
		for (String id : ids) {
			assertThat(output.getAll()).as("log names id " + id).doesNotContain(id.substring(0, 6));
		}
	}

	private static List<String> allIds() {
		List<String> ids = new ArrayList<>(List.of(A, B, C, D, G));
		ids.addAll(F);
		ids.addAll(BAD_IDS);
		return ids;
	}

	private static void assertEmpty(CoverMap map) {
		assertThat(map.size()).isZero();
		assertThat(map.idFor(1)).isEmpty();
		assertThat(map.idFor(2)).isEmpty();
		assertThat(map.keyFor(A)).isEmpty();
		assertThat(map.fileFor(A)).isEmpty();
	}

	// ---- empty ----

	@Test
	void emptyMapHasNothing() {
		assertEmpty(CoverMap.empty());
	}

	// ---- disk mode ----

	@Test
	void diskEntriesWithMatchingFilesLoad() {
		writeFile(A + ".jpg", 40);
		writeFile(B + ".jpg", 55);
		writeFile(D + ".jpg", 7);
		writeIndex(disk(1, A, 40), disk(2, B, 55), disk(4, D, 7));

		CoverMap map = loadDisk();

		assertThat(map.size()).isEqualTo(3);
		assertThat(map.idFor(1)).hasValue(A);
		assertThat(map.idFor(2)).hasValue(B);
		assertThat(map.idFor(4)).hasValue(D);
		assertThat(map.idFor(3)).isEmpty();
		assertThat(map.fileFor(A)).isPresent();
		assertThat(read(map.fileFor(A).get())).isEqualTo(read(dir.resolve(A + ".jpg")));
		assertThat(read(map.fileFor(B).get())).isEqualTo(read(dir.resolve(B + ".jpg")));
		assertThat(read(map.fileFor(D).get())).isEqualTo(read(dir.resolve(D + ".jpg")));
		assertThat(map.fileFor(C)).isEmpty();
		assertThat(map.keyFor(A)).as("a disk map has no S3 keys").isEmpty();
	}

	// The id is a token, not a path: lookups are exact (a case-insensitive file system would
	// otherwise open A.jpg for upper-case A).
	@Test
	void diskLookupsAreExact() {
		writeFile(A + ".jpg", 40);
		writeIndex(disk(1, A, 40));

		CoverMap map = loadDisk();

		assertThat(map.fileFor(A)).isPresent();
		assertThat(map.fileFor(A.toUpperCase())).isEmpty();
		assertThat(map.fileFor(A.toLowerCase())).isEmpty();
		assertThat(map.fileFor(A + ".jpg")).isEmpty();
		assertThat(map.fileFor("")).isEmpty();
		assertThat(map.fileFor("../" + A)).isEmpty();
		assertThat(map.fileFor("1")).isEmpty();
	}

	@Test
	void diskEntryWithMissingOrWrongSizeFileIsNotServed(CapturedOutput output) {
		writeFile(B + ".jpg", 50);
		writeFile(C + ".jpg", 30);
		writeIndex(disk(1, A, 40), disk(2, B, 40), disk(3, C, 30));

		CoverMap map = loadDisk();

		assertThat(map.size()).isEqualTo(1);
		assertThat(map.idFor(1)).as("file missing").isEmpty();
		assertThat(map.idFor(2)).as("file of the wrong size").isEmpty();
		assertThat(map.fileFor(A)).isEmpty();
		assertThat(map.fileFor(B)).isEmpty();
		assertThat(map.idFor(3)).hasValue(C);
		assertThat(read(map.fileFor(C).get())).isEqualTo(read(dir.resolve(C + ".jpg")));
		assertNamesNoId(output, List.of(A, B, C));
	}

	@Test
	void badDiskEntriesAreSkippedWithAWarnThatNamesNoId(CapturedOutput output) {
		// Every F id has a file of the declared (or obvious) size, so only the entry rule rejects it.
		for (String id : F) {
			writeFile(id + ".jpg", 40);
		}
		writeFile("other.jpg", 40);
		writeFile(G + ".jpg", 40);
		// Bad ids that are legal file names get a file too, so only the id rule rejects them.
		for (String id : BAD_IDS.subList(1, BAD_IDS.size())) {
			writeFile(id + ".jpg", 40);
		}
		writeFile(".jpg", 40);
		writeFile("12345.jpg", 40);
		writeIndex(
				"\"10\": {\"id\": \"" + BAD_IDS.get(0) + "\", \"file\": \"" + BAD_IDS.get(0) + ".jpg\", \"size\": 40}",
				"\"11\": {\"id\": \"" + BAD_IDS.get(1) + "\", \"file\": \"" + BAD_IDS.get(1) + ".jpg\", \"size\": 40}",
				"\"12\": {\"id\": \"" + BAD_IDS.get(2) + "\", \"file\": \"" + BAD_IDS.get(2) + ".jpg\", \"size\": 40}",
				"\"13\": {\"id\": \"" + BAD_IDS.get(3) + "\", \"file\": \"" + BAD_IDS.get(3) + ".jpg\", \"size\": 40}",
				"\"14\": {\"id\": \"\", \"file\": \".jpg\", \"size\": 40}",
				"\"15\": {\"id\": 12345, \"file\": \"12345.jpg\", \"size\": 40}",
				// file is not <id>.jpg
				"\"16\": {\"id\": \"" + F.get(0) + "\", \"file\": \"other.jpg\", \"size\": 40}",
				// size not a positive integer
				"\"17\": {\"id\": \"" + F.get(1) + "\", \"file\": \"" + F.get(1) + ".jpg\", \"size\": 0}",
				"\"18\": {\"id\": \"" + F.get(2) + "\", \"file\": \"" + F.get(2) + ".jpg\", \"size\": -40}",
				"\"19\": {\"id\": \"" + F.get(3) + "\", \"file\": \"" + F.get(3) + ".jpg\", \"size\": \"40\"}",
				"\"20\": {\"id\": \"" + F.get(4) + "\", \"file\": \"" + F.get(4) + ".jpg\", \"size\": 40.5}",
				"\"21\": {\"id\": \"" + F.get(5) + "\", \"file\": \"" + F.get(5) + ".jpg\"}",
				// anime id not an integer
				"\"abc\": {\"id\": \"" + F.get(6) + "\", \"file\": \"" + F.get(6) + ".jpg\", \"size\": 40}",
				"\"1.5\": {\"id\": \"" + F.get(7) + "\", \"file\": \"" + F.get(7) + ".jpg\", \"size\": 40}",
				// not an object
				"\"22\": \"" + F.get(8) + "\"",
				"\"23\": null",
				disk(30, G, 40));

		CoverMap map = assertDoesNotThrow(this::loadDisk);

		assertThat(map.size()).isEqualTo(1);
		assertThat(map.idFor(30)).hasValue(G);
		assertThat(read(map.fileFor(G).get())).isEqualTo(read(dir.resolve(G + ".jpg")));
		for (int animeId = 10; animeId <= 23; animeId++) {
			assertThat(map.idFor(animeId)).as("anime " + animeId).isEmpty();
		}
		for (String id : F) {
			assertThat(map.fileFor(id)).as("id " + id).isEmpty();
		}
		assertThat(output.getAll()).contains("WARN");
		assertNamesNoId(output, allIds());
	}

	@Test
	void caseInsensitiveDuplicateDiskIdIsSkipped(CapturedOutput output) {
		String upper = A.toUpperCase();
		writeFile(A + ".jpg", 40);
		// On a case-insensitive file system this is the same file, of the same size.
		writeFile(upper + ".jpg", 40);
		writeFile(C + ".jpg", 30);
		writeIndex(disk(1, A, 40), disk(2, upper, 40), disk(3, A, 40), disk(4, C, 30));

		CoverMap map = assertDoesNotThrow(this::loadDisk);

		assertThat(map.idFor(1)).as("first in file order is kept").hasValue(A);
		assertThat(map.idFor(2)).as("upper-case duplicate").isEmpty();
		assertThat(map.idFor(3)).as("exact duplicate").isEmpty();
		assertThat(map.idFor(4)).hasValue(C);
		assertThat(map.fileFor(upper)).isEmpty();
		assertThat(map.fileFor(A)).isPresent();
		assertThat(map.size()).isEqualTo(2);
		assertThat(output.getAll()).contains("WARN");
		assertNamesNoId(output, List.of(A, C));
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"",
			"not json",
			"{\"version\": 1, \"covers\": {",
			"null",
			"[]",
			"\"text\"",
			"{}",
			"{\"version\": 1}",
			"{\"version\": 1, \"covers\": []}",
			"{\"version\": 1, \"covers\": null}",
			"{\"version\": 2, \"covers\": {\"1\": {\"id\": \"Qm7Zx2Lp9sVb4nWk1rTy0A\", \"file\": \"Qm7Zx2Lp9sVb4nWk1rTy0A.jpg\", \"size\": 40}}}",
			"{\"version\": \"1\", \"covers\": {\"1\": {\"id\": \"Qm7Zx2Lp9sVb4nWk1rTy0A\", \"file\": \"Qm7Zx2Lp9sVb4nWk1rTy0A.jpg\", \"size\": 40}}}",
			"{\"covers\": {\"1\": {\"id\": \"Qm7Zx2Lp9sVb4nWk1rTy0A\", \"file\": \"Qm7Zx2Lp9sVb4nWk1rTy0A.jpg\", \"size\": 40}}}" })
	void wrongShapeDiskIndexGivesAnEmptyMapAndAWarn(String text, CapturedOutput output) {
		writeFile(A + ".jpg", 40);
		writeText("index.json", text);

		CoverMap map = assertDoesNotThrow(this::loadDisk);

		assertEmpty(map);
		assertThat(output.getAll()).contains("WARN");
		assertNamesNoId(output, List.of(A));
	}

	@Test
	void missingDiskIndexGivesAnEmptyMapAndAWarn(CapturedOutput output) {
		writeFile(A + ".jpg", 40);

		assertEmpty(assertDoesNotThrow(this::loadDisk));
		assertThat(output.getAll()).contains("WARN");
	}

	@Test
	void missingCoverCacheDirGivesAnEmptyMapAndAWarn(CapturedOutput output) {
		CoverMap map = assertDoesNotThrow(() -> CoverMap.loadDisk(dir.resolve("no-such-dir")));

		assertEmpty(map);
		assertThat(output.getAll()).contains("WARN");
	}

	@Test
	void unreadableDiskIndexGivesAnEmptyMapAndAWarn(CapturedOutput output) throws IOException {
		// A directory where the file should be: exists, but cannot be read as a file.
		Files.createDirectories(dir.resolve("index.json"));

		assertEmpty(assertDoesNotThrow(this::loadDisk));
		assertThat(output.getAll()).contains("WARN");
	}

	// ---- S3 mode ----

	@Test
	void servedMapEntriesLoadWithTheirKeys() {
		// No file is written: S3 mode needs none.
		Path served = writeServed(s3(1, A, 40), s3(2, B, 55), s3(4, D, 7));

		CoverMap map = CoverMap.loadS3(served);

		assertThat(map.size()).isEqualTo(3);
		assertThat(map.idFor(1)).hasValue(A);
		assertThat(map.idFor(2)).hasValue(B);
		assertThat(map.idFor(4)).hasValue(D);
		assertThat(map.idFor(3)).isEmpty();
		assertThat(map.keyFor(A)).hasValue("c/" + A);
		assertThat(map.keyFor(B)).hasValue("c/" + B);
		assertThat(map.keyFor(D)).hasValue("c/" + D);
		assertThat(map.keyFor(C)).isEmpty();
		assertThat(map.keyFor(A.toUpperCase())).isEmpty();
		assertThat(map.keyFor("c/" + A)).isEmpty();
		assertThat(map.fileFor(A)).as("an S3 map serves no file").isEmpty();
	}

	@Test
	void badServedMapEntriesAreSkippedWithAWarnThatNamesNoId(CapturedOutput output) {
		Path served = writeServed(
				"\"10\": {\"id\": \"" + BAD_IDS.get(0) + "\", \"key\": \"c/" + BAD_IDS.get(0) + "\", \"size\": 40}",
				"\"11\": {\"id\": \"" + BAD_IDS.get(1) + "\", \"key\": \"c/" + BAD_IDS.get(1) + "\", \"size\": 40}",
				"\"12\": {\"id\": \"" + BAD_IDS.get(3) + "\", \"key\": \"c/" + BAD_IDS.get(3) + "\", \"size\": 40}",
				"\"13\": {\"id\": \"\", \"key\": \"c/\", \"size\": 40}",
				// key is not c/<id>
				"\"14\": {\"id\": \"" + F.get(0) + "\", \"key\": \"a/" + F.get(0) + "\", \"size\": 40}",
				"\"15\": {\"id\": \"" + F.get(1) + "\", \"key\": \"c/" + F.get(2) + "\", \"size\": 40}",
				"\"16\": {\"id\": \"" + F.get(3) + "\", \"size\": 40}",
				// size not a positive integer
				"\"17\": {\"id\": \"" + F.get(4) + "\", \"key\": \"c/" + F.get(4) + "\", \"size\": 0}",
				"\"18\": {\"id\": \"" + F.get(5) + "\", \"key\": \"c/" + F.get(5) + "\", \"size\": \"40\"}",
				// anime id not an integer
				"\"abc\": {\"id\": \"" + F.get(6) + "\", \"key\": \"c/" + F.get(6) + "\", \"size\": 40}",
				// not an object
				"\"19\": \"" + F.get(7) + "\"",
				s3(30, G, 40));

		CoverMap map = assertDoesNotThrow(() -> CoverMap.loadS3(served));

		assertThat(map.size()).isEqualTo(1);
		assertThat(map.idFor(30)).hasValue(G);
		assertThat(map.keyFor(G)).hasValue("c/" + G);
		for (int animeId = 10; animeId <= 19; animeId++) {
			assertThat(map.idFor(animeId)).as("anime " + animeId).isEmpty();
		}
		for (String id : F) {
			assertThat(map.keyFor(id)).as("id " + id).isEmpty();
		}
		assertThat(output.getAll()).contains("WARN");
		assertNamesNoId(output, allIds());
	}

	@Test
	void caseInsensitiveDuplicateServedIdIsSkipped(CapturedOutput output) {
		String upper = A.toUpperCase();
		Path served = writeServed(s3(1, A, 40), s3(2, upper, 40), s3(3, A, 40), s3(4, C, 30));

		CoverMap map = assertDoesNotThrow(() -> CoverMap.loadS3(served));

		assertThat(map.idFor(1)).as("first in file order is kept").hasValue(A);
		assertThat(map.idFor(2)).isEmpty();
		assertThat(map.idFor(3)).isEmpty();
		assertThat(map.idFor(4)).hasValue(C);
		assertThat(map.keyFor(upper)).isEmpty();
		assertThat(map.keyFor(A)).hasValue("c/" + A);
		assertThat(map.size()).isEqualTo(2);
		assertThat(output.getAll()).contains("WARN");
		assertNamesNoId(output, List.of(A, C));
	}

	@ParameterizedTest
	@ValueSource(strings = { "", "not json", "{\"1\": {\"id\": \"Qm7Zx2Lp9sVb4nWk1rTy0A\"", "null", "[]", "\"text\"",
			"42" })
	void wrongShapeServedMapGivesAnEmptyMapAndAWarn(String text, CapturedOutput output) {
		writeText("s3-covers.json", text);

		CoverMap map = assertDoesNotThrow(() -> CoverMap.loadS3(dir.resolve("s3-covers.json")));

		assertEmpty(map);
		assertThat(output.getAll()).contains("WARN");
		assertNamesNoId(output, List.of(A));
	}

	@Test
	void missingServedMapGivesAnEmptyMapAndAWarn(CapturedOutput output) {
		CoverMap map = assertDoesNotThrow(() -> CoverMap.loadS3(dir.resolve("s3-covers.json")));

		assertEmpty(map);
		assertThat(output.getAll()).contains("WARN");
	}

	// ---- toString ----

	@Test
	void toStringShowsCountsOnly() {
		writeFile(A + ".jpg", 40);
		writeFile(B + ".jpg", 55);
		writeIndex(disk(1, A, 40), disk(2, B, 55));
		CoverMap diskMap = loadDisk();
		CoverMap s3Map = CoverMap.loadS3(writeServed(s3(1, A, 40), s3(2, B, 55)));

		for (CoverMap map : List.of(diskMap, s3Map)) {
			String text = map.toString();
			assertThat(text).contains("2");
			for (String id : List.of(A, B)) {
				assertThat(text).doesNotContain(id.substring(0, 6));
			}
			assertThat(text).doesNotContain("c/").doesNotContain(".jpg");
		}
	}

	// ---- startup (criterion 6: startup succeeds, the bean is empty) ----

	private static final String PROVIDER_BEAN = "awsCredentialsProvider";

	private static final Path FIXTURE_CATALOG = Path.of("src/test/resources/catalog/catalog.json");

	// The fixture catalog and an audio index, as AudioEndpointTest and S3Fixture write them.
	private void writeDataDir(Path data) throws IOException {
		Path cache = Files.createDirectories(data.resolve("audio-cache"));
		Files.copy(FIXTURE_CATALOG, data.resolve("catalog.json"));
		Files.writeString(cache.resolve("index.json"), """
				{
				  "10": {"basename": "CowboyBebop-OP1.ogg", "size": 101, "durationMs": 89000}
				}
				""", StandardCharsets.UTF_8);
		Files.write(cache.resolve("CowboyBebop-OP1.ogg"), new byte[101]);
		Files.writeString(cache.resolve("s3-keys.json"), """
				{
				  "CowboyBebop-OP1.ogg": {"key": "a/Q7mZ2xLp9sVb4nWk1rTy0A", "size": 101}
				}
				""", StandardCharsets.UTF_8);
	}

	// As in S3StartupTest: the provider bean definition is replaced before any bean is created.
	private static ApplicationContextInitializer<ConfigurableApplicationContext> fakeProvider() {
		AwsCredentialsProvider provider = StaticCredentialsProvider
				.create(AwsBasicCredentials.create("AKIDFAKETEST0000000", "fakeSecretKeyForTestsOnly0000000000000000"));
		return context -> context.addBeanFactoryPostProcessor(beanFactory -> {
			BeanDefinitionRegistry registry = (BeanDefinitionRegistry) beanFactory;
			if (registry.containsBeanDefinition(PROVIDER_BEAN)) {
				registry.removeBeanDefinition(PROVIDER_BEAN);
			}
			registry.registerBeanDefinition(PROVIDER_BEAN,
					new RootBeanDefinition(AwsCredentialsProvider.class, () -> provider));
		});
	}

	@Test
	void diskModeStartsWithAMalformedCoverMap(CapturedOutput output) throws IOException {
		Path data = dir.resolve("data");
		writeDataDir(data);
		Path covers = Files.createDirectories(data.resolve("cover-cache"));
		Files.writeString(covers.resolve("index.json"), "{\"version\": 1, \"covers\": ", StandardCharsets.UTF_8);

		try (ConfigurableApplicationContext context = new SpringApplicationBuilder(BackendApplication.class)
				.web(WebApplicationType.SERVLET)
				.run("--server.port=0", "--spring.main.banner-mode=off", "--songrush.data-dir=" + data)) {
			assertEmpty(context.getBean(CoverMap.class));
		}
		assertThat(output.getAll()).contains("WARN");
	}

	@Test
	void diskModeStartsWithoutACoverCache() throws IOException {
		Path data = dir.resolve("data");
		writeDataDir(data);

		try (ConfigurableApplicationContext context = new SpringApplicationBuilder(BackendApplication.class)
				.web(WebApplicationType.SERVLET)
				.run("--server.port=0", "--spring.main.banner-mode=off", "--songrush.data-dir=" + data)) {
			assertEmpty(context.getBean(CoverMap.class));
		}
	}

	@Test
	void s3ModeStartsWithAMalformedServedMapAndIgnoresTheDiskIndex(CapturedOutput output) throws IOException {
		Path data = dir.resolve("data");
		writeDataDir(data);
		Path covers = Files.createDirectories(data.resolve("cover-cache"));
		// A valid disk index and file: S3 mode must not read them.
		Files.write(covers.resolve(A + ".jpg"), jpeg(1, 40));
		Files.writeString(covers.resolve("index.json"), "{\"version\": 1, \"covers\": {" + disk(1, A, 40) + "}}",
				StandardCharsets.UTF_8);
		Files.writeString(covers.resolve("s3-covers.json"), "[1, 2", StandardCharsets.UTF_8);

		try (ConfigurableApplicationContext context = new SpringApplicationBuilder(BackendApplication.class)
				.web(WebApplicationType.SERVLET)
				.initializers(fakeProvider())
				.run("--server.port=0", "--spring.main.banner-mode=off", "--songrush.data-dir=" + data,
						"--songrush.audio.bucket=test-bucket")) {
			assertEmpty(context.getBean(CoverMap.class));
		}
		assertThat(output.getAll()).contains("WARN");
	}

	// ---- helpers ----

	private static <T> T assertDoesNotThrow(Supplier<T> load) {
		Object[] result = new Object[1];
		assertThatCode(() -> result[0] = load.get()).as("loading never throws").doesNotThrowAnyException();
		@SuppressWarnings("unchecked")
		T value = (T) result[0];
		assertThat(Optional.ofNullable(value)).as("a map is returned").isPresent();
		return value;
	}

}

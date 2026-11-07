package com.songrush.feedback;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

// The feedback inbox (cycle 083, D-097): one file, <dir>/feedback.json = {"items":[record...]}.
// Every access is serialised on one lock. Every write rewrites the whole file: a temp file in the same
// folder, then an atomic move over feedback.json (REPLACE_EXISTING only where the file system has no
// atomic move). A failed write deletes its temp file, rolls the memory back (except markSent, see
// there) and propagates the IOException.
// Startup: creates the folder, deletes leftover feedback.json.*.tmp, probes that the folder is
// writable, then loads the file. A folder that cannot be created or written disables the store
// (logged at ERROR); an unparsable file is renamed to feedback.json.broken-<millis> and the store
// starts empty, so the bad file is never overwritten.
// The in-flight set marks records whose GitHub send is running: it is memory-only and guarded by the
// same lock, which is never held during the GitHub call itself.
// Logs never carry the user's text.
public class FeedbackStore {

	private static final Logger log = LoggerFactory.getLogger(FeedbackStore.class);

	static final String FILE = "feedback.json";

	private static final String TMP_PREFIX = FILE + ".";

	private static final String TMP_SUFFIX = ".tmp";

	static final int MAX_NEW = 1000;

	static final int MAX_TOTAL = 2000;

	private static final int ID_BYTES = 9;

	private static final ObjectMapper MAPPER = JsonMapper.builder().build();

	private static final SecureRandom RANDOM = new SecureRandom();

	// add() refuses a post: MAX_NEW records are already "new".
	public static final class FullException extends Exception {

		private static final long serialVersionUID = 1L;

		FullException() {
			super("feedback store holds " + MAX_NEW + " new records");
		}

	}

	// A status change that the record's current state does not allow.
	public static final class StateException extends RuntimeException {

		private static final long serialVersionUID = 1L;

		public enum Reason {
			NOT_FOUND, ALREADY_SENT, IN_FLIGHT
		}

		private final Reason reason;

		StateException(Reason reason) {
			super(reason.name());
			this.reason = reason;
		}

		public Reason reason() {
			return reason;
		}

	}

	private final Object lock = new Object();

	private final Path dir;

	private final Path file;

	private final Clock clock;

	private final boolean enabled;

	// By id, in insertion order (the tie-break of list()).
	private final LinkedHashMap<String, FeedbackRecord> records = new LinkedHashMap<>();

	private final Set<String> inFlight = new HashSet<>();

	public FeedbackStore(Path dir, Clock clock) {
		this.dir = dir;
		this.file = dir == null ? null : dir.resolve(FILE);
		this.clock = clock;
		this.enabled = dir != null && open();
	}

	public boolean enabled() {
		return enabled;
	}

	// ---- startup ----------------------------------------------------------------------------------------

	private boolean open() {
		try {
			Files.createDirectories(dir);
			deleteLeftoverTemps();
			Files.delete(Files.createTempFile(dir, TMP_PREFIX, TMP_SUFFIX));
		}
		catch (IOException | RuntimeException e) {
			log.error("feedback: store-dir {} not writable (uid {}) - feedback storage disabled ({})", dir, uid(),
					e.getClass().getSimpleName());
			return false;
		}
		return load();
	}

	private void deleteLeftoverTemps() throws IOException {
		try (DirectoryStream<Path> temps = Files.newDirectoryStream(dir, TMP_PREFIX + "*" + TMP_SUFFIX)) {
			for (Path temp : temps) {
				Files.deleteIfExists(temp);
				log.info("feedback: deleted leftover temp file {}", temp.getFileName());
			}
		}
	}

	// Loads feedback.json; false (store disabled) only when a broken file cannot be moved aside.
	private boolean load() {
		byte[] bytes;
		try {
			bytes = Files.readAllBytes(file);
		}
		catch (NoSuchFileException e) {
			return true;
		}
		catch (IOException e) {
			log.error("feedback: {} unreadable - feedback storage disabled ({})", file, e.getClass().getSimpleName());
			return false;
		}
		List<FeedbackRecord> loaded = parse(bytes);
		if (loaded != null) {
			loaded.forEach(record -> records.put(record.id(), record));
			log.info("feedback: loaded {} records", records.size());
			return true;
		}
		Path broken = dir.resolve(FILE + ".broken-" + clock.millis());
		try {
			Files.move(file, broken);
		}
		catch (IOException e) {
			log.error("feedback: {} is broken and could not be renamed - feedback storage disabled ({})", file,
					e.getClass().getSimpleName());
			return false;
		}
		log.error("feedback: {} is broken; renamed to {} and starting empty", file, broken.getFileName());
		return true;
	}

	// The records in the file, or null when it is not {"items":[valid record...]} with distinct ids.
	private static List<FeedbackRecord> parse(byte[] bytes) {
		JsonNode root;
		try {
			root = MAPPER.readTree(bytes);
		}
		catch (JacksonException e) {
			return null;
		}
		JsonNode items = root == null ? null : root.get("items");
		if (items == null || !items.isArray()) {
			return null;
		}
		List<FeedbackRecord> loaded = new ArrayList<>();
		Set<String> ids = new HashSet<>();
		for (JsonNode item : items) {
			FeedbackRecord record = FeedbackRecord.fromJson(item);
			if (record == null || !ids.add(record.id())) {
				return null;
			}
			loaded.add(record);
		}
		return loaded;
	}

	// The process uid on Linux (for the "chown to uid N" hint), "?" elsewhere.
	private static String uid() {
		try {
			return String.valueOf(Files.getAttribute(Path.of("/proc/self"), "unix:uid"));
		}
		catch (IOException | RuntimeException e) {
			return "?";
		}
	}

	// ---- reads ------------------------------------------------------------------------------------------

	// "new", "sent", "hidden" or "all"; newest first (createdAt descending, ties in reverse insertion order).
	public List<FeedbackRecord> list(String status) {
		List<FeedbackRecord> result = new ArrayList<>();
		synchronized (lock) {
			for (FeedbackRecord record : records.values()) {
				if ("all".equals(status) || record.status().equals(status)) {
					result.add(record);
				}
			}
		}
		Collections.reverse(result);
		result.sort(Comparator.comparing(FeedbackRecord::createdAt).reversed());
		return result;
	}

	public Optional<FeedbackRecord> get(String id) {
		synchronized (lock) {
			return Optional.ofNullable(records.get(id));
		}
	}

	// ---- writes -----------------------------------------------------------------------------------------

	// Stores a "new" record with the values as given.
	public FeedbackRecord add(String category, String title, String body, String lang, String page)
			throws IOException, FullException {
		requireEnabled();
		synchronized (lock) {
			long fresh = records.values().stream().filter(r -> FeedbackRecord.NEW.equals(r.status())).count();
			if (fresh >= MAX_NEW) {
				throw new FullException();
			}
			Map<String, FeedbackRecord> before = new LinkedHashMap<>(records);
			Instant now = clock.instant();
			FeedbackRecord record = new FeedbackRecord(newId(), now, category, title, body, lang, page,
					FeedbackRecord.NEW, null, null, now);
			records.put(record.id(), record);
			prune();
			writeOrRollBack(before);
			return record;
		}
	}

	// new -> hidden; a hidden record is returned unchanged.
	public FeedbackRecord hide(String id) throws IOException {
		return change(id, FeedbackRecord.NEW, FeedbackRecord.HIDDEN);
	}

	// hidden -> new; a new record is returned unchanged.
	public FeedbackRecord unhide(String id) throws IOException {
		return change(id, FeedbackRecord.HIDDEN, FeedbackRecord.NEW);
	}

	private FeedbackRecord change(String id, String from, String to) throws IOException {
		requireEnabled();
		synchronized (lock) {
			FeedbackRecord record = changeable(id);
			if (inFlight.contains(id)) {
				throw new StateException(StateException.Reason.IN_FLIGHT);
			}
			if (record.status().equals(to)) {
				return record;
			}
			Map<String, FeedbackRecord> before = new LinkedHashMap<>(records);
			FeedbackRecord changed = record.withStatus(to, clock.instant());
			records.put(id, changed);
			writeOrRollBack(before);
			return changed;
		}
	}

	// Marks a "new" record as being sent: until endSend, beginSend, hide and unhide on it throw IN_FLIGHT.
	public FeedbackRecord beginSend(String id) {
		requireEnabled();
		synchronized (lock) {
			FeedbackRecord record = changeable(id);
			if (!inFlight.add(id)) {
				throw new StateException(StateException.Reason.IN_FLIGHT);
			}
			return record;
		}
	}

	public void endSend(String id) {
		synchronized (lock) {
			inFlight.remove(id);
		}
	}

	// -> sent with the issue's number and URL. GitHub has already created the issue, so a failed write
	// keeps the record "sent" in memory (no duplicate issue on a retry) and the IOException propagates;
	// the next successful write persists it.
	public FeedbackRecord markSent(String id, CreatedIssue issue) throws IOException {
		requireEnabled();
		synchronized (lock) {
			FeedbackRecord sent = changeable(id).sent(issue, clock.instant());
			records.put(id, sent);
			write();
			return sent;
		}
	}

	// The record, unless it is unknown or already sent.
	private FeedbackRecord changeable(String id) {
		FeedbackRecord record = records.get(id);
		if (record == null) {
			throw new StateException(StateException.Reason.NOT_FOUND);
		}
		if (FeedbackRecord.SENT.equals(record.status())) {
			throw new StateException(StateException.Reason.ALREADY_SENT);
		}
		return record;
	}

	private void requireEnabled() {
		if (!enabled) {
			throw new IllegalStateException("feedback store is disabled");
		}
	}

	// Over MAX_TOTAL: removes the oldest (by createdAt) sent or hidden records; never a "new" one.
	private void prune() {
		int excess = records.size() - MAX_TOTAL;
		if (excess <= 0) {
			return;
		}
		List<FeedbackRecord> done = records.values().stream()
				.filter(r -> !FeedbackRecord.NEW.equals(r.status()) && !inFlight.contains(r.id()))
				.sorted(Comparator.comparing(FeedbackRecord::createdAt))
				.limit(excess)
				.toList();
		done.forEach(r -> records.remove(r.id()));
		log.info("feedback: pruned {} old sent or hidden records", done.size());
	}

	private void writeOrRollBack(Map<String, FeedbackRecord> before) throws IOException {
		try {
			write();
		}
		catch (IOException e) {
			records.clear();
			records.putAll(before);
			throw e;
		}
	}

	private void write() throws IOException {
		Map<String, Object> root = new LinkedHashMap<>();
		root.put("items", records.values().stream().map(FeedbackRecord::toJson).toList());
		byte[] bytes = MAPPER.writeValueAsBytes(root);
		Path temp = Files.createTempFile(dir, TMP_PREFIX, TMP_SUFFIX);
		try {
			Files.write(temp, bytes);
			try {
				Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE);
			}
			catch (AtomicMoveNotSupportedException e) {
				Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
			}
		}
		catch (IOException | RuntimeException e) {
			try {
				Files.deleteIfExists(temp);
			}
			catch (IOException cleanup) {
				e.addSuppressed(cleanup);
			}
			log.warn("feedback: writing {} failed ({})", file, e.getClass().getSimpleName());
			throw e;
		}
	}

	private String newId() {
		String id;
		do {
			byte[] bytes = new byte[ID_BYTES];
			RANDOM.nextBytes(bytes);
			id = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
		}
		while (records.containsKey(id));
		return id;
	}

}

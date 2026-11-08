"""Cycle 092 criteria 1-7: the resumable S3 upload of covers (C-020); PNG covers from cycle 095 (C-022).

docs/cycles/092-cover-s3.md and docs/cycles/095-png-covers.md are the
specification. These tests pin the interface:

    songrush_data.s3covers
        KEY_PREFIX = "c/"
        CONTENT_TYPE = "image/jpeg"
        PNG_CONTENT_TYPE = "image/png"
        SERVED_MAP_NAME = "s3-covers.json"
        run(s3, covers_dir, bucket, *, log=print) -> dict
            counts: uploaded, skipped, missing, mismatched, failed, served.
            Reads covers_dir/index.json ({"version": 1, "covers": {"<animeId>":
            {"id", "file": "<id>.jpg" or "<id>.png", "size"}}}); raises before
            any S3 call when it is missing or malformed, or when an entry breaks
            the rules (id matches ^[A-Za-z0-9_-]{1,64}$, file is exactly <id>.jpg
            or <id>.png, size a positive int and not a bool, no two ids equal
            case-insensitively). The local file is <covers>/<file>. Lists the
            prefix with list_objects_v2 (Bucket, Prefix, ContinuationToken across
            pages); an object listed with the index size is skipped even when the
            local file is missing. Uploads with put_object(Bucket, Key="c/<id>",
            Body, ContentType="image/jpeg" for .jpg or "image/png" for .png),
            lists again, then writes covers_dir/s3-covers.json:
            {"<animeId>": {"id": "<id>", "key": "c/<id>", "size": <bytes>}}.
        main(argv=None) -> int
            --bucket NAME (required), --profile NAME, --covers DIR. The client is
            boto3.Session(profile_name=...).client("s3", region_name="ap-southeast-2"),
            as in s3upload.main; the tests replace boto3.Session. Errors are
            printed by type name and S3 error code only, never their text.

The bucket is a stateful in-memory fake (FakeS3) so that a rerun can use the
same bucket; it pages its listings two objects at a time, so every listing
must follow continuation tokens. Cover files are a few fake bytes under
tmp_path. Nothing touches the network or the owner's AWS setup. The import of
songrush_data.s3covers stays inside a fixture so that a missing module fails
these tests, not the collection.
"""

import io
import json
import sys

import pytest

BUCKET = "test-cover-bucket"
REGION = "ap-southeast-2"
ACCESS_KEY = "AKIDFAKECOVERS"
SECRET_KEY = "fakecoversecret"
PREFIX = "c/"
CONTENT_TYPE = "image/jpeg"
PNG_CONTENT_TYPE = "image/png"
SERVED_MAP = "s3-covers.json"
INDEX = "index.json"
LEAK = "LEAKMARKER-https://test-cover-bucket.s3.example/c/"

# Distinctive ids, so a leak into the log cannot be a coincidence.
ANIME_IDS = ["7301942", "8812035", "9420517", "6158833", "5027719"]
COVER_IDS = ["QzXvKpWm7RtYb3NcLd9sHf", "JhGfDsAp0LkMnBvCxZ8wEe", "TrEwQyUi5OpAsDfGhJk2Lz",
             "MnBvCxZa4SdFgHjKlPo1Iu", "WqErTyUi9OpLkJhGfDs6Az"]


@pytest.fixture
def s3covers():
    from songrush_data import s3covers as module

    return module


@pytest.fixture(autouse=True)
def no_aws_environment(monkeypatch, tmp_path):
    """Keep the owner's AWS profile, variables and config files out of the tests."""
    for name in ("AWS_PROFILE", "AWS_DEFAULT_PROFILE", "AWS_ACCESS_KEY_ID", "AWS_SECRET_ACCESS_KEY",
                 "AWS_SESSION_TOKEN", "AWS_REGION", "AWS_DEFAULT_REGION", "AWS_ENDPOINT_URL",
                 "AWS_ENDPOINT_URL_S3"):
        monkeypatch.delenv(name, raising=False)
    monkeypatch.setenv("AWS_CONFIG_FILE", str(tmp_path / "no-aws-config"))
    monkeypatch.setenv("AWS_SHARED_CREDENTIALS_FILE", str(tmp_path / "no-aws-credentials"))
    monkeypatch.setenv("AWS_EC2_METADATA_DISABLED", "true")


# Builders.

def body_of(cover_id, size, suffix=".jpg"):
    head = b"\x89PNG\r\n\x1a\n" if suffix == ".png" else b"\xff\xd8"
    seed = head + cover_id.encode("ascii")
    return (seed * (size // len(seed) + 1))[:size]


class Covers:
    """A covers folder with index.json and fake cover files."""

    def __init__(self, root):
        self.dir = root / "cover-cache"
        self.dir.mkdir()
        self.entries = {}  # animeId (str) -> (cover id, size, suffix)
        self.write_index()

    def add(self, anime_id, cover_id, size, *, local_size=None, present=True, suffix=".jpg"):
        """Index the cover with size; write local_size bytes (default size) unless present is False."""
        self.entries[str(anime_id)] = (cover_id, size, suffix)
        if present:
            self.write_file(cover_id, size if local_size is None else local_size, suffix)
        self.write_index()
        return str(anime_id)

    def write_file(self, cover_id, size, suffix=".jpg"):
        (self.dir / (cover_id + suffix)).write_bytes(body_of(cover_id, size, suffix))

    def write_index(self):
        covers = {anime_id: {"id": cover_id, "file": cover_id + suffix, "size": size}
                  for anime_id, (cover_id, size, suffix) in self.entries.items()}
        (self.dir / INDEX).write_text(json.dumps({"version": 1, "covers": covers}), encoding="utf-8")

    def body(self, cover_id, suffix=".jpg"):
        return (self.dir / (cover_id + suffix)).read_bytes()

    def served_path(self):
        return self.dir / SERVED_MAP

    def served_map(self):
        return json.loads(self.served_path().read_text(encoding="utf-8"))

    def snapshot(self):
        """Bytes of every file in the folder, for an untouched-files check."""
        return {path.name: path.read_bytes() for path in sorted(self.dir.iterdir())}


@pytest.fixture
def covers(tmp_path):
    return Covers(tmp_path)


class FakeS3:
    """A stateful in-memory bucket with the two S3 calls the tool may make."""

    def __init__(self, page_size=2):
        self.objects = {}  # key -> bytes
        self.calls = []  # ("list", params) or ("put", params)
        self.page_size = page_size
        self.fail_keys = set()
        self.before_put = None
        self.list_error = None  # raised by every list_objects_v2 call when set

    def seed(self, key, data):
        self.objects[key] = data

    def list_objects_v2(self, **params):
        self.calls.append(("list", dict(params)))
        assert set(params) <= {"Bucket", "Prefix", "ContinuationToken", "MaxKeys"}, params
        assert params["Bucket"] == BUCKET
        assert params["Prefix"] == PREFIX
        if self.list_error is not None:
            raise self.list_error
        keys = sorted(key for key in self.objects if key.startswith(params["Prefix"]))
        token = params.get("ContinuationToken")
        start = 0 if token is None else int(token[len("token-"):])
        chunk = keys[start:start + self.page_size]
        truncated = start + self.page_size < len(keys)
        page = {"IsTruncated": truncated, "KeyCount": len(chunk), "Name": BUCKET, "Prefix": PREFIX}
        if chunk:
            page["Contents"] = [{"Key": key, "Size": len(self.objects[key])} for key in chunk]
        if truncated:
            page["NextContinuationToken"] = "token-%d" % (start + self.page_size)
        return page

    def put_object(self, **params):
        self.calls.append(("put", {k: v for k, v in params.items() if k != "Body"}))
        assert {"Bucket", "Key", "Body", "ContentType"} <= set(params), params
        assert "ACL" not in params, "the bucket is private"
        assert params["Bucket"] == BUCKET
        if self.before_put is not None:
            self.before_put(params)
        if params["Key"] in self.fail_keys:
            from botocore.exceptions import ClientError

            raise ClientError({"Error": {"Code": "InternalError", "Message": LEAK + params["Key"]}}, "PutObject")
        body = params["Body"]
        if hasattr(body, "read"):
            body = body.read()
        self.objects[params["Key"]] = bytes(body)
        return {"ETag": '"etag"'}

    def puts(self):
        return [params for kind, params in self.calls if kind == "put"]

    def listing_passes(self):
        """Indexes into calls where a full listing starts (a call without a continuation token)."""
        return [n for n, (kind, params) in enumerate(self.calls)
                if kind == "list" and "ContinuationToken" not in params]


@pytest.fixture
def s3():
    return FakeS3()


def key_of(cover_id):
    return PREFIX + cover_id


def served_entry(cover_id, size):
    return {"id": cover_id, "key": key_of(cover_id), "size": size}


def summary(uploaded=0, skipped=0, missing=0, mismatched=0, failed=0, served=0):
    return {"uploaded": uploaded, "skipped": skipped, "missing": missing, "mismatched": mismatched,
            "failed": failed, "served": served}


def run(s3covers, s3, covers, log=None):
    lines = [] if log is None else log
    result = s3covers.run(s3, covers.dir, BUCKET, log=lines.append)
    return result, lines


def final_line(result):
    return ("s3 covers done: uploaded=%d skipped=%d missing=%d mismatched=%d failed=%d served=%d"
            % (result["uploaded"], result["skipped"], result["missing"], result["mismatched"],
               result["failed"], result["served"]))


# Command line plumbing (pattern of test_s3upload.py, no cross-test imports).

@pytest.fixture
def streams(monkeypatch):
    """Return install(), which swaps stdout and stderr for cp949 streams over byte buffers.

    Call install() in the test body: pytest's capture reinstalls its own streams
    when the body starts, so a swap made during fixture setup would be undone.
    """

    def install():
        out = io.TextIOWrapper(io.BytesIO(), encoding="cp949")
        err = io.TextIOWrapper(io.BytesIO(), encoding="cp949")
        monkeypatch.setattr(sys, "stdout", out)
        monkeypatch.setattr(sys, "stderr", err)

        def captured():
            text = ""
            for stream in (out, err):
                stream.flush()
                text += stream.buffer.getvalue().decode("utf-8")
            return text

        return captured

    yield install
    for stream in (sys.stdout, sys.stderr):
        reconfigure = getattr(stream, "reconfigure", None)
        if reconfigure is not None:
            reconfigure(encoding="utf-8", errors="backslashreplace")


class FakeSessions:
    """Stands in for boto3.Session; records every construction and client request."""

    def __init__(self, client, session_error=None):
        self.client_to_return = client
        self.session_error = session_error
        self.sessions = []
        self.clients = []

    def __call__(self, *args, **kwargs):
        self.sessions.append((args, kwargs))
        if self.session_error is not None:
            raise self.session_error
        owner = self

        class Session:
            def client(self, *c_args, **c_kwargs):
                owner.clients.append((c_args, c_kwargs))
                if owner.client_to_return is None:
                    raise AssertionError("no S3 client may be created in this test")
                return owner.client_to_return

        return Session()


@pytest.fixture
def sessions(monkeypatch):
    import boto3

    def install(client, session_error=None):
        fake = FakeSessions(client, session_error)
        monkeypatch.setattr(boto3, "Session", fake)
        return fake

    return install


def exit_code(call):
    """main's result, whether it returns the code or lets argparse raise SystemExit."""
    try:
        return call()
    except SystemExit as error:
        return error.code


def main_args(covers, *extra):
    return ["--bucket", BUCKET, "--covers", str(covers.dir), *extra]


# Interface constants.

def test_module_constants(s3covers):
    assert s3covers.KEY_PREFIX == PREFIX
    assert s3covers.CONTENT_TYPE == CONTENT_TYPE
    assert s3covers.PNG_CONTENT_TYPE == PNG_CONTENT_TYPE
    assert s3covers.SERVED_MAP_NAME == SERVED_MAP


# Criterion 1: every indexed, right-size cover is uploaded once; nothing else is.

def test_first_run_uploads_every_indexed_cover_once(s3covers, s3, covers):
    for anime_id, cover_id, size in zip(ANIME_IDS[:3], COVER_IDS[:3], (11, 13, 17)):
        covers.add(anime_id, cover_id, size)

    result, _ = run(s3covers, s3, covers)

    assert result == summary(uploaded=3, served=3)
    puts = s3.puts()
    assert sorted(params["Key"] for params in puts) == sorted(key_of(c) for c in COVER_IDS[:3])
    assert all(params["ContentType"] == CONTENT_TYPE for params in puts)
    for cover_id in COVER_IDS[:3]:
        assert s3.objects[key_of(cover_id)] == covers.body(cover_id)


def test_files_the_index_does_not_name_are_not_uploaded(s3covers, s3, covers):
    covers.add(ANIME_IDS[0], COVER_IDS[0], 11)
    covers.write_file(COVER_IDS[1], 13)  # a cover file left over, not in the index
    (covers.dir / "stray.jpg").write_bytes(b"\xff\xd8stray")
    (covers.dir / "notes.txt").write_bytes(b"not a cover")

    result, _ = run(s3covers, s3, covers)

    assert result == summary(uploaded=1, served=1)
    assert [params["Key"] for params in s3.puts()] == [key_of(COVER_IDS[0])]
    assert sorted(s3.objects) == [key_of(COVER_IDS[0])]


def test_objects_outside_the_prefix_are_ignored(s3covers, s3, covers):
    a = covers.add(ANIME_IDS[0], COVER_IDS[0], 11)
    # The audio upload's prefix: same key tail and size, but not a cover object.
    s3.seed("a/" + COVER_IDS[0], body_of(COVER_IDS[0], 11))

    result, _ = run(s3covers, s3, covers)

    assert result == summary(uploaded=1, served=1)
    assert covers.served_map() == {a: served_entry(COVER_IDS[0], 11)}


# Criterion 2: a rerun against the same bucket uploads nothing.

def test_rerun_uploads_nothing_and_skips_everything(s3covers, s3, covers):
    for anime_id, cover_id, size in zip(ANIME_IDS, COVER_IDS, (11, 13, 17, 19, 23)):
        covers.add(anime_id, cover_id, size)
    first, _ = run(s3covers, s3, covers)
    served_first = covers.served_map()
    puts_before = len(s3.puts())

    second, _ = run(s3covers, s3, covers)

    assert first == summary(uploaded=5, served=5)
    assert second == summary(skipped=5, served=5)
    assert len(s3.puts()) == puts_before
    assert covers.served_map() == served_first


def test_listed_object_with_another_size_is_uploaded_again(s3covers, s3, covers):
    a = covers.add(ANIME_IDS[0], COVER_IDS[0], 11)
    b = covers.add(ANIME_IDS[1], COVER_IDS[1], 13)
    s3.seed(key_of(COVER_IDS[0]), b"\xff\xd8short")  # a short leftover
    s3.seed(key_of(COVER_IDS[1]), covers.body(COVER_IDS[1]))

    result, _ = run(s3covers, s3, covers)

    assert result == summary(uploaded=1, skipped=1, served=2)
    assert [params["Key"] for params in s3.puts()] == [key_of(COVER_IDS[0])]
    assert covers.served_map() == {a: served_entry(COVER_IDS[0], 11), b: served_entry(COVER_IDS[1], 13)}


def test_listing_follows_every_page(s3covers, s3, covers):
    # Five objects at two per page: the last ones are seen only on page 3.
    for anime_id, cover_id, size in zip(ANIME_IDS, COVER_IDS, (11, 13, 17, 19, 23)):
        covers.add(anime_id, cover_id, size)
        s3.seed(key_of(cover_id), covers.body(cover_id))

    result, _ = run(s3covers, s3, covers)

    assert result == summary(skipped=5, served=5)
    assert s3.puts() == []
    assert len(s3.listing_passes()) == 2


def test_listed_object_is_skipped_even_when_the_local_file_is_missing(s3covers, s3, covers):
    # Precedence: the listing check comes before the local-file checks.
    gone = covers.add(ANIME_IDS[0], COVER_IDS[0], 11, present=False)
    s3.seed(key_of(COVER_IDS[0]), body_of(COVER_IDS[0], 11))
    odd = covers.add(ANIME_IDS[1], COVER_IDS[1], 13, local_size=12)
    s3.seed(key_of(COVER_IDS[1]), body_of(COVER_IDS[1], 13))

    result, _ = run(s3covers, s3, covers)

    assert result == summary(skipped=2, served=2)
    assert s3.puts() == []
    assert covers.served_map() == {gone: served_entry(COVER_IDS[0], 11), odd: served_entry(COVER_IDS[1], 13)}


# Criterion 3: missing and wrong-size files; a failed put.

def test_missing_and_wrong_size_files_are_counted_not_uploaded(s3covers, s3, covers):
    a = covers.add(ANIME_IDS[0], COVER_IDS[0], 11)
    gone = covers.add(ANIME_IDS[1], COVER_IDS[1], 13, present=False)
    odd = covers.add(ANIME_IDS[2], COVER_IDS[2], 17, local_size=16)

    result, _ = run(s3covers, s3, covers)

    assert result == summary(uploaded=1, missing=1, mismatched=1, served=1)
    assert [params["Key"] for params in s3.puts()] == [key_of(COVER_IDS[0])]
    served = covers.served_map()
    assert gone not in served
    assert odd not in served
    assert served == {a: served_entry(COVER_IDS[0], 11)}


def test_failed_put_is_counted_and_the_run_continues(s3covers, s3, covers):
    names = [covers.add(anime_id, cover_id, size)
             for anime_id, cover_id, size in zip(ANIME_IDS[:3], COVER_IDS[:3], (11, 13, 17))]
    s3.fail_keys.add(key_of(COVER_IDS[1]))

    result, _ = run(s3covers, s3, covers)

    assert result == summary(uploaded=2, failed=1, served=2)
    assert sorted(params["Key"] for params in s3.puts()) == sorted(key_of(c) for c in COVER_IDS[:3])
    assert set(covers.served_map()) == {names[0], names[2]}


def test_main_returns_1_after_a_failed_put(s3covers, s3, covers, sessions, streams):
    covers.add(ANIME_IDS[0], COVER_IDS[0], 11)
    b = covers.add(ANIME_IDS[1], COVER_IDS[1], 13)
    s3.fail_keys.add(key_of(COVER_IDS[0]))
    sessions(s3)
    streams()

    assert exit_code(lambda: s3covers.main(main_args(covers))) == 1
    assert set(covers.served_map()) == {b}


def test_main_returns_0_when_nothing_fails(s3covers, s3, covers, sessions, streams):
    covers.add(ANIME_IDS[0], COVER_IDS[0], 11)
    fake = sessions(s3)
    streams()

    assert exit_code(lambda: s3covers.main(main_args(covers, "--profile", "songrush-upload"))) == 0
    assert len(fake.sessions) == 1
    assert fake.sessions[0][1].get("profile_name") == "songrush-upload"
    assert fake.clients == [(("s3",), {"region_name": REGION})]
    assert [params["Key"] for params in s3.puts()] == [key_of(COVER_IDS[0])]


def test_main_without_profile_uses_the_default_chain(s3covers, s3, covers, sessions, streams):
    covers.add(ANIME_IDS[0], COVER_IDS[0], 11)
    fake = sessions(s3)
    streams()

    assert exit_code(lambda: s3covers.main(main_args(covers))) == 0
    assert len(fake.sessions) == 1
    args, kwargs = fake.sessions[0]
    assert args == ()
    assert kwargs.get("profile_name") is None


def test_main_with_missing_files_but_no_failure_returns_0(s3covers, s3, covers, sessions, streams):
    covers.add(ANIME_IDS[0], COVER_IDS[0], 11, present=False)
    covers.add(ANIME_IDS[1], COVER_IDS[1], 13, local_size=12)
    sessions(s3)
    streams()

    assert exit_code(lambda: s3covers.main(main_args(covers))) == 0
    assert s3.puts() == []


# Criterion 4: the served map.

def test_served_map_holds_exactly_the_covers_listed_with_the_index_size(s3covers, s3, covers):
    up = covers.add(ANIME_IDS[0], COVER_IDS[0], 11)
    # Local file gone, but the object is already listed with the index size: served.
    kept = covers.add(ANIME_IDS[1], COVER_IDS[1], 13, present=False)
    s3.seed(key_of(COVER_IDS[1]), body_of(COVER_IDS[1], 13))
    # Local file gone and the object has another size: missing, not served.
    short = covers.add(ANIME_IDS[2], COVER_IDS[2], 17, present=False)
    s3.seed(key_of(COVER_IDS[2]), body_of(COVER_IDS[2], 5))
    # A cover object no index entry names: never served.
    s3.seed(key_of(COVER_IDS[3]), body_of(COVER_IDS[3], 19))

    result, _ = run(s3covers, s3, covers)

    assert result == summary(uploaded=1, skipped=1, missing=1, served=2)
    assert covers.served_map() == {up: served_entry(COVER_IDS[0], 11),
                                   kept: served_entry(COVER_IDS[1], 13)}
    assert short not in covers.served_map()
    assert not list(covers.dir.glob("*.part"))


def test_served_map_is_written_after_every_upload(s3covers, s3, covers):
    for anime_id, cover_id, size in zip(ANIME_IDS[:3], COVER_IDS[:3], (11, 13, 17)):
        covers.add(anime_id, cover_id, size)
    # An older run served the cover whose upload now fails.
    old = json.dumps({ANIME_IDS[1]: served_entry(COVER_IDS[1], 13)}).encode("ascii")
    covers.served_path().write_bytes(old)
    s3.fail_keys.add(key_of(COVER_IDS[1]))
    seen = []
    s3.before_put = lambda params: seen.append(covers.served_path().read_bytes())

    result, _ = run(s3covers, s3, covers)

    assert result == summary(uploaded=2, failed=1, served=2)
    assert seen == [old, old, old]  # untouched while uploads run
    served = covers.served_map()
    assert ANIME_IDS[1] not in served
    assert served == {ANIME_IDS[0]: served_entry(COVER_IDS[0], 11),
                      ANIME_IDS[2]: served_entry(COVER_IDS[2], 17)}
    # One listing before the uploads, one after the last of them.
    first, second = s3.listing_passes()
    put_positions = [n for n, (kind, _) in enumerate(s3.calls) if kind == "put"]
    assert first < min(put_positions)
    assert second > max(put_positions)


def test_served_map_is_written_on_an_empty_index(s3covers, s3, covers):
    result, _ = run(s3covers, s3, covers)

    assert result == summary()
    assert covers.served_map() == {}
    assert s3.puts() == []


# Criterion 5: a missing or malformed index stops everything before any S3 call.

MALFORMED = {
    "bad-json": b"{not json",
    "not-utf8": b"\xff\xfe\x00",
    "not-an-object": b"[]",
    "wrong-version": json.dumps({"version": 2, "covers": {}}).encode("ascii"),
    "no-version": json.dumps({"covers": {}}).encode("ascii"),
    "covers-not-an-object": json.dumps({"version": 1, "covers": []}).encode("ascii"),
    "no-covers": json.dumps({"version": 1}).encode("ascii"),
}


@pytest.mark.parametrize("content", list(MALFORMED.values()), ids=list(MALFORMED))
def test_malformed_index_returns_1_before_any_s3_call(s3covers, s3, covers, sessions, streams, content):
    covers.write_file(COVER_IDS[0], 11)
    (covers.dir / INDEX).write_bytes(content)
    covers.served_path().write_bytes(b'{"1": {"id": "OLD", "size": 3}}')
    before = covers.snapshot()
    fake = sessions(s3)
    streams()

    assert exit_code(lambda: s3covers.main(main_args(covers))) == 1
    assert s3.calls == []
    assert fake.clients == []
    assert covers.snapshot() == before


def test_missing_index_returns_1_before_any_s3_call(s3covers, s3, covers, sessions, streams):
    (covers.dir / INDEX).unlink()
    covers.write_file(COVER_IDS[0], 11)
    fake = sessions(s3)
    captured = streams()

    assert exit_code(lambda: s3covers.main(main_args(covers))) == 1
    assert s3.calls == []
    assert fake.clients == []
    assert not covers.served_path().exists()
    assert INDEX in captured()


@pytest.mark.parametrize("content", list(MALFORMED.values()), ids=list(MALFORMED))
def test_run_raises_on_a_malformed_index(s3covers, s3, covers, content):
    (covers.dir / INDEX).write_bytes(content)

    with pytest.raises(Exception):
        run(s3covers, s3, covers)
    assert s3.calls == []
    assert not covers.served_path().exists()


def test_run_raises_on_a_missing_index(s3covers, s3, covers):
    (covers.dir / INDEX).unlink()

    with pytest.raises(Exception):
        run(s3covers, s3, covers)
    assert s3.calls == []
    assert not covers.served_path().exists()


def entry(cover_id, size=13, file=None):
    return {"id": cover_id, "file": cover_id + ".jpg" if file is None else file, "size": size}


# The second index entry (anime ANIME_IDS[1]) breaks one rule; the first is valid.
BAD_ENTRIES = {
    "id-dot-dot": {"id": "../x", "file": "../x.jpg", "size": 13},
    "id-space": {"id": "a b", "file": "a b.jpg", "size": 13},
    "id-slash": {"id": "ab/cd", "file": "ab/cd.jpg", "size": 13},
    "id-empty": {"id": "", "file": ".jpg", "size": 13},
    "id-too-long": entry("A" * 65),
    "id-not-a-string": {"id": 5, "file": "5.jpg", "size": 13},
    "id-absent": {"file": COVER_IDS[1] + ".jpg", "size": 13},
    "file-mismatch": entry(COVER_IDS[1], file=COVER_IDS[2] + ".jpg"),
    "file-other-id-png": entry(COVER_IDS[1], file=COVER_IDS[2] + ".png"),
    "file-dot-dot-png": entry(COVER_IDS[1], file="../x.png"),
    "file-upper-png": entry(COVER_IDS[1], file=COVER_IDS[1] + ".PNG"),
    "file-upper-jpg": entry(COVER_IDS[1], file=COVER_IDS[1] + ".JPG"),
    "file-jpeg": entry(COVER_IDS[1], file=COVER_IDS[1] + ".jpeg"),
    "file-gif": entry(COVER_IDS[1], file=COVER_IDS[1] + ".gif"),
    "file-webp": entry(COVER_IDS[1], file=COVER_IDS[1] + ".webp"),
    "file-png-part": entry(COVER_IDS[1], file=COVER_IDS[1] + ".png.part"),
    "file-no-suffix": entry(COVER_IDS[1], file=COVER_IDS[1]),
    "file-absent": {"id": COVER_IDS[1], "size": 13},
    "size-zero": entry(COVER_IDS[1], size=0),
    "size-negative": entry(COVER_IDS[1], size=-1),
    "size-true": entry(COVER_IDS[1], size=True),
    "size-string": entry(COVER_IDS[1], size="5"),
    "size-float": entry(COVER_IDS[1], size=5.0),
    "size-absent": {"id": COVER_IDS[1], "file": COVER_IDS[1] + ".jpg"},
    "entry-not-an-object": [COVER_IDS[1], 13],
    "duplicate-id-case": entry(COVER_IDS[0].lower()),
    "duplicate-id-exact": entry(COVER_IDS[0]),
}


def write_bad_index(covers, bad):
    covers.write_file(COVER_IDS[0], 11)
    covers.write_file(COVER_IDS[1], 13)
    index = {"version": 1, "covers": {ANIME_IDS[0]: entry(COVER_IDS[0], 11), ANIME_IDS[1]: bad}}
    (covers.dir / INDEX).write_text(json.dumps(index), encoding="utf-8")


@pytest.mark.parametrize("bad", list(BAD_ENTRIES.values()), ids=list(BAD_ENTRIES))
def test_bad_entry_returns_1_before_any_s3_call_and_names_no_id(s3covers, s3, covers, sessions, streams, bad):
    write_bad_index(covers, bad)
    before = covers.snapshot()
    fake = sessions(s3)
    captured = streams()

    assert exit_code(lambda: s3covers.main(main_args(covers))) == 1
    assert s3.calls == []
    assert fake.clients == []
    assert covers.snapshot() == before
    text = captured()
    assert text.strip(), "the tool says why it stopped"
    for value in ANIME_IDS + COVER_IDS + [COVER_IDS[0].lower()]:
        assert value not in text, value


@pytest.mark.parametrize("bad", list(BAD_ENTRIES.values()), ids=list(BAD_ENTRIES))
def test_run_raises_on_a_bad_entry(s3covers, s3, covers, bad):
    write_bad_index(covers, bad)

    with pytest.raises(Exception):
        run(s3covers, s3, covers)
    assert s3.calls == []
    assert not covers.served_path().exists()


def test_full_id_alphabet_and_64_characters_are_accepted(s3covers, s3, covers):
    # The full id alphabet, and a 64-character id, are valid.
    a = covers.add(ANIME_IDS[0], "a-Z_09", 11)
    b = covers.add(ANIME_IDS[1], "B" * 64, 13)

    result, _ = run(s3covers, s3, covers)

    assert result == summary(uploaded=2, served=2)
    assert covers.served_map() == {a: served_entry("a-Z_09", 11), b: served_entry("B" * 64, 13)}


# Cycle 095 criteria 3-4: PNG covers.

def test_index_mixing_jpg_and_png_entries_loads(s3covers, covers):
    covers.add(ANIME_IDS[0], COVER_IDS[0], 11)
    covers.add(ANIME_IDS[1], COVER_IDS[1], 13, suffix=".png")

    entries = s3covers.load_index(covers.dir)

    assert set(entries) == {ANIME_IDS[0], ANIME_IDS[1]}


def test_png_uploads_with_image_png_and_jpg_with_image_jpeg(s3covers, s3, covers):
    j = covers.add(ANIME_IDS[0], COVER_IDS[0], 11)
    p = covers.add(ANIME_IDS[1], COVER_IDS[1], 13, suffix=".png")

    result, _ = run(s3covers, s3, covers)

    assert result == summary(uploaded=2, served=2)
    types = {params["Key"]: params["ContentType"] for params in s3.puts()}
    assert types == {key_of(COVER_IDS[0]): CONTENT_TYPE, key_of(COVER_IDS[1]): PNG_CONTENT_TYPE}
    assert s3.objects[key_of(COVER_IDS[0])] == covers.body(COVER_IDS[0])
    assert s3.objects[key_of(COVER_IDS[1])] == covers.body(COVER_IDS[1], ".png")
    # Key and served map are unchanged by the format.
    assert covers.served_map() == {j: served_entry(COVER_IDS[0], 11), p: served_entry(COVER_IDS[1], 13)}


def test_png_entry_reads_the_png_file_not_a_jpg_of_the_same_id(s3covers, s3, covers):
    # The local path is <covers>/<file>: a stray <id>.jpg must not stand in for a missing <id>.png.
    covers.add(ANIME_IDS[0], COVER_IDS[0], 13, suffix=".png", present=False)
    covers.write_file(COVER_IDS[0], 13)  # <id>.jpg with the indexed size

    result, _ = run(s3covers, s3, covers)

    assert result == summary(missing=1)
    assert s3.puts() == []


def test_png_entry_with_a_wrong_size_file_is_mismatched(s3covers, s3, covers):
    covers.add(ANIME_IDS[0], COVER_IDS[0], 13, suffix=".png", local_size=12)

    result, _ = run(s3covers, s3, covers)

    assert result == summary(mismatched=1)
    assert s3.puts() == []


def test_listed_png_is_skipped_on_a_rerun(s3covers, s3, covers):
    covers.add(ANIME_IDS[0], COVER_IDS[0], 13, suffix=".png")
    run(s3covers, s3, covers)
    puts_before = len(s3.puts())

    result, _ = run(s3covers, s3, covers)

    assert result == summary(skipped=1, served=1)
    assert len(s3.puts()) == puts_before


def test_main_uploads_a_mixed_index(s3covers, s3, covers, sessions, streams):
    covers.add(ANIME_IDS[0], COVER_IDS[0], 11)
    covers.add(ANIME_IDS[1], COVER_IDS[1], 13, suffix=".png")
    sessions(s3)
    streams()

    assert exit_code(lambda: s3covers.main(main_args(covers))) == 0
    assert sorted(params["ContentType"] for params in s3.puts()) == [CONTENT_TYPE, PNG_CONTENT_TYPE]


# Criterion 6: quiet logs and the final counts line.

def secrets_of_the_run():
    values = list(ANIME_IDS) + list(COVER_IDS)
    values += [key_of(cover_id) for cover_id in COVER_IDS]
    values += [cover_id + ".jpg" for cover_id in COVER_IDS]
    values += [cover_id + ".png" for cover_id in COVER_IDS]
    values += [ACCESS_KEY, SECRET_KEY, LEAK]
    return values


def build_mixed_run(covers, s3):
    """Five covers: two uploaded, one failed put, one missing, one wrong size."""
    covers.add(ANIME_IDS[0], COVER_IDS[0], 11)
    covers.add(ANIME_IDS[1], COVER_IDS[1], 13)
    covers.add(ANIME_IDS[2], COVER_IDS[2], 17)
    covers.add(ANIME_IDS[3], COVER_IDS[3], 19, present=False)
    covers.add(ANIME_IDS[4], COVER_IDS[4], 23, local_size=22)
    s3.fail_keys.add(key_of(COVER_IDS[2]))


def test_run_log_carries_no_id_key_or_error_text(s3covers, s3, covers):
    build_mixed_run(covers, s3)

    result, lines = run(s3covers, s3, covers)

    assert result == summary(uploaded=2, missing=1, mismatched=1, failed=1, served=2)
    assert lines
    for line in lines:
        for value in secrets_of_the_run():
            assert value not in line, (value, line)
        assert "LEAKMARKER" not in line
    assert lines[-1].startswith(final_line(result)), lines[-1]


def test_main_output_carries_no_id_key_or_credential(s3covers, s3, covers, sessions, streams):
    build_mixed_run(covers, s3)
    sessions(s3)
    captured = streams()

    assert exit_code(lambda: s3covers.main(main_args(covers))) == 1
    text = captured()
    assert text.strip(), "the tool prints a summary"
    for value in secrets_of_the_run():
        assert value not in text, value
    assert "LEAKMARKER" not in text
    last = [line for line in text.splitlines() if line.strip()][-1]
    assert last.startswith(final_line(summary(uploaded=2, missing=1, mismatched=1, failed=1, served=2))), last


def test_final_line_on_a_rerun(s3covers, s3, covers):
    covers.add(ANIME_IDS[0], COVER_IDS[0], 11)
    run(s3covers, s3, covers)

    result, lines = run(s3covers, s3, covers)

    assert lines[-1].startswith("s3 covers done: uploaded=0 skipped=1 missing=0 mismatched=0 failed=0 served=1")


def assert_quiet_error(text, type_name, code=None):
    assert type_name in text, text
    if code is not None:
        assert code in text, text
    assert "LEAKMARKER" not in text
    for value in secrets_of_the_run():
        assert value not in text, value


def test_main_prints_a_session_error_by_type_only(s3covers, s3, covers, sessions, streams):
    from botocore.exceptions import ProfileNotFound

    covers.add(ANIME_IDS[0], COVER_IDS[0], 11)
    sessions(s3, session_error=ProfileNotFound(profile=LEAK + COVER_IDS[0]))
    captured = streams()

    assert exit_code(lambda: s3covers.main(main_args(covers, "--profile", "songrush-upload"))) == 1
    assert s3.calls == []
    assert_quiet_error(captured(), "ProfileNotFound")


def test_main_prints_a_listing_client_error_by_type_and_code_only(s3covers, s3, covers, sessions, streams):
    from botocore.exceptions import ClientError

    covers.add(ANIME_IDS[0], COVER_IDS[0], 11)
    s3.list_error = ClientError({"Error": {"Code": "AccessDenied", "Message": LEAK + key_of(COVER_IDS[0])}},
                               "ListObjectsV2")
    sessions(s3)
    captured = streams()

    assert exit_code(lambda: s3covers.main(main_args(covers))) == 1
    assert s3.puts() == []
    assert not covers.served_path().exists()
    assert_quiet_error(captured(), "ClientError", "AccessDenied")


def test_main_prints_a_transport_error_by_type_only(s3covers, s3, covers, sessions, streams):
    from botocore.exceptions import EndpointConnectionError

    covers.add(ANIME_IDS[0], COVER_IDS[0], 11)
    s3.list_error = EndpointConnectionError(endpoint_url=LEAK + key_of(COVER_IDS[0]))
    sessions(s3)
    captured = streams()

    assert exit_code(lambda: s3covers.main(main_args(covers))) == 1
    assert s3.puts() == []
    assert_quiet_error(captured(), "EndpointConnectionError")


def test_main_put_error_is_printed_by_type_and_code_only(s3covers, s3, covers, sessions, streams):
    covers.add(ANIME_IDS[0], COVER_IDS[0], 11)
    s3.fail_keys.add(key_of(COVER_IDS[0]))
    sessions(s3)
    captured = streams()

    assert exit_code(lambda: s3covers.main(main_args(covers))) == 1
    assert_quiet_error(captured(), "ClientError", "InternalError")


# Criterion 7: strict command line.

def test_main_without_bucket_exits_2(s3covers, covers, sessions, streams):
    fake = sessions(None)
    streams()

    assert exit_code(lambda: s3covers.main(["--covers", str(covers.dir)])) == 2
    assert fake.sessions == []


def test_main_rejects_a_positional_argument(s3covers, covers, sessions, streams):
    covers.add(ANIME_IDS[0], COVER_IDS[0], 11)
    before = covers.snapshot()
    fake = sessions(None)
    streams()

    assert exit_code(lambda: s3covers.main(main_args(covers, "stray"))) == 2
    assert fake.sessions == []
    assert covers.snapshot() == before


def test_main_rejects_an_unknown_option(s3covers, covers, sessions, streams):
    fake = sessions(None)
    streams()

    assert exit_code(lambda: s3covers.main(["--bucket", BUCKET, "--cover", str(covers.dir)])) == 2
    assert fake.sessions == []

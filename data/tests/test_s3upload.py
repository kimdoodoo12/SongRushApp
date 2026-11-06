"""Cycle 074 criteria 1-10: the resumable S3 audio upload (C-017).

docs/cycles/074-s3-audio.md is the specification; its "Revisions after spec
review round 1" section governs. Every test drives a real boto3 S3 client,
built with fake credentials, through botocore.stub.Stubber, so the calls are
checked against the real S3 model and nothing touches the network. Audio files
are a few fake bytes written under tmp_path; no media is involved. The import of
songrush_data.s3upload stays inside a fixture so that a missing module fails
these tests, not the collection.
"""

import io
import json
import sys

import pytest

BUCKET = "test-bucket"
REGION = "ap-southeast-2"
ACCESS_KEY = "AKIDFAKE"
SECRET_KEY = "fake"
PREFIX = "a/"
WORK_MAP = "s3-keys.work.json"
SERVED_MAP = "s3-keys.json"
INDEX = "index.json"


@pytest.fixture
def s3upload():
    from songrush_data import s3upload as module

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

def key_of(number):
    """A deterministic object key: "a/" and 22 URL-safe characters, like the real ones."""
    return PREFIX + "TESTKEY%015d" % number


def keys_from(numbers):
    """A zero-argument new_key that hands out key_of(n) for each n in turn."""
    pending = list(numbers)

    def new_key():
        assert pending, "new_key called more often than expected"
        return key_of(pending.pop(0))

    return new_key


def no_new_key():
    raise AssertionError("new_key must not be called when every basename has a key")


def body_of(basename, size):
    seed = basename.encode("ascii")
    return (seed * (size // len(seed) + 1))[:size]


class Cache:
    """A cache folder with index.json, fake audio files and the two maps."""

    def __init__(self, root):
        self.dir = root / "cache"
        self.dir.mkdir()
        self.entries = {}  # basename -> (themeId, size)

    def add(self, theme_id, basename, size, *, local_size=None, present=True):
        """Index basename with size; write local_size bytes (default size) unless present is False."""
        self.entries[basename] = (theme_id, size)
        if present:
            (self.dir / basename).write_bytes(body_of(basename, size if local_size is None else local_size))
        self.write_index()
        return basename

    def write_index(self):
        index = {str(theme_id): {"basename": basename, "durationMs": 90000, "size": size}
                 for basename, (theme_id, size) in self.entries.items()}
        (self.dir / INDEX).write_text(json.dumps(index), encoding="utf-8")

    def body(self, basename):
        return (self.dir / basename).read_bytes()

    def size(self, basename):
        return self.entries[basename][1]

    def write_work_map(self, mapping):
        (self.dir / WORK_MAP).write_text(json.dumps(mapping), encoding="utf-8")

    def work_map(self):
        return json.loads((self.dir / WORK_MAP).read_text(encoding="utf-8"))

    def served_map(self):
        return json.loads((self.dir / SERVED_MAP).read_text(encoding="utf-8"))

    def snapshot(self):
        """Bytes of every file in the cache folder, for an untouched-files check."""
        return {path.name: path.read_bytes() for path in sorted(self.dir.iterdir())}


@pytest.fixture
def cache(tmp_path):
    return Cache(tmp_path)


def s3_client():
    import boto3

    session = boto3.session.Session(aws_access_key_id=ACCESS_KEY, aws_secret_access_key=SECRET_KEY,
                                    region_name=REGION)
    return session.client("s3")


class Bucket:
    """A real boto3 S3 client behind a Stubber, plus helpers that queue the expected calls."""

    def __init__(self):
        from botocore.stub import Stubber

        self.client = s3_client()
        self.stubber = Stubber(self.client)
        self.stubber.activate()

    def listing(self, objects, *, pages=1):
        """Queue one full ListObjectsV2 pass over objects {key: size}, split into pages."""
        items = [{"Key": key, "Size": size} for key, size in objects.items()]
        if not items or pages == 1:
            chunks = [items]
        else:
            step = -(-len(items) // pages)
            chunks = [items[i:i + step] for i in range(0, len(items), step)]
        for number, chunk in enumerate(chunks):
            params = {"Bucket": BUCKET, "Prefix": PREFIX}
            if number > 0:
                params["ContinuationToken"] = "token-%d" % number
            response = {"IsTruncated": number < len(chunks) - 1, "KeyCount": len(chunk),
                        "Name": BUCKET, "Prefix": PREFIX}
            if chunk:
                response["Contents"] = chunk
            if number < len(chunks) - 1:
                response["NextContinuationToken"] = "token-%d" % (number + 1)
            self.stubber.add_response("list_objects_v2", response, params)

    def put(self, key, body):
        self.stubber.add_response("put_object", {"ETag": '"etag"'},
                                  {"Bucket": BUCKET, "Key": key, "Body": body, "ContentType": "audio/ogg"})

    def put_fails(self, key, body):
        self.stubber.add_client_error("put_object", service_error_code="InternalError",
                                      service_message="We encountered an internal error.",
                                      http_status_code=500,
                                      expected_params={"Bucket": BUCKET, "Key": key, "Body": body,
                                                       "ContentType": "audio/ogg"})

    def done(self):
        self.stubber.assert_no_pending_responses()


@pytest.fixture
def bucket():
    stubbed = Bucket()
    yield stubbed
    stubbed.stubber.deactivate()


def summary(uploaded=0, skipped=0, missing=0, mismatched=0, failed=0, served=0):
    return {"uploaded": uploaded, "skipped": skipped, "missing": missing, "mismatched": mismatched,
            "failed": failed, "served": served}


def run(s3upload, bucket, cache, new_key, log=None):
    lines = [] if log is None else log
    result = s3upload.run(bucket.client, cache.dir, BUCKET, log=lines.append, new_key=new_key)
    return result, lines


# Command line plumbing (pattern of test_audio.py, no cross-test imports).

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

    def __init__(self, client):
        self.client_to_return = client
        self.sessions = []
        self.clients = []

    def __call__(self, *args, **kwargs):
        self.sessions.append((args, kwargs))
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

    def install(client):
        fake = FakeSessions(client)
        monkeypatch.setattr(boto3, "Session", fake)
        return fake

    return install


def exit_code(call):
    """main's result, whether it returns the code or lets argparse raise SystemExit."""
    try:
        return call()
    except SystemExit as error:
        return error.code


def main_args(cache, *extra):
    return ["--bucket", BUCKET, "--cache", str(cache.dir), *extra]


# Criterion 1: first run on an empty bucket.

def test_first_run_uploads_every_file_and_serves_them(s3upload, bucket, cache):
    a = cache.add(101, "ShowA-OP1.ogg", 11)
    b = cache.add(102, "ShowB-ED1.ogg", 13)
    c = cache.add(103, "ShowC-OP2.ogg", 17)
    keys = {a: key_of(1), b: key_of(2), c: key_of(3)}
    bucket.listing({})  # an empty bucket has no Contents
    for name in (a, b, c):
        bucket.put(keys[name], cache.body(name))
    bucket.listing({keys[name]: cache.size(name) for name in (a, b, c)})

    result, _ = run(s3upload, bucket, cache, keys_from([1, 2, 3]))

    assert result == summary(uploaded=3, served=3)
    bucket.done()
    assert cache.work_map() == keys
    assert cache.served_map() == {name: {"key": keys[name], "size": cache.size(name)} for name in (a, b, c)}


def test_work_map_is_written_before_the_first_put(s3upload, bucket, cache):
    a = cache.add(101, "ShowA-OP1.ogg", 11)
    b = cache.add(102, "ShowB-ED1.ogg", 13)
    keys = {a: key_of(1), b: key_of(2)}
    work = cache.dir / WORK_MAP
    seen = []

    def before_put(params, **kwargs):
        # Record, do not assert: an exception here would surface as a failed upload.
        if work.exists():
            seen.append((params["Key"], json.loads(work.read_text(encoding="utf-8"))))
        else:
            seen.append((params["Key"], None))
        return None

    # The spec names before-call.s3.PutObject, but Stubber answers that event from its
    # own wildcard handler first, so a handler there never runs. before-parameter-build
    # is emitted to every handler, before the call is made.
    bucket.client.meta.events.register("before-parameter-build.s3.PutObject", before_put)

    def new_key_without_map():
        assert not work.exists(), "the work map must be written after the keys are assigned"
        return generate()

    generate = keys_from([1, 2])
    bucket.listing({})
    bucket.put(keys[a], cache.body(a))
    bucket.put(keys[b], cache.body(b))
    bucket.listing({keys[a]: 11, keys[b]: 13})

    result, _ = run(s3upload, bucket, cache, new_key_without_map)

    assert result == summary(uploaded=2, served=2)
    bucket.done()
    assert seen == [(keys[a], keys), (keys[b], keys)]


def test_uploads_go_in_sorted_basename_order(s3upload, bucket, cache):
    # The index lists them out of order; puts follow sorted basenames.
    z = cache.add(201, "Zeta-OP1.ogg", 5)
    m = cache.add(202, "Mu-OP1.ogg", 6)
    cache.write_work_map({z: key_of(1), m: key_of(2)})
    bucket.listing({})
    bucket.put(key_of(2), cache.body(m))
    bucket.put(key_of(1), cache.body(z))
    bucket.listing({key_of(1): 5, key_of(2): 6})

    result, _ = run(s3upload, bucket, cache, no_new_key)

    assert result == summary(uploaded=2, served=2)
    bucket.done()


def test_new_keys_are_distinct_and_in_the_key_form(s3upload, bucket, cache):
    names = [cache.add(300 + n, "Show%02d-OP1.ogg" % n, 4 + n) for n in range(5)]
    bucket.listing({})
    for number, name in enumerate(names, start=1):
        bucket.put(key_of(number), cache.body(name))
    bucket.listing({key_of(n): cache.size(name) for n, name in enumerate(names, start=1)})

    result, _ = run(s3upload, bucket, cache, keys_from(range(1, 6)))

    assert result["uploaded"] == 5
    work = cache.work_map()
    assert sorted(work) == sorted(names)
    assert len(set(work.values())) == 5
    assert all(key.startswith(PREFIX) for key in work.values())


def test_default_new_key_is_random_and_url_safe(s3upload, bucket, cache):
    import re

    a = cache.add(101, "ShowA-OP1.ogg", 3)
    b = cache.add(102, "ShowB-OP1.ogg", 4)
    from botocore.stub import ANY

    bucket.listing({})
    bucket.stubber.add_response("put_object", {"ETag": '"etag"'},
                                {"Bucket": BUCKET, "Key": ANY, "Body": cache.body(a), "ContentType": "audio/ogg"})
    bucket.stubber.add_response("put_object", {"ETag": '"etag"'},
                                {"Bucket": BUCKET, "Key": ANY, "Body": cache.body(b), "ContentType": "audio/ogg"})
    # The verify pass cannot know the random keys; listing nothing leaves nothing served.
    bucket.listing({})

    result = s3upload.run(bucket.client, cache.dir, BUCKET, log=[].append)

    assert result["uploaded"] == 2
    keys = list(cache.work_map().values())
    assert len(set(keys)) == 2
    for key in keys:
        assert re.fullmatch(r"a/[A-Za-z0-9_-]{22}", key), key
        assert "Show" not in key


# Criterion 2: second run.

def test_second_run_keeps_keys_and_skips_listed_objects(s3upload, bucket, cache):
    a = cache.add(101, "ShowA-OP1.ogg", 11)
    b = cache.add(102, "ShowB-ED1.ogg", 13)
    keys = {a: key_of(7), b: key_of(8)}
    cache.write_work_map(keys)
    bucket.listing({keys[a]: 11, keys[b]: 13})
    bucket.listing({keys[a]: 11, keys[b]: 13})

    result, _ = run(s3upload, bucket, cache, no_new_key)

    assert result == summary(skipped=2, served=2)
    bucket.done()
    assert cache.work_map() == keys
    assert cache.served_map() == {a: {"key": keys[a], "size": 11}, b: {"key": keys[b], "size": 13}}


def test_second_run_adds_a_key_only_for_a_new_basename(s3upload, bucket, cache):
    a = cache.add(101, "ShowA-OP1.ogg", 11)
    b = cache.add(102, "ShowB-ED1.ogg", 13)
    cache.write_work_map({a: key_of(7)})
    bucket.listing({key_of(7): 11})
    bucket.put(key_of(1), cache.body(b))
    bucket.listing({key_of(7): 11, key_of(1): 13})

    result, _ = run(s3upload, bucket, cache, keys_from([1]))

    assert result == summary(uploaded=1, skipped=1, served=2)
    bucket.done()
    assert cache.work_map() == {a: key_of(7), b: key_of(1)}


def test_work_map_entries_not_in_the_index_are_kept(s3upload, bucket, cache):
    a = cache.add(101, "ShowA-OP1.ogg", 11)
    cache.write_work_map({a: key_of(7), "Gone-OP1.ogg": key_of(9)})
    bucket.listing({key_of(7): 11, key_of(9): 50})
    bucket.listing({key_of(7): 11, key_of(9): 50})

    result, _ = run(s3upload, bucket, cache, no_new_key)

    assert result == summary(skipped=1, served=1)
    assert cache.work_map() == {a: key_of(7), "Gone-OP1.ogg": key_of(9)}
    assert cache.served_map() == {a: {"key": key_of(7), "size": 11}}


# Criterion 3: two-page listing.

def test_two_page_listing_is_followed(s3upload, bucket, cache):
    a = cache.add(101, "ShowA-OP1.ogg", 11)
    b = cache.add(102, "ShowB-ED1.ogg", 13)
    keys = {a: key_of(1), b: key_of(2)}
    cache.write_work_map(keys)
    # Page 1 holds only a's object, page 2 only b's: b is skipped only if page 2 is read.
    bucket.listing({keys[a]: 11, keys[b]: 13}, pages=2)
    bucket.listing({keys[a]: 11, keys[b]: 13}, pages=2)

    result, _ = run(s3upload, bucket, cache, no_new_key)

    assert result == summary(skipped=2, served=2)
    bucket.done()
    assert set(cache.served_map()) == {a, b}


def test_two_page_verify_listing_is_followed(s3upload, bucket, cache):
    a = cache.add(101, "ShowA-OP1.ogg", 11)
    b = cache.add(102, "ShowB-ED1.ogg", 13)
    keys = {a: key_of(1), b: key_of(2)}
    cache.write_work_map(keys)
    bucket.listing({})
    bucket.put(keys[a], cache.body(a))
    bucket.put(keys[b], cache.body(b))
    bucket.listing({keys[a]: 11, keys[b]: 13}, pages=2)

    result, _ = run(s3upload, bucket, cache, no_new_key)

    assert result == summary(uploaded=2, served=2)
    bucket.done()
    assert set(cache.served_map()) == {a, b}


# Criterion 4: listed size differs from the index.

def test_listed_size_mismatch_is_uploaded_again(s3upload, bucket, cache):
    a = cache.add(101, "ShowA-OP1.ogg", 11)
    b = cache.add(102, "ShowB-ED1.ogg", 13)
    keys = {a: key_of(1), b: key_of(2)}
    cache.write_work_map(keys)
    bucket.listing({keys[a]: 4, keys[b]: 13})  # a's object is a short leftover
    bucket.put(keys[a], cache.body(a))
    bucket.listing({keys[a]: 11, keys[b]: 13})

    result, _ = run(s3upload, bucket, cache, no_new_key)

    assert result == summary(uploaded=1, skipped=1, served=2)
    bucket.done()
    assert cache.served_map()[a] == {"key": keys[a], "size": 11}


def test_object_still_wrong_after_upload_is_not_served(s3upload, bucket, cache):
    a = cache.add(101, "ShowA-OP1.ogg", 11)
    cache.write_work_map({a: key_of(1)})
    bucket.listing({key_of(1): 4})
    bucket.put(key_of(1), cache.body(a))
    bucket.listing({key_of(1): 4})

    result, _ = run(s3upload, bucket, cache, no_new_key)

    assert result["uploaded"] == 1
    assert result["served"] == 0
    assert cache.served_map() == {}


# Criterion 5: missing local file.

def test_missing_local_file_is_counted_and_not_served(s3upload, bucket, cache):
    a = cache.add(101, "ShowA-OP1.ogg", 11)
    gone = cache.add(102, "Gone-OP1.ogg", 13, present=False)
    keys = {a: key_of(1), gone: key_of(2)}
    cache.write_work_map(keys)
    bucket.listing({})
    bucket.put(keys[a], cache.body(a))
    bucket.listing({keys[a]: 11})

    result, _ = run(s3upload, bucket, cache, no_new_key)

    assert result == summary(uploaded=1, missing=1, served=1)
    bucket.done()
    assert gone not in cache.served_map()


def test_missing_local_file_already_listed_with_index_size_is_served(s3upload, bucket, cache):
    gone = cache.add(102, "Gone-OP1.ogg", 13, present=False)
    cache.write_work_map({gone: key_of(2)})
    bucket.listing({key_of(2): 13})
    bucket.listing({key_of(2): 13})

    result, _ = run(s3upload, bucket, cache, no_new_key)

    bucket.done()  # no put_object
    assert result["uploaded"] == 0
    assert result["failed"] == 0
    assert result["served"] == 1
    assert cache.served_map() == {gone: {"key": key_of(2), "size": 13}}


# Criterion 6: local size differs from the index.

def test_local_size_mismatch_is_counted_not_uploaded_not_served(s3upload, bucket, cache):
    a = cache.add(101, "ShowA-OP1.ogg", 11)
    odd = cache.add(102, "Odd-OP1.ogg", 13, local_size=12)
    keys = {a: key_of(1), odd: key_of(2)}
    cache.write_work_map(keys)
    bucket.listing({})
    bucket.put(keys[a], cache.body(a))
    bucket.listing({keys[a]: 11})

    result, _ = run(s3upload, bucket, cache, no_new_key)

    assert result == summary(uploaded=1, mismatched=1, served=1)
    bucket.done()
    assert odd not in cache.served_map()


# Criterion 7: failed upload.

def test_failed_put_is_counted_and_the_run_continues(s3upload, bucket, cache):
    a = cache.add(101, "ShowA-OP1.ogg", 11)
    b = cache.add(102, "ShowB-ED1.ogg", 13)
    c = cache.add(103, "ShowC-OP2.ogg", 17)
    keys = {a: key_of(1), b: key_of(2), c: key_of(3)}
    bucket.listing({})
    bucket.put(keys[a], cache.body(a))
    bucket.put_fails(keys[b], cache.body(b))
    bucket.put(keys[c], cache.body(c))
    bucket.listing({keys[a]: 11, keys[c]: 17})

    result, _ = run(s3upload, bucket, cache, keys_from([1, 2, 3]))

    assert result == summary(uploaded=2, failed=1, served=2)
    bucket.done()
    assert cache.work_map() == keys  # the work map keeps the failed basename's key
    assert set(cache.served_map()) == {a, c}


def test_main_returns_1_after_a_failed_put(s3upload, bucket, cache, sessions, streams):
    a = cache.add(101, "ShowA-OP1.ogg", 11)
    b = cache.add(102, "ShowB-ED1.ogg", 13)
    keys = {a: key_of(1), b: key_of(2)}
    cache.write_work_map(keys)
    bucket.listing({})
    bucket.put_fails(keys[a], cache.body(a))
    bucket.put(keys[b], cache.body(b))
    bucket.listing({keys[b]: 13})
    fake = sessions(bucket.client)
    streams()

    assert exit_code(lambda: s3upload.main(main_args(cache))) == 1
    bucket.done()
    assert set(cache.served_map()) == {b}


def test_main_returns_0_when_nothing_fails(s3upload, bucket, cache, sessions, streams):
    a = cache.add(101, "ShowA-OP1.ogg", 11)
    cache.write_work_map({a: key_of(1)})
    bucket.listing({})
    bucket.put(key_of(1), cache.body(a))
    bucket.listing({key_of(1): 11})
    fake = sessions(bucket.client)
    streams()

    assert exit_code(lambda: s3upload.main(main_args(cache, "--profile", "songrush-upload"))) == 0
    bucket.done()
    assert len(fake.sessions) == 1
    assert fake.sessions[0][1].get("profile_name") == "songrush-upload"
    assert fake.clients == [(("s3",), {"region_name": REGION})]


def test_main_without_profile_uses_the_default_chain(s3upload, bucket, cache, sessions, streams):
    a = cache.add(101, "ShowA-OP1.ogg", 11)
    cache.write_work_map({a: key_of(1)})
    bucket.listing({key_of(1): 11})
    bucket.listing({key_of(1): 11})
    fake = sessions(bucket.client)
    streams()

    assert exit_code(lambda: s3upload.main(main_args(cache))) == 0
    assert len(fake.sessions) == 1
    args, kwargs = fake.sessions[0]
    assert args == ()
    assert kwargs.get("profile_name") is None


# Criterion 8: a corrupt work map stops the tool before anything happens.

@pytest.mark.parametrize("content", [b"{not json", b"[]", b'{"ShowA-OP1.ogg": 5}', b"\xff\xfe\x00"],
                         ids=["bad-json", "not-an-object", "key-not-a-string", "not-utf8"])
def test_corrupt_work_map_returns_1_and_touches_nothing(s3upload, cache, sessions, streams, content):
    cache.add(101, "ShowA-OP1.ogg", 11)
    (cache.dir / WORK_MAP).write_bytes(content)
    (cache.dir / SERVED_MAP).write_bytes(b'{"Old-OP1.ogg": {"key": "a/OLDKEY", "size": 3}}')
    before = cache.snapshot()
    fake = sessions(None)
    captured = streams()

    assert exit_code(lambda: s3upload.main(main_args(cache))) == 1
    assert fake.sessions == []  # no client created, so no S3 call
    assert fake.clients == []
    assert cache.snapshot() == before
    assert WORK_MAP in captured()


def test_run_raises_value_error_on_a_corrupt_work_map(s3upload, bucket, cache):
    cache.add(101, "ShowA-OP1.ogg", 11)
    (cache.dir / WORK_MAP).write_bytes(b"{not json")

    with pytest.raises(ValueError):
        run(s3upload, bucket, cache, no_new_key)
    bucket.done()  # nothing was queued, and nothing was called
    assert (cache.dir / WORK_MAP).read_bytes() == b"{not json"
    assert not (cache.dir / SERVED_MAP).exists()


def test_corrupt_served_map_is_rebuilt(s3upload, bucket, cache):
    # M6: the served map is output only; a corrupt one is simply replaced.
    a = cache.add(101, "ShowA-OP1.ogg", 11)
    cache.write_work_map({a: key_of(1)})
    (cache.dir / SERVED_MAP).write_bytes(b"{not json")
    bucket.listing({key_of(1): 11})
    bucket.listing({key_of(1): 11})

    result, _ = run(s3upload, bucket, cache, no_new_key)

    assert result == summary(skipped=1, served=1)
    assert cache.served_map() == {a: {"key": key_of(1), "size": 11}}


def test_missing_index_returns_1_with_a_message(s3upload, bucket, cache, sessions, streams):
    assert not (cache.dir / INDEX).exists()
    sessions(bucket.client)
    captured = streams()

    assert exit_code(lambda: s3upload.main(main_args(cache))) == 1
    bucket.done()  # no S3 call
    assert INDEX in captured()
    assert not (cache.dir / SERVED_MAP).exists()


def test_run_raises_on_a_missing_index(s3upload, bucket, cache):
    with pytest.raises(Exception):
        run(s3upload, bucket, cache, no_new_key)
    bucket.done()


# Criterion 9: quiet output.

def test_output_is_quiet(s3upload, bucket, cache, sessions, streams):
    names = [cache.add(400 + n, "Secret%02d-OP1.ogg" % n, 5 + n) for n in range(28)]
    gone = cache.add(450, "Hidden-ED9.ogg", 9, present=False)
    odd = cache.add(451, "Crooked-ED8.ogg", 9, local_size=8)
    every = sorted(names + [gone, odd])
    keys = {name: key_of(number) for number, name in enumerate(every, start=1)}
    cache.write_work_map(keys)
    bucket.listing({})
    for name in sorted(names):
        if name == names[3]:
            bucket.put_fails(keys[name], cache.body(name))
        else:
            bucket.put(keys[name], cache.body(name))
    bucket.listing({keys[name]: cache.size(name) for name in names if name != names[3]})
    sessions(bucket.client)
    captured = streams()

    assert exit_code(lambda: s3upload.main(main_args(cache))) == 1
    bucket.done()
    text = captured()
    assert text.strip(), "the tool prints progress and a summary"
    for name in every:
        assert name not in text
        assert name[:-len(".ogg")] not in text
    for key in keys.values():
        assert key not in text
        assert key[len(PREFIX):] not in text
    assert ACCESS_KEY not in text
    assert SECRET_KEY not in text.lower()


def test_run_log_is_quiet(s3upload, bucket, cache):
    a = cache.add(101, "ShowA-OP1.ogg", 11)
    b = cache.add(102, "ShowB-ED1.ogg", 13)
    keys = {a: key_of(1), b: key_of(2)}
    bucket.listing({})
    bucket.put(keys[a], cache.body(a))
    bucket.put_fails(keys[b], cache.body(b))
    bucket.listing({keys[a]: 11})

    _, lines = run(s3upload, bucket, cache, keys_from([1, 2]))

    text = "\n".join(lines)
    assert lines
    for value in (a, b, "ShowA", "ShowB", keys[a], keys[b], ACCESS_KEY):
        assert value not in text


# Criterion 10: strict command line.

def test_main_without_bucket_exits_2(s3upload, sessions, streams):
    fake = sessions(None)
    streams()

    assert exit_code(lambda: s3upload.main([])) == 2
    assert fake.sessions == []


def test_main_rejects_a_positional_argument(s3upload, cache, sessions, streams):
    cache.add(101, "ShowA-OP1.ogg", 11)
    before = cache.snapshot()
    fake = sessions(None)
    streams()

    assert exit_code(lambda: s3upload.main(main_args(cache, "stray"))) == 2
    assert fake.sessions == []
    assert cache.snapshot() == before


def test_main_rejects_the_cache_dir_spelling(s3upload, cache, sessions, streams):
    fake = sessions(None)
    streams()

    assert exit_code(lambda: s3upload.main(["--bucket", BUCKET, "--cache-dir", str(cache.dir)])) == 2
    assert fake.sessions == []

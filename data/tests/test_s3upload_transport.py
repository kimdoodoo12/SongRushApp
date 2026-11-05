"""Cycle 074 criterion 7, transport errors: a put_object that fails below HTTP.

botocore's transport errors (ReadTimeoutError, EndpointConnectionError and the
other HTTPClientError subclasses) carry no parsed response: their `response`
attribute is None, not a dict. The spec says any put_object exception counts
`failed` and the run continues, so these must too, and the log must name only
the error type, never the endpoint URL (which holds the object key).

The harness matches test_s3upload.py (a real boto3 client with fake
credentials behind a Stubber, fake bytes under tmp_path); a thin wrapper
raises the transport error for one key, since Stubber can only stub service
errors. The import of songrush_data.s3upload stays inside a fixture.
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
    for name in ("AWS_PROFILE", "AWS_DEFAULT_PROFILE", "AWS_ACCESS_KEY_ID", "AWS_SECRET_ACCESS_KEY",
                 "AWS_SESSION_TOKEN", "AWS_REGION", "AWS_DEFAULT_REGION", "AWS_ENDPOINT_URL",
                 "AWS_ENDPOINT_URL_S3"):
        monkeypatch.delenv(name, raising=False)
    monkeypatch.setenv("AWS_CONFIG_FILE", str(tmp_path / "no-aws-config"))
    monkeypatch.setenv("AWS_SHARED_CREDENTIALS_FILE", str(tmp_path / "no-aws-credentials"))
    monkeypatch.setenv("AWS_EC2_METADATA_DISABLED", "true")


def key_of(number):
    return PREFIX + "TESTKEY%015d" % number


def body_of(basename, size):
    seed = basename.encode("ascii")
    return (seed * (size // len(seed) + 1))[:size]


def no_new_key():
    raise AssertionError("new_key must not be called when every basename has a key")


class Cache:
    def __init__(self, root):
        self.dir = root / "cache"
        self.dir.mkdir()
        self.entries = {}

    def add(self, theme_id, basename, size):
        self.entries[basename] = (theme_id, size)
        (self.dir / basename).write_bytes(body_of(basename, size))
        index = {str(t): {"basename": b, "durationMs": 90000, "size": s} for b, (t, s) in self.entries.items()}
        (self.dir / INDEX).write_text(json.dumps(index), encoding="utf-8")
        return basename

    def body(self, basename):
        return (self.dir / basename).read_bytes()

    def write_work_map(self, mapping):
        (self.dir / WORK_MAP).write_text(json.dumps(mapping), encoding="utf-8")

    def served_map(self):
        return json.loads((self.dir / SERVED_MAP).read_text(encoding="utf-8"))


@pytest.fixture
def cache(tmp_path):
    return Cache(tmp_path)


class Bucket:
    def __init__(self):
        import boto3
        from botocore.stub import Stubber

        session = boto3.session.Session(aws_access_key_id=ACCESS_KEY, aws_secret_access_key=SECRET_KEY,
                                        region_name=REGION)
        self.client = session.client("s3")
        self.stubber = Stubber(self.client)
        self.stubber.activate()

    def listing(self, objects):
        response = {"IsTruncated": False, "KeyCount": len(objects), "Name": BUCKET, "Prefix": PREFIX}
        if objects:
            response["Contents"] = [{"Key": key, "Size": size} for key, size in objects.items()]
        self.stubber.add_response("list_objects_v2", response, {"Bucket": BUCKET, "Prefix": PREFIX})

    def put(self, key, body):
        self.stubber.add_response("put_object", {"ETag": '"etag"'},
                                  {"Bucket": BUCKET, "Key": key, "Body": body, "ContentType": "audio/ogg"})

    def done(self):
        self.stubber.assert_no_pending_responses()


@pytest.fixture
def bucket():
    stubbed = Bucket()
    yield stubbed
    stubbed.stubber.deactivate()


class FailingPut:
    """The stubbed client, except put_object raises a transport error for one key."""

    def __init__(self, client, failing_key, error):
        self._client = client
        self.failing_key = failing_key
        self.error = error
        self.failed_calls = 0

    def put_object(self, **kwargs):
        if kwargs.get("Key") == self.failing_key:
            self.failed_calls += 1
            raise self.error
        return self._client.put_object(**kwargs)

    def __getattr__(self, name):
        return getattr(self._client, name)


def read_timeout(key):
    from botocore.exceptions import ReadTimeoutError

    return ReadTimeoutError(endpoint_url="https://%s.s3.%s.amazonaws.com/%s" % (BUCKET, REGION, key))


def connection_closed(key):
    from botocore.exceptions import ConnectionClosedError

    return ConnectionClosedError(endpoint_url="https://%s.s3.%s.amazonaws.com/%s" % (BUCKET, REGION, key))


def endpoint_connection(key):
    from botocore.exceptions import EndpointConnectionError

    return EndpointConnectionError(endpoint_url="https://%s.s3.%s.amazonaws.com/%s" % (BUCKET, REGION, key))


# ReadTimeoutError and ConnectionClosedError are HTTPClientError subclasses with
# response = None. EndpointConnectionError is a ConnectionError subclass with no
# response attribute at all; it is kept as a regression case.
TRANSPORT_ERRORS = [(read_timeout, "ReadTimeoutError"), (connection_closed, "ConnectionClosedError"),
                    (endpoint_connection, "EndpointConnectionError")]
TRANSPORT_IDS = ["read-timeout", "connection-closed", "endpoint-connection"]


def three_files(cache):
    a = cache.add(101, "ShowA-OP1.ogg", 11)
    b = cache.add(102, "ShowB-ED1.ogg", 13)
    c = cache.add(103, "ShowC-OP2.ogg", 17)
    keys = {a: key_of(1), b: key_of(2), c: key_of(3)}
    cache.write_work_map(keys)
    return a, b, c, keys


def assert_quiet(text, names, keys, type_name):
    for name in names:
        assert name not in text
        assert name[:-len(".ogg")] not in text
    for key in keys:
        assert key not in text
        assert key[len(PREFIX):] not in text
    assert "amazonaws.com" not in text
    assert "https://" not in text
    assert ACCESS_KEY not in text
    assert SECRET_KEY not in text.lower()


@pytest.mark.parametrize("make_error,type_name", TRANSPORT_ERRORS, ids=TRANSPORT_IDS)
def test_transport_error_on_put_counts_failed_and_the_run_continues(s3upload, bucket, cache,
                                                                   make_error, type_name):
    a, b, c, keys = three_files(cache)
    error = make_error(keys[b])
    # The shape that broke the error handler: no response, or response None.
    assert getattr(error, "response", None) is None
    s3 = FailingPut(bucket.client, keys[b], error)
    bucket.listing({})
    bucket.put(keys[a], cache.body(a))
    bucket.put(keys[c], cache.body(c))
    bucket.listing({keys[a]: 11, keys[c]: 17})
    lines = []

    result = s3upload.run(s3, cache.dir, BUCKET, log=lines.append, new_key=no_new_key)

    assert s3.failed_calls == 1
    assert result == {"uploaded": 2, "skipped": 0, "missing": 0, "mismatched": 0, "failed": 1, "served": 2}
    bucket.done()
    assert set(cache.served_map()) == {a, c}
    text = "\n".join(lines)
    assert type_name in text
    assert_quiet(text, (a, b, c), keys.values(), type_name)


# main end to end, with boto3.Session patched (pattern of test_s3upload.py).

@pytest.fixture
def streams(monkeypatch):
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


@pytest.mark.parametrize("make_error,type_name", TRANSPORT_ERRORS, ids=TRANSPORT_IDS)
def test_main_returns_1_after_a_transport_error(s3upload, bucket, cache, streams, monkeypatch,
                                                make_error, type_name):
    import boto3

    a, b, c, keys = three_files(cache)
    s3 = FailingPut(bucket.client, keys[b], make_error(keys[b]))
    bucket.listing({})
    bucket.put(keys[a], cache.body(a))
    bucket.put(keys[c], cache.body(c))
    bucket.listing({keys[a]: 11, keys[c]: 17})

    class Session:
        def __init__(self, *args, **kwargs):
            pass

        def client(self, *args, **kwargs):
            return s3

    monkeypatch.setattr(boto3, "Session", Session)
    captured = streams()

    try:
        code = s3upload.main(["--bucket", BUCKET, "--cache", str(cache.dir)])
    except SystemExit as error:
        code = error.code
    assert code == 1
    bucket.done()
    assert set(cache.served_map()) == {a, c}
    text = captured()
    assert type_name in text
    assert_quiet(text, (a, b, c), keys.values(), type_name)

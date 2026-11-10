"""strip_har must also strip the HTTP/2 `:path` pseudo-header (cycle 106).

Over HTTP/2 (CloudFront) Chromium's HAR lists the pseudo-header `:path` in
request.headers, and its value carries the presigned query
(`/a/key?X-Amz-Credential=...&X-Amz-Signature=...`). strip_har must drop the
query from it, keep the path, and leave other headers untouched.

Run from the repo root: data/.venv/Scripts/python.exe -m pytest perf
Imported as plain `perflib` like test_run.py (pytest "prepend" import mode).
No test here touches the network.
"""

import json

import perflib


def _har(path_header_name):
    return {
        "log": {
            "entries": [
                {
                    "request": {
                        "method": "GET",
                        "url": "https://d1.cloudfront.net/a/key",
                        "headers": [
                            {
                                "name": path_header_name,
                                "value": "/a/key?X-Amz-Credential=x&X-Amz-Signature=y",
                            },
                            {"name": ":authority", "value": "d1.cloudfront.net"},
                            {"name": "accept", "value": "*/*"},
                        ],
                    },
                    "response": {"status": 200, "headers": []},
                }
            ]
        }
    }


def _headers(har):
    return har["log"]["entries"][0]["request"]["headers"]


def test_strip_har_strips_h2_path_pseudo_header():
    har = perflib.strip_har(_har(":path"))

    headers = _headers(har)
    assert headers[0] == {"name": ":path", "value": "/a/key"}
    assert headers[1] == {"name": ":authority", "value": "d1.cloudfront.net"}
    assert headers[2] == {"name": "accept", "value": "*/*"}
    assert perflib.contains_secret(json.dumps(har)) is False


def test_strip_har_strips_path_pseudo_header_case_insensitively():
    har = perflib.strip_har(_har(":PATH"))

    headers = _headers(har)
    assert headers[0] == {"name": ":PATH", "value": "/a/key"}
    assert headers[1] == {"name": ":authority", "value": "d1.cloudfront.net"}
    assert headers[2] == {"name": "accept", "value": "*/*"}
    assert perflib.contains_secret(json.dumps(har)) is False

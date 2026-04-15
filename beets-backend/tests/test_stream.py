"""Regression tests for B5 (GitHub #23) — stream transcode contract + Content-Type.

The pure helpers (``_wants_aac_transcode``, ``_should_transcode``,
``_pass_through_content_type``, ``_ffmpeg_unavailable_response``) carry the
contract we promised to the Android + frontend clients; these tests pin them.
Full end-to-end streaming requires a real library.db + media file and is covered
by the Android E2E suite.
"""
from __future__ import annotations

import pytest


@pytest.fixture(scope='module')
def upload_server(app):
    import sys
    return sys.modules['upload_server']


@pytest.mark.parametrize('ext,mime', [
    ('.flac', 'audio/flac'),
    ('.mp3', 'audio/mpeg'),
    ('.m4a', 'audio/mp4'),
    ('.wav', 'audio/x-wav'),
])
def test_pass_through_content_type_canonical(upload_server, ext, mime):
    assert upload_server._pass_through_content_type(f'/tmp/x{ext}') == mime


def test_pass_through_falls_back_for_unknown_ext(upload_server):
    # Unknown ext → mimetypes.guess_type() or octet-stream; should not raise.
    got = upload_server._pass_through_content_type('/tmp/x.xyz')
    assert got  # non-empty string
    assert got in ('application/octet-stream',) or '/' in got


@pytest.mark.parametrize('query,expected', [
    ({'transcode': 'aac'}, True),
    ({'transcode': 'AAC'}, True),
    ({'format': 'aac'}, True),
    ({'format': 'AAC'}, True),
    ({}, False),
    ({'transcode': 'flac'}, False),
    ({'format': 'mp3'}, False),
])
def test_wants_aac_transcode_parses_primary_and_legacy_alias(upload_server, query, expected):
    assert upload_server._wants_aac_transcode(query) is expected


@pytest.mark.parametrize('path,query,expected', [
    ('/lib/a.flac', {'transcode': 'aac'}, True),
    ('/lib/a.wav', {'transcode': 'aac'}, True),
    ('/lib/a.ogg', {'format': 'aac'}, True),
    ('/lib/a.mp3', {'transcode': 'aac'}, False),   # MP3 already device-compatible
    ('/lib/a.m4a', {'transcode': 'aac'}, False),   # Already AAC in MP4
    ('/lib/a.flac', {}, False),                    # No opt-in: pass through
])
def test_should_transcode_only_when_client_asks_and_source_is_transcodeable(
    upload_server, path, query, expected,
):
    assert upload_server._should_transcode(path, query) is expected


def test_ffmpeg_unavailable_response_shape(upload_server, app):
    """503 with the documented error code — clients switch to pass-through."""
    with app.app_context():
        resp, status = upload_server._ffmpeg_unavailable_response()
        assert status == 503
        body = resp.get_json()
        assert body == {'error': 'transcode unavailable', 'code': 'FFMPEG_MISSING'}


def test_stream_missing_track_returns_404(client):
    resp = client.get('/stream/999999')
    # Track isn't in library.db → 404 JSON rather than the 500 we used to 500 on.
    assert resp.status_code == 404
    assert resp.get_json() == {'error': 'Track not found'}


def test_stream_missing_track_even_with_transcode_param_returns_json(client):
    # Transcode request without ffmpeg still has to fail JSON, never HTML.
    resp = client.get('/stream/999999?transcode=aac')
    assert resp.headers['Content-Type'].startswith('application/json')


def test_transcodeable_ext_set_covers_lossless_formats(upload_server):
    # This is the spec — if these ever drop out, Android Auto's pipeline breaks.
    expected = {'.wav', '.flac', '.ogg', '.aiff', '.aif'}
    assert upload_server._TRANSCODEABLE_EXTS == expected

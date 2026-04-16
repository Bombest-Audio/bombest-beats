"""Regression tests for B4 (GitHub #22) — mediafile import + safe transcode temp files.

Before the fix, ``update_item`` called ``mediafile.MediaFile(...)`` without
importing mediafile at module scope (NameError at runtime) and the transcode
path used ``tempfile.mkstemp()`` with no prefix, so orphaned files in /tmp had
no deterministic cleanup path when the process was killed.
"""
from __future__ import annotations

import os
import time

import pytest


@pytest.fixture(scope='module')
def upload_server(app):
    import sys
    return sys.modules['upload_server']


def test_mediafile_is_imported_at_module_level(upload_server):
    """The top-level `import mediafile` must be present; without it, the
    update_item PUT /items/<id> handler NameErrors at runtime."""
    assert hasattr(upload_server, 'mediafile'), (
        'upload_server must import mediafile at module level (see issue #22)'
    )
    # Sanity: the symbol we actually call through must exist.
    assert hasattr(upload_server.mediafile, 'MediaFile')


def test_transcode_temp_prefix_namespaced(upload_server):
    """All transcode temp files must carry the namespaced prefix so the sweeper
    only touches our own files, not arbitrary /tmp content."""
    assert upload_server._TRANSCODE_TMP_PREFIX == 'bombest-transcode-'
    assert upload_server._TRANSCODE_TMP_PREFIX.endswith('-')  # hyphen avoids prefix-collision


def test_transcode_sweeper_removes_stale_files(upload_server, tmp_path, monkeypatch):
    """Stale files matching the prefix are unlinked; fresh files + non-matching files aren't."""
    monkeypatch.setattr('tempfile.gettempdir', lambda: str(tmp_path))

    stale = tmp_path / f'{upload_server._TRANSCODE_TMP_PREFIX}stale.wav'
    stale.write_bytes(b'x')
    old_ts = time.time() - upload_server._TRANSCODE_STALE_THRESHOLD_SEC - 60
    os.utime(stale, (old_ts, old_ts))

    fresh = tmp_path / f'{upload_server._TRANSCODE_TMP_PREFIX}fresh.wav'
    fresh.write_bytes(b'x')

    unrelated = tmp_path / 'someone-else.wav'
    unrelated.write_bytes(b'x')
    os.utime(unrelated, (old_ts, old_ts))

    # Run a single sweep iteration inline (not the infinite loop).
    now = time.time()
    for entry in os.listdir(str(tmp_path)):
        if not entry.startswith(upload_server._TRANSCODE_TMP_PREFIX):
            continue
        p = tmp_path / entry
        if now - os.path.getmtime(p) >= upload_server._TRANSCODE_STALE_THRESHOLD_SEC:
            os.unlink(p)

    assert not stale.exists(), 'stale transcode temp file should have been swept'
    assert fresh.exists(), 'fresh transcode temp file must survive the sweep'
    assert unrelated.exists(), 'non-namespaced file must not be touched'


def test_sweeper_thread_is_daemon(upload_server):
    assert upload_server._transcode_sweeper.daemon is True
    # Sanity: the thread was given a recognizable name for ops observability.
    assert 'transcode' in upload_server._transcode_sweeper.name

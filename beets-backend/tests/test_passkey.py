"""Regression tests for B7 (GitHub #25) — persist passkey challenges in users.db.

Before the fix, challenges lived in a process-local dict — they leaked across
multi-worker deploys (register on worker A, verify on worker B) and never
expired. Now they live in a `passkey_challenges` SQLite table with a 5-min TTL.
"""
from __future__ import annotations

import sqlite3
import time

import pytest


@pytest.fixture(scope='module')
def upload_server(app):
    import sys
    return sys.modules['upload_server']


def test_set_get_pop_roundtrip(upload_server):
    key = 'user:1'
    upload_server._passkey_challenge_set(key, b'\x01\x02\x03', 'beats.bom.best', ['https://beats.bom.best'])
    got = upload_server._passkey_challenge_get(key)
    assert got is not None
    assert got['challenge'] == b'\x01\x02\x03'
    assert got['rp_id'] == 'beats.bom.best'
    assert got['origins'] == ['https://beats.bom.best']
    # pop removes it
    upload_server._passkey_challenge_pop(key)
    assert upload_server._passkey_challenge_get(key) is None


def test_get_missing_returns_none(upload_server):
    assert upload_server._passkey_challenge_get('nonexistent:999') is None


def test_set_is_idempotent_replaces_existing(upload_server):
    """Re-registering kicks off a fresh options call — second set should overwrite."""
    key = 'user:7'
    upload_server._passkey_challenge_set(key, b'first', 'rp', ['o1'])
    upload_server._passkey_challenge_set(key, b'second', 'rp', ['o2'])
    got = upload_server._passkey_challenge_get(key)
    assert got['challenge'] == b'second'
    assert got['origins'] == ['o2']
    upload_server._passkey_challenge_pop(key)


def test_expired_challenges_are_not_returned(upload_server, users_db):
    """Rows older than _PASSKEY_CHALLENGE_TTL_SEC silently pop and return None."""
    key = 'user:stale'
    # Insert directly with a stale created_at so we don't sleep 5 real minutes.
    conn = sqlite3.connect(users_db)
    cur = conn.cursor()
    cur.execute("DELETE FROM passkey_challenges WHERE challenge_key = ?", (key,))
    stale_ts = time.time() - upload_server._PASSKEY_CHALLENGE_TTL_SEC - 10
    cur.execute(
        'INSERT INTO passkey_challenges (challenge_key, challenge, rp_id, origins, created_at) '
        'VALUES (?, ?, ?, ?, ?)',
        (key, b'expired', 'rp', '[]', stale_ts),
    )
    conn.commit()
    conn.close()

    assert upload_server._passkey_challenge_get(key) is None

    # Expired row should also have been swept from the table.
    conn = sqlite3.connect(users_db)
    cur = conn.cursor()
    cur.execute('SELECT COUNT(*) FROM passkey_challenges WHERE challenge_key = ?', (key,))
    (count,) = cur.fetchone()
    conn.close()
    assert count == 0


def test_passkey_register_options_requires_auth(client):
    """/auth/passkey/register/options is @jwt_required — unauthenticated → 401."""
    resp = client.post('/auth/passkey/register/options')
    assert resp.status_code == 401


def test_passkey_challenges_table_exists(users_db):
    conn = sqlite3.connect(users_db)
    cur = conn.cursor()
    cur.execute("SELECT name FROM sqlite_master WHERE type='table' AND name='passkey_challenges'")
    row = cur.fetchone()
    conn.close()
    assert row is not None, 'passkey_challenges table should be created by upload_server init'


def test_assetlinks_includes_credential_relation(client):
    """`/.well-known/assetlinks.json` must include get_login_creds for Android passkeys."""
    resp = client.get('/.well-known/assetlinks.json')
    assert resp.status_code == 200
    data = resp.get_json()
    assert isinstance(data, list) and len(data) > 0
    relations = data[0]["relation"]
    assert "delegate_permission/common.get_login_creds" in relations
    assert "delegate_permission/common.handle_all_urls" in relations

"""Shared pytest fixtures for backend regression tests.

Import-time constraints on upload_server.py:
- Reads ``config.yaml`` from cwd.
- Computes USERS_DB from ``$DATA_DIR`` via ``db_path.get_users_db_path``.
- Creates music/uploads/waveforms folders under cwd.
- Starts a daemon transcode-sweeper thread.

The session-scope ``app`` fixture chdir's into beets-backend and sets DATA_DIR to
an isolated tmp directory before importing upload_server, then reuses the single
module for all tests (the Flask ``app`` object is stateless across requests; per-
test isolation is provided by rebuilding ``users.db``).
"""
from __future__ import annotations

import importlib
import os
import sqlite3
import sys
from pathlib import Path

import bcrypt
import pytest

BACKEND_DIR = Path(__file__).resolve().parent.parent


@pytest.fixture(scope='session')
def data_dir(tmp_path_factory):
    """Isolated DATA_DIR for the whole test session; users.db lives here."""
    d = tmp_path_factory.mktemp('bombest_data')
    return d


@pytest.fixture(scope='session')
def app(data_dir):
    """Import upload_server with a clean DATA_DIR and hand back the Flask app.

    Runs once per session — subsequent tests rebuild users.db but reuse the
    loaded module to avoid the daemon-thread churn and module re-registration
    errors that happen on reimport.
    """
    os.environ['DATA_DIR'] = str(data_dir)
    # Keep upload_server's 500 handler terse; don't spill secrets into test logs.
    os.environ.setdefault('FLASK_ENV', 'testing')
    # No S3 creds — exercises the local-file + 'File not found' paths.
    for key in ('S3_BUCKET', 'AWS_ACCESS_KEY_ID', 'AWS_SECRET_ACCESS_KEY'):
        os.environ.pop(key, None)

    # config.yaml is read relative to cwd at import time.
    os.chdir(BACKEND_DIR)
    sys.path.insert(0, str(BACKEND_DIR))

    # Fresh users.db before upload_server is imported so its first query succeeds.
    _rebuild_users_db(data_dir / 'users.db')

    if 'upload_server' in sys.modules:
        # Shouldn't happen in a clean session, but guard against re-entrancy.
        del sys.modules['upload_server']
    upload_server = importlib.import_module('upload_server')
    upload_server.app.config['TESTING'] = True
    upload_server.app.config['PROPAGATE_EXCEPTIONS'] = True
    return upload_server.app


@pytest.fixture()
def client(app, data_dir):
    """Flask test client with users.db rebuilt before every test."""
    _rebuild_users_db(data_dir / 'users.db')
    # init_db.init_db() doesn't know about passkey_challenges/passkey_credentials
    # (those tables are owned by upload_server.init_passkey_table). Re-run it so
    # every test starts with a complete schema.
    import upload_server
    upload_server.init_passkey_table()
    return app.test_client()


def _rebuild_users_db(db_path: Path):
    """Drop and re-initialize users.db so every test starts clean.

    ``init_db.init_db()`` reads DATA_DIR itself, so we just delete the file and
    let it recreate the schema (plus the default admin user).
    """
    try:
        db_path.unlink()
    except FileNotFoundError:
        pass
    # Re-import so DB_PATH is recomputed against the current DATA_DIR.
    if 'init_db' in sys.modules:
        del sys.modules['init_db']
    sys.path.insert(0, str(BACKEND_DIR))
    import init_db  # noqa: WPS433
    init_db.init_db()


def _seed_user(db_path: Path, username: str, password: str, role: str = 'user') -> int:
    """Insert a user directly (skips the invite-code gate)."""
    conn = sqlite3.connect(db_path)
    try:
        hashed = bcrypt.hashpw(password.encode(), bcrypt.gensalt()).decode()
        cur = conn.cursor()
        cur.execute(
            'INSERT INTO users (username, password_hash, role) VALUES (?, ?, ?)',
            (username, hashed, role),
        )
        conn.commit()
        return cur.lastrowid
    finally:
        conn.close()


def _login_token(client, username: str, password: str) -> str:
    resp = client.post('/auth/login', json={'username': username, 'password': password})
    assert resp.status_code == 200, resp.get_json()
    return resp.get_json()['access_token']


@pytest.fixture()
def admin_token(client, data_dir):
    """Bearer token for the default admin seeded by init_db."""
    return _login_token(client, 'admin', 'admin_password')


@pytest.fixture()
def user_token(client, data_dir):
    """Bearer token for a freshly-seeded regular user."""
    _seed_user(data_dir / 'users.db', 'regular', 'pw-regular', role='user')
    return _login_token(client, 'regular', 'pw-regular')


@pytest.fixture()
def second_user_token(client, data_dir):
    """A second regular user — used by ownership-boundary tests."""
    _seed_user(data_dir / 'users.db', 'other', 'pw-other', role='user')
    return _login_token(client, 'other', 'pw-other')


@pytest.fixture()
def refresh_tokens(client):
    """Login response with both access and refresh tokens for the admin."""
    resp = client.post('/auth/login', json={'username': 'admin', 'password': 'admin_password'})
    assert resp.status_code == 200, resp.get_json()
    return resp.get_json()


def auth_headers(token: str) -> dict[str, str]:
    return {'Authorization': f'Bearer {token}'}


# Exported for tests that need to poke the DB or build tokens manually.
@pytest.fixture()
def seed_user(data_dir):
    def _seed(username: str, password: str, role: str = 'user') -> int:
        return _seed_user(data_dir / 'users.db', username, password, role)
    return _seed


@pytest.fixture()
def users_db(data_dir):
    return data_dir / 'users.db'

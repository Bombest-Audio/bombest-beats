"""Regression tests for B1 (GitHub #19) — loops API 500s before table rename.

Before the fix, `init_db.py` created a `loops` table while `upload_server.py`
queried `loop_points` with `user_id`/`label` columns — so GET and POST on
`/tracks/<id>/loops` both raised sqlite3.OperationalError and the route's bare
`try/except` bubbled up as a 500.
"""
from __future__ import annotations

import sqlite3


def test_get_loops_on_fresh_db_returns_empty_list(client, user_token):
    resp = client.get(
        '/tracks/1/loops',
        headers={'Authorization': f'Bearer {user_token}'},
    )
    assert resp.status_code == 200
    assert resp.get_json() == {'loops': []}


def test_create_and_retrieve_loop_roundtrip(client, user_token):
    create = client.post(
        '/tracks/42/loops',
        headers={'Authorization': f'Bearer {user_token}'},
        json={'start_time': 1.0, 'end_time': 4.5, 'label': 'intro'},
    )
    assert create.status_code == 201, create.get_json()
    loop_id = create.get_json()['id']
    assert isinstance(loop_id, int)

    listing = client.get(
        '/tracks/42/loops',
        headers={'Authorization': f'Bearer {user_token}'},
    )
    assert listing.status_code == 200
    loops = listing.get_json()['loops']
    assert len(loops) == 1
    assert loops[0]['start_time'] == 1.0
    assert loops[0]['end_time'] == 4.5
    assert loops[0]['label'] == 'intro'
    assert loops[0]['username'] == 'regular'


def test_loops_endpoint_requires_auth(client):
    """Regression: route is @jwt_required(); unauthenticated callers get 401."""
    resp = client.get('/tracks/1/loops')
    assert resp.status_code == 401


def test_schema_has_expected_columns(users_db):
    """Columns the server queries must exist on loop_points."""
    conn = sqlite3.connect(users_db)
    cur = conn.cursor()
    cur.execute("PRAGMA table_info(loop_points)")
    cols = {row[1] for row in cur.fetchall()}
    conn.close()
    # The old table was named `loops` with `name` — these are the columns the
    # server has always queried, and the migration must backfill them all.
    for col in ('track_id', 'user_id', 'start_time', 'end_time', 'label'):
        assert col in cols, f'loop_points missing expected column: {col}'


def test_legacy_loops_rows_migrate_to_loop_points(tmp_path, monkeypatch):
    """Simulate an install where the old `loops` table exists and verify the
    lazy migrator copies rows to loop_points + backfills user_id/label."""
    # Build a pre-migration schema in an isolated DATA_DIR.
    data_dir = tmp_path / 'legacy'
    data_dir.mkdir()
    db_path = data_dir / 'users.db'
    conn = sqlite3.connect(db_path)
    cur = conn.cursor()
    cur.executescript(
        """
        CREATE TABLE users (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            username TEXT UNIQUE NOT NULL,
            password_hash TEXT NOT NULL,
            role TEXT DEFAULT 'user'
        );
        INSERT INTO users (id, username, password_hash, role)
        VALUES (1, 'admin', 'stub', 'admin');
        CREATE TABLE loops (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            track_id INTEGER NOT NULL,
            start_time REAL NOT NULL,
            end_time REAL NOT NULL,
            name TEXT,
            created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
        );
        INSERT INTO loops (track_id, start_time, end_time, name)
            VALUES (7, 0.0, 2.0, 'legacy-loop');
        """
    )
    conn.commit()
    conn.close()

    # Point init_db at the legacy DB and run its migrator.
    monkeypatch.setenv('DATA_DIR', str(data_dir))
    import importlib
    import sys
    if 'init_db' in sys.modules:
        del sys.modules['init_db']
    init_db_mod = importlib.import_module('init_db')
    init_db_mod.init_db()

    conn = sqlite3.connect(db_path)
    cur = conn.cursor()
    cur.execute("SELECT track_id, start_time, end_time, label, user_id FROM loop_points")
    rows = cur.fetchall()
    cur.execute("SELECT name FROM sqlite_master WHERE type='table' AND name='loops'")
    loops_still_present = cur.fetchone()
    conn.close()

    assert loops_still_present is None, 'legacy loops table was not dropped'
    assert len(rows) == 1
    track_id, start, end, label, user_id = rows[0]
    assert (track_id, start, end) == (7, 0.0, 2.0)
    assert label == 'legacy-loop', 'name column should have been copied into label'
    assert user_id == 1, 'orphan row should be backfilled to the admin user'

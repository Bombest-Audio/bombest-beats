"""Regression tests for B3 (GitHub #21) — mutating endpoints missing auth guards.

Before the fix, PUT /track/<id>, PUT /tracks/batch, PUT /tracks/reorder, and
DELETE /tracks/batch all accepted unauthenticated requests. Now they sit behind
``@admin_required()`` which rejects with 401 (no token) or 403 (non-admin).
"""
from __future__ import annotations


ADMIN_MUTATION_ROUTES = [
    ('PUT', '/track/1', {'title': 'x'}),
    ('PUT', '/tracks/batch', {'track_ids': [1], 'fields': {'album': 'x'}}),
    ('PUT', '/tracks/reorder', {'playlist_id': 1, 'track_ids': [1]}),
    ('DELETE', '/tracks/batch', {'track_ids': [1]}),
    ('PUT', '/items/1', {'title': 'x'}),
]


def _send(client, method: str, url: str, headers: dict, json=None):
    fn = getattr(client, method.lower())
    return fn(url, headers=headers, json=json)


def test_admin_routes_reject_unauthenticated(client):
    for method, url, body in ADMIN_MUTATION_ROUTES:
        resp = _send(client, method, url, headers={}, json=body)
        assert resp.status_code == 401, f'{method} {url} should be 401, got {resp.status_code}'


def test_admin_routes_reject_regular_users(client, user_token):
    headers = {'Authorization': f'Bearer {user_token}'}
    for method, url, body in ADMIN_MUTATION_ROUTES:
        resp = _send(client, method, url, headers=headers, json=body)
        assert resp.status_code == 403, f'{method} {url} should be 403 for user, got {resp.status_code}'
        assert resp.get_json() == {'error': 'Admins only!'}


def test_metrics_play_accepts_optional_auth(client):
    """/metrics/play is @jwt_required(optional=True) — anonymous and authed both OK."""
    anon = client.post('/metrics/play', json={'track_id': 1})
    # The handler may 400 on missing fields, but must NOT 401 on missing token.
    assert anon.status_code != 401


def test_metrics_batch_requires_auth(client):
    resp = client.post('/metrics/batch', json={'events': []})
    assert resp.status_code == 401


def test_login_returns_both_tokens(client):
    """Shape of the login response after C6 — carries refresh_token + TTL."""
    resp = client.post(
        '/auth/login',
        json={'username': 'admin', 'password': 'admin_password'},
    )
    assert resp.status_code == 200
    body = resp.get_json()
    assert 'access_token' in body
    assert 'refresh_token' in body
    assert body['expires_in'] == 30 * 24 * 60 * 60
    assert body['user']['role'] == 'admin'
    assert body['user']['username'] == 'admin'


def test_login_rejects_bad_password(client):
    resp = client.post(
        '/auth/login',
        json={'username': 'admin', 'password': 'wrong'},
    )
    assert resp.status_code == 401

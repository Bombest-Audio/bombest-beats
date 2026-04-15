"""Regression tests for B6 (GitHub #24) — JWT refresh + 30d access TTL + legacy grace.

freezegun drives the clock so we can cross the 30-day access-token boundary
without waiting real time, and across the LEGACY_TOKEN_GRACE_UNTIL cutoff.
"""
from __future__ import annotations

from datetime import datetime, timedelta, timezone

import jwt as pyjwt
import pytest
from freezegun import freeze_time


def _decode_no_verify(token: str) -> dict:
    return pyjwt.decode(token, options={'verify_signature': False})


def test_login_issues_access_and_refresh_tokens(client):
    resp = client.post(
        '/auth/login',
        json={'username': 'admin', 'password': 'admin_password'},
    )
    body = resp.get_json()
    access = _decode_no_verify(body['access_token'])
    refresh = _decode_no_verify(body['refresh_token'])
    assert access['type'] == 'access'
    assert refresh['type'] == 'refresh'
    # Same subject; distinct lifetimes.
    assert access['sub'] == refresh['sub']
    assert (refresh['exp'] - refresh['iat']) > (access['exp'] - access['iat'])


def test_refresh_endpoint_returns_new_access_token(client, refresh_tokens):
    resp = client.post(
        '/auth/refresh',
        headers={'Authorization': f'Bearer {refresh_tokens["refresh_token"]}'},
    )
    assert resp.status_code == 200
    body = resp.get_json()
    assert 'access_token' in body
    assert body['expires_in'] == 30 * 24 * 60 * 60
    # The new access token should be usable against a protected route.
    me = client.get('/auth/me', headers={'Authorization': f'Bearer {body["access_token"]}'})
    assert me.status_code == 200


def test_refresh_endpoint_rejects_access_token(client, refresh_tokens):
    """An access token passed to /auth/refresh must fail — wrong type."""
    resp = client.post(
        '/auth/refresh',
        headers={'Authorization': f'Bearer {refresh_tokens["access_token"]}'},
    )
    assert resp.status_code == 422


def test_expired_access_token_rejected_but_refresh_still_valid(app, client, refresh_tokens):
    """After 31 days, access token is rejected but refresh still works (180d TTL)."""
    with freeze_time(datetime.now(tz=timezone.utc) + timedelta(days=31)):
        bad = client.get(
            '/auth/me',
            headers={'Authorization': f'Bearer {refresh_tokens["access_token"]}'},
        )
        assert bad.status_code == 401

        renewed = client.post(
            '/auth/refresh',
            headers={'Authorization': f'Bearer {refresh_tokens["refresh_token"]}'},
        )
        assert renewed.status_code == 200


def test_legacy_no_exp_token_accepted_within_grace_window(app, client):
    """Field-issued tokens without `exp` must still authenticate until the cutoff."""
    secret = app.config['JWT_SECRET_KEY']
    # Mint a legacy token with no exp — mirrors the pre-C6 production shape.
    legacy = pyjwt.encode(
        {'sub': '1', 'type': 'access', 'role': 'admin', 'username': 'admin'},
        secret,
        algorithm='HS256',
    )
    # One day before the cutoff: should still be honored with a warning log.
    import upload_server
    with freeze_time(upload_server.LEGACY_TOKEN_GRACE_UNTIL - timedelta(days=1)):
        resp = client.get('/auth/me', headers={'Authorization': f'Bearer {legacy}'})
        assert resp.status_code == 200, resp.get_json()


def test_legacy_no_exp_token_rejected_after_grace_window(app, client):
    import upload_server
    secret = app.config['JWT_SECRET_KEY']
    legacy = pyjwt.encode(
        {'sub': '1', 'type': 'access', 'role': 'admin', 'username': 'admin'},
        secret,
        algorithm='HS256',
    )
    with freeze_time(upload_server.LEGACY_TOKEN_GRACE_UNTIL + timedelta(days=1)):
        resp = client.get('/auth/me', headers={'Authorization': f'Bearer {legacy}'})
        # flask-jwt-extended surfaces a failed token_verification_loader as 400
        # (UserClaimsVerificationError). Either a 400 or 401 counts as "rejected"
        # — what matters is the token is NOT honored past the grace window.
        assert resp.status_code in (400, 401), resp.get_json()

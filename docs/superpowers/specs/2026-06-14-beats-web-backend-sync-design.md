# Design: Sync `bom.best/beats` with the backend (playlists + background refresh)

**Date:** 2026-06-14
**Status:** Approved (design)
**Repo:** `bombest-beats` (web frontend lives in the embedded `music-frontend/` repo, branch `master`)

## Problem

Playlists created in the Android app do not appear on the web app at `bom.best/beats`,
and the web app never refreshes data after initial load, so changes made elsewhere are
invisible until a manual page reload.

The user wants:
1. Android-created playlists to show up on the web app.
2. Cross-device changes to appear "live" on the web app without a manual reload —
   specifically at the **refetch-on-focus + short interval** level (not WebSocket push).

## Root cause (part 1)

`bom.best/beats` is built and deployed from `music-frontend/` via
`scripts/deploy-frontend.sh` (`FRONTEND_DIR="$REPO_ROOT/music-frontend"`, synced to
`s3://bombest-beats-web/beats/`, CloudFront `E1RBYOEP5K0UI3`).

- The **deployed** build's `index.html` `last-modified` is **2026-03-20**.
- Commit **`c0dd8d2`** ("fix(playlists): send JWT for playlist mutations and optional
  auth on GET"), dated **2026-04-04**, is the **tip of `master`** and was **never deployed**.

Before `c0dd8d2`, `BeetsService.getPlaylists()` called `fetch(${UPLOAD_URL}/playlists)`
with **no `Authorization` header**. The backend's `GET /playlists`
(`beets-backend/upload_server.py:2733`) is user-scoped — without the JWT it cannot
identify the user and returns only public/none. Android-created playlists are private to
the account, so they never appear on web.

The fix already exists in `c0dd8d2`; it is simply unshipped. The web app already targets
the same backend (`https://beats.bom.best`, same as Android `NetworkModule`) and already
supports passkey login (`src/views/auth/login.tsx`), confirmed against the live UI.

## Design

### Part 1 — Deploy the existing fix (no code change)

Rebuild and redeploy `music-frontend` at `master` (`c0dd8d2`) using the canonical
`scripts/deploy-frontend.sh`. This ships the JWT-on-`getPlaylists` fix, so a web user
logged into the same account sees their Android playlists (on load).

- Production deploy → **must be confirmed with the user before pushing**.
- Standard build env: `PUBLIC_URL=/beats`, `REACT_APP_API_BASE=https://beats.bom.best`,
  `GENERATE_SOURCEMAP=false`, `NODE_OPTIONS=--openssl-legacy-provider`.
- Followed by CloudFront invalidation (handled by the script).

### Part 2 — Background sync hook (new code)

The web app fetches data once and never re-fetches. Add a single small hook that, **only
while authenticated**, re-runs the existing fetch paths so cross-device changes appear
without a manual reload.

**New file:** `src/hooks/useBackgroundSync.ts`

Responsibilities:
- Expose nothing to callers beyond being mounted; it wires side effects.
- Define `syncNow()` which dispatches the **existing** Redux updates:
  - playlists: `BeetsService.getPlaylists()` → `SET_PLAYLISTS`
  - current view's tracks: the existing `refreshLibrary()` logic — `getPlaylistTracks(currentPlaylist.id)`
    when a playlist is selected, else `getLibrary()` → `ADD_SONGS`.
- Triggers `syncNow()` on:
  - `window` `focus` and `document` `visibilitychange` (when `visibilityState === 'visible'`)
  - an interval, `SYNC_INTERVAL_MS` (single tunable const, default **25000**)
- Gating & safety:
  - No-op when `!isAuthenticated` (from `useAuth()`); listeners/interval are torn down on
    logout and on unmount.
  - In-flight guard (a `useRef` boolean) prevents overlapping syncs.
  - Read-only and idempotent; does not touch the `<audio>` element, so playback is
    unaffected (same dispatch the app already performs on playlist switch).
  - Errors are caught and logged with `String(e)` (matches existing fail-silently pattern);
    a failed sync never logs the user out or clears the UI.

**Mount point:** `src/app/index.tsx` (always-mounted root). `refreshLibrary()` already
lives here; the hook reuses that logic rather than duplicating it (extract a shared
`syncNow`/`refreshLibrary` so playlist-switch and background-sync share one implementation).

### Data flow

```
focus / visibilitychange / interval (>=25s)
   |- (isAuthenticated only) syncNow()
        |- getPlaylists() ----> dispatch SET_PLAYLISTS ----> state.playlists.items ----> menu UI
        |- refreshLibrary() --> getLibrary()/getPlaylistTracks() --> dispatch ADD_SONGS --> track list
```

### Components & boundaries

| Unit | Purpose | Depends on |
|------|---------|-----------|
| `useBackgroundSync` (new) | Trigger periodic/focus refresh while authed | `useAuth`, redux dispatch, `BeetsService`, shared `syncNow` |
| `syncNow`/`refreshLibrary` (refactor in `app/index.tsx`) | Single refetch implementation for playlists + tracks | `BeetsService`, redux actions |
| `auth.tsx` (unchanged) | Source of `isAuthenticated`/`token` | localStorage |
| `deploy-frontend.sh` (unchanged) | Build + S3 + CloudFront | AWS creds |

## Out of scope (YAGNI)

- WebSocket / SSE push-based sync.
- Syncing now-playing transport position across devices.
- Offline edit / conflict resolution.
- Any backend (`beets-backend`) change.

## Verification

1. Deploy Part 1; on web, log into the same account used on Android; confirm Android
   playlists are listed (after the menu opens / page load).
2. With the web tab open, create a playlist on Android; confirm it appears on web within
   ~25s, or immediately when the web tab regains focus.
3. Confirm logged-out web does not poll (no `/playlists` requests while unauthenticated).
4. Confirm playback is uninterrupted while a background sync runs.

## Risks / notes

- Interval polling replaces the whole list each cycle; at this app's scale that is fine.
  If flicker appears, switch the reducers to merge-by-id (deferred until observed).
- The same-account requirement is a user action, not code: if web is logged in as a
  different user, Android playlists still won't show. Called out in verification step 1.

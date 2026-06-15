# Beats Web Backend Sync Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `bom.best/beats` show playlists created on Android and keep cross-device changes refreshed without a manual page reload.

**Architecture:** The fix that makes `getPlaylists()` send the JWT already exists at the tip of `music-frontend` `master` (`c0dd8d2`) but was never deployed. We add one small `useBackgroundSync` hook that re-runs the existing playlist/library fetches on tab focus and on a ~25s interval while authenticated, then build + deploy `music-frontend` once (which ships both the unshipped playlist fix and the new hook) to S3/CloudFront.

**Tech Stack:** React 17 + TypeScript (CRA / `react-scripts`), Redux, Jest + @testing-library/react v11. Deploy via `scripts/deploy-frontend.sh` (S3 `bombest-beats-web`, CloudFront `E1RBYOEP5K0UI3`).

**IMPORTANT — two repos:**
- Code changes (Tasks 1–3) happen in the embedded **`music-frontend/`** repo (branch `master`). `cd music-frontend` before any code/git command in those tasks.
- The deploy (Task 4) runs from the **`bombest-beats`** repo root via its script.

---

## File Structure

| File | Repo | Responsibility |
|------|------|----------------|
| `src/hooks/useBackgroundSync.ts` | music-frontend | NEW. Wires focus + interval triggers to a caller-provided `syncNow`, gated on `enabled`, with an in-flight guard and cleanup. Exports `SYNC_INTERVAL_MS`. |
| `src/hooks/useBackgroundSync.test.tsx` | music-frontend | NEW. Unit tests for the hook's trigger/gating/cleanup behavior via a wrapper component + fake timers. |
| `src/hooks/index.ts` | music-frontend | MODIFY. Re-export `useBackgroundSync`. |
| `src/app/index.tsx` | music-frontend | MODIFY. Add `SET_PLAYLISTS` import, wrap `refreshLibrary` in `useCallback`, add a `syncNow` callback, mount `useBackgroundSync(isAuthenticated, syncNow)`. |
| `scripts/deploy-frontend.sh` | bombest-beats | UNCHANGED. Used as-is for Task 4. |

---

## Task 1: `useBackgroundSync` hook (TDD)

**Files:**
- Create: `music-frontend/src/hooks/useBackgroundSync.ts`
- Test: `music-frontend/src/hooks/useBackgroundSync.test.tsx`

- [ ] **Step 1: Write the failing test**

Create `music-frontend/src/hooks/useBackgroundSync.test.tsx`:

```tsx
import { render, act } from '@testing-library/react';
import { useBackgroundSync, SYNC_INTERVAL_MS } from './useBackgroundSync';

function Harness({ enabled, syncNow, intervalMs }: {
  enabled: boolean;
  syncNow: () => Promise<void>;
  intervalMs: number;
}) {
  useBackgroundSync(enabled, syncNow, intervalMs);
  return null;
}

describe('useBackgroundSync', () => {
  beforeEach(() => jest.useFakeTimers());
  afterEach(() => {
    jest.runOnlyPendingTimers();
    jest.useRealTimers();
  });

  it('exposes a sane default interval', () => {
    expect(SYNC_INTERVAL_MS).toBeGreaterThanOrEqual(10000);
  });

  it('does not sync when disabled', () => {
    const syncNow = jest.fn().mockResolvedValue(undefined);
    render(<Harness enabled={false} syncNow={syncNow} intervalMs={1000} />);
    act(() => { jest.advanceTimersByTime(5000); });
    act(() => { window.dispatchEvent(new Event('focus')); });
    expect(syncNow).not.toHaveBeenCalled();
  });

  it('syncs on the interval when enabled', () => {
    const syncNow = jest.fn().mockResolvedValue(undefined);
    render(<Harness enabled={true} syncNow={syncNow} intervalMs={1000} />);
    act(() => { jest.advanceTimersByTime(1000); });
    expect(syncNow).toHaveBeenCalledTimes(1);
    act(() => { jest.advanceTimersByTime(1000); });
    expect(syncNow).toHaveBeenCalledTimes(2);
  });

  it('syncs when the window regains focus', () => {
    const syncNow = jest.fn().mockResolvedValue(undefined);
    render(<Harness enabled={true} syncNow={syncNow} intervalMs={100000} />);
    act(() => { window.dispatchEvent(new Event('focus')); });
    expect(syncNow).toHaveBeenCalledTimes(1);
  });

  it('stops syncing after unmount', () => {
    const syncNow = jest.fn().mockResolvedValue(undefined);
    const { unmount } = render(
      <Harness enabled={true} syncNow={syncNow} intervalMs={1000} />,
    );
    unmount();
    act(() => { jest.advanceTimersByTime(5000); });
    act(() => { window.dispatchEvent(new Event('focus')); });
    expect(syncNow).not.toHaveBeenCalled();
  });
});
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd music-frontend && CI=true npx react-scripts test src/hooks/useBackgroundSync.test.tsx --watchAll=false`
Expected: FAIL — `Cannot find module './useBackgroundSync'`.

- [ ] **Step 3: Write the minimal implementation**

Create `music-frontend/src/hooks/useBackgroundSync.ts`:

```ts
import { useEffect, useRef } from 'react';

/** How often (ms) the web app refetches shared state while authenticated. */
export const SYNC_INTERVAL_MS = 25000;

/**
 * While `enabled`, re-runs `syncNow` on window focus, on tab becoming visible,
 * and on a fixed interval. Read-only/idempotent by contract — `syncNow` should
 * only refetch + dispatch, never mutate. Overlapping runs are skipped, and all
 * listeners/timers are torn down on disable or unmount.
 */
export function useBackgroundSync(
  enabled: boolean,
  syncNow: () => Promise<void>,
  intervalMs: number = SYNC_INTERVAL_MS,
): void {
  const inFlight = useRef(false);
  const syncRef = useRef(syncNow);
  syncRef.current = syncNow;

  useEffect(() => {
    if (!enabled) return;

    const run = () => {
      if (inFlight.current) return;
      inFlight.current = true;
      Promise.resolve(syncRef.current())
        .catch((e) => console.error('Background sync failed:', String(e)))
        .finally(() => {
          inFlight.current = false;
        });
    };

    const onFocus = () => run();
    const onVisibility = () => {
      if (document.visibilityState === 'visible') run();
    };

    window.addEventListener('focus', onFocus);
    document.addEventListener('visibilitychange', onVisibility);
    const id = window.setInterval(run, intervalMs);

    return () => {
      window.removeEventListener('focus', onFocus);
      document.removeEventListener('visibilitychange', onVisibility);
      window.clearInterval(id);
    };
  }, [enabled, intervalMs]);
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd music-frontend && CI=true npx react-scripts test src/hooks/useBackgroundSync.test.tsx --watchAll=false`
Expected: PASS — all 5 tests green.

- [ ] **Step 5: Commit**

```bash
cd music-frontend
git add src/hooks/useBackgroundSync.ts src/hooks/useBackgroundSync.test.tsx
git commit -m "feat(sync): add useBackgroundSync hook for focus + interval refresh"
```

---

## Task 2: Export the hook from the barrel

**Files:**
- Modify: `music-frontend/src/hooks/index.ts`

- [ ] **Step 1: Inspect the current barrel**

Run: `cd music-frontend && cat src/hooks/index.ts`
Expected: a list of `export ... from './useX'` lines (e.g. `useResize`, `useDuration`, `usePrevious`, `useScroll`).

- [ ] **Step 2: Add the export**

Append this line to `music-frontend/src/hooks/index.ts`, matching the existing export style in that file (use `export *` if the file uses `export *`, otherwise a named re-export):

```ts
export { useBackgroundSync, SYNC_INTERVAL_MS } from './useBackgroundSync';
```

- [ ] **Step 3: Typecheck**

Run: `cd music-frontend && npx tsc --noEmit`
Expected: no errors.

- [ ] **Step 4: Commit**

```bash
cd music-frontend
git add src/hooks/index.ts
git commit -m "chore(sync): export useBackgroundSync from hooks barrel"
```

---

## Task 3: Wire the hook into the app

**Files:**
- Modify: `music-frontend/src/app/index.tsx`

Context (verified line numbers, may drift a few lines):
- L7–15: imports from `'../redux'` (currently includes `ADD_SONGS`, not `SET_PLAYLISTS`).
- L6: `import { useResize } from '../hooks';`
- L31: `const { isAuthenticated, isLoading } = useAuth();`
- L70: `const dispatch = useDispatch();`
- L76: `const { currentPlaylist } = useSelector((state: any) => state.playlists);`
- ~L367–382: `const refreshLibrary = () => { ... }` followed by a `useEffect(() => { refreshLibrary(); }, [currentPlaylist])`.

- [ ] **Step 1: Add `SET_PLAYLISTS` to the redux import**

In `music-frontend/src/app/index.tsx`, change the `'../redux'` import block from:

```ts
import {
  ADD_SONGS,
  PLAY_SONG,
  PAUSE_SONG,
  RESUME_SONG,
  DELETE_SONG,
  SET_VIEW,
} from '../redux';
```

to:

```ts
import {
  ADD_SONGS,
  PLAY_SONG,
  PAUSE_SONG,
  RESUME_SONG,
  DELETE_SONG,
  SET_VIEW,
  SET_PLAYLISTS,
} from '../redux';
```

- [ ] **Step 2: Import the hook**

Change `import { useResize } from '../hooks';` to:

```ts
import { useResize, useBackgroundSync } from '../hooks';
```

- [ ] **Step 3: Wrap `refreshLibrary` in `useCallback` so it can be reused**

Find the existing block (around L367):

```ts
  const refreshLibrary = () => {
    if (currentPlaylist) {
      BeetsService.getPlaylistTracks(currentPlaylist.id).then(items => {
        dispatch(ADD_SONGS(items));
      });
    } else {
      BeetsService.getLibrary()
        .then(items => {
          dispatch(ADD_SONGS(items));
        })
        .catch(err => {
          console.error('Library load failed:', err);
          dispatch(ADD_SONGS([]));
        });
    }
  };
```

Replace it with (wrap in `useCallback`; `useCallback` is already imported on L1):

```ts
  const refreshLibrary = useCallback(() => {
    if (currentPlaylist) {
      BeetsService.getPlaylistTracks(currentPlaylist.id).then(items => {
        dispatch(ADD_SONGS(items));
      });
    } else {
      BeetsService.getLibrary()
        .then(items => {
          dispatch(ADD_SONGS(items));
        })
        .catch(err => {
          console.error('Library load failed:', err);
          dispatch(ADD_SONGS([]));
        });
    }
  }, [currentPlaylist, dispatch]);
```

- [ ] **Step 4: Add `syncNow` and mount the hook**

Immediately after the `refreshLibrary` definition from Step 3, add:

```ts
  // Refetch shared state (playlists + current view's tracks) for cross-device sync.
  const syncNow = useCallback(async () => {
    const playlists = await BeetsService.getPlaylists();
    dispatch(SET_PLAYLISTS(playlists));
    refreshLibrary();
  }, [dispatch, refreshLibrary]);

  useBackgroundSync(isAuthenticated, syncNow);
```

- [ ] **Step 5: Typecheck and build**

Run: `cd music-frontend && npx tsc --noEmit`
Expected: no errors.

Run: `cd music-frontend && NODE_OPTIONS=--openssl-legacy-provider CI=true npx react-scripts build`
Expected: "Compiled successfully." (warnings about existing eslint-disable lines are OK; no errors).

- [ ] **Step 6: Run the full test suite**

Run: `cd music-frontend && CI=true npx react-scripts test --watchAll=false`
Expected: PASS (the `useBackgroundSync` suite; no other tests exist).

- [ ] **Step 7: Commit**

```bash
cd music-frontend
git add src/app/index.tsx
git commit -m "feat(sync): refresh playlists + library on focus and interval when authed"
```

---

## Task 4: Deploy to `bom.best/beats` (production — REQUIRES USER CONFIRMATION)

This single deploy ships both the previously-unshipped playlist JWT fix (`c0dd8d2`) and the new background-sync hook. This is an outward-facing production deploy — **do not run it without explicit user go-ahead in the moment.**

**Files:**
- Use: `bombest-beats/scripts/deploy-frontend.sh` (unchanged)

- [ ] **Step 1: Confirm with the user**

Ask: "Ready to deploy `music-frontend` (playlist fix + sync hook) to `bom.best/beats` (S3 + CloudFront invalidation)?" Wait for an explicit yes.

- [ ] **Step 2: Push the `music-frontend` changes to its remote**

The deploy builds from local `music-frontend/`, but push so the deploy is reproducible. Confirm whether to PR or push to `master` per the user's preference; default to a feature branch + PR:

```bash
cd music-frontend
git push -u origin HEAD
```

- [ ] **Step 3: Run the canonical deploy script from the bombest-beats repo root**

Run (from `bombest-beats/`):

```bash
CLOUDFRONT_DIST_ID=E1RBYOEP5K0UI3 ./scripts/deploy-frontend.sh
```

Expected: build "Compiled successfully.", `aws s3 sync` output, CloudFront invalidation created. (The script sets `PUBLIC_URL=/beats`, `REACT_APP_API_BASE=https://beats.bom.best`, `GENERATE_SOURCEMAP=false`, `NODE_OPTIONS=--openssl-legacy-provider`.)

- [ ] **Step 4: Verify the deployed bundle is fresh**

Run: `curl -sI https://bom.best/beats/ | grep -i last-modified`
Expected: today's date (2026-06-14), not 2026-03-20.

---

## Manual Verification (after Task 4)

These map to the spec's verification section and require two devices / accounts:

- [ ] On web, log in to the **same account** used on Android (password or passkey). Open the menu; confirm playlists created on Android are listed. (If they don't appear, confirm you're on the same account — same-account is a precondition, not a code path.)
- [ ] Keep the web tab open. Create a playlist on Android. Confirm it appears on web within ~25s, or immediately when the web tab regains focus.
- [ ] Log out on web. In devtools Network, confirm no `/playlists` requests fire on an interval while logged out.
- [ ] Start playback on web, then trigger a background sync (switch tabs and back). Confirm audio is uninterrupted.

---

## Self-Review notes

- **Spec coverage:** Part 1 (deploy unshipped fix) → Task 4. Part 2 (focus + interval hook, authed-only, in-flight guard, reuse `refreshLibrary`, no backend change) → Tasks 1–3. Verification section → Manual Verification. All covered.
- **Type consistency:** `useBackgroundSync(enabled: boolean, syncNow: () => Promise<void>, intervalMs?: number)` is defined in Task 1 and called identically in Task 3 (`useBackgroundSync(isAuthenticated, syncNow)`). `syncNow` returns `Promise<void>` in both. `SET_PLAYLISTS(playlists: any[])` matches its definition in `src/redux/actions/index.ts`.
- **No placeholders:** every code step shows full code; every run step shows the command and expected output.

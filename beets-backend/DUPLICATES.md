# Finding and Removing Duplicate Tracks

When the same song appears as two tracks (e.g. "slow sippin (añeja)" and a wrong entry like "aneja" with "00 slow sippin" as filename), fix it at the source so S3 and the app show a single track.

## Quick fix: Dashboard "Remove duplicates"

Admins can use the web app:

1. Open **Settings** → **Dashboard**
2. Click **Remove duplicates**

This finds tracks with the same normalized title (case-insensitive, underscores treated as spaces), keeps the lowest ID per group, removes the rest from the library and playlists, and refreshes the app.

## Prevention: Upload duplicate check

Folder and file uploads now normalize track names before checking. "w_mcnichols" and "w mcnichols" are treated as the same, so new duplicates are skipped during import.

## Manual CLI (for edge cases)

1. **Find duplicates**  
   From `beets-backend/`:
   ```bash
   python find_and_remove_duplicate_tracks.py --list
   ```
   This finds pairs with the same album and similar/related titles (e.g. one title substring of another, or "00 X" vs "X (y)").

2. **Remove the wrong track**    
   Keep the correct track (e.g. "slow sippin (añeja)"). Remove the other by ID:
   ```bash
   python find_and_remove_duplicate_tracks.py --remove <ID> [--delete-file]
   ```
   Use `--delete-file` to remove the file from disk so it is no longer synced to S3.

3. **Sync to S3**  
   Run from the repo root:
   ```bash
   ./sync-to-s3.sh
   ```
   The duplicate will disappear from the app once S3 is updated.

No app code change is required for the duplicate itself; correcting data in beets and S3 is sufficient.

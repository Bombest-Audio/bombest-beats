# Finding and Removing Duplicate Tracks

When the same song appears as two tracks (e.g. "slow sippin (añeja)" and a wrong entry like "aneja" with "00 slow sippin" as filename), fix it at the source so S3 and the app show a single track.

## Checklist

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

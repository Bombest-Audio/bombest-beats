#!/usr/bin/env python3
"""
Find and remove duplicate/split tracks from the beets library.

Use case: A track like "slow sippin (añeja)" was imported as two items—e.g. one
with title "aneja" and filename "00 slow sippin", and one correct. This script
finds likely duplicates (same album, similar or related titles) and can remove
the wrong one from the DB and optionally delete its file from disk.

Usage:
  # From beets-backend directory (or set LIBRARY_DB):
  python find_and_remove_duplicate_tracks.py --list          # List possible duplicates
  python find_and_remove_duplicate_tracks.py --remove ID    # Remove track ID from DB (and optionally disk)

After removing duplicates, run sync-to-s3.sh so S3 no longer serves the removed file.
"""
import argparse
import os
import sqlite3
import sys

# Default: same directory layout as upload_server (music/library.db relative to CWD)
LIBRARY_DB = os.environ.get('LIBRARY_DB', os.path.join(os.path.dirname(__file__), 'music', 'library.db'))


def list_tracks(conn):
    """Return all tracks with id, title, artist, album, path."""
    cursor = conn.cursor()
    cursor.execute("""
        SELECT id, title, artist, album, path
        FROM items
        ORDER BY album, title
    """)
    return cursor.fetchall()


def find_possible_duplicates(conn):
    """
    Find pairs/groups of tracks that might be duplicates:
    - Same album
    - Titles that look related (e.g. one title is a substring of another, or "00 X" vs "X (y)")
    """
    rows = list_tracks(conn)
    # Decode path if bytes
    tracks = []
    for r in rows:
        tid, title, artist, album, path = r
        if isinstance(path, bytes):
            path = path.decode('utf-8', errors='ignore')
        if isinstance(title, bytes):
            title = title.decode('utf-8', errors='ignore')
        if isinstance(album, bytes):
            album = album.decode('utf-8', errors='ignore') if album else ''
        tracks.append({'id': tid, 'title': title or '', 'artist': artist, 'album': album or '', 'path': path})

    # Group by album
    by_album = {}
    for t in tracks:
        key = (t['album'] or '').strip()
        by_album.setdefault(key, []).append(t)

    candidates = []
    for album, album_tracks in by_album.items():
        if len(album_tracks) < 2:
            continue
        for i, a in enumerate(album_tracks):
            for b in album_tracks[i + 1:]:
                if _looks_like_duplicate(a['title'], b['title']):
                    candidates.append((a, b))
    return candidates


def _looks_like_duplicate(title_a, title_b):
    """Heuristic: one title is substring of other, or one is '00 X' and other is 'X (...)' etc."""
    a = (title_a or '').strip().lower()
    b = (title_b or '').strip().lower()
    if a == b:
        return True
    # One contains the other (e.g. "slow sippin" in "slow sippin (añeja)" and "aneja" with "00 slow sippin" in path)
    if a in b or b in a:
        return True
    # Strip leading digits and compare
    import re
    a_clean = re.sub(r'^\d+\s+', '', a)
    b_clean = re.sub(r'^\d+\s+', '', b)
    if a_clean in b or b_clean in a or a_clean in b_clean or b_clean in a_clean:
        return True
    return False


def remove_track(conn, track_id, delete_file=False):
    """Remove track from DB. If delete_file=True, remove the file from disk too."""
    cursor = conn.cursor()
    cursor.execute("SELECT path FROM items WHERE id = ?", (track_id,))
    row = cursor.fetchone()
    if not row:
        return False, "Track not found"
    path = row[0]
    if isinstance(path, bytes):
        path = path.decode('utf-8', errors='ignore')

    cursor.execute("DELETE FROM items WHERE id = ?", (track_id,))
    conn.commit()

    if delete_file and path and os.path.exists(path):
        try:
            os.remove(path)
            return True, f"Deleted from DB and removed file: {path}"
        except OSError as e:
            return True, f"Deleted from DB; failed to remove file: {e}"
    return True, "Deleted from DB (file left on disk)"


def main():
    parser = argparse.ArgumentParser(description="Find/remove duplicate tracks in beets library")
    parser.add_argument('--list', action='store_true', help='List possible duplicate pairs')
    parser.add_argument('--remove', type=int, metavar='ID', help='Remove track with this ID from DB')
    parser.add_argument('--delete-file', action='store_true', help='With --remove, also delete the file from disk')
    parser.add_argument('--db', default=LIBRARY_DB, help='Path to library.db')
    args = parser.parse_args()

    if not os.path.exists(args.db):
        print(f"Library DB not found: {args.db}", file=sys.stderr)
        sys.exit(1)

    conn = sqlite3.connect(args.db)

    if args.list:
        candidates = find_possible_duplicates(conn)
        if not candidates:
            print("No obvious duplicate pairs found.")
            conn.close()
            return
        print(f"Found {len(candidates)} possible duplicate pair(s):\n")
        for a, b in candidates:
            print(f"  A: id={a['id']}  title={a['title']!r}  album={a['album']!r}")
            print(f"  B: id={b['id']}  title={b['title']!r}  album={b['album']!r}")
            print(f"  To remove A: python find_and_remove_duplicate_tracks.py --remove {a['id']} [--delete-file]")
            print(f"  To remove B: python find_and_remove_duplicate_tracks.py --remove {b['id']} [--delete-file]")
            print()
        print("After removing, run sync-to-s3.sh so S3 stays in sync.")
        conn.close()
        return

    if args.remove is not None:
        ok, msg = remove_track(conn, args.remove, delete_file=args.delete_file)
        conn.close()
        print(msg)
        if not ok:
            sys.exit(1)
        print("Run sync-to-s3.sh to update S3.")
        return

    parser.print_help()


if __name__ == '__main__':
    main()

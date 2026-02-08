#!/usr/bin/env python3
"""
Fix all tracks that don't have proper album associations
"""
import sqlite3

LIBRARY_DB = "/Users/thomasphillips/bombest-audio/bombest-beats/beets-backend/music/library.db"

def fix_all_albums():
    conn = sqlite3.connect(LIBRARY_DB)
    cursor = conn.cursor()
    
    # Get all tracks without album_id
    cursor.execute("""
        SELECT id, title, artist, album, albumartist, path, length, track
        FROM items 
        WHERE album_id IS NULL
        ORDER BY album, track
    """)
    tracks = cursor.fetchall()
    
    if not tracks:
        print("✅ All tracks already have album associations!")
        return
    
    print(f"Found {len(tracks)} tracks without album associations")
    
    # Group tracks by album name
    albums = {}
    for track in tracks:
        track_id, title, artist, album, albumartist, path, length, track_num = track
        album_key = album or "Non-Album"
        if album_key not in albums:
            albums[album_key] = []
        albums[album_key].append(track)
    
    print(f"Grouped into {len(albums)} albums\n")
    
    fixed_count = 0
    for album_name, album_tracks in albums.items():
        print(f"Processing: {album_name} ({len(album_tracks)} tracks)")
        
        # Get albumartist from first track
        albumartist = album_tracks[0][4] or album_tracks[0][2] or "thomas phillips"
        
        # Check if album entry exists
        cursor.execute("SELECT id FROM albums WHERE album = ?", (album_name,))
        album_entry = cursor.fetchone()
        
        if album_entry:
            album_id = album_entry[0]
            print(f"  ✅ Using existing album ID: {album_id}")
        else:
            # Create album entry
            cursor.execute("""
                INSERT INTO albums (album, albumartist, artpath, added)
                VALUES (?, ?, NULL, datetime('now'))
            """, (album_name, albumartist))
            album_id = cursor.lastrowid
            print(f"  ✅ Created new album ID: {album_id}")
        
        # Update all tracks in this album
        track_ids = [t[0] for t in album_tracks]
        for track_id in track_ids:
            cursor.execute("UPDATE items SET album_id = ? WHERE id = ?", (album_id, track_id))
        
        fixed_count += len(track_ids)
        print(f"  ✅ Associated {len(track_ids)} tracks\n")
    
    conn.commit()
    conn.close()
    
    print(f"{'='*50}")
    print(f"✨ Fixed {fixed_count} tracks across {len(albums)} albums!")
    print(f"\nAll tracks should now appear properly grouped in your apps.")
    print(f"You may need to refresh/restart your music apps to see the changes.")

if __name__ == '__main__':
    fix_all_albums()

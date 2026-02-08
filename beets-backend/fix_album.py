#!/usr/bin/env python3
"""
Fix the album grouping by creating an album entry and associating tracks
"""
import sqlite3
import os

LIBRARY_DB = "/Users/thomasphillips/bombest-audio/bombest-beats/beets-backend/music/library.db"

def fix_album():
    conn = sqlite3.connect(LIBRARY_DB)
    cursor = conn.cursor()
    
    # Get the tracks for "time off 3"
    cursor.execute("""
        SELECT id, title, artist, album, path, length, track
        FROM items 
        WHERE album = 'time off 3'
        ORDER BY track
    """)
    tracks = cursor.fetchall()
    
    if not tracks:
        print("❌ No tracks found for 'time off 3'")
        return
    
    print(f"Found {len(tracks)} tracks for 'time off 3'")
    
    # Check if album entry already exists
    cursor.execute("SELECT id FROM albums WHERE album = 'time off 3'")
    album_entry = cursor.fetchone()
    
    if album_entry:
        album_id = album_entry[0]
        print(f"✅ Album entry already exists with ID: {album_id}")
    else:
        # Create album entry
        cursor.execute("""
            INSERT INTO albums (album, albumartist, artpath, added)
            VALUES ('time off 3', 'thomas phillips', NULL, datetime('now'))
        """)
        album_id = cursor.lastrowid
        print(f"✅ Created new album entry with ID: {album_id}")
    
    # Update all tracks to have this album_id
    track_ids = [t[0] for t in tracks]
    placeholders = ','.join('?' * len(track_ids))
    cursor.execute(f"""
        UPDATE items 
        SET album_id = ?
        WHERE id IN ({placeholders})
    """, [album_id] + track_ids)
    
    print(f"✅ Associated {len(track_ids)} tracks with album ID {album_id}")
    
    conn.commit()
    conn.close()
    
    print("\n✨ Album grouping fixed!")
    print("The tracks should now appear as a proper album in your apps.")

if __name__ == '__main__':
    fix_album()

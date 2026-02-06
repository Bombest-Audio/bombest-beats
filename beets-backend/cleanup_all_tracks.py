#!/usr/bin/env python3
"""
Clean up all tracks: convert to MP3, add metadata, organize into proper folders
"""
import os
import sqlite3
import subprocess
import shutil

CONFIG_FILE = "/Users/thomasphillips/bombest-audio/bombest-beats/beets-backend/config.yaml"
LIBRARY_DB = "/Users/thomasphillips/bombest-audio/bombest-beats/beets-backend/music/library.db"
MUSIC_DIR = "/Users/thomasphillips/bombest-audio/bombest-beats/beets-backend/music"
TEMP_DIR = "/Users/thomasphillips/bombest-audio/bombest-beats/beets-backend/temp_cleanup"

def convert_to_mp3_with_metadata(source_path, dest_path, title, artist, album, track_num=None):
    """Convert audio file to MP3 with metadata"""
    cmd = [
        'ffmpeg', '-y', '-i', source_path,
        '-codec:a', 'libmp3lame',
        '-qscale:a', '0',  # Highest quality VBR
        '-metadata', f'title={title}',
        '-metadata', f'artist={artist}',
    ]
    
    if album:
        cmd.extend(['-metadata', f'album={album}'])
    if track_num:
        cmd.extend(['-metadata', f'track={track_num}'])
    
    cmd.append(dest_path)
    
    result = subprocess.run(cmd, capture_output=True, text=True)
    return result.returncode == 0

def main():
    print("🧹 Cleaning up all tracks and organizing library...")
    print("=" * 60)
    
    # Connect to beets database
    conn = sqlite3.connect(LIBRARY_DB)
    cursor = conn.cursor()
    
    # Get all tracks
    cursor.execute("""
        SELECT id, title, artist, album, albumartist, path, track
        FROM items
        ORDER BY album, track
    """)
    tracks = cursor.fetchall()
    conn.close()
    
    print(f"Found {len(tracks)} tracks in library\n")
    
    # Create temp directory
    os.makedirs(TEMP_DIR, exist_ok=True)
    
    # Group tracks by album
    albums = {}
    for track_id, title, artist, album, albumartist, path, track_num in tracks:
        # Decode path if bytes
        if isinstance(path, bytes):
            path = path.decode('utf-8', errors='ignore')
        
        # Skip if file doesn't exist
        if not os.path.exists(path):
            print(f"  ⚠️  File not found: {title} ({path})")
            continue
        
        album_key = album or "Singles"
        if album_key not in albums:
            albums[album_key] = []
        
        albums[album_key].append({
            'id': track_id,
            'title': title or f"Track {track_id}",
            'artist': artist or albumartist or "thomas phillips",
            'album': album or "",
            'albumartist': albumartist or artist or "thomas phillips",
            'path': path,
            'track_num': track_num
        })
    
    print(f"📁 Organized into {len(albums)} albums\n")
    
    # Process each album
    processed_count = 0
    for album_name, album_tracks in albums.items():
        print(f"\n{'='*60}")
        print(f"📀 Processing album: {album_name} ({len(album_tracks)} tracks)")
        print(f"{'='*60}")
        
        # Create album directory in temp
        safe_album_name = "".join(c if c.isalnum() or c in (' ', '-', '_') else '_' for c in album_name)
        album_temp_dir = os.path.join(TEMP_DIR, safe_album_name)
        os.makedirs(album_temp_dir, exist_ok=True)
        
        # Convert each track
        for track in album_tracks:
            title = track['title']
            artist = track['artist']
            album = track['album']
            source_path = track['path']
            track_num = track['track_num']
            
            # Create safe filename
            if track_num:
                safe_title = f"{track_num:02d} {title}"
            else:
                safe_title = title
            safe_title = "".join(c if c.isalnum() or c in (' ', '-', '_') else '_' for c in safe_title)
            mp3_filename = f"{safe_title}.mp3"
            dest_path = os.path.join(album_temp_dir, mp3_filename)
            
            print(f"  🎵 Converting: {title}")
            
            if convert_to_mp3_with_metadata(
                source_path, dest_path, title, artist, album, track_num
            ):
                processed_count += 1
                print(f"     ✅ Converted to MP3 with metadata")
            else:
                print(f"     ❌ Failed to convert")
    
    print(f"\n{'='*60}")
    print(f"✨ Conversion complete!")
    print(f"📊 Processed {processed_count} tracks")
    print(f"\n📦 Removing old tracks from beets...")
    
    # Remove all tracks from beets (we'll re-import)
    subprocess.run(
        ['beet', '-c', CONFIG_FILE, 'remove', '-f'],
        input='y\n',
        capture_output=True,
        text=True
    )
    
    print(f"✅ Old tracks removed")
    print(f"\n📥 Re-importing organized tracks...")
    
    # Import each album directory
    for album_dir in os.listdir(TEMP_DIR):
        album_path = os.path.join(TEMP_DIR, album_dir)
        if not os.path.isdir(album_path):
            continue
        
        print(f"  Importing: {album_dir}")
        
        result = subprocess.run(
            [
                'beet', '-c', CONFIG_FILE,
                'import',
                '-q',           # Quiet
                '--noautotag',  # Don't auto-tag
                '-A',           # Album mode
                album_path
            ],
            capture_output=True,
            text=True,
            input='y\n'
        )
        
        if result.returncode == 0:
            print(f"    ✅ Imported")
        else:
            print(f"    ⚠️  Warnings: {result.stderr[:100]}")
    
    print(f"\n🧹 Cleaning up temp directory...")
    shutil.rmtree(TEMP_DIR, ignore_errors=True)
    
    print(f"\n{'='*60}")
    print(f"✨ Library cleanup complete!")
    print(f"\nNext steps:")
    print(f"  1. Sync to S3:")
    print(f"     cd /Users/thomasphillips/bombest-audio/bombest-beats")
    print(f"     MUSIC_DIR=beets-backend/music ./sync-to-s3.sh")
    print(f"  2. Refresh your Android app")

if __name__ == '__main__':
    main()

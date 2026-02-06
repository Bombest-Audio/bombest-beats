#!/usr/bin/env python3
"""
Upload an album to the beets library with proper metadata
"""
import os
import sys
import subprocess
import shutil
from mutagen.wave import WAVE
from mutagen.id3 import ID3, TIT2, TPE1, TALB, TRCK

# Album configuration
ALBUM_NAME = "time off 3"
ARTIST_NAME = "thomas phillips"
ALBUM_TRACKS = [
    ("01 while it counts.wav", "while it counts", 1),
    ("02 my kinda crazy.wav", "my kinda crazy", 2),
    ("03 away.wav", "away", 3),
    ("04 take u down.wav", "take u down", 4),
    ("05 underthespelll.wav", "underthespelll", 5),
    ("06 ghosting.wav", "ghosting", 6),
    ("07 what we imagined.wav", "what we imagined", 7),
    ("08 any other day.wav", "any other day", 8),
]

SOURCE_DIR = "/Users/thomasphillips/Library/Mobile Documents/com~apple~CloudDocs/BEATS/time off 3/masters"
TEMP_DIR = "/Users/thomasphillips/bombest-audio/bombest-beats/beets-backend/uploads"
CONFIG_FILE = "/Users/thomasphillips/bombest-audio/bombest-beats/beets-backend/config.yaml"

def convert_to_mp3_with_metadata(wav_path, mp3_path, title, artist, album, track_num):
    """Convert WAV to MP3 and embed metadata using ffmpeg"""
    print(f"Converting and adding metadata: {title}")
    
    cmd = [
        'ffmpeg', '-y', '-i', wav_path,
        '-codec:a', 'libmp3lame',
        '-qscale:a', '0',  # Highest quality VBR
        '-metadata', f'title={title}',
        '-metadata', f'artist={artist}',
        '-metadata', f'album={album}',
        '-metadata', f'track={track_num}',
        mp3_path
    ]
    
    result = subprocess.run(cmd, capture_output=True, text=True)
    if result.returncode != 0:
        print(f"Error converting {wav_path}: {result.stderr}")
        return False
    return True

def import_track(mp3_path, title):
    """Import track into beets library"""
    print(f"Importing into beets: {title}")
    
    cmd = [
        'beet', '-c', CONFIG_FILE,
        'import', '-q',  # Quiet mode
        '--noautotag',   # Don't auto-tag from MusicBrainz
        '-s',            # Singleton mode (single track)
        mp3_path
    ]
    
    result = subprocess.run(cmd, capture_output=True, text=True)
    if result.returncode != 0:
        print(f"Warning during import: {result.stderr}")
    return True

def main():
    print(f"📀 Uploading album: {ALBUM_NAME}")
    print(f"🎵 Artist: {ARTIST_NAME}")
    print(f"🎶 Tracks: {len(ALBUM_TRACKS)}\n")
    
    # Ensure temp directory exists
    os.makedirs(TEMP_DIR, exist_ok=True)
    
    success_count = 0
    
    for filename, title, track_num in ALBUM_TRACKS:
        wav_path = os.path.join(SOURCE_DIR, filename)
        mp3_filename = f"{track_num:02d} {title}.mp3"
        mp3_path = os.path.join(TEMP_DIR, mp3_filename)
        
        if not os.path.exists(wav_path):
            print(f"❌ File not found: {wav_path}")
            continue
        
        print(f"\n[{track_num}/{len(ALBUM_TRACKS)}] Processing: {title}")
        
        # Convert WAV to MP3 with metadata
        if not convert_to_mp3_with_metadata(
            wav_path, mp3_path, title, ARTIST_NAME, ALBUM_NAME, track_num
        ):
            print(f"❌ Failed to convert: {title}")
            continue
        
        # Import into beets
        if import_track(mp3_path, title):
            success_count += 1
            print(f"✅ Successfully imported: {title}")
        else:
            print(f"❌ Failed to import: {title}")
        
        # Clean up temp MP3
        try:
            os.remove(mp3_path)
        except:
            pass
    
    print(f"\n{'='*50}")
    print(f"✨ Upload complete!")
    print(f"📊 Successfully imported {success_count}/{len(ALBUM_TRACKS)} tracks")
    print(f"\nℹ️  To sync to S3, run:")
    print(f"  cd /Users/thomasphillips/bombest-audio/bombest-beats")
    print(f"  MUSIC_DIR=beets-backend/music ./sync-to-s3.sh")

if __name__ == '__main__':
    main()

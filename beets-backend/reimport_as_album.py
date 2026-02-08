#!/usr/bin/env python3
"""
Re-import "time off 3" tracks as a proper album with correct folder structure
"""
import os
import subprocess
import shutil

ALBUM_NAME = "time off 3"
ARTIST_NAME = "thomas phillips"
SOURCE_DIR = "/Users/thomasphillips/Library/Mobile Documents/com~apple~CloudDocs/BEATS/time off 3/masters"
TEMP_ALBUM_DIR = "/Users/thomasphillips/bombest-audio/bombest-beats/beets-backend/uploads/time_off_3_album"
CONFIG_FILE = "/Users/thomasphillips/bombest-audio/bombest-beats/beets-backend/config.yaml"

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

def convert_to_mp3_with_metadata(wav_path, mp3_path, title, artist, album, track_num):
    """Convert WAV to MP3 and embed metadata using ffmpeg"""
    cmd = [
        'ffmpeg', '-y', '-i', wav_path,
        '-codec:a', 'libmp3lame',
        '-qscale:a', '0',
        '-metadata', f'title={title}',
        '-metadata', f'artist={artist}',
        '-metadata', f'album={album}',
        '-metadata', f'track={track_num}',
        mp3_path
    ]
    
    result = subprocess.run(cmd, capture_output=True, text=True)
    return result.returncode == 0

def main():
    print(f"🗑️  Removing old singleton imports...")
    
    # Remove old tracks from beets
    result = subprocess.run(
        ['beet', '-c', CONFIG_FILE, 'remove', '-f', 'album:time off 3'],
        capture_output=True,
        text=True
    )
    print(f"✅ Removed old imports")
    
    # Create temp album directory
    os.makedirs(TEMP_ALBUM_DIR, exist_ok=True)
    print(f"\n📁 Created temp album directory: {TEMP_ALBUM_DIR}")
    
    print(f"\n🎵 Converting {len(ALBUM_TRACKS)} tracks to MP3...")
    
    # Convert all tracks to MP3 in the temp directory
    for filename, title, track_num in ALBUM_TRACKS:
        wav_path = os.path.join(SOURCE_DIR, filename)
        mp3_filename = f"{track_num:02d} {title}.mp3"
        mp3_path = os.path.join(TEMP_ALBUM_DIR, mp3_filename)
        
        if not os.path.exists(wav_path):
            print(f"  ⚠️  Skipping {filename} (not found)")
            continue
        
        print(f"  Converting: {title}")
        if not convert_to_mp3_with_metadata(
            wav_path, mp3_path, title, ARTIST_NAME, ALBUM_NAME, track_num
        ):
            print(f"  ❌ Failed to convert: {title}")
            continue
    
    print(f"\n📦 Importing as complete album (not singletons)...")
    
    # Import the entire directory as an album
    result = subprocess.run(
        [
            'beet', '-c', CONFIG_FILE,
            'import',
            '-q',           # Quiet
            '--noautotag',  # Don't auto-tag
            '-A',           # Album mode (not singleton)
            TEMP_ALBUM_DIR
        ],
        capture_output=True,
        text=True,
        input='y\n'  # Auto-confirm prompts
    )
    
    if result.returncode == 0:
        print(f"✅ Album imported successfully!")
    else:
        print(f"⚠️  Import completed with warnings:")
        if result.stderr:
            print(result.stderr)
    
    # Clean up temp directory
    print(f"\n🧹 Cleaning up temp directory...")
    shutil.rmtree(TEMP_ALBUM_DIR, ignore_errors=True)
    
    print(f"\n{'='*50}")
    print(f"✨ Re-import complete!")
    print(f"\nNext step: Sync to S3")
    print(f"  cd /Users/thomasphillips/bombest-audio/bombest-beats")
    print(f"  MUSIC_DIR=beets-backend/music ./sync-to-s3.sh")

if __name__ == '__main__':
    main()

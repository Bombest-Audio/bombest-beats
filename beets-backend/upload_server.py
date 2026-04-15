import base64
import errno
import logging
import os
import mimetypes
import re
import json
import sys
import uuid
import subprocess
import sqlite3
import tempfile
import shutil
import zipfile
import traceback
import wave
import struct
import time
import threading
from flask import Flask, request, jsonify, send_file, Response
from flask_cors import CORS
from werkzeug.utils import secure_filename

# Module-level logger (INFO default, stderr). Route handlers and helpers should
# use `logger` — not `print()` — so logs route through the configured handlers
# and can be filtered/tagged in deployment.
logging.basicConfig(
    level=os.environ.get('LOG_LEVEL', 'INFO'),
    format='%(asctime)s %(levelname)s %(name)s: %(message)s',
    stream=sys.stderr,
)
logger = logging.getLogger(__name__)

from flask_jwt_extended import JWTManager, create_access_token, jwt_required, get_jwt_identity, get_jwt
import bcrypt
import smtplib
import yaml
import boto3
from botocore.config import Config as BotocoreConfig
from botocore.exceptions import ClientError
from flask import redirect
from email.mime.text import MIMEText
from email.mime.multipart import MIMEMultipart

from functools import wraps
from urllib.parse import urlparse

app = Flask(__name__)
# Load config for JWT
with open('config.yaml', 'r') as f:
    config = yaml.safe_load(f)

# JWT Configuration
app.config['JWT_SECRET_KEY'] = config.get('jwt_secret', 'dev-secret-key')
app.config['JWT_ACCESS_TOKEN_EXPIRES'] = False # Non-expiring for simplicty in MVP
jwt = JWTManager(app)
# CORS: allow frontend origins (bom.best, CloudFront, beats-app host the app; beats.bom.best is API)
_cors_origins = [
    'https://bom.best', 'https://www.bom.best',
    'https://beats.bom.best', 'https://beats-app.bom.best',
    'https://d37qdccady5d3d.cloudfront.net',  # CloudFront distribution URL
]
# Only allow localhost origins in development
if os.environ.get('FLASK_ENV') == 'development' or os.environ.get('FLASK_DEBUG') == '1':
    _cors_origins += ['http://localhost:3000', 'http://localhost:8338', 'http://127.0.0.1:3000']
CORS(app, origins=_cors_origins, supports_credentials=True)

# --- S3 Configuration ---
S3_BUCKET = os.environ.get('S3_BUCKET')
S3_REGION = os.environ.get('S3_REGION', 'auto')
S3_ENDPOINT = os.environ.get('S3_ENDPOINT') # For Cloudflare R2 / MinIO
AWS_ACCESS_KEY = os.environ.get('AWS_ACCESS_KEY_ID')
AWS_SECRET_KEY = os.environ.get('AWS_SECRET_ACCESS_KEY')

s3_client = None
if S3_BUCKET and AWS_ACCESS_KEY and AWS_SECRET_KEY:
    try:
        s3_client = boto3.client(
            's3',
            region_name=S3_REGION,
            endpoint_url=S3_ENDPOINT,
            aws_access_key_id=AWS_ACCESS_KEY,
            aws_secret_access_key=AWS_SECRET_KEY,
            config=BotocoreConfig(signature_version='s3v4'),
        )
        logger.info("S3 client initialized for bucket: %s", S3_BUCKET)
    except Exception as e:
        logger.error("Failed to init S3: %s", e)


# --- Helpers ---
def admin_required():
    def wrapper(fn):
        @wraps(fn)
        @jwt_required()
        def decorator(*args, **kwargs):
            current_user_id = get_jwt_identity()
            conn = sqlite3.connect(USERS_DB)
            cursor = conn.cursor()
            cursor.execute("SELECT role FROM users WHERE id = ?", (current_user_id,))
            result = cursor.fetchone()
            conn.close()
            
            if not result or result[0] != 'admin':
                 return jsonify({'error': 'Admins only!'}), 403
            return fn(*args, **kwargs)
        return decorator
    return wrapper


def _jwt_role():
    try:
        return (get_jwt() or {}).get('role')
    except Exception:
        return None


def ensure_playlist_schema(conn):
    """Ensure playlists.user_id exists; backfill public catalog rows (user_id NULL) as is_public."""
    cursor = conn.cursor()
    cursor.execute("PRAGMA table_info(playlists)")
    columns = [col[1] for col in cursor.fetchall()]
    if 'user_id' not in columns:
        cursor.execute("ALTER TABLE playlists ADD COLUMN user_id INTEGER")
    if 'is_public' not in columns:
        cursor.execute("ALTER TABLE playlists ADD COLUMN is_public INTEGER DEFAULT 0")
    conn.commit()
    cursor.execute("UPDATE playlists SET is_public = 1 WHERE user_id IS NULL AND IFNULL(is_system, 0) = 0")
    cursor.execute("UPDATE playlists SET is_public = 1 WHERE IFNULL(is_system, 0) = 1")
    conn.commit()


def get_playlist_row_dict(cursor, playlist_id):
    cursor.execute(
        "SELECT id, name, user_id, is_public, is_system FROM playlists WHERE id = ?",
        (playlist_id,),
    )
    row = cursor.fetchone()
    if not row:
        return None
    return {
        'id': row[0],
        'name': row[1],
        'user_id': row[2],
        'is_public': int(row[3] or 0),
        'is_system': bool(row[4]) if row[4] is not None else False,
    }


def playlist_visible_to_user(pl, jwt_uid, jwt_role):
    """Who may list/open a playlist (GET list, GET tracks, GET art). Admin sees all."""
    if jwt_role == 'admin':
        return True
    if pl['is_system'] or pl['is_public']:
        return True
    if jwt_uid is None:
        return False
    if pl['user_id'] is not None and int(pl['user_id']) == int(jwt_uid):
        return True
    return False


def playlist_editable_by_user(pl, jwt_uid, jwt_role):
    """Who may mutate playlist rows and membership. Catalog (user_id NULL) is admin-only."""
    if jwt_role == 'admin':
        return True
    if jwt_uid is None:
        return False
    if pl['user_id'] is not None and int(pl['user_id']) == int(jwt_uid):
        return True
    return False


# Persist playlists: set DATA_DIR to a path that is volume-mounted (e.g. /app/data in Docker).
# users.db lives in DATA_DIR; library and music stay under cwd/music so image content is used.
from db_path import get_users_db_path

USERS_DB = get_users_db_path()

UPLOAD_FOLDER = os.path.join(os.getcwd(), 'uploads')
MUSIC_FOLDER = os.path.join(os.getcwd(), 'music')
WAVEFORM_FOLDER = os.path.join(os.getcwd(), 'waveforms')
LIBRARY_DB = os.path.join(MUSIC_FOLDER, 'library.db')
PLAYLIST_ART_FOLDER = os.path.join(MUSIC_FOLDER, 'playlist_art')

os.makedirs(UPLOAD_FOLDER, exist_ok=True)
os.makedirs(MUSIC_FOLDER, exist_ok=True)
os.makedirs(WAVEFORM_FOLDER, exist_ok=True)
os.makedirs(PLAYLIST_ART_FOLDER, exist_ok=True)

# Track artwork storage (per-track cover art and canvas GIF/video)
TRACK_ART_FOLDER = os.path.join(MUSIC_FOLDER, 'track_art')
os.makedirs(TRACK_ART_FOLDER, exist_ok=True)

app.config['UPLOAD_FOLDER'] = UPLOAD_FOLDER
# Allow large folder uploads (65+ files); default is unlimited but some proxies limit
app.config['MAX_CONTENT_LENGTH'] = 500 * 1024 * 1024  # 500 MB

# Ensure 500 errors return JSON so frontend can display the message
@app.errorhandler(500)
def handle_500(err):
    traceback.print_exc()
    message = str(err) if (err and app.debug) else 'Internal server error'
    return jsonify({'error': message}), 500

# Script directory for beet/config - works regardless of cwd when starting server
BEETS_DIR = os.path.dirname(os.path.abspath(__file__))


def init_track_artwork():
    """Create track_artwork table in library.db if not exists"""
    try:
        conn = sqlite3.connect(LIBRARY_DB)
        cursor = conn.cursor()
        cursor.execute('''
            CREATE TABLE IF NOT EXISTS track_artwork (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                track_id INTEGER NOT NULL,
                file_path TEXT NOT NULL,
                art_type TEXT NOT NULL,
                is_primary INTEGER DEFAULT 0,
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            )
        ''')
        conn.commit()
        conn.close()
    except Exception as e:
        logger.warning("track_artwork init: %s", e)

ALLOWED_EXTENSIONS = {'mp3', 'wav', 'ogg', 'flac', 'm4a'}

# --- SQLite concurrency helpers ---
_db_lock = threading.Lock()

def _get_db(db_path=None, timeout=10):
    """Open a SQLite connection with WAL mode and retry-friendly timeout."""
    path = db_path or LIBRARY_DB
    conn = sqlite3.connect(path, timeout=timeout)
    conn.execute("PRAGMA journal_mode=WAL")
    conn.execute("PRAGMA busy_timeout=5000")
    return conn

def _db_retry(fn, max_retries=3, delay=0.3):
    """Retry a database operation on SQLITE_BUSY / SQLITE_LOCKED."""
    for attempt in range(max_retries):
        try:
            return fn()
        except sqlite3.OperationalError as e:
            if 'locked' in str(e).lower() or 'busy' in str(e).lower():
                if attempt < max_retries - 1:
                    time.sleep(delay * (attempt + 1))
                    continue
            raise
    return None


# --- Import marker for safe track lookup (avoids race condition) ---
IMPORT_MARKER_FIELD = 'comments'  # beets stores this as 'comments' in items table

def _set_import_marker(filepath, marker):
    """Write a unique import marker into the audio file's genre field."""
    try:
        from mutagen import File
        from mutagen.wave import WAVE
        from mutagen.id3 import TCON
        audio = File(filepath, easy=True)
        if audio is not None:
            try:
                audio['genre'] = marker
                audio.save()
                return True
            except (TypeError, KeyError):
                # WAV files don't support easy-tag writes; use raw TCON frame instead
                raw = File(filepath)
                if isinstance(raw, WAVE):
                    if raw.tags is None:
                        raw.add_tags()
                    raw.tags.add(TCON(encoding=3, text=[marker]))
                    raw.save()
                    return True
    except Exception as e:
        logger.warning("Could not set import marker: %s", e)
    return False

def _find_track_by_marker(marker, timeout=10, title_hint=None):
    """Look up a track by its import marker in the genre field. Retries for up to `timeout` seconds.
    Falls back to title+artist lookup if the marker was not written (e.g. for WAV files where
    mutagen easy-mode tag writes are unsupported and beets may not read TCON as genre)."""
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            conn = _get_db()
            cursor = conn.cursor()
            cursor.execute("SELECT id FROM items WHERE genre = ? ORDER BY id DESC LIMIT 1", (marker,))
            row = cursor.fetchone()
            conn.close()
            if row:
                return row[0]
        except Exception:
            pass
        time.sleep(0.5)

    # Fallback: look up by title + artist (works when marker write failed but metadata tags succeeded)
    if title_hint:
        try:
            normalized_title = title_hint.replace('_', ' ').strip().lower()
            conn = _get_db()
            cursor = conn.cursor()
            cursor.execute(
                "SELECT id FROM items WHERE LOWER(TRIM(title)) = ? AND LOWER(TRIM(artist)) = ? ORDER BY id DESC LIMIT 1",
                (normalized_title, 'thomas phillips')
            )
            row = cursor.fetchone()
            conn.close()
            if row:
                return row[0]
        except Exception as e:
            logger.warning("Title-based track lookup failed: %s", e)
    return None

def _clear_import_marker(track_id, genre_value=''):
    """Clear the import marker from a track after successful lookup."""
    try:
        conn = _get_db()
        cursor = conn.cursor()
        cursor.execute("UPDATE items SET genre = ? WHERE id = ?", (genre_value, track_id))
        conn.commit()
        conn.close()
    except Exception as e:
        logger.warning("Could not clear import marker: %s", e)


def allowed_file(filename):
    return '.' in filename and \
           filename.rsplit('.', 1)[1].lower() in ALLOWED_EXTENSIONS

def get_track_name(filename):
    """Extract clean track name from filename (without extension)"""
    name = os.path.splitext(filename)[0]
    # Clean up common patterns
    name = re.sub(r'^\d+[\s\-_\.]+', '', name)  # Remove leading track numbers
    return name.strip() or filename

def check_duplicate(track_name):
    """Check if a track with similar name already exists in the library.
    Normalizes track_name to match stored format (underscores->spaces, lowercase)."""
    try:
        normalized = (track_name or '').replace('_', ' ').strip().lower()
        if not normalized:
            return None
        conn = sqlite3.connect(LIBRARY_DB)
        cursor = conn.cursor()
        # DB stores title as track_name.replace('_', ' ').lower(); compare normalized
        cursor.execute(
            "SELECT id, title FROM items WHERE LOWER(TRIM(REPLACE(title, '_', ' '))) = ?",
            (normalized,)
        )
        result = cursor.fetchone()
        conn.close()
        return result  # Returns (id, title) if found, None otherwise
    except Exception as e:
        logger.warning("Duplicate check error: %s", e)
        return None

def set_audio_title(filepath, title):
    """Set the title and artist metadata on the audio file before import"""
    try:
        from mutagen import File
        audio = File(filepath, easy=True)
        if audio is not None:
            # Format title: remove underscores, all lowercase
            formatted_title = title.replace('_', ' ').lower()
            audio['title'] = formatted_title
            audio['artist'] = 'thomas phillips'
            audio.save()
            return True
    except Exception as e:
        logger.warning("Could not set metadata: %s", e)
    return False

def generate_waveform_peaks(audio_path, num_peaks=200):
    """Generate waveform peaks data from an audio file"""
    try:
        # Convert to WAV if needed using ffmpeg, then read
        temp_wav = audio_path + '.temp.wav'
        
        # Use ffmpeg to convert to mono WAV for easy reading
        subprocess.run(
            ['ffmpeg', '-y', '-i', audio_path, '-ac', '1', '-ar', '22050', temp_wav],
            check=True, capture_output=True
        )
        
        # Read WAV file
        with wave.open(temp_wav, 'rb') as wav:
            n_frames = wav.getnframes()
            sample_width = wav.getsampwidth()
            
            # Read all frames
            frames = wav.readframes(n_frames)
            
            # Convert to samples based on sample width
            if sample_width == 1:
                fmt = f'{n_frames}b'  # signed char
            elif sample_width == 2:
                fmt = f'{n_frames}h'  # signed short
            else:
                fmt = f'{n_frames}i'  # signed int
            
            samples = list(struct.unpack(fmt, frames))
        
        # Clean up temp file
        if os.path.exists(temp_wav):
            os.remove(temp_wav)
        
        # Calculate peaks
        samples_per_peak = max(1, len(samples) // num_peaks)
        peaks = []
        
        for i in range(num_peaks):
            start = i * samples_per_peak
            end = min(start + samples_per_peak, len(samples))
            chunk = samples[start:end]
            
            if chunk:
                # Get max absolute value in this chunk
                max_val = max(abs(min(chunk)), abs(max(chunk)))
                # Normalize to 0-1 range
                normalized = max_val / (2 ** (sample_width * 8 - 1)) if max_val > 0 else 0
                peaks.append(round(normalized, 3))
            else:
                peaks.append(0)
        
        return peaks
        
    except Exception as e:
        logger.warning("Waveform generation error: %s", e)
        return None

def get_track_path(track_id):
    """Get the file path for a track from the database"""
    try:
        conn = sqlite3.connect(LIBRARY_DB)
        cursor = conn.cursor()
        cursor.execute("SELECT path FROM items WHERE id = ?", (track_id,))
        result = cursor.fetchone()
        conn.close()
        if result:
            return result[0]
    except Exception as e:
        logger.warning("Get track path error: %s", e)
    return None

@app.route('/waveform/<int:track_id>', methods=['GET'])
def get_waveform(track_id):
    """Get or generate waveform peaks for a track"""
    waveform_file = os.path.join(WAVEFORM_FOLDER, f'{track_id}.json')
    
    # Check if waveform already exists
    if os.path.exists(waveform_file):
        with open(waveform_file, 'r') as f:
            return jsonify(json.load(f))
    
    # Generate waveform
    track_path = get_track_path(track_id)
    if not track_path:
        return jsonify({'error': 'Track not found'}), 404
    
    # Handle path encoding (beets stores as bytes)
    if isinstance(track_path, bytes):
        track_path = track_path.decode('utf-8')
    
    if not os.path.exists(track_path):
        return jsonify({'error': 'Audio file not found'}), 404
    
    peaks = generate_waveform_peaks(track_path)
    if peaks is None:
        return jsonify({'error': 'Failed to generate waveform'}), 500
    
    # Save for future requests
    waveform_data = {'peaks': peaks, 'track_id': track_id}
    with open(waveform_file, 'w') as f:
        json.dump(waveform_data, f)
    
    return jsonify(waveform_data)


def detect_beats(audio_path, beats_per_bar=4):
    """Detect BPM, beat times, and bar boundaries using librosa."""
    try:
        import librosa
        y, sr = librosa.load(audio_path, sr=22050, mono=True)
        tempo, beat_frames = librosa.beat.beat_track(y=y, sr=sr)
        bpm = float(tempo) if not hasattr(tempo, '__len__') else float(tempo[0])
        beat_times = librosa.frames_to_time(beat_frames, sr=sr).tolist()
        # Group beats into bars
        bar_times = [beat_times[i] for i in range(0, len(beat_times), beats_per_bar)]
        # Add track end as final bar boundary
        duration = float(len(y) / sr)
        if bar_times and bar_times[-1] < duration - 0.5:
            bar_times.append(duration)
        return {
            'bpm': round(bpm, 1),
            'beat_times': [round(t, 3) for t in beat_times],
            'bar_times': [round(t, 3) for t in bar_times],
            'beats_per_bar': beats_per_bar,
            'duration': round(duration, 3),
        }
    except Exception as e:
        logger.warning("Beat detection error: %s", e)
        return None


@app.route('/track/<int:track_id>/beats', methods=['GET'])
def get_beats(track_id):
    """Get or generate beat/bar grid for a track. Cached as JSON."""
    beats_file = os.path.join(WAVEFORM_FOLDER, f'{track_id}_beats.json')

    if os.path.exists(beats_file):
        with open(beats_file, 'r') as f:
            return jsonify(json.load(f))

    track_path = get_track_path(track_id)
    if not track_path:
        return jsonify({'error': 'Track not found'}), 404
    if isinstance(track_path, bytes):
        track_path = track_path.decode('utf-8')
    if not os.path.exists(track_path):
        return jsonify({'error': 'Audio file not found'}), 404

    beats_data = detect_beats(track_path)
    if beats_data is None:
        return jsonify({'error': 'Beat detection failed'}), 500

    beats_data['track_id'] = track_id
    with open(beats_file, 'w') as f:
        json.dump(beats_data, f)

    return jsonify(beats_data)


@app.route('/upload', methods=['POST'])
@admin_required()
def upload_file():
    if 'file' not in request.files:
        return jsonify({'error': 'No file part'}), 400
    
    file = request.files['file']
    
    if file.filename == '':
        return jsonify({'error': 'No selected file'}), 400
    
    if file and allowed_file(file.filename):
        filename = secure_filename(file.filename)
        track_name = get_track_name(filename)
        
        # Check for duplicates
        existing = check_duplicate(track_name)
        if existing:
            return jsonify({
                'error': f'Duplicate detected: "{track_name}" already exists in library',
                'existing_id': existing[0]
            }), 409  # 409 Conflict
        
        filepath = os.path.join(app.config['UPLOAD_FOLDER'], filename)
        file.save(filepath)
        
        # Set title metadata before import
        set_audio_title(filepath, track_name)

        # Write a unique import marker so we can find this exact track after import
        import_marker = f"_import_{uuid.uuid4().hex}"
        _set_import_marker(filepath, import_marker)

        # Run beet import
        try:
            subprocess.run(
                ['beet', '-c', 'config.yaml', 'import', '-q', '--noautotag', '-s', filepath],
                check=True
            )

            # Find the imported track by its unique marker (race-condition-safe)
            formatted_title = track_name.replace('_', ' ').lower()
            new_id = _find_track_by_marker(import_marker, timeout=10)
            s3_warning = None

            if new_id:
                # Update metadata and clear the import marker
                try:
                    conn = _get_db()
                    cursor = conn.cursor()
                    cursor.execute("UPDATE items SET title = ?, artist = ?, genre = '' WHERE id = ?",
                                 (formatted_title, 'thomas phillips', new_id))
                    conn.commit()
                    conn.close()
                except Exception as db_err:
                    logger.warning("Could not update title in database: %s", db_err)

                # --- S3 Upload (report failures to frontend) ---
                if s3_client:
                    try:
                        conn = _get_db()
                        cursor = conn.cursor()
                        cursor.execute("SELECT path FROM items WHERE id = ?", (new_id,))
                        res = cursor.fetchone()
                        conn.close()

                        if res:
                            final_path = res[0]
                            if isinstance(final_path, bytes):
                                final_path = final_path.decode('utf-8', errors='replace')

                            rel_path = os.path.relpath(final_path, start=os.getcwd())
                            if not rel_path.startswith('..'):
                                logger.info("Uploading to S3: %s", rel_path)
                                s3_client.upload_file(final_path, S3_BUCKET, rel_path)
                    except Exception as s3_err:
                        logger.error("S3 upload failed: %s", s3_err)
                        s3_warning = f"Track imported locally but S3 sync failed: {s3_err}"
            else:
                logger.warning("Could not find imported track by marker: %s", import_marker)


            # Clean up uploaded file
            if os.path.exists(filepath):
                os.remove(filepath)

            response = {
                'message': f'Successfully uploaded "{track_name}"',
                'track_name': track_name,
                'track_id': new_id,
            }
            if s3_warning:
                response['s3_warning'] = s3_warning

            return jsonify(response), 200
        except subprocess.CalledProcessError as e:
            return jsonify({'error': f'Import failed: {str(e)}'}), 500
        except Exception as e:
            return jsonify({'error': f'Server error: {str(e)}'}), 500
            
    return jsonify({'error': 'Invalid file type'}), 400


# --- Folder / Zip Upload ---
ALLOWED_IMAGE_EXT = {'jpg', 'jpeg', 'png', 'gif', 'webp'}
ALLOWED_CANVAS_EXT = {'gif', 'mp4', 'webm', 'mov'}

def _basename_stem(path):
    """Get basename stem (no extension), case-insensitive, ignore leading ._"""
    name = os.path.basename(path)
    if name.startswith('._'):
        name = name[2:]
    return os.path.splitext(name)[0].lower().strip()

def _scan_for_audio_and_images(root_dir):
    """Scan root_dir recursively. Return (audio_files, image_map).
    image_map: {dir_path: {stem: [full_path, ...]}}
    """
    audio_ext = ALLOWED_EXTENSIONS
    image_ext = ALLOWED_IMAGE_EXT | ALLOWED_CANVAS_EXT
    audio_files = []
    images_by_dir = {}
    for dirpath, _, filenames in os.walk(root_dir):
        dir_images = {}
        for f in filenames:
            if f.startswith('._'):
                continue
            fp = os.path.join(dirpath, f)
            ext = f.rsplit('.', 1)[-1].lower() if '.' in f else ''
            if ext in audio_ext:
                audio_files.append(fp)
            elif ext in image_ext:
                stem = _basename_stem(fp)
                dir_images.setdefault(stem, []).append(fp)
        if dir_images:
            images_by_dir[dirpath] = dir_images
    return audio_files, images_by_dir

def _match_images_to_audio(audio_path, images_by_dir):
    """Return (cover_candidates, canvas_candidates) for this audio file."""
    audio_dir = os.path.dirname(audio_path)
    stem = _basename_stem(audio_path)
    dir_images = images_by_dir.get(audio_dir, {})
    candidates = dir_images.get(stem, [])
    covers = []
    canvases = []
    for p in candidates:
        ext = os.path.splitext(p)[1].lower().lstrip('.')
        if ext in ALLOWED_CANVAS_EXT:
            canvases.append(p)
        elif ext in ALLOWED_IMAGE_EXT:
            covers.append(p)
    # Prefer jpeg > png > gif > webp for cover
    cover_order = ('jpg', 'jpeg', 'png', 'gif', 'webp')
    covers.sort(key=lambda x: (cover_order.index(os.path.splitext(x)[1].lower().lstrip('.'))
                              if os.path.splitext(x)[1].lower().lstrip('.') in cover_order
                              else 99))
    return covers, canvases

def _derive_metadata_from_path(audio_path, extract_root):
    """Derive album from folder structure. extract_root is the scan root."""
    rel = os.path.relpath(audio_path, extract_root)
    parts = rel.split(os.sep)
    if len(parts) >= 2:
        root_name = parts[0]
        return root_name  # e.g. "8 Tracks n 6 Packs"
    return 'Unknown Album'

def _set_audio_metadata(filepath, title, artist, album):
    """Set title, artist, album on audio file before import."""
    try:
        from mutagen import File
        from mutagen.wave import WAVE
        from mutagen.id3 import TIT2, TPE1, TALB
        formatted_title = title.replace('_', ' ').lower()
        audio = File(filepath, easy=True)
        if audio is not None:
            try:
                audio['title'] = formatted_title
                audio['artist'] = artist
                audio['album'] = album
                audio.save()
                return True
            except (TypeError, KeyError):
                # WAV files don't support easy-tag writes; use raw ID3 frames instead
                raw = File(filepath)
                if isinstance(raw, WAVE):
                    if raw.tags is None:
                        raw.add_tags()
                    raw.tags.add(TIT2(encoding=3, text=[formatted_title]))
                    raw.tags.add(TPE1(encoding=3, text=[artist]))
                    raw.tags.add(TALB(encoding=3, text=[album]))
                    raw.save()
                    return True
    except Exception as e:
        logger.warning("Could not set metadata: %s", e)
    return False

def _insert_track_artwork(track_id, src_path, art_type, is_primary=0, suffix=None):
    """Copy file to TRACK_ART_FOLDER and insert into track_artwork. Returns rel_path or None."""
    ext = os.path.splitext(src_path)[1].lower()
    if ext == '.jpeg':
        ext = '.jpg'
    dest_name = f"{track_id}_{art_type}_{suffix}{ext}" if suffix is not None else f"{track_id}_{art_type}{ext}"
    dest_path = os.path.join(TRACK_ART_FOLDER, dest_name)
    try:
        shutil.copy2(src_path, dest_path)
        rel_path = os.path.join('music', 'track_art', dest_name)
        conn = sqlite3.connect(LIBRARY_DB)
        cursor = conn.cursor()
        cursor.execute(
            "INSERT INTO track_artwork (track_id, file_path, art_type, is_primary) VALUES (?, ?, ?, ?)",
            (track_id, rel_path, art_type, is_primary)
        )
        conn.commit()
        conn.close()
        return rel_path
    except Exception as e:
        logger.warning("Failed to store track artwork: %s", e)
        return None


def _safe_extract_zip(zip_path, work_dir):
    """Extract zip contents safely, rejecting path traversal (Zip Slip) attacks."""
    work_dir_abs = os.path.abspath(work_dir)
    with zipfile.ZipFile(zip_path, 'r') as z:
        for member in z.namelist():
            if member.startswith('/') or '..' in member:
                continue
            dest = os.path.normpath(os.path.join(work_dir, member))
            if not os.path.abspath(dest).startswith(work_dir_abs):
                continue
            z.extract(member, work_dir)


@app.route('/upload/folder', methods=['POST'])
@admin_required()
def upload_folder():
    """Accept multiple files or a zip, extract/scan, import audio with metadata from folder structure, associate matching images."""
    work_dir = None
    extract_root = None

    try:
        # Determine input: zip or multiple files
        if 'file' in request.files:
            f = request.files['file']
            if f.filename and f.filename.lower().endswith('.zip'):
                work_dir = tempfile.mkdtemp()
                zip_path = os.path.join(work_dir, 'upload.zip')
                f.save(zip_path)
                _safe_extract_zip(zip_path, work_dir)
                extract_root = work_dir
            else:
                return jsonify({'error': 'Expected a .zip file for single file upload'}), 400
        elif 'files' in request.files or 'files[]' in request.files:
            files = request.files.getlist('files') or request.files.getlist('files[]')
            if not files:
                return jsonify({'error': 'No files provided'}), 400
            work_dir = tempfile.mkdtemp()
            extract_root = work_dir
            work_dir_abs = os.path.abspath(work_dir)
            # Extensions we save (audio + images); avoids saving "Volume 1" as file when it's a dir
            save_exts = ALLOWED_EXTENSIONS | {'jpg', 'jpeg', 'png', 'gif', 'webp', 'mp4', 'webm', 'mov'}
            for i, f in enumerate(files):
                if f.filename and f.filename.strip():
                    raw_fn = f.filename.replace('\\', '/').strip()
                    ext = raw_fn.rsplit('.', 1)[-1].lower() if '.' in raw_fn else ''
                    if ext not in save_exts:
                        continue
                    parts = [secure_filename(p) for p in raw_fn.split('/') if p]
                    fn = os.path.join(*parts) if parts else f"file_{i}"
                    save_path = os.path.join(work_dir, fn)
                    if not os.path.abspath(save_path).startswith(work_dir_abs):
                        continue
                    try:
                        os.makedirs(os.path.dirname(save_path), exist_ok=True)
                        f.save(save_path)
                    except OSError as e:
                        if e.errno != errno.EEXIST:
                            raise
                        continue
        else:
            return jsonify({'error': 'Provide "file" (zip) or "files"/"files[]" (multiple files)'}), 400

        album_override = (request.form.get('album') or '').strip() or None
        user_id = get_jwt_identity()
        result, status_code = _import_from_directory(extract_root, album_override=album_override, user_id=user_id)
        return jsonify(result), status_code

    except Exception as e:
        traceback.print_exc()
        return jsonify({'error': str(e)}), 500

    finally:
        if work_dir and os.path.isdir(work_dir):
            try:
                shutil.rmtree(work_dir)
            except Exception:
                pass


def _import_from_directory(extract_root, album_override=None, user_id=None):
    """Shared import logic: scan a directory for audio/images, run beets import, return results dict.
    Used by both upload_folder (direct upload) and upload_process (presigned S3 flow).
    album_override: if provided, use this album name instead of deriving from path.
    user_id: if provided with album_override, auto-create a playlist from imported tracks."""
    audio_files, images_by_dir = _scan_for_audio_and_images(extract_root)
    if not audio_files:
        return {'error': 'No audio files found (mp3, wav, ogg, flac, m4a)'}, 400

    results = {'imported': [], 'failed': [], 'skipped': [], 'needs_art_selection': [], 's3_warnings': []}

    for audio_path in audio_files:
        track_name = get_track_name(os.path.basename(audio_path))
        formatted_check = track_name.replace('_', ' ').strip().lower()
        if not formatted_check:
            results['skipped'].append({'file': os.path.basename(audio_path), 'reason': 'could not determine track name'})
            continue
        existing = check_duplicate(track_name)
        if existing:
            results['skipped'].append({'file': os.path.basename(audio_path), 'reason': 'duplicate'})
            continue

        derived = _derive_metadata_from_path(audio_path, extract_root)
        album = album_override if album_override else derived
        _set_audio_metadata(audio_path, track_name, 'thomas phillips', album)

        # Write unique import marker for safe post-import lookup
        import_marker = f"_import_{uuid.uuid4().hex}"
        _set_import_marker(audio_path, import_marker)

        try:
            subprocess.run(
                ['beet', '-c', os.path.join(BEETS_DIR, 'config.yaml'), 'import', '-q', '--noautotag', '-s', audio_path],
                check=True, cwd=BEETS_DIR
            )
        except subprocess.CalledProcessError as e:
            results['failed'].append({'file': os.path.basename(audio_path), 'reason': str(e)})
            continue

        # Find the imported track by its unique marker (race-condition-safe)
        # title_hint is used as fallback when marker write fails (e.g. WAV files)
        new_id = _find_track_by_marker(import_marker, timeout=10, title_hint=track_name)
        if not new_id:
            results['failed'].append({'file': os.path.basename(audio_path), 'reason': 'could not locate imported track'})
            continue

        formatted_title = track_name.replace('_', ' ').lower()
        def _update_metadata():
            conn = _get_db()
            cursor = conn.cursor()
            cursor.execute("UPDATE items SET title = ?, artist = ?, album = ?, genre = '' WHERE id = ?",
                          (formatted_title, 'thomas phillips', album, new_id))
            conn.commit()
            conn.close()
        _db_retry(_update_metadata)

        covers, canvases = _match_images_to_audio(audio_path, images_by_dir)
        if covers:
            if len(covers) == 1:
                _insert_track_artwork(new_id, covers[0], 'cover', is_primary=1)
            else:
                candidates = []
                for i, c in enumerate(covers):
                    rel = _insert_track_artwork(new_id, c, 'cover', is_primary=0, suffix=i)
                    if rel:
                        candidates.append({
                            'path': rel,
                            'display_name': os.path.basename(c)
                        })
                if candidates:
                    results['needs_art_selection'].append({
                        'track_id': new_id,
                        'track_name': formatted_title,
                        'candidates': candidates
                    })
        if canvases:
            _insert_track_artwork(new_id, canvases[0], 'canvas', is_primary=0)

        # S3 upload with error reporting (non-blocking but tracked)
        if s3_client:
            try:
                conn = _get_db()
                cursor = conn.cursor()
                cursor.execute("SELECT path FROM items WHERE id = ?", (new_id,))
                res = cursor.fetchone()
                conn.close()
                if res:
                    final_path = res[0]
                    if isinstance(final_path, bytes):
                        final_path = final_path.decode('utf-8', errors='replace')
                    rel_path = os.path.relpath(final_path, start=os.getcwd())
                    if not rel_path.startswith('..'):
                        s3_client.upload_file(final_path, S3_BUCKET, rel_path)
            except Exception as s3_err:
                logger.error("S3 upload failed for %s: %s", new_id, s3_err)
                results['s3_warnings'].append({'track_id': new_id, 'error': str(s3_err)})

        results['imported'].append({'track_name': track_name, 'id': new_id})

    # Auto-create playlist from album upload
    playlist_info = None
    if album_override and user_id and results['imported']:
        try:
            conn = sqlite3.connect(USERS_DB)
            cursor = conn.cursor()
            cursor.execute("INSERT INTO playlists (name, user_id, is_public) VALUES (?, ?, 1)",
                           (album_override, int(user_id)))
            playlist_id = cursor.lastrowid
            for i, track in enumerate(results['imported']):
                cursor.execute("INSERT INTO playlist_tracks (playlist_id, track_id, position) VALUES (?, ?, ?)",
                               (playlist_id, track['id'], i + 1))
            conn.commit()
            conn.close()
            playlist_info = {'id': playlist_id, 'name': album_override, 'count': len(results['imported'])}
        except Exception as e:
            logger.warning("Failed to auto-create playlist: %s", e)

    response = {
        'message': f"Imported {len(results['imported'])} tracks",
        'imported': results['imported'],
        'failed': results['failed'],
        'skipped': results['skipped'],
        'needs_art_selection': results['needs_art_selection'],
    }
    if results['s3_warnings']:
        response['s3_warnings'] = results['s3_warnings']
    if playlist_info:
        response['playlist'] = playlist_info

    return response, 200


# --- Presigned URL Upload (bypasses Cloudflare body size limit) ---
# Track active presigned sessions: {session_id: {'created_at': timestamp, 'keys': [...]}}
_presign_sessions = {}
_presign_sessions_lock = threading.Lock()
PRESIGN_SESSION_TTL = 3600  # 1 hour
PRESIGN_MAX_FILES = 200     # Max files per session
MAX_FILENAME_LENGTH = 255   # Max individual filename length

# --- Async process jobs ---
# Jobs: {job_id: {'status': 'processing'|'done'|'error', 'result': ..., 'status_code': ..., 'error': ..., 'created_at': float}}
_process_jobs = {}
_process_jobs_lock = threading.Lock()
_PROCESS_JOB_TTL = 7200  # 2 hours

def _cleanup_expired_jobs():
    """Remove stale completed/errored jobs older than TTL."""
    cutoff = time.time() - _PROCESS_JOB_TTL
    with _process_jobs_lock:
        expired = [jid for jid, j in _process_jobs.items() if j.get('created_at', 0) < cutoff]
        for jid in expired:
            del _process_jobs[jid]

def _cleanup_expired_sessions():
    """Remove expired presigned sessions from memory."""
    now = time.time()
    with _presign_sessions_lock:
        expired = [sid for sid, info in _presign_sessions.items()
                   if now - info['created_at'] > PRESIGN_SESSION_TTL]
        for sid in expired:
            del _presign_sessions[sid]


@app.route('/upload/presign', methods=['POST'])
@admin_required()
def upload_presign():
    """Generate presigned S3 PUT URLs so the browser/app can upload files directly to S3."""
    if not s3_client:
        return jsonify({'error': 'S3 is not configured on this server'}), 501

    data = request.get_json(silent=True) or {}
    filenames = data.get('filenames', [])
    if not filenames:
        return jsonify({'error': 'Provide "filenames": ["file1.wav", ...]'}), 400

    if len(filenames) > PRESIGN_MAX_FILES:
        return jsonify({'error': f'Too many files ({len(filenames)}). Max is {PRESIGN_MAX_FILES}.'}), 400

    # Validate filenames
    for fn in filenames:
        if len(fn) > MAX_FILENAME_LENGTH:
            return jsonify({'error': f'Filename too long: {fn[:50]}...'}), 400

    # Cleanup expired sessions periodically
    _cleanup_expired_sessions()

    session_id = str(uuid.uuid4())
    urls = []
    session_keys = []
    for fn in filenames:
        safe_fn = secure_filename(fn) or f"file_{uuid.uuid4().hex[:8]}"
        key = f"uploads/{session_id}/{safe_fn}"
        try:
            presigned_url = s3_client.generate_presigned_url(
                'put_object',
                Params={'Bucket': S3_BUCKET, 'Key': key},
                ExpiresIn=PRESIGN_SESSION_TTL,
            )
            urls.append({'filename': fn, 'url': presigned_url, 'key': key})
            session_keys.append(key)
        except Exception as e:
            return jsonify({'error': f'Failed to generate presigned URL: {e}'}), 500

    # Register this session
    with _presign_sessions_lock:
        _presign_sessions[session_id] = {
            'created_at': time.time(),
            'keys': session_keys,
        }

    return jsonify({'session_id': session_id, 'urls': urls}), 200


def _cleanup_s3_session(session_id, keys):
    """Clean up S3 staging objects for a session (best-effort)."""
    if not s3_client:
        return
    for key in keys:
        try:
            s3_client.delete_object(Bucket=S3_BUCKET, Key=key)
        except Exception as e:
            logger.warning("S3 cleanup failed for %s: %s", key, e)
    # Remove session tracking
    with _presign_sessions_lock:
        _presign_sessions.pop(session_id, None)


def _run_process_job(job_id, session_id, keys, work_dir, prefix, album_override=None, user_id=None):
    """Background worker: download S3 files and run import pipeline."""
    try:
        for key in keys:
            rel = key[len(prefix):]
            local_path = os.path.join(work_dir, rel)
            os.makedirs(os.path.dirname(local_path), exist_ok=True)
            s3_client.download_file(S3_BUCKET, key, local_path)

        result, status_code = _import_from_directory(work_dir, album_override=album_override, user_id=user_id)
        _cleanup_s3_session(session_id, keys)

        with _process_jobs_lock:
            _process_jobs[job_id]['status'] = 'done'
            _process_jobs[job_id]['result'] = result
            _process_jobs[job_id]['status_code'] = status_code

    except Exception as e:
        traceback.print_exc()
        _cleanup_s3_session(session_id, keys)
        with _process_jobs_lock:
            _process_jobs[job_id]['status'] = 'error'
            _process_jobs[job_id]['error'] = str(e)
    finally:
        if work_dir and os.path.isdir(work_dir):
            try:
                shutil.rmtree(work_dir)
            except Exception as cleanup_err:
                logger.warning("Temp dir cleanup failed: %s", cleanup_err)


@app.route('/upload/process', methods=['POST'])
@admin_required()
def upload_process():
    """Start async import: download files from S3 staging and run import pipeline in background.
    Returns 202 with job_id immediately; poll /upload/status/<job_id> for completion."""
    if not s3_client:
        return jsonify({'error': 'S3 is not configured on this server'}), 501

    data = request.get_json(silent=True) or {}
    session_id = data.get('session_id', '')
    keys = data.get('keys', [])
    album_override = data.get('album', '').strip() or None
    user_id = get_jwt_identity()
    if not session_id or not keys:
        return jsonify({'error': 'Provide "session_id" and "keys"'}), 400

    # Validate session exists and hasn't expired
    with _presign_sessions_lock:
        session_info = _presign_sessions.get(session_id)
    if session_info:
        age = time.time() - session_info['created_at']
        if age > PRESIGN_SESSION_TTL:
            _cleanup_s3_session(session_id, keys)
            return jsonify({'error': 'Upload session has expired. Please upload again.'}), 410

    # Validate all keys belong to this session
    prefix = f"uploads/{session_id}/"
    for k in keys:
        if not k.startswith(prefix):
            return jsonify({'error': f'Key does not belong to session: {k}'}), 400

    _cleanup_expired_jobs()

    job_id = str(uuid.uuid4())
    work_dir = tempfile.mkdtemp()

    with _process_jobs_lock:
        _process_jobs[job_id] = {'status': 'processing', 'created_at': time.time()}

    thread = threading.Thread(
        target=_run_process_job,
        args=(job_id, session_id, keys, work_dir, prefix, album_override, user_id),
        daemon=True,
    )
    thread.start()

    return jsonify({'job_id': job_id, 'status': 'processing'}), 202


@app.route('/upload/status/<job_id>', methods=['GET'])
@admin_required()
def upload_status(job_id):
    """Poll the status of an async /upload/process job."""
    with _process_jobs_lock:
        job = _process_jobs.get(job_id)

    if not job:
        return jsonify({'error': 'Job not found or expired'}), 404

    status = job['status']
    if status == 'processing':
        return jsonify({'status': 'processing'}), 202

    # Job finished — remove from store and return result
    with _process_jobs_lock:
        _process_jobs.pop(job_id, None)

    if status == 'done':
        return jsonify({'status': 'done', **job['result']}), job.get('status_code', 200)

    return jsonify({'status': 'error', 'error': job.get('error', 'Unknown error')}), 500


@app.route('/upload/folder/confirm-art', methods=['POST'])
@admin_required()
def confirm_art_selection():
    """Set the primary cover for tracks that had multiple matching images."""
    data = request.get_json(silent=True) or {}
    selections = data.get('selections', [])
    if not selections:
        return jsonify({'error': 'Provide "selections": [{ "track_id", "selected_path" }]'}), 400

    try:
        conn = sqlite3.connect(LIBRARY_DB)
        cursor = conn.cursor()
        for sel in selections:
            track_id = sel.get('track_id')
            selected_path = sel.get('selected_path')
            if not track_id or not selected_path:
                continue
            # Clear primary for all covers of this track
            cursor.execute(
                "UPDATE track_artwork SET is_primary = 0 WHERE track_id = ? AND art_type = 'cover'",
                (track_id,)
            )
            # Set primary for the selected one
            cursor.execute(
                "UPDATE track_artwork SET is_primary = 1 WHERE track_id = ? AND file_path = ? AND art_type = 'cover'",
                (track_id, selected_path)
            )
        conn.commit()
        conn.close()
        return jsonify({'message': 'Art selection confirmed'}), 200
    except Exception as e:
        return jsonify({'error': str(e)}), 500


@app.route('/duplicates', methods=['DELETE'])
@admin_required()
def remove_duplicates():
    """Find and remove duplicate tracks from the library. Keeps lowest ID per group.
    Case-insensitive; normalizes title (underscores, whitespace) before grouping."""
    try:
        from collections import defaultdict

        lib_conn = sqlite3.connect(LIBRARY_DB)
        lib_cursor = lib_conn.cursor()
        lib_cursor.execute("SELECT id, title FROM items")
        rows = lib_cursor.fetchall()

        # Group by normalized title (same as check_duplicate)
        def normalize(t):
            return (t or '').replace('_', ' ').strip().lower()

        by_normalized = defaultdict(list)
        for item_id, title in rows:
            by_normalized[normalize(title)].append((item_id, title))

        to_delete = []
        for ntitle, items in by_normalized.items():
            if len(items) > 1:
                items.sort(key=lambda x: x[0])
                for item_id, _ in items[1:]:
                    to_delete.append(item_id)

        if to_delete:
            # Clean playlist_tracks (USERS_DB) before removing from items
            users_conn = sqlite3.connect(USERS_DB)
            users_cursor = users_conn.cursor()
            placeholders = ','.join('?' for _ in to_delete)
            users_cursor.execute(
                f"DELETE FROM playlist_tracks WHERE track_id IN ({placeholders})",
                to_delete
            )
            users_conn.commit()
            users_conn.close()

            for item_id in to_delete:
                lib_cursor.execute("DELETE FROM items WHERE id = ?", (item_id,))

        lib_conn.commit()
        lib_conn.close()

        return jsonify({
            'message': f'Removed {len(to_delete)} duplicate tracks',
            'deleted_ids': to_delete
        }), 200
    except Exception as e:
        return jsonify({'error': f'Failed to remove duplicates: {str(e)}'}), 500


def _update_audio_tags(filepath, title=None, artist=None, album=None):
    """Write metadata tags to an audio file, with raw-ID3 fallback for WAV files."""
    try:
        from mutagen import File
        from mutagen.wave import WAVE
        from mutagen.id3 import TIT2, TPE1, TALB
        audio = File(filepath, easy=True)
        if audio is None:
            return
        try:
            if title is not None:
                audio['title'] = title
            if artist is not None:
                audio['artist'] = artist
            if album is not None:
                audio['album'] = album
            audio.save()
        except (TypeError, KeyError):
            raw = File(filepath)
            if isinstance(raw, WAVE):
                if raw.tags is None:
                    raw.add_tags()
                if title is not None:
                    raw.tags.add(TIT2(encoding=3, text=[title]))
                if artist is not None:
                    raw.tags.add(TPE1(encoding=3, text=[artist]))
                if album is not None:
                    raw.tags.add(TALB(encoding=3, text=[album]))
                raw.save()
    except Exception as e:
        logger.warning("Could not update audio file metadata for %s: %s", filepath, e)


@app.route('/track/<int:track_id>', methods=['PUT'])
def update_track(track_id):
    """Update track metadata in database and audio file"""
    try:
        data = request.get_json()
        if not data:
            return jsonify({'error': 'No data provided'}), 400
        
        title = data.get('title')
        artist = data.get('artist')
        album = data.get('album')

        conn = sqlite3.connect(LIBRARY_DB)
        cursor = conn.cursor()
        
        # Get current track path
        cursor.execute("SELECT path FROM items WHERE id = ?", (track_id,))
        result = cursor.fetchone()
        if not result:
            conn.close()
            return jsonify({'error': 'Track not found'}), 404
        
        track_path = result[0]
        if isinstance(track_path, bytes):
            track_path = track_path.decode('utf-8')
        
        # Update database
        updates = []
        params = []
        if title is not None:
            updates.append("title = ?")
            params.append(title)
        if artist is not None:
            updates.append("artist = ?")
            params.append(artist)
        if album is not None:
            updates.append("album = ?")
            params.append(album)
        
        if updates:
            params.append(track_id)
            cursor.execute(f"UPDATE items SET {', '.join(updates)} WHERE id = ?", params)
            conn.commit()
        
        conn.close()
        
        # Update audio file metadata
        if os.path.exists(track_path):
            _update_audio_tags(track_path, title=title, artist=artist, album=album)
        
        return jsonify({
            'message': 'Track updated successfully',
            'track_id': track_id
        }), 200
        
    except Exception as e:
        return jsonify({'error': f'Failed to update track: {str(e)}'}), 500


@app.route('/track/<int:track_id>', methods=['DELETE'])
@admin_required()
def delete_track(track_id):
    """Delete track from database and optionally from disk"""
    try:
        conn = sqlite3.connect(LIBRARY_DB)
        cursor = conn.cursor()
        
        # Get track path before deletion
        cursor.execute("SELECT path FROM items WHERE id = ?", (track_id,))
        result = cursor.fetchone()
        if not result:
            conn.close()
            return jsonify({'error': 'Track not found'}), 404
        
        track_path = result[0]
        if isinstance(track_path, bytes):
            track_path = track_path.decode('utf-8')
        
        # Delete from database
        cursor.execute("DELETE FROM items WHERE id = ?", (track_id,))
        conn.commit()
        conn.close()

        # Clean up playlist references
        try:
            users_conn = sqlite3.connect(USERS_DB)
            users_cursor = users_conn.cursor()
            users_cursor.execute("DELETE FROM playlist_tracks WHERE track_id = ?", (track_id,))
            users_conn.commit()
            users_conn.close()
        except Exception as e:
            logger.warning("playlist_tracks cleanup failed for track %s: %s", track_id, e)
        
        # Delete waveform cache
        waveform_file = os.path.join(WAVEFORM_FOLDER, f'{track_id}.json')
        if os.path.exists(waveform_file):
            os.remove(waveform_file)

        # Delete local file
        if os.path.exists(track_path):
            try:
                os.remove(track_path)
            except Exception as e:
                logger.warning("Could not delete local file %s: %s", track_path, e)

        # Delete from S3
        if s3_client:
            try:
                rel_path = os.path.relpath(track_path, start=os.getcwd())
                if not rel_path.startswith('..'):
                    s3_client.delete_object(Bucket=S3_BUCKET, Key=rel_path)
            except Exception as e:
                logger.warning("S3 delete failed for track %s: %s", track_id, e)

        return jsonify({
            'message': 'Track deleted successfully',
            'track_id': track_id
        }), 200
        
    except Exception as e:
        return jsonify({'error': f'Failed to delete track: {str(e)}'}), 500


@app.route('/tracks/batch', methods=['PUT'])
def batch_update_tracks():
    """Update multiple tracks with the same metadata"""
    try:
        data = request.get_json()
        if not data:
            return jsonify({'error': 'No data provided'}), 400
        
        ids = data.get('ids', [])
        if not ids:
            return jsonify({'error': 'No track IDs provided'}), 400
        
        artist = data.get('artist')
        album = data.get('album')
        
        if not artist and not album:
            return jsonify({'error': 'No metadata to update'}), 400
        
        conn = sqlite3.connect(LIBRARY_DB)
        cursor = conn.cursor()
        
        updated_count = 0
        
        for track_id in ids:
            # Get track path
            cursor.execute("SELECT path FROM items WHERE id = ?", (track_id,))
            result = cursor.fetchone()
            if not result:
                continue
            
            track_path = result[0]
            if isinstance(track_path, bytes):
                track_path = track_path.decode('utf-8')
            
            # Update database
            updates = []
            params = []
            if artist:
                updates.append("artist = ?")
                params.append(artist)
            if album:
                updates.append("album = ?")
                params.append(album)
            
            if updates:
                params.append(track_id)
                cursor.execute(f"UPDATE items SET {', '.join(updates)} WHERE id = ?", params)
                updated_count += 1
            
            # Update audio file metadata
            if os.path.exists(track_path):
                _update_audio_tags(track_path, artist=artist, album=album)
        
        conn.commit()
        conn.close()
        
        return jsonify({
            'message': f'Updated {updated_count} tracks successfully',
            'updated_count': updated_count
        }), 200
        
    except Exception as e:
        return jsonify({'error': f'Failed to batch update tracks: {str(e)}'}), 500

@app.route('/tracks/reorder', methods=['PUT'])
def reorder_tracks():
    """Update track order based on list of IDs"""
    try:
        data = request.get_json()
        ordered_ids = data.get('ordered_ids', [])
        
        if not ordered_ids:
            return jsonify({'error': 'No IDs provided'}), 400
            
        conn = sqlite3.connect(LIBRARY_DB)
        cursor = conn.cursor()
        
        # Update track numbers to match new order (1-based)
        for index, track_id in enumerate(ordered_ids):
            cursor.execute("UPDATE items SET track = ? WHERE id = ?", (index + 1, track_id))
            
        conn.commit()
        conn.close()
        
        return jsonify({'message': 'Tracks reordered successfully'}), 200
    except Exception as e:
        return jsonify({'error': f'Failed to reorder tracks: {str(e)}'}), 500

@app.route('/tracks/batch', methods=['DELETE'])
def batch_delete_tracks():
    """Delete multiple tracks from database and optionally from disk"""
    try:
        data = request.get_json()
        if not data:
            return jsonify({'error': 'No data provided'}), 400
        
        ids = data.get('ids', [])
        if not ids:
            return jsonify({'error': 'No track IDs provided'}), 400
            
        conn = sqlite3.connect(LIBRARY_DB)
        cursor = conn.cursor()
        
        deleted_count = 0
        
        for track_id in ids:
            # Delete from database
            cursor.execute("DELETE FROM items WHERE id = ?", (track_id,))
            
            # Delete waveform cache
            waveform_file = os.path.join(WAVEFORM_FOLDER, f'{track_id}.json')
            if os.path.exists(waveform_file):
                os.remove(waveform_file)
                
            # Optional: Delete file from disk
            cursor.execute("SELECT path FROM items WHERE id = ?", (track_id,))
            result = cursor.fetchone()
            if result:
                track_path = result[0]
                if isinstance(track_path, bytes):
                    track_path = track_path.decode('utf-8')
                if os.path.exists(track_path):
                    try:
                        os.remove(track_path)
                    except Exception as e:
                        logger.warning("Error deleting file %s: %s", track_path, e)
            
            deleted_count += 1
            
        conn.commit()
        conn.close()
        
        return jsonify({
            'message': f'Deleted {deleted_count} tracks successfully',
            'deleted_count': deleted_count
        }), 200
        
    except Exception as e:
        return jsonify({'error': f'Failed to batch delete tracks: {str(e)}'}), 500

def _decode_val(val):
    """Decode bytes to str for JSON; leave other types as-is."""
    if isinstance(val, bytes):
        return val.decode('utf-8', errors='ignore')
    return val


def _path_to_s3_key(path):
    """Normalize DB path to a relative path matching S3 key (e.g. music/Artist/Album/track.mp3)."""
    if not path:
        return None
    p = _decode_val(path) if isinstance(path, bytes) else path
    if not p:
        return None
    # If absolute, make relative to CWD so app can match to S3 key
    try:
        rel = os.path.relpath(p, start=os.getcwd())
    except ValueError:
        rel = p
    # Normalize: no leading slash, forward slashes
    rel = rel.replace('\\', '/').lstrip('/')
    return rel if not rel.startswith('..') else None


@app.route('/library', methods=['GET'])
def get_library():
    """Get all tracks from the library. App-friendly shape: id, title, artist, album, path, stream_url.
    path is relative (S3-key style) so the app can match S3 keys to backend track id."""
    try:
        conn = sqlite3.connect(LIBRARY_DB)
        conn.row_factory = sqlite3.Row
        cursor = conn.cursor()
        try:
            cursor.execute(
                "SELECT id, title, artist, album, album_id, path, bpm FROM items ORDER BY track ASC, id ASC"
            )
            rows = cursor.fetchall()
        except sqlite3.OperationalError as e:
            if 'no such table' in str(e).lower():
                conn.close()
                return jsonify({'items': []}), 200
            raise
        canvas_track_ids = set()
        try:
            cursor.execute("SELECT DISTINCT track_id FROM track_artwork WHERE art_type = 'canvas'")
            canvas_track_ids = {r[0] for r in cursor.fetchall()}
        except sqlite3.OperationalError:
            pass
        conn.close()

        items = []
        for row in rows:
            item_id = row['id']
            raw_path = row['path']
            path_key = _path_to_s3_key(raw_path)
            item = {
                'id': item_id,
                'title': _decode_val(row['title']) or 'Unknown',
                'artist': _decode_val(row['artist']) or 'Unknown Artist',
                'album': _decode_val(row['album']) or '',
                'album_id': row['album_id'],
                'path': path_key,
                'stream_url': f'/stream/{item_id}',
                'bpm': row['bpm'] or 0,
            }
            if item_id in canvas_track_ids:
                item['canvas_url'] = f'/track/{item_id}/canvas'
            items.append(item)
        return jsonify({'items': items}), 200
    except Exception as e:
        if 'no such table' in str(e).lower() or 'no such column' in str(e).lower():
            return jsonify({'items': []}), 200
        return jsonify({'error': f'Failed to fetch library: {str(e)}'}), 500

@app.route('/tracks/<int:track_id>/loops', methods=['GET'])
@jwt_required()
def get_loops(track_id):
    conn = sqlite3.connect(USERS_DB)
    conn.row_factory = sqlite3.Row
    cursor = conn.cursor()
    
    cursor.execute('''
        SELECT l.*, u.username 
        FROM loop_points l 
        JOIN users u ON l.user_id = u.id 
        WHERE l.track_id = ? 
        ORDER BY l.created_at DESC
    ''', (track_id,))
    
    loops = [dict(row) for row in cursor.fetchall()]
    conn.close()
    return jsonify({'loops': loops}), 200

@app.route('/tracks/<int:track_id>/loops', methods=['POST'])
@jwt_required()
def create_loop(track_id):
    current_user_id = get_jwt_identity()
    data = request.get_json()
    
    start_time = data.get('start_time')
    end_time = data.get('end_time')
    label = data.get('label', 'Loop')
    
    if start_time is None or end_time is None:
        return jsonify({'error': 'Missing start/end time'}), 400
        
    conn = sqlite3.connect(USERS_DB)
    cursor = conn.cursor()
    
    try:
        cursor.execute('''
            INSERT INTO loop_points (track_id, user_id, start_time, end_time, label)
            VALUES (?, ?, ?, ?, ?)
        ''', (track_id, current_user_id, start_time, end_time, label))
        conn.commit()
        loop_id = cursor.lastrowid
        conn.close()
        return jsonify({'id': loop_id, 'message': 'Loop created'}), 201
    except Exception as e:
        conn.close()
        return jsonify({'error': str(e)}), 500

@app.route('/items/<int:item_id>', methods=['PUT'])
@admin_required()
def update_item(item_id):
    data = request.get_json()
    
    conn = sqlite3.connect(LIBRARY_DB)
    cursor = conn.cursor()
    
    # Get current path
    cursor.execute("SELECT path FROM items WHERE id = ?", (item_id,))
    result = cursor.fetchone()
    
    if not result:
        conn.close()
        return jsonify({'error': 'Item not found'}), 404
        
    path = result[0]
    if isinstance(path, bytes):
        path = path.decode('utf-8', errors='ignore')
        
    # Update beets database
    fields = []
    values = []
    if 'title' in data:
        fields.append("title = ?")
        values.append(data['title'])
    if 'artist' in data:
        fields.append("artist = ?")
        values.append(data['artist'])
    if 'album' in data:
        fields.append("album = ?")
        values.append(data['album'])
        
    if not fields:
        conn.close()
        return jsonify({'message': 'No changes'}), 200
        
    values.append(item_id)
    cursor.execute(f"UPDATE items SET {', '.join(fields)} WHERE id = ?", values)
    conn.commit()
    conn.close()
    
    # Update file metadata using mediafile
    try:
        f = mediafile.MediaFile(path)
        if 'title' in data: f.title = data['title']
        if 'artist' in data: f.artist = data['artist']
        if 'album' in data: f.album = data['album']
        f.save()
    except Exception as e:
        logger.warning("Metadata write error: %s", e)
        # Non-fatal, DB is updated
        
    return jsonify({'message': 'Item updated'}), 200

@app.route('/loops/<int:loop_id>', methods=['DELETE'])
@jwt_required()
def delete_loop(loop_id):
    current_user_id = get_jwt_identity()
    conn = sqlite3.connect(USERS_DB)
    cursor = conn.cursor()
    
    # Check ownership
    cursor.execute('SELECT user_id FROM loop_points WHERE id = ?', (loop_id,))
    row = cursor.fetchone()
    if not row:
        conn.close()
        return jsonify({'error': 'Loop not found'}), 404
        
    cursor.execute("SELECT role FROM users WHERE id = ?", (current_user_id,))
    role_row = cursor.fetchone()
    user_role = role_row[0] if role_row else 'user'
    if row[0] != current_user_id and user_role != 'admin':
        conn.close()
        return jsonify({'error': 'Unauthorized'}), 403

    cursor.execute('DELETE FROM loop_points WHERE id = ?', (loop_id,))
    conn.commit()
    conn.close()
    return jsonify({'message': 'Loop deleted'}), 200

@app.route('/tracks/<int:track_id>/lyrics', methods=['GET'])
@jwt_required()
def get_lyrics(track_id):
    conn = sqlite3.connect(USERS_DB)
    conn.row_factory = sqlite3.Row
    cursor = conn.cursor()
    
    current_user_id = get_jwt_identity()

    # Get user's own lyrics AND any public lyrics
    cursor.execute('''
        SELECT l.*, u.username,
               CASE WHEN l.user_id = ? THEN 1 ELSE 0 END as is_mine
        FROM lyrics l 
        JOIN users u ON l.user_id = u.id 
        WHERE l.track_id = ? AND (l.visibility = 'public' OR l.user_id = ?)
        ORDER BY l.updated_at DESC
    ''', (current_user_id, track_id, current_user_id))
    
    lyrics = [dict(row) for row in cursor.fetchall()]
    conn.close()
    return jsonify({'lyrics': lyrics}), 200

@app.route('/tracks/<int:track_id>/lyrics', methods=['POST'])
@jwt_required()
def save_lyrics(track_id):
    current_user_id = get_jwt_identity()
    data = request.get_json()
    
    content = data.get('content')
    visibility = data.get('visibility', 'private')
    
    if content is None:
        return jsonify({'error': 'Missing content'}), 400
        
    conn = sqlite3.connect(USERS_DB)
    cursor = conn.cursor()
    
    try:
        # Check if user already has lyrics for this track
        cursor.execute('SELECT id FROM lyrics WHERE track_id = ? AND user_id = ?', (track_id, current_user_id))
        existing = cursor.fetchone()
        
        if existing:
            cursor.execute('''
                UPDATE lyrics 
                SET content = ?, visibility = ?, updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
            ''', (content, visibility, existing[0]))
            lid = existing[0]
        else:
            cursor.execute('''
                INSERT INTO lyrics (track_id, user_id, content, visibility)
                VALUES (?, ?, ?, ?)
            ''', (track_id, current_user_id, content, visibility))
            lid = cursor.lastrowid
            
        conn.commit()
        conn.close()
        return jsonify({'id': lid, 'message': 'Lyrics saved'}), 200 # Using 200 for upsert mostly
    except Exception as e:
        conn.close()
        return jsonify({'error': str(e)}), 500

@app.route('/tracks/<int:track_id>/comments', methods=['GET'])
@jwt_required()
def get_comments(track_id):
    conn = sqlite3.connect(USERS_DB)
    conn.row_factory = sqlite3.Row
    cursor = conn.cursor()
    
    cursor.execute('''
        SELECT c.*, u.username 
        FROM comments c 
        JOIN users u ON c.user_id = u.id 
        WHERE c.track_id = ? 
        ORDER BY c.created_at DESC
    ''', (track_id,))
    
    comments = [dict(row) for row in cursor.fetchall()]
    conn.close()
    return jsonify({'comments': comments}), 200

@app.route('/tracks/<int:track_id>/comments', methods=['POST'])
@jwt_required()
def create_comment(track_id):
    current_user_id = get_jwt_identity()
    data = request.get_json()
    
    content = data.get('content')
    
    if not content:
        return jsonify({'error': 'Missing content'}), 400
        
    conn = sqlite3.connect(USERS_DB)
    cursor = conn.cursor()
    
    try:
        cursor.execute('''
            INSERT INTO comments (track_id, user_id, content)
            VALUES (?, ?, ?)
        ''', (track_id, current_user_id, content))
        conn.commit()
        cid = cursor.lastrowid
        conn.close()
        return jsonify({'id': cid, 'message': 'Comment added'}), 201
    except Exception as e:
        conn.close()
        return jsonify({'error': str(e)}), 500

@app.route('/comments/<int:comment_id>', methods=['DELETE'])
@jwt_required()
def delete_comment(comment_id):
    current_user_id = get_jwt_identity()
    conn = sqlite3.connect(USERS_DB)
    cursor = conn.cursor()
    
    # Check ownership
    cursor.execute('SELECT user_id FROM comments WHERE id = ?', (comment_id,))
    row = cursor.fetchone()
    if not row:
        conn.close()
        return jsonify({'error': 'Comment not found'}), 404
        
    cursor.execute("SELECT role FROM users WHERE id = ?", (current_user_id,))
    role_row = cursor.fetchone()
    user_role = role_row[0] if role_row else 'user'
    if row[0] != current_user_id and user_role != 'admin':
        conn.close()
        return jsonify({'error': 'Unauthorized'}), 403

    cursor.execute('DELETE FROM comments WHERE id = ?', (comment_id,))
    conn.commit()
    conn.close()
    return jsonify({'message': 'Comment deleted'}), 200

def _ensure_users_db():
    """Create users.db and tables if missing (e.g. first run or old image)."""
    from init_db import init_db
    init_db()


@app.route('/auth/register', methods=['POST'])
def register():
    data = request.get_json(force=True, silent=True) or {}
    username = data.get('username')
    password = data.get('password')
    invite_code = data.get('invite_code')

    if not username or not password or not invite_code:
        return jsonify({'error': 'Missing required fields'}), 400

    conn = sqlite3.connect(USERS_DB)
    cursor = conn.cursor()

    # If users table is missing (old image or first deploy), create it now
    try:
        cursor.execute('SELECT 1 FROM users LIMIT 1')
    except sqlite3.OperationalError:
        conn.close()
        _ensure_users_db()
        conn = sqlite3.connect(USERS_DB)
        cursor = conn.cursor()

    # Check invite code (for now, simplistic check or check against used codes if we table them)
    # Since we don't have an invites table yet, we'll just check if the code exists on a user who has it? 
    # Or simpler: Admin generates a code, we store it in a list? 
    # Actually, simpler model for now: Check if code exists in users table as a 'valid_invite' entry? 
    # No, let's just make it so Admin can generate a code and we store it in a simple 'invites' table.
    # But to keep it simple for now, we'll verify against a hardcoded secret OR a database table later.
    # User requested: "Invite-only registration (you generate invite codes)"
    
    # For Phase 1 MVP, let's assume we create a table for invites now.
    # Dynamic table creation if not exists
    cursor.execute('CREATE TABLE IF NOT EXISTS invites (code TEXT PRIMARY KEY, created_by INTEGER, used_by INTEGER, used_at TIMESTAMP, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)')
    
    # Simple migration: Add used_at column if it doesn't exist (for existing tables)
    try:
        cursor.execute("ALTER TABLE invites ADD COLUMN used_at TIMESTAMP")
    except sqlite3.OperationalError:
        pass # Column likely exists
    
    cursor.execute('SELECT * FROM invites WHERE LOWER(code) = LOWER(?) AND used_by IS NULL', (invite_code,))
    invite = cursor.fetchone()
    
    if not invite and invite_code.lower() != 'whatupdoe' and invite_code.lower() != 'bombest-admin-2025': # Community or Admin
        conn.close()
        return jsonify({'error': 'Invalid or used invite code'}), 400

    # Check username
    cursor.execute('SELECT id FROM users WHERE username = ?', (username,))
    if cursor.fetchone():
        conn.close()
        return jsonify({'error': 'Username already taken'}), 400

    # Hash password
    salt = bcrypt.gensalt()
    hashed = bcrypt.hashpw(password.encode('utf-8'), salt)

    # Determine role
    role = 'user'
    if invite_code.lower() == 'bombest-admin-2025':
        role = 'admin'

    try:
        cursor.execute(
            'INSERT INTO users (username, password_hash, invite_code, role) VALUES (?, ?, ?, ?)',
            (username, hashed.decode('utf-8'), invite_code, role)
        )
        new_user_id = cursor.lastrowid
        
        # Mark invite as used
        cursor.execute('UPDATE invites SET used_by = ?, used_at = CURRENT_TIMESTAMP WHERE code = ?', (new_user_id, invite_code))
            
        conn.commit()
        
        # Create token
        access_token = create_access_token(identity=str(new_user_id), additional_claims={'role': role, 'username': username})
        
        return jsonify({
            'message': 'Registration successful',
            'access_token': access_token,
            'user': {'id': new_user_id, 'username': username, 'role': role}
        }), 201
        
    except Exception as e:
        traceback.print_exc()
        return jsonify({'error': str(e)}), 500
    finally:
        conn.close()

@app.route('/auth/login', methods=['GET', 'POST'])
def login():
    if request.method == 'GET':
        return jsonify({
            'message': 'Login requires POST with {"username","password"}. Use the web app or API client.',
            'endpoint': '/auth/login',
            'methods': ['POST']
        }), 405
    data = request.get_json()
    username = data.get('username')
    password = data.get('password')

    if not username or not password:
        return jsonify({'error': 'Missing username or password'}), 400

    conn = sqlite3.connect(USERS_DB)
    cursor = conn.cursor()
    
    cursor.execute('SELECT id, password_hash, role FROM users WHERE username = ?', (username,))
    user = cursor.fetchone()
    conn.row_factory = sqlite3.Row # Added to allow dict-like access
    cursor = conn.cursor()
    
    cursor.execute('SELECT id, password_hash, role, username FROM users WHERE username = ?', (username,)) # Added username to select
    user = cursor.fetchone()
    conn.close()

    if not user or not bcrypt.checkpw(password.encode('utf-8'), user['password_hash'].encode('utf-8') if isinstance(user['password_hash'], str) else user['password_hash']):
        return jsonify({'error': 'Invalid credentials'}), 401

    access_token = create_access_token(identity=str(user['id']), additional_claims={'role': user['role'], 'username': user['username']})
    
    return jsonify({
        'access_token': access_token,
        'user': {'id': user['id'], 'username': user['username'], 'role': user['role']}
    }), 200

@app.route('/auth/me', methods=['GET'])
@jwt_required()
def me():
    current_user_id = get_jwt_identity()
    conn = sqlite3.connect(USERS_DB)
    conn.row_factory = sqlite3.Row
    cursor = conn.cursor()
    
    cursor.execute("SELECT id, username, role FROM users WHERE id = ?", (current_user_id,))
    user = cursor.fetchone()
    conn.close()
    
    if user:
        return jsonify(dict(user)), 200
    return jsonify({'error': 'User not found'}), 404

# ==================== PASSKEY / WEBAUTHN ====================
from webauthn import (
    generate_registration_options,
    verify_registration_response,
    generate_authentication_options,
    verify_authentication_response,
    options_to_json,
)
from webauthn.helpers.structs import (
    AuthenticatorSelectionCriteria,
    UserVerificationRequirement,
    ResidentKeyRequirement,
    PublicKeyCredentialDescriptor,
)
from webauthn.helpers import bytes_to_base64url, base64url_to_bytes

RP_ID = "beats.bom.best"
RP_NAME = "bombest beats"
RP_ORIGIN_WEB = "https://beats.bom.best"
# Android origin: android:apk-key-hash:<base64url of SHA256 fingerprint>
# SHA256: 2C:A4:5B:A8:27:2C:C9:57:F9:AC:0D:DA:85:D5:F1:CF:D9:DF:F8:49:34:C8:58:52:4B:C4:34:5B:30:99:36:E8
# Convert hex to bytes then base64url
_android_sha256_hex = "2CA45BA8272CC957F9AC0DDA85D5F1CFD9DFF84934C858524BC4345B309936E8"
_android_sha256_bytes = bytes.fromhex(_android_sha256_hex)
RP_ORIGIN_ANDROID = "android:apk-key-hash:" + base64.urlsafe_b64encode(_android_sha256_bytes).decode().rstrip('=')
RP_ORIGINS = [RP_ORIGIN_WEB, RP_ORIGIN_ANDROID]

def _get_rp_id_and_origin():
    """Derive rp_id and origin from request so passkeys work from localhost or production."""
    origin = request.origin or request.headers.get('Referer') or ''
    if not origin or origin == 'null':
        return RP_ID, RP_ORIGINS
    try:
        parsed = urlparse(origin)
        host = (parsed.hostname or '').lower() or RP_ID
        if host in ('localhost', '127.0.0.1'):
            rp_id = 'localhost'
            request_origin = origin.rstrip('/')
            origins = [request_origin] + [o for o in RP_ORIGINS if o != request_origin]
            return rp_id, origins
        # Production or EC2 host
        rp_id = host
        request_origin = f"{parsed.scheme or 'https'}://{host}" + (f":{parsed.port}" if parsed.port and parsed.port not in (80, 443) else '')
        if request_origin not in RP_ORIGINS:
            origins = [request_origin] + list(RP_ORIGINS)
        else:
            origins = list(RP_ORIGINS)
        return rp_id, origins
    except Exception:
        return RP_ID, RP_ORIGINS

# Store challenges temporarily (in production, use Redis or similar)
# Values: { key: {'challenge': bytes, 'rp_id': str, 'origins': list} }
passkey_challenges = {}

def init_passkey_table():
    """Create passkey_credentials table if not exists"""
    conn = sqlite3.connect(USERS_DB)
    cursor = conn.cursor()
    cursor.execute('''
        CREATE TABLE IF NOT EXISTS passkey_credentials (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            user_id INTEGER NOT NULL,
            credential_id TEXT NOT NULL UNIQUE,
            public_key TEXT NOT NULL,
            sign_count INTEGER DEFAULT 0,
            created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
            FOREIGN KEY (user_id) REFERENCES users(id)
        )
    ''')
    conn.commit()
    conn.close()

init_passkey_table()

@app.route('/auth/passkey/register/options', methods=['POST'])
@jwt_required()
def passkey_register_options():
    """Generate options for registering a new passkey"""
    current_user_id = get_jwt_identity()
    
    conn = sqlite3.connect(USERS_DB)
    conn.row_factory = sqlite3.Row
    cursor = conn.cursor()
    cursor.execute("SELECT id, username FROM users WHERE id = ?", (current_user_id,))
    user = cursor.fetchone()
    
    if not user:
        conn.close()
        return jsonify({'error': 'User not found'}), 404
    
    # Get existing credentials to exclude
    cursor.execute("SELECT credential_id FROM passkey_credentials WHERE user_id = ?", (current_user_id,))
    existing_creds = cursor.fetchall()
    conn.close()
    
    exclude_credentials = [
        PublicKeyCredentialDescriptor(id=base64url_to_bytes(row['credential_id']))
        for row in existing_creds
    ]
    
    rp_id, origins = _get_rp_id_and_origin()
    options = generate_registration_options(
        rp_id=rp_id,
        rp_name=RP_NAME,
        user_id=str(user['id']).encode(),
        user_name=user['username'],
        user_display_name=user['username'],
        exclude_credentials=exclude_credentials,
        authenticator_selection=AuthenticatorSelectionCriteria(
            resident_key=ResidentKeyRequirement.PREFERRED,
            user_verification=UserVerificationRequirement.PREFERRED,
        ),
    )
    
    # Store challenge and rp context for verification
    passkey_challenges[str(user['id'])] = {'challenge': options.challenge, 'rp_id': rp_id, 'origins': origins}
    
    return options_to_json(options)

@app.route('/auth/passkey/register/verify', methods=['POST'])
@jwt_required()
def passkey_register_verify():
    """Verify and store the new passkey credential"""
    current_user_id = get_jwt_identity()
    data = request.get_json()
    
    stored = passkey_challenges.get(current_user_id)
    if not stored:
        return jsonify({'error': 'No pending registration'}), 400
    challenge = stored.get('challenge') if isinstance(stored, dict) else stored
    rp_id = stored.get('rp_id', RP_ID) if isinstance(stored, dict) else RP_ID
    origins = stored.get('origins', RP_ORIGINS) if isinstance(stored, dict) else RP_ORIGINS
    
    try:
        verification = verify_registration_response(
            credential=data,
            expected_challenge=challenge,
            expected_rp_id=rp_id,
            expected_origin=origins,
        )
        
        # Store the credential
        conn = sqlite3.connect(USERS_DB)
        cursor = conn.cursor()
        cursor.execute('''
            INSERT INTO passkey_credentials (user_id, credential_id, public_key, sign_count)
            VALUES (?, ?, ?, ?)
        ''', (
            current_user_id,
            bytes_to_base64url(verification.credential_id),
            bytes_to_base64url(verification.credential_public_key),
            verification.sign_count,
        ))
        conn.commit()
        conn.close()
        
        # Clean up challenge
        del passkey_challenges[current_user_id]
        
        return jsonify({'success': True, 'message': 'Passkey registered successfully'})
    except Exception as e:
        if current_user_id in passkey_challenges:
            del passkey_challenges[current_user_id]
        err_msg = str(e)
        if 'already registered' in err_msg.lower() or 'credentials already' in err_msg.lower():
            err_msg = 'This passkey is already registered to your account. Try a different device or passkey, or remove the existing one first.'
        return jsonify({'error': err_msg}), 400

@app.route('/auth/passkey/login/options', methods=['POST'])
def passkey_login_options():
    """Generate options for passkey login"""
    data = request.get_json() or {}
    username = data.get('username')
    
    conn = sqlite3.connect(USERS_DB)
    conn.row_factory = sqlite3.Row
    cursor = conn.cursor()
    
    if username:
        # User specified - get their credentials
        cursor.execute("SELECT id FROM users WHERE username = ?", (username,))
        user = cursor.fetchone()
        if not user:
            conn.close()
            return jsonify({'error': 'User not found'}), 404
        cursor.execute("SELECT credential_id FROM passkey_credentials WHERE user_id = ?", (user['id'],))
    else:
        # Discoverable credential flow - allow any stored credential
        cursor.execute("SELECT credential_id FROM passkey_credentials")
    
    creds = cursor.fetchall()
    conn.close()
    
    if not creds:
        return jsonify({'error': 'No passkeys registered'}), 404
    
    allow_credentials = [
        PublicKeyCredentialDescriptor(id=base64url_to_bytes(row['credential_id']))
        for row in creds
    ]
    
    rp_id, origins = _get_rp_id_and_origin()
    options = generate_authentication_options(
        rp_id=rp_id,
        allow_credentials=allow_credentials if username else None,  # None = discoverable
        user_verification=UserVerificationRequirement.PREFERRED,
    )
    
    # Store challenge and rp context (keyed by a random ID for login)
    import secrets
    login_id = secrets.token_urlsafe(16)
    passkey_challenges[login_id] = {'challenge': options.challenge, 'rp_id': rp_id, 'origins': origins}
    
    response = json.loads(options_to_json(options))
    response['loginId'] = login_id
    return jsonify(response)

@app.route('/auth/passkey/login/verify', methods=['POST'])
def passkey_login_verify():
    """Verify passkey and issue JWT"""
    data = request.get_json()
    login_id = data.get('loginId')
    
    stored = passkey_challenges.get(login_id)
    if not stored:
        return jsonify({'error': 'No pending login'}), 400
    challenge = stored.get('challenge') if isinstance(stored, dict) else stored
    rp_id = stored.get('rp_id', RP_ID) if isinstance(stored, dict) else RP_ID
    origins = stored.get('origins', RP_ORIGINS) if isinstance(stored, dict) else RP_ORIGINS
    
    # Find the credential
    credential_id = data.get('id')
    if not credential_id:
        return jsonify({'error': 'Missing credential ID'}), 400
    
    conn = sqlite3.connect(USERS_DB)
    conn.row_factory = sqlite3.Row
    cursor = conn.cursor()
    cursor.execute('''
        SELECT pc.*, u.username, u.role 
        FROM passkey_credentials pc 
        JOIN users u ON pc.user_id = u.id 
        WHERE pc.credential_id = ?
    ''', (credential_id,))
    cred = cursor.fetchone()
    
    if not cred:
        conn.close()
        return jsonify({'error': 'Unknown credential'}), 401
    
    try:
        verification = verify_authentication_response(
            credential=data,
            expected_challenge=challenge,
            expected_rp_id=rp_id,
            expected_origin=origins,
            credential_public_key=base64url_to_bytes(cred['public_key']),
            credential_current_sign_count=cred['sign_count'],
        )
        
        # Update sign count
        cursor.execute(
            "UPDATE passkey_credentials SET sign_count = ? WHERE credential_id = ?",
            (verification.new_sign_count, credential_id)
        )
        conn.commit()
        conn.close()
        
        # Clean up challenge
        del passkey_challenges[login_id]
        
        # Issue JWT
        access_token = create_access_token(
            identity=str(cred['user_id']),
            additional_claims={'role': cred['role'], 'username': cred['username']}
        )
        
        return jsonify({
            'access_token': access_token,
            'user': {'id': cred['user_id'], 'username': cred['username'], 'role': cred['role']}
        })
    except Exception as e:
        conn.close()
        if login_id in passkey_challenges:
            del passkey_challenges[login_id]
        return jsonify({'error': str(e)}), 401

@app.route('/auth/passkey/list', methods=['GET'])
@jwt_required()
def passkey_list():
    """List user's registered passkeys"""
    current_user_id = get_jwt_identity()
    
    conn = sqlite3.connect(USERS_DB)
    conn.row_factory = sqlite3.Row
    cursor = conn.cursor()
    cursor.execute(
        "SELECT id, credential_id, created_at FROM passkey_credentials WHERE user_id = ?",
        (current_user_id,)
    )
    creds = cursor.fetchall()
    conn.close()
    
    return jsonify([dict(c) for c in creds])

@app.route('/auth/passkey/delete/<int:passkey_id>', methods=['DELETE'])
@jwt_required()
def passkey_delete(passkey_id):
    """Delete a passkey"""
    current_user_id = get_jwt_identity()
    
    conn = sqlite3.connect(USERS_DB)
    cursor = conn.cursor()
    cursor.execute(
        "DELETE FROM passkey_credentials WHERE id = ? AND user_id = ?",
        (passkey_id, current_user_id)
    )
    conn.commit()
    deleted = cursor.rowcount > 0
    conn.close()
    
    if deleted:
        return jsonify({'success': True})
    return jsonify({'error': 'Passkey not found'}), 404

@app.route('/auth/passkeys', methods=['GET'])
@jwt_required()
def list_passkeys():
    """List all passkeys for the current user"""
    current_user_id = get_jwt_identity()
    
    conn = sqlite3.connect(USERS_DB)
    conn.row_factory = sqlite3.Row
    cursor = conn.cursor()
    cursor.execute(
        "SELECT id, credential_id, created_at FROM passkey_credentials WHERE user_id = ?",
        (current_user_id,)
    )
    passkeys = [dict(row) for row in cursor.fetchall()]
    conn.close()
    
    return jsonify(passkeys)

@app.route('/auth/change-password', methods=['POST'])
@jwt_required()
def change_password():
    """Change the current user's password"""
    current_user_id = get_jwt_identity()
    data = request.get_json()
    
    current_password = data.get('current_password')
    new_password = data.get('new_password')
    
    if not current_password or not new_password:
        return jsonify({'error': 'Current and new password required'}), 400
    
    if len(new_password) < 6:
        return jsonify({'error': 'Password must be at least 6 characters'}), 400
    
    conn = sqlite3.connect(USERS_DB)
    cursor = conn.cursor()
    cursor.execute("SELECT password_hash FROM users WHERE id = ?", (current_user_id,))
    row = cursor.fetchone()
    
    if not row:
        conn.close()
        return jsonify({'error': 'User not found'}), 404
    
    # Verify current password
    if not check_password_hash(row[0], current_password):
        conn.close()
        return jsonify({'error': 'Current password is incorrect'}), 400
    
    # Update password
    new_hash = generate_password_hash(new_password)
    cursor.execute("UPDATE users SET password_hash = ? WHERE id = ?", (new_hash, current_user_id))
    conn.commit()
    conn.close()
    
    return jsonify({'success': True, 'message': 'Password changed successfully'})

# ==================== END PASSKEY ====================


@app.route('/auth/invite', methods=['POST'])
@jwt_required()
def create_invite():
    # Verify admin role (simplified check)
    from flask_jwt_extended import get_jwt
    claims = get_jwt()
    if claims.get('role') != 'admin':
        return jsonify({'error': 'Admin privileges required'}), 403
        
    import secrets
    code = secrets.token_urlsafe(8)
    
    conn = sqlite3.connect(USERS_DB)
    cursor = conn.cursor()
    
    # Ensure invites table exists
    cursor.execute('CREATE TABLE IF NOT EXISTS invites (code TEXT PRIMARY KEY, created_by INTEGER, used_by INTEGER, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)')
    
    try:
        cursor.execute('INSERT INTO invites (code, created_by) VALUES (?, ?)', (code, get_jwt_identity()))
        conn.commit()
        return jsonify({'code': code}), 201
    finally:
        conn.close()

@app.route('/.well-known/assetlinks.json')
def assetlinks():
    """Serve Android Asset Links for Passkey verification"""
    # Use the calculated hash from above
    fingerprint = _android_sha256_hex # "2CA45BA8..."
    # Format as colon-separated octets for assetlinks
    formatted_fingerprint = ":".join(fingerprint[i:i+2] for i in range(0, len(fingerprint), 2))
    
    return jsonify([{
        "relation": ["delegate_permission/common.handle_all_urls"],
        "target": {
            "namespace": "android_app",
            "package_name": "com.bombest.music",
            "sha256_cert_fingerprints": [formatted_fingerprint]
        }
    }])

@app.route('/.well-known/apple-app-site-association')
def apple_association():
    """Serve iOS Apple App Site Association for Passkey/WebCredentials"""
    return jsonify({
        "webcredentials": {
            "apps": ["8C4A8V568P.best.bom.beats"]
        }
    })



# --- Playlist Routes ---

@app.route('/playlists', methods=['GET'])
@jwt_required(optional=True)
def get_playlists():
    """List playlists: public/system for everyone; logged-in users also see their private playlists; admin sees all."""
    try:
        jwt_uid = get_jwt_identity()
        jwt_role = _jwt_role()
        conn = sqlite3.connect(USERS_DB)
        ensure_playlist_schema(conn)
        cursor = conn.cursor()
        # Ensure art_path column exists
        cursor.execute("PRAGMA table_info(playlists)")
        columns = [col[1] for col in cursor.fetchall()]
        if 'art_path' not in columns:
            cursor.execute("ALTER TABLE playlists ADD COLUMN art_path TEXT")
            conn.commit()
        if 'is_system' not in columns:
            cursor.execute("ALTER TABLE playlists ADD COLUMN is_system INTEGER DEFAULT 0")
            conn.commit()
        if 'share_token' not in columns:
            cursor.execute("ALTER TABLE playlists ADD COLUMN share_token TEXT")
            cursor.execute(
                "CREATE UNIQUE INDEX IF NOT EXISTS idx_playlists_share_token "
                "ON playlists(share_token) WHERE share_token IS NOT NULL"
            )
            conn.commit()

        if jwt_role == 'admin':
            cursor.execute(
                "SELECT id, name, created_at, is_system, art_path, share_token, user_id, IFNULL(is_public,0) "
                "FROM playlists ORDER BY created_at DESC"
            )
        elif jwt_uid is not None:
            cursor.execute(
                "SELECT id, name, created_at, is_system, art_path, share_token, user_id, IFNULL(is_public,0) "
                "FROM playlists WHERE IFNULL(is_public,0) = 1 OR IFNULL(is_system,0) = 1 OR user_id = ? "
                "ORDER BY created_at DESC",
                (int(jwt_uid),),
            )
        else:
            cursor.execute(
                "SELECT id, name, created_at, is_system, art_path, share_token, user_id, IFNULL(is_public,0) "
                "FROM playlists WHERE IFNULL(is_public,0) = 1 OR IFNULL(is_system,0) = 1 "
                "ORDER BY created_at DESC"
            )
            params = ()

        playlists = []
        for row in cursor.fetchall():
            pl = {
                'id': row[0],
                'name': row[1],
                'created_at': row[2],
                'is_system': bool(row[3]),
                'user_id': row[6],
                'is_public': bool(row[7]),
            }
            art_path = row[4] if len(row) > 4 else None
            pl['art_url'] = f"/playlists/{pl['id']}/art" if art_path else None
            pl['share_token'] = row[5] if len(row) > 5 else None
            playlists.append(pl)
        for pl in playlists:
            cursor.execute("SELECT COUNT(*) FROM playlist_tracks WHERE playlist_id = ?", (pl['id'],))
            pl['count'] = cursor.fetchone()[0]
        conn.close()
        return jsonify({'playlists': playlists})
    except Exception as e:
        return jsonify({'error': str(e)}), 500

@app.route('/playlists', methods=['POST'])
@jwt_required()
def create_playlist():
    """Create a playlist. Non-admin: private, owned by JWT user. Admin: optional is_public catalog (user_id NULL)."""
    try:
        uid = int(get_jwt_identity())
        role = _jwt_role() or 'user'
        data = request.get_json()
        name = data.get('name')
        is_public = bool(data.get('is_public', False))
        if not name:
            return jsonify({'error': 'Name is required'}), 400
        if role != 'admin':
            is_public = False

        conn = sqlite3.connect(USERS_DB)
        ensure_playlist_schema(conn)
        cursor = conn.cursor()
        cursor.execute("PRAGMA table_info(playlists)")
        columns = [col[1] for col in cursor.fetchall()]
        if 'is_public' not in columns:
            cursor.execute("ALTER TABLE playlists ADD COLUMN is_public INTEGER DEFAULT 0")
            conn.commit()

        if role == 'admin' and is_public:
            cursor.execute("INSERT INTO playlists (name, is_public, user_id) VALUES (?, 1, NULL)", (name,))
            ret_uid = None
            out_public = True
        else:
            cursor.execute("INSERT INTO playlists (name, is_public, user_id) VALUES (?, 0, ?)", (name, uid))
            ret_uid = uid
            out_public = False
        playlist_id = cursor.lastrowid
        conn.commit()
        conn.close()

        return jsonify({
            'id': playlist_id,
            'name': name,
            'count': 0,
            'is_public': out_public,
            'user_id': ret_uid,
        }), 201
    except Exception as e:
        return jsonify({'error': str(e)}), 500

@app.route('/playlists/<int:playlist_id>', methods=['PUT'])
@jwt_required()
def update_playlist(playlist_id):
    """Rename a playlist (owner or admin)."""
    try:
        data = request.get_json()
        name = data.get('name')
        if not name:
            return jsonify({'error': 'Name is required'}), 400

        conn = sqlite3.connect(USERS_DB)
        ensure_playlist_schema(conn)
        cursor = conn.cursor()
        pl = get_playlist_row_dict(cursor, playlist_id)
        if not pl:
            conn.close()
            return jsonify({'error': 'Not found'}), 404
        uid = get_jwt_identity()
        role = _jwt_role()
        if not playlist_editable_by_user(pl, uid, role):
            conn.close()
            return jsonify({'error': 'Forbidden'}), 403

        cursor.execute("UPDATE playlists SET name = ? WHERE id = ?", (name, playlist_id))
        conn.commit()
        conn.close()

        return jsonify({'success': True})
    except Exception as e:
        return jsonify({'error': str(e)}), 500

@app.route('/playlists/<int:playlist_id>', methods=['DELETE'])
@jwt_required()
def delete_playlist(playlist_id):
    """Delete a playlist (owner or admin)."""
    try:
        conn = sqlite3.connect(USERS_DB)
        ensure_playlist_schema(conn)
        cursor = conn.cursor()
        pl = get_playlist_row_dict(cursor, playlist_id)
        if not pl:
            conn.close()
            return jsonify({'error': 'Not found'}), 404
        uid = get_jwt_identity()
        role = _jwt_role()
        if not playlist_editable_by_user(pl, uid, role):
            conn.close()
            return jsonify({'error': 'Forbidden'}), 403

        cursor.execute("DELETE FROM playlist_tracks WHERE playlist_id = ?", (playlist_id,))
        cursor.execute("DELETE FROM playlists WHERE id = ?", (playlist_id,))
        conn.commit()
        conn.close()

        return jsonify({'success': True})
    except Exception as e:
        return jsonify({'error': str(e)}), 500

SHARE_BASE_URL = os.environ.get('SHARE_BASE_URL', 'https://bom.best')
API_PUBLIC_URL = os.environ.get('API_PUBLIC_URL', 'https://beats.bom.best')

@app.route('/playlists/<int:playlist_id>/share', methods=['POST'])
@jwt_required()
def share_playlist(playlist_id):
    """Generate a share token for a playlist. Returns share_url for sharing with others."""
    try:
        conn = sqlite3.connect(USERS_DB)
        ensure_playlist_schema(conn)
        cursor = conn.cursor()
        pl = get_playlist_row_dict(cursor, playlist_id)
        if not pl:
            conn.close()
            return jsonify({'error': 'Playlist not found'}), 404
        uid = get_jwt_identity()
        role = _jwt_role()
        if not playlist_editable_by_user(pl, uid, role):
            conn.close()
            return jsonify({'error': 'Forbidden'}), 403
        cursor.execute("PRAGMA table_info(playlists)")
        columns = [col[1] for col in cursor.fetchall()]
        if 'share_token' not in columns:
            cursor.execute("ALTER TABLE playlists ADD COLUMN share_token TEXT")
            cursor.execute(
                "CREATE UNIQUE INDEX IF NOT EXISTS idx_playlists_share_token "
                "ON playlists(share_token) WHERE share_token IS NOT NULL"
            )
            conn.commit()
        cursor.execute("SELECT share_token FROM playlists WHERE id = ?", (playlist_id,))
        row = cursor.fetchone()
        if not row:
            conn.close()
            return jsonify({'error': 'Playlist not found'}), 404
        share_token = row[0]
        if not share_token:
            share_token = str(uuid.uuid4())
            cursor.execute("UPDATE playlists SET share_token = ? WHERE id = ?", (share_token, playlist_id))
            conn.commit()
        conn.close()
        share_url = f"{SHARE_BASE_URL}/beats/playlist/{share_token}"
        return jsonify({'share_token': share_token, 'share_url': share_url})
    except Exception as e:
        return jsonify({'error': str(e)}), 500

@app.route('/playlists/<int:playlist_id>/share', methods=['DELETE'])
@jwt_required()
def unshare_playlist(playlist_id):
    """Revoke sharing for a playlist."""
    try:
        conn = sqlite3.connect(USERS_DB)
        ensure_playlist_schema(conn)
        cursor = conn.cursor()
        pl = get_playlist_row_dict(cursor, playlist_id)
        if not pl:
            conn.close()
            return jsonify({'error': 'Not found'}), 404
        uid = get_jwt_identity()
        role = _jwt_role()
        if not playlist_editable_by_user(pl, uid, role):
            conn.close()
            return jsonify({'error': 'Forbidden'}), 403
        cursor.execute("UPDATE playlists SET share_token = NULL WHERE id = ?", (playlist_id,))
        conn.commit()
        conn.close()
        return jsonify({'message': 'Sharing disabled'})
    except Exception as e:
        return jsonify({'error': str(e)}), 500

@app.route('/shared/<token>', methods=['GET'])
def get_shared_playlist(token):
    """Get playlist by share token. No auth required."""
    try:
        conn = sqlite3.connect(USERS_DB)
        cursor = conn.cursor()
        cursor.execute("PRAGMA table_info(playlists)")
        columns = [col[1] for col in cursor.fetchall()]
        if 'share_token' not in columns:
            conn.close()
            return jsonify({'error': 'Not found'}), 404
        cursor.execute("SELECT id, name, art_path FROM playlists WHERE share_token = ?", (token,))
        row = cursor.fetchone()
        if not row:
            conn.close()
            return jsonify({'error': 'Not found'}), 404
        playlist_id, name, art_path = row[0], row[1], row[2]
        cursor.execute("SELECT track_id FROM playlist_tracks WHERE playlist_id = ? ORDER BY position ASC", (playlist_id,))
        track_ids = [r[0] for r in cursor.fetchall()]
        conn.close()
        if not track_ids:
            art_url = f"{API_PUBLIC_URL}/playlists/{playlist_id}/art" if art_path else None
            return jsonify({'name': name, 'art_url': art_url, 'tracks': []})
        lib_conn = sqlite3.connect(LIBRARY_DB)
        lib_conn.row_factory = sqlite3.Row
        lib_cursor = lib_conn.cursor()
        placeholders = ','.join('?' for _ in track_ids)
        lib_cursor.execute(f"SELECT * FROM items WHERE id IN ({placeholders})", track_ids)
        rows = lib_cursor.fetchall()
        lib_conn.close()
        tracks_map = {r['id']: dict(r) for r in rows}
        ordered_tracks = [tracks_map[tid] for tid in track_ids if tid in tracks_map]

        def decode_val(val):
            if isinstance(val, bytes):
                return val.decode('utf-8', errors='ignore')
            return val

        base_url = API_PUBLIC_URL or request.host_url.rstrip('/')
        tracks = []
        for track in ordered_tracks:
            tid = track['id']
            tracks.append({
                'id': tid,
                'title': decode_val(track.get('title') or track.get('title_sort', 'Unknown')),
                'artist': decode_val(track.get('artist') or track.get('artist_sort', 'Unknown Artist')),
                'album': decode_val(track.get('album') or track.get('album_sort')),
                'duration': track.get('length', 0.0),
                'length': track.get('length', 0.0),
                'path': decode_val(track.get('path', '')),
                'stream_url': f"{base_url}/stream/{tid}",
                'art_url': f"{base_url}/track/{tid}/art"
            })
        art_url = f"{base_url}/playlists/{playlist_id}/art" if art_path else None
        return jsonify({'name': name, 'art_url': art_url, 'tracks': tracks})
    except Exception as e:
        return jsonify({'error': str(e)}), 500

@app.route('/shared/<token>/art', methods=['GET'])
def get_shared_playlist_art(token):
    """Serve playlist art by share token."""
    try:
        conn = sqlite3.connect(USERS_DB)
        cursor = conn.cursor()
        cursor.execute("SELECT id FROM playlists WHERE share_token = ?", (token,))
        row = cursor.fetchone()
        conn.close()
        if not row:
            return '', 404
        return get_playlist_art(row[0])
    except Exception:
        return '', 404

ALLOWED_IMAGE_EXTENSIONS = {'jpg', 'jpeg', 'png', 'gif', 'webp'}

def allowed_image_file(filename):
    return '.' in filename and filename.rsplit('.', 1)[1].lower() in ALLOWED_IMAGE_EXTENSIONS

@app.route('/playlists/<int:playlist_id>/art', methods=['GET'])
@jwt_required(optional=True)
def get_playlist_art(playlist_id):
    """Serve playlist cover image if set"""
    try:
        conn = sqlite3.connect(USERS_DB)
        ensure_playlist_schema(conn)
        cursor = conn.cursor()
        pl = get_playlist_row_dict(cursor, playlist_id)
        jwt_uid = get_jwt_identity()
        jwt_role = _jwt_role()
        if not pl or not playlist_visible_to_user(pl, jwt_uid, jwt_role):
            conn.close()
            return '', 404
        cursor.execute("PRAGMA table_info(playlists)")
        columns = [col[1] for col in cursor.fetchall()]
        if 'art_path' not in columns:
            conn.close()
            return '', 404
        cursor.execute("SELECT art_path FROM playlists WHERE id = ?", (playlist_id,))
        row = cursor.fetchone()
        conn.close()
        if not row or not row[0]:
            return '', 404
        art_path = row[0]
        if not os.path.isabs(art_path):
            art_path = os.path.join(os.getcwd(), art_path)
        if not os.path.isfile(art_path):
            return '', 404
        return send_file(art_path, mimetype=mimetypes.guess_type(art_path)[0] or 'image/jpeg')
    except Exception as e:
        logger.warning("Error serving playlist art: %s", e)
        return '', 404

@app.route('/playlists/<int:playlist_id>/art', methods=['PUT', 'POST'])
@jwt_required()
def set_playlist_art(playlist_id):
    """Upload playlist cover image (multipart form, file key 'image' or first file)"""
    try:
        conn = sqlite3.connect(USERS_DB)
        ensure_playlist_schema(conn)
        cursor = conn.cursor()
        pl = get_playlist_row_dict(cursor, playlist_id)
        if not pl:
            conn.close()
            return jsonify({'error': 'Not found'}), 404
        uid = get_jwt_identity()
        role = _jwt_role()
        if not playlist_editable_by_user(pl, uid, role):
            conn.close()
            return jsonify({'error': 'Forbidden'}), 403
        conn.close()
        if 'file' not in request.files and 'image' not in request.files:
            # Some clients send as first key
            files = list(request.files.keys())
            if not files:
                return jsonify({'error': 'No file provided'}), 400
            file = request.files[files[0]]
        else:
            file = request.files.get('file') or request.files.get('image')
        if not file or file.filename == '':
            return jsonify({'error': 'No file selected'}), 400
        if not allowed_image_file(file.filename):
            return jsonify({'error': 'Invalid image type'}), 400
        ext = file.filename.rsplit('.', 1)[1].lower()
        if ext == 'jpeg':
            ext = 'jpg'
        save_name = f"{playlist_id}.{ext}"
        save_path = os.path.join(PLAYLIST_ART_FOLDER, save_name)
        file.save(save_path)
        # Store relative path for portability
        rel_path = os.path.join('music', 'playlist_art', save_name)
        conn = sqlite3.connect(USERS_DB)
        cursor = conn.cursor()
        cursor.execute("PRAGMA table_info(playlists)")
        columns = [col[1] for col in cursor.fetchall()]
        if 'art_path' not in columns:
            cursor.execute("ALTER TABLE playlists ADD COLUMN art_path TEXT")
            conn.commit()
        cursor.execute("UPDATE playlists SET art_path = ? WHERE id = ?", (rel_path, playlist_id))
        conn.commit()
        conn.close()
        return jsonify({'success': True, 'art_url': f"/playlists/{playlist_id}/art"})
    except Exception as e:
        logger.warning("Error setting playlist art: %s", e)
        return jsonify({'error': str(e)}), 500

@app.route('/playlists/<int:playlist_id>/tracks', methods=['GET'])
@jwt_required(optional=True)
def get_playlist_tracks(playlist_id):
    """Get tracks for a playlist with full metadata"""
    try:
        conn = sqlite3.connect(USERS_DB)
        ensure_playlist_schema(conn)
        cursor = conn.cursor()
        pl = get_playlist_row_dict(cursor, playlist_id)
        jwt_uid = get_jwt_identity()
        jwt_role = _jwt_role()
        if not pl or not playlist_visible_to_user(pl, jwt_uid, jwt_role):
            conn.close()
            return jsonify({'error': 'Not found'}), 404

        cursor.execute("SELECT track_id FROM playlist_tracks WHERE playlist_id = ? ORDER BY position ASC", (playlist_id,))
        track_ids = [row[0] for row in cursor.fetchall()]
        conn.close()

        if not track_ids:
            return jsonify({'tracks': []})

        # Get track metadata from library DB
        lib_conn = sqlite3.connect(LIBRARY_DB)
        lib_conn.row_factory = sqlite3.Row
        lib_cursor = lib_conn.cursor()
        
        placeholders = ','.join('?' for _ in track_ids)
        # We need to preserve order, so we fetch all and sort in python or use CASE/WHEN (complex for sqlite)
        lib_cursor.execute(f"SELECT * FROM items WHERE id IN ({placeholders})", track_ids)
        rows = lib_cursor.fetchall()
        lib_conn.close()
        
        # Convert to list of dicts and reorder
        tracks_map = {row['id']: dict(row) for row in rows}
        ordered_tracks = [tracks_map[tid] for tid in track_ids if tid in tracks_map]
        
        # Format tracks for Android API (expects 'tracks' field, not 'items')
        formatted_tracks = []
        for track in ordered_tracks:
            # Helper to decode bytes to string
            def decode_val(val):
                if isinstance(val, bytes):
                    return val.decode('utf-8', errors='ignore')
                return val

            formatted_tracks.append({
                'id': track['id'],
                'title': decode_val(track.get('title') or track.get('title_sort', 'Unknown')),
                'artist': decode_val(track.get('artist') or track.get('artist_sort', 'Unknown Artist')),
                'album': decode_val(track.get('album') or track.get('album_sort')),
                'duration': track.get('length', 0.0),
                'path': decode_val(track.get('path', ''))
            })
        
        return jsonify({'tracks': formatted_tracks})
    except Exception as e:
        logger.warning("Error fetching playlist tracks: %s", e)
        return jsonify({'error': str(e)}), 500

@app.route('/playlists/<int:playlist_id>/tracks', methods=['POST'])
@jwt_required()
def add_tracks_to_playlist(playlist_id):
    """Add tracks to playlist"""
    try:
        data = request.get_json()
        track_ids = data.get('track_ids', [])

        conn = sqlite3.connect(USERS_DB)
        ensure_playlist_schema(conn)
        cursor = conn.cursor()
        pl = get_playlist_row_dict(cursor, playlist_id)
        if not pl:
            conn.close()
            return jsonify({'error': 'Not found'}), 404
        uid = get_jwt_identity()
        role = _jwt_role()
        if not playlist_editable_by_user(pl, uid, role):
            conn.close()
            return jsonify({'error': 'Forbidden'}), 403

        # Get current max position
        cursor.execute("SELECT MAX(position) FROM playlist_tracks WHERE playlist_id = ?", (playlist_id,))
        result = cursor.fetchone()
        start_pos = (result[0] or 0) + 1
        
        for i, tid in enumerate(track_ids):
            try:
                cursor.execute(
                    "INSERT INTO playlist_tracks (playlist_id, track_id, position) VALUES (?, ?, ?)", 
                    (playlist_id, tid, start_pos + i)
                )
            except sqlite3.IntegrityError:
                pass # Ignore duplicates if unique constraint hit (though primary key is composite)
                
        conn.commit()
        conn.close()
        
        return jsonify({'success': True})
    except Exception as e:
        return jsonify({'error': str(e)}), 500

@app.route('/playlists/<int:playlist_id>/tracks', methods=['DELETE'])
@jwt_required()
def remove_tracks_from_playlist(playlist_id):
    """Remove tracks from playlist"""
    try:
        data = request.get_json()
        track_ids = data.get('track_ids', [])

        conn = sqlite3.connect(USERS_DB)
        ensure_playlist_schema(conn)
        cursor = conn.cursor()
        pl = get_playlist_row_dict(cursor, playlist_id)
        if not pl:
            conn.close()
            return jsonify({'error': 'Not found'}), 404
        uid = get_jwt_identity()
        role = _jwt_role()
        if not playlist_editable_by_user(pl, uid, role):
            conn.close()
            return jsonify({'error': 'Forbidden'}), 403

        placeholders = ','.join('?' for _ in track_ids)
        cursor.execute(f"DELETE FROM playlist_tracks WHERE playlist_id = ? AND track_id IN ({placeholders})", 
                      (playlist_id, *track_ids))
                      
        conn.commit()
        conn.close()
        
        return jsonify({'success': True})
    except Exception as e:
        return jsonify({'error': str(e)}), 500

@app.route('/playlists/system/init', methods=['POST'])
@admin_required()
def initialize_system_playlists():
    """Initialize All Songs and Favorites system playlists"""
    try:
        conn = sqlite3.connect(USERS_DB)
        cursor = conn.cursor()
        
        # Create All Songs playlist if it doesn't exist
        cursor.execute("SELECT id FROM playlists WHERE name = 'All Songs' AND is_system = 1")
        all_songs_exists = cursor.fetchone()
        
        if not all_songs_exists:
            cursor.execute("""
                INSERT INTO playlists (name, is_system, is_synced, sort_mode, description)
                VALUES ('All Songs', 1, 0, 'custom', 'Contains every track in your library')
            """)
            all_songs_id = cursor.lastrowid
            
            # Populate with all tracks from library
            lib_conn = sqlite3.connect(LIBRARY_DB)
            lib_cursor = lib_conn.cursor()
            lib_cursor.execute("SELECT id FROM items ORDER BY added DESC")
            track_ids = [row[0] for row in lib_cursor.fetchall()]
            lib_conn.close()
            
            for pos, track_id in enumerate(track_ids):
                cursor.execute("""
                    INSERT INTO playlist_tracks (playlist_id, track_id, position)
                    VALUES (?, ?, ?)
                """, (all_songs_id, track_id, pos))
        else:
            all_songs_id = all_songs_exists[0]
        
        # Create Favorites playlist if it doesn't exist
        cursor.execute("SELECT id FROM playlists WHERE name = 'Favorites' AND is_system = 1")
        favorites_exists = cursor.fetchone()
        
        if not favorites_exists:
            cursor.execute("""
                INSERT INTO playlists (name, is_system, is_synced, sort_mode, description)
                VALUES ('Favorites', 1, 1, 'custom', 'Your favorite tracks')
            """)
            favorites_id = cursor.lastrowid
        else:
            favorites_id = favorites_exists[0]
        
        conn.commit()
        conn.close()
        
        return jsonify({
            'success': True,
            'all_songs_id': all_songs_id,
            'favorites_id': favorites_id
        })
    except Exception as e:
        logger.warning("Error initializing system playlists: %s", e)
        return jsonify({'error': str(e)}), 500

@app.route('/playlists/<int:playlist_id>/reorder', methods=['PUT'])
@jwt_required()
def reorder_playlist_tracks(playlist_id):
    """Reorder tracks in playlist"""
    try:
        data = request.get_json()
        track_ids = data.get('track_ids', [])  # New ordered list

        conn = sqlite3.connect(USERS_DB)
        ensure_playlist_schema(conn)
        cursor = conn.cursor()
        pl = get_playlist_row_dict(cursor, playlist_id)
        if not pl:
            conn.close()
            return jsonify({'error': 'Not found'}), 404
        uid = get_jwt_identity()
        role = _jwt_role()
        if not playlist_editable_by_user(pl, uid, role):
            conn.close()
            return jsonify({'error': 'Forbidden'}), 403

        # Delete all existing positions
        cursor.execute("DELETE FROM playlist_tracks WHERE playlist_id = ?", (playlist_id,))
        
        # Insert with new positions
        for pos, track_id in enumerate(track_ids):
            cursor.execute("""
                INSERT INTO playlist_tracks (playlist_id, track_id, position)
                VALUES (?, ?, ?)
            """, (playlist_id, track_id, pos))
        
        conn.commit()
        conn.close()
        
        return jsonify({'success': True, 'count': len(track_ids)})
    except Exception as e:
        logger.warning("Error reordering playlist: %s", e)
        return jsonify({'error': str(e)}), 500

@app.route('/playlists/<int:playlist_id>/search', methods=['GET'])
@jwt_required(optional=True)
def search_playlist(playlist_id):
    """Search within playlist"""
    try:
        query = request.args.get('q', '').lower()

        if not query:
            return jsonify({'tracks': []})

        conn = sqlite3.connect(USERS_DB)
        ensure_playlist_schema(conn)
        cursor = conn.cursor()
        pl = get_playlist_row_dict(cursor, playlist_id)
        jwt_uid = get_jwt_identity()
        jwt_role = _jwt_role()
        if not pl or not playlist_visible_to_user(pl, jwt_uid, jwt_role):
            conn.close()
            return jsonify({'error': 'Not found'}), 404

        # Get track IDs from playlist
        cursor.execute("SELECT track_id FROM playlist_tracks WHERE playlist_id = ? ORDER BY position", (playlist_id,))
        track_ids = [row[0] for row in cursor.fetchall()]
        conn.close()
        
        if not track_ids:
            return jsonify({'tracks': []})
        
        # Search in library
        lib_conn = sqlite3.connect(LIBRARY_DB)
        lib_conn.row_factory = sqlite3.Row
        lib_cursor = lib_conn.cursor()
        
        placeholders = ','.join('?' for _ in track_ids)
        lib_cursor.execute(f"""
            SELECT * FROM items 
            WHERE id IN ({placeholders})
            AND (LOWER(title) LIKE ? OR LOWER(artist) LIKE ? OR LOWER(album) LIKE ?)
        """, (*track_ids, f'%{query}%', f'%{query}%', f'%{query}%'))
        
        rows = lib_cursor.fetchall()
        lib_conn.close()
        
        # Format tracks and decode bytes
        formatted_tracks = []
        for row in rows:
            track = dict(row)
            def decode_val(val):
                if isinstance(val, bytes):
                    return val.decode('utf-8', errors='ignore')
                return val

            formatted_tracks.append({
                'id': track['id'],
                'title': decode_val(track.get('title') or track.get('title_sort', 'Unknown')),
                'artist': decode_val(track.get('artist') or track.get('artist_sort', 'Unknown Artist')),
                'album': decode_val(track.get('album') or track.get('album_sort')),
                'duration': track.get('length', 0.0),
                'path': decode_val(track.get('path', ''))
            })
        
        return jsonify({'tracks': formatted_tracks})
    except Exception as e:
        logger.warning("Error searching playlist: %s", e)
        return jsonify({'error': str(e)}), 500

# Formats that need transcoding to AAC for mobile playback.
# MP3 and AAC/M4A already have hardware offload support — serve as-is.
_TRANSCODE_EXTS = {'.wav', '.flac', '.ogg', '.aiff', '.aif'}

# Max source size for on-the-fly transcode. Guards /tmp when the source lives in S3
# and must be downloaded locally so ffmpeg can seek (FLAC header parsing needs seek).
MAX_TRANSCODE_SOURCE_BYTES = int(os.environ.get('MAX_TRANSCODE_SOURCE_BYTES', 200 * 1024 * 1024))


def _should_transcode(file_path, fmt):
    ext = os.path.splitext(file_path)[1].lower()
    # Always transcode non-AAC/MP3 formats so all clients get fragmented MP4.
    # Explicit ?format=aac also triggers it, but the default for WAV/FLAC/OGG
    # is to transcode rather than serving raw files that most players can't stream.
    if ext in _TRANSCODE_EXTS:
        return True
    return fmt == 'aac' and ext in _TRANSCODE_EXTS


def _transcode_generator(file_path, bitrate='256k'):
    """Yield fragmented MP4 (AAC-LC) chunks by piping file_path through ffmpeg.

    Fragmented MP4 rather than raw ADTS because:
    - The 'empty_moov' atom lets ExoPlayer know the codec and duration immediately,
      so Android Auto can display a progress bar and album art without waiting for EOF.
    - 'frag_keyframe' writes a fragment boundary at every keyframe so the stream is
      playable from any fragment without seeking the whole file.
    - ExoPlayer correctly reports isCurrentMediaItemSeekable=true for frag MP4,
      so onPlayerError can safely seek to resume position on transient errors.
    """
    cmd = [
        'ffmpeg', '-y', '-i', file_path,
        '-vn',
        '-c:a', 'aac',
        '-b:a', bitrate,
        '-movflags', 'frag_keyframe+empty_moov+faststart',
        '-f', 'mp4',
        'pipe:1'
    ]
    # Capture stderr to a tempfile so we can surface transcode errors for triage.
    # A PIPE would deadlock because we read stdout incrementally and never drain stderr.
    stderr_fd, stderr_path = tempfile.mkstemp(prefix='ffmpeg_stderr_', suffix='.log')
    proc = None
    try:
        proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=stderr_fd)
        os.close(stderr_fd)
        stderr_fd = -1
        while True:
            chunk = proc.stdout.read(65536)
            if not chunk:
                break
            yield chunk
    finally:
        if proc is not None:
            # Client may have disconnected mid-stream — don't let ffmpeg hang on a closed pipe.
            if proc.poll() is None:
                proc.terminate()
                try:
                    proc.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    proc.kill()
                    proc.wait()
            if proc.returncode not in (0, -15):  # 0 = OK, -15 = SIGTERM from our kill path
                try:
                    with open(stderr_path, 'r', errors='replace') as f:
                        tail = f.read()[-2000:]
                    logger.warning("ffmpeg exit %s for %s: %s", proc.returncode, file_path, tail)
                except OSError:
                    pass
        if stderr_fd >= 0:
            try:
                os.close(stderr_fd)
            except OSError:
                pass
        try:
            os.unlink(stderr_path)
        except OSError:
            pass


@app.route('/stream/<int:track_id>', methods=['GET'])
def stream_track(track_id):
    """Stream audio file directly from upload server to bypass beets CORS issues"""
    try:
        conn = sqlite3.connect(LIBRARY_DB)
        cursor = conn.cursor()
        cursor.execute("SELECT path FROM items WHERE id = ?", (track_id,))
        result = cursor.fetchone()
        conn.close()

        if not result:
            return jsonify({'error': 'Track not found'}), 404

        file_path = result[0]
        if isinstance(file_path, bytes):
            file_path = file_path.decode('utf-8', errors='ignore')

        # ?format=aac&bitrate=256 — requested by Android client for hardware-offload-compatible
        # streaming. WAV/FLAC/OGG are transcoded on-the-fly; MP3/AAC pass through unchanged.
        fmt = request.args.get('format', '').lower()
        raw_bitrate = request.args.get('bitrate', '256')
        try:
            bitrate = f"{int(raw_bitrate)}k"
        except ValueError:
            bitrate = '256k'

        # --- Local Strategy (preferred — avoids S3 download for transcode) ---
        if os.path.exists(file_path):
            if _should_transcode(file_path, fmt):
                return Response(
                    _transcode_generator(file_path, bitrate),
                    mimetype='audio/mp4',
                    headers={'Accept-Ranges': 'none'}
                )
            mime_type, _ = mimetypes.guess_type(file_path)
            if file_path.lower().endswith('.wav') and (not mime_type or mime_type == 'application/octet-stream'):
                mime_type = 'audio/x-wav'
            return send_file(file_path, mimetype=mime_type, conditional=True)

        # --- S3 Fallback (file not on local disk) ---
        if s3_client:
            try:
                rel_path = os.path.relpath(file_path, start=os.getcwd())
                if not rel_path.startswith('..'):
                    if _should_transcode(rel_path, fmt):
                        # Cap source size before downloading — a 500 MB FLAC would fill /tmp.
                        # head_object is cheap and lets us 413 cleanly rather than failing mid-download.
                        try:
                            head = s3_client.head_object(Bucket=S3_BUCKET, Key=rel_path)
                            content_length = int(head.get('ContentLength') or 0)
                        except ClientError as e:
                            logger.warning("S3 head_object error (transcode): %s", e)
                            return jsonify({'error': 'S3 error'}), 502
                        if content_length > MAX_TRANSCODE_SOURCE_BYTES:
                            return jsonify({
                                'error': 'Source file too large to transcode',
                                'limit_bytes': MAX_TRANSCODE_SOURCE_BYTES,
                            }), 413

                        # Download to temp file so ffmpeg can seek (needed for FLAC header parsing)
                        ext = os.path.splitext(rel_path)[1] or '.wav'
                        tmp_fd, tmp_path = tempfile.mkstemp(suffix=ext)
                        os.close(tmp_fd)
                        try:
                            resp = s3_client.get_object(Bucket=S3_BUCKET, Key=rel_path)
                            with open(tmp_path, 'wb') as f:
                                shutil.copyfileobj(resp['Body'], f)
                        except ClientError as e:
                            os.unlink(tmp_path)
                            logger.warning("S3 get_object error (transcode): %s", e)
                            return jsonify({'error': 'S3 error'}), 502

                        def generate_s3_transcode():
                            try:
                                yield from _transcode_generator(tmp_path, bitrate)
                            finally:
                                try:
                                    os.unlink(tmp_path)
                                except OSError:
                                    pass

                        return Response(
                            generate_s3_transcode(),
                            mimetype='audio/mp4',
                            headers={'Accept-Ranges': 'none'}
                        )
                    else:
                        # Original format — pass through with range support
                        range_header = request.headers.get('Range')
                        params = {'Bucket': S3_BUCKET, 'Key': rel_path}
                        if range_header:
                            params['Range'] = range_header
                        try:
                            resp = s3_client.get_object(**params)
                            body = resp['Body']
                            content_type = resp.get('ContentType') or mimetypes.guess_type(rel_path)[0] or 'application/octet-stream'
                            if rel_path.lower().endswith('.wav'):
                                content_type = 'audio/x-wav'
                            status = resp.get('ResponseMetadata', {}).get('HTTPStatusCode', 200)
                            headers = {}
                            if 'ContentLength' in resp:
                                headers['Content-Length'] = str(resp['ContentLength'])
                            if 'ContentRange' in resp:
                                headers['Content-Range'] = resp['ContentRange']
                            return Response(body, status=status, mimetype=content_type, headers=headers, direct_passthrough=True)
                        except ClientError as e:
                            logger.warning("S3 get_object error: %s", e)
            except (ValueError, OSError):
                pass

        return jsonify({'error': 'File not found'}), 404

    except Exception as e:
        return jsonify({'error': str(e)}), 500

# --- Artwork Routes ---

@app.route('/album/<int:album_id>/art', methods=['GET'])
def get_album_art(album_id):
    """Serve album art directly to bypass beets server"""
    try:
        conn = sqlite3.connect(LIBRARY_DB)
        cursor = conn.cursor()
        cursor.execute("SELECT artpath FROM albums WHERE id = ?", (album_id,))
        result = cursor.fetchone()
        conn.close()

        if not result or not result[0]:
            return jsonify({'error': 'No artwork found'}), 404

        art_path = result[0]
        if isinstance(art_path, bytes):
            art_path = art_path.decode('utf-8', errors='ignore')

        if not os.path.exists(art_path):
             return jsonify({'error': 'Artwork file not found'}), 404

        return send_file(art_path)
    except Exception as e:
        logger.warning("Art error: %s", e)
        return jsonify({'error': str(e)}), 500

@app.route('/track/<int:track_id>/art', methods=['GET'])
def get_track_art(track_id):
    """Serve track artwork - track_artwork first, then album, embedded, default. ?path=... serves a specific track_artwork path."""
    try:
        requested_path = request.args.get('path')
        if requested_path:
            try:
                conn = sqlite3.connect(LIBRARY_DB)
                cursor = conn.cursor()
                cursor.execute(
                    "SELECT file_path FROM track_artwork WHERE track_id = ? AND file_path = ? AND art_type = 'cover'",
                    (track_id, requested_path)
                )
                row = cursor.fetchone()
                conn.close()
                if row and row[0]:
                    art_path = row[0] if isinstance(row[0], str) else row[0].decode('utf-8', errors='ignore')
                    if not os.path.isabs(art_path):
                        art_path = os.path.join(os.getcwd(), art_path)
                    if os.path.exists(art_path):
                        return send_file(art_path, mimetype=mimetypes.guess_type(art_path)[0] or 'image/jpeg')
            except sqlite3.OperationalError:
                pass
            return jsonify({'error': 'Art not found'}), 404

        conn = sqlite3.connect(LIBRARY_DB)
        cursor = conn.cursor()
        cursor.execute("SELECT path, album_id FROM items WHERE id = ?", (track_id,))
        result = cursor.fetchone()
        conn.close()

        if not result:
            return jsonify({'error': 'Track not found'}), 404

        file_path, album_id = result
        if isinstance(file_path, bytes):
            file_path = file_path.decode('utf-8', errors='ignore')

        # 1. Try track_artwork (per-track cover from folder upload)
        try:
            conn = sqlite3.connect(LIBRARY_DB)
            cursor = conn.cursor()
            cursor.execute(
                "SELECT file_path FROM track_artwork WHERE track_id = ? AND art_type = 'cover' ORDER BY is_primary DESC, id ASC LIMIT 1",
                (track_id,)
            )
            art_row = cursor.fetchone()
            conn.close()
            if art_row and art_row[0]:
                art_path = art_row[0] if isinstance(art_row[0], str) else art_row[0].decode('utf-8', errors='ignore')
                if not os.path.isabs(art_path):
                    art_path = os.path.join(os.getcwd(), art_path)
                if os.path.exists(art_path):
                    return send_file(art_path, mimetype=mimetypes.guess_type(art_path)[0] or 'image/jpeg')
        except sqlite3.OperationalError:
            pass  # track_artwork table may not exist yet

        # 2. Try album art if album_id exists
        if album_id:
            conn = sqlite3.connect(LIBRARY_DB)
            cursor = conn.cursor()
            cursor.execute("SELECT artpath FROM albums WHERE id = ?", (album_id,))
            art_result = cursor.fetchone()
            conn.close()
            if art_result and art_result[0]:
                art_path = art_result[0]
                if isinstance(art_path, bytes):
                    art_path = art_path.decode('utf-8', errors='ignore')
                if os.path.exists(art_path):
                    return send_file(art_path)

        # Try to extract embedded artwork from audio file
        if os.path.exists(file_path):
            try:
                from mutagen import File as MutagenFile
                from mutagen.mp3 import MP3
                from mutagen.id3 import ID3
                from io import BytesIO
                
                audio = MutagenFile(file_path)
                artwork_data = None
                
                # Check for ID3 tags (MP3)
                if hasattr(audio, 'tags') and audio.tags:
                    for key in audio.tags.keys():
                        if key.startswith('APIC'):
                            artwork_data = audio.tags[key].data
                            break
                
                # Check for FLAC/OGG pictures
                if not artwork_data and hasattr(audio, 'pictures') and audio.pictures:
                    artwork_data = audio.pictures[0].data
                
                # Check for MP4/M4A cover
                if not artwork_data and hasattr(audio, 'tags') and audio.tags:
                    if 'covr' in audio.tags:
                        artwork_data = bytes(audio.tags['covr'][0])
                
                if artwork_data:
                    return send_file(BytesIO(artwork_data), mimetype='image/jpeg')
            except Exception as extract_error:
                logger.warning("Artwork extraction error: %s", extract_error)

        # Return default artwork
        default_art = os.path.join(os.path.dirname(__file__), 'static', 'no-image.png')
        if os.path.exists(default_art):
            return send_file(default_art)
        
        return jsonify({'error': 'No artwork found'}), 404
    except Exception as e:
        logger.warning("Track art error: %s", e)
        return jsonify({'error': str(e)}), 500


@app.route('/track/<int:track_id>/canvas', methods=['GET'])
def get_track_canvas(track_id):
    """Serve track canvas (GIF or video) for fullscreen display like Spotify Canvas"""
    try:
        conn = sqlite3.connect(LIBRARY_DB)
        cursor = conn.cursor()
        cursor.execute(
            "SELECT file_path FROM track_artwork WHERE track_id = ? AND art_type = 'canvas' LIMIT 1",
            (track_id,)
        )
        row = cursor.fetchone()
        conn.close()
        if not row or not row[0]:
            return jsonify({'error': 'No canvas found'}), 404
        art_path = row[0] if isinstance(row[0], str) else row[0].decode('utf-8', errors='ignore')
        if not os.path.isabs(art_path):
            art_path = os.path.join(os.getcwd(), art_path)
        if not os.path.exists(art_path):
            return jsonify({'error': 'Canvas file not found'}), 404
        mime = mimetypes.guess_type(art_path)[0] or 'image/gif'
        resp = send_file(art_path, mimetype=mime, conditional=True)
        canvas_type = 'video' if mime.startswith('video/') else 'gif'
        resp.headers['X-Canvas-Type'] = canvas_type
        return resp
    except sqlite3.OperationalError:
        return jsonify({'error': 'No canvas found'}), 404
    except Exception as e:
        logger.warning("Canvas error: %s", e)
        return jsonify({'error': str(e)}), 500


@app.route('/notify-interest', methods=['POST'])
@jwt_required()
def notify_interest():
    try:
        current_user_id = get_jwt_identity()
        data = request.get_json()
        track_id = data.get('track_id')
        
        if not track_id:
            return jsonify({'error': 'Missing track ID'}), 400

        # Get user details
        conn = sqlite3.connect(USERS_DB)
        cursor = conn.cursor()
        cursor.execute("SELECT username FROM users WHERE id = ?", (current_user_id,))
        user_result = cursor.fetchone()
        conn.close()
        
        username = user_result[0] if user_result else "Unknown User"
        
        # Get track details
        conn = sqlite3.connect(LIBRARY_DB)
        cursor = conn.cursor()
        cursor.execute("SELECT title, artist FROM items WHERE id = ?", (track_id,))
        track_result = cursor.fetchone()
        conn.close()
        
        if not track_result:
            return jsonify({'error': 'Track not found'}), 404
            
        track_title = track_result[0]
        track_artist = track_result[1]

        # Load config
        with open('config.yaml', 'r') as f:
            config = yaml.safe_load(f)
            
        email_config = config.get('email', {})
        if not email_config:
             return jsonify({'error': 'Email configuration missing'}), 500
        # Send Email Logic (Optimized for SMS/MMS gateways if needed, or just standard SMTP)
        # For now, this endpoint handles the Email notification side if used.
        # Front-end uses direct SMS link now, so this might be redundant but keeping for fallback.
        subject = f"Interest: {track_title}"
        body = f"User {username} is interested in {track_title} by {track_artist}.\nTrack ID: {track_id}"
        
        msg = MIMEMultipart()
        msg['From'] = config['email']['sender_email']
        msg['To'] = config['email']['recipient_email']
        msg['Subject'] = subject
        msg.attach(MIMEText(body, 'plain'))
        
        server = smtplib.SMTP(config['email']['smtp_server'], config['email']['smtp_port'])
        server.starttls()
        server.login(config['email']['sender_email'], config['email']['password'])
        server.send_message(msg)
        server.quit()
        
        return jsonify({'message': 'Notification sent'}), 200
        
    except Exception as e:
        logger.warning("Notify error: %s", e)
        return jsonify({'error': str(e)}), 500

# --- Favorites & Roles ---

@app.route('/favorites', methods=['GET', 'POST', 'DELETE'])
@jwt_required()
def favorites():
    user_id = get_jwt_identity()
    conn = sqlite3.connect(USERS_DB)
    cursor = conn.cursor()
    
    # Ensure tables exist (Lazy migration)
    cursor.execute('CREATE TABLE IF NOT EXISTS favorites (user_id INTEGER, track_id INTEGER, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, PRIMARY KEY(user_id, track_id))')
    try:
        cursor.execute("ALTER TABLE users ADD COLUMN role TEXT DEFAULT 'user'")
    except sqlite3.OperationalError:
        pass

    if request.method == 'GET':
        cursor.execute("SELECT track_id FROM favorites WHERE user_id = ?", (user_id,))
        rows = cursor.fetchall()
        favs = [r[0] for r in rows]
        conn.close()
        return jsonify(favs)

    if request.method == 'POST':
        data = request.get_json()
        track_id = data.get('track_id')
        if not track_id:
             conn.close()
             return jsonify({'error': 'No track_id'}), 400
        try:
            cursor.execute("INSERT INTO favorites (user_id, track_id) VALUES (?, ?)", (user_id, track_id))
            conn.commit()
        except sqlite3.IntegrityError:
            pass # Already exists
        conn.close()
        return jsonify({'message': 'Added to favorites'})

    if request.method == 'DELETE':
        track_id = request.args.get('track_id')
        if not track_id:
            conn.close()
            return jsonify({'error': 'No track_id'}), 400
        cursor.execute("DELETE FROM favorites WHERE user_id = ? AND track_id = ?", (user_id, track_id))
        conn.commit()
        conn.close()
        return jsonify({'message': 'Removed from favorites'})

@app.route('/playlists/<int:playlist_id>/favorites/toggle', methods=['POST'])
@jwt_required()
def toggle_favorite(playlist_id):
    """Toggle favorite status for a track and add/remove from Favorites playlist"""
    try:
        user_id = int(get_jwt_identity())  # Get user ID from JWT token
        data = request.get_json()
        track_id = data.get('track_id')
        
        if not track_id:
            return jsonify({'error': 'No track_id provided'}), 400
        
        conn = sqlite3.connect(USERS_DB)
        cursor = conn.cursor()
        
        # Ensure favorites table exists
        cursor.execute('CREATE TABLE IF NOT EXISTS favorites (user_id INTEGER, track_id INTEGER, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, PRIMARY KEY(user_id, track_id))')
        
        # Check if already favorited
        cursor.execute("SELECT 1 FROM favorites WHERE user_id = ? AND track_id = ?", (user_id, track_id))
        is_favorited = cursor.fetchone() is not None
        
        if is_favorited:
            # Remove from favorites
            cursor.execute("DELETE FROM favorites WHERE user_id = ? AND track_id = ?", (user_id, track_id))
            
            # Remove from Favorites playlist
            cursor.execute("""
                SELECT id FROM playlists WHERE name = 'Favorites' AND is_system = 1 LIMIT 1
            """)
            fav_playlist = cursor.fetchone()
            if fav_playlist:
                cursor.execute("""
                    DELETE FROM playlist_tracks 
                    WHERE playlist_id = ? AND track_id = ?
                """, (fav_playlist[0], track_id))
            
            favorited = False
        else:
            # Add to favorites
            cursor.execute("""
                INSERT INTO favorites (user_id, track_id) VALUES (?, ?)
            """, (user_id, track_id))
            
            # Add to Favorites playlist
            cursor.execute("""
                SELECT id FROM playlists WHERE name = 'Favorites' AND is_system = 1 LIMIT 1
            """)
            fav_playlist = cursor.fetchone()
            if fav_playlist:
                cursor.execute("""
                    SELECT MAX(position) FROM playlist_tracks WHERE playlist_id = ?
                """, (fav_playlist[0],))
                max_pos_result = cursor.fetchone()
                max_pos = max_pos_result[0] if max_pos_result and max_pos_result[0] is not None else -1
                cursor.execute("""
                    INSERT INTO playlist_tracks (playlist_id, track_id, position)
                    VALUES (?, ?, ?)
                """, (fav_playlist[0], track_id, max_pos + 1))
            
            favorited = True
        
        conn.commit()
        conn.close()
        
        return jsonify({'success': True, 'favorited': favorited})
    except Exception as e:
        logger.warning("Error toggling favorite: %s", e)
        import traceback
        traceback.print_exc()
        return jsonify({'error': str(e)}), 500


# --- Metrics ---

@app.route('/metrics/play', methods=['POST'])
@jwt_required(optional=True)
def record_play():
    data = request.get_json()
    track_id = data.get('track_id')
    if not track_id:
        return jsonify({'error': 'No track_id'}), 400
        
    user_id = get_jwt_identity() # Might be None if not logged in (optional=True)
    
    conn = sqlite3.connect(USERS_DB)
    cursor = conn.cursor()
    
    # Lazy migration for plays table
    cursor.execute('CREATE TABLE IF NOT EXISTS plays (id INTEGER PRIMARY KEY, track_id INTEGER, user_id INTEGER, played_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)')
    
    cursor.execute('INSERT INTO plays (track_id, user_id) VALUES (?, ?)', (track_id, user_id))
    conn.commit()
    conn.close()
    
    return jsonify({'message': 'Play recorded'}), 200

@app.route('/metrics/batch', methods=['POST'])
@jwt_required()
def batch_record_plays():
    """Batch record play events to reduce server round-trips"""
    try:
        current_user_id = get_jwt_identity()
        data = request.get_json()
        events = data.get('events', [])
        
        if not events:
            return jsonify({'message': 'No events processed'}), 200
            
        conn = sqlite3.connect(USERS_DB)
        cursor = conn.cursor()
        
        # Ensure table exists
        cursor.execute('CREATE TABLE IF NOT EXISTS plays (id INTEGER PRIMARY KEY, track_id INTEGER, user_id INTEGER, played_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)')
        
        count = 0
        for event in events:
            track_id = event.get('track_id')
            timestamp = event.get('timestamp') # Optional, default to now if None
            
            if track_id:
                if timestamp:
                    cursor.execute('INSERT INTO plays (track_id, user_id, played_at) VALUES (?, ?, ?)', (track_id, current_user_id, timestamp))
                else:
                    cursor.execute('INSERT INTO plays (track_id, user_id) VALUES (?, ?)', (track_id, current_user_id))
                count += 1
                
        conn.commit()
        conn.close()
        
        return jsonify({'message': f'Successfully recorded {count} events'}), 201
        
    except Exception as e:
        logger.warning("Batch metrics error: %s", e)
        return jsonify({'error': str(e)}), 500

@app.route('/metrics/dashboard', methods=['GET'])
@admin_required()
def get_dashboard_metrics():
    user_id_filter = request.args.get('user_id')  # Optional filter
    
    conn = sqlite3.connect(USERS_DB)
    cursor = conn.cursor()
    
    # Ensure tables exist
    cursor.execute('CREATE TABLE IF NOT EXISTS plays (id INTEGER PRIMARY KEY, track_id INTEGER, user_id INTEGER, played_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)')

    # Build WHERE clause for user filter
    where_clause = ""
    params = []
    if user_id_filter:
        where_clause = "WHERE user_id = ?"
        params = [int(user_id_filter)]

    # 1. Top Tracks (All Time) - with optional user filter
    cursor.execute(f'''
        SELECT track_id, COUNT(*) as count 
        FROM plays 
        {where_clause}
        GROUP BY track_id 
        ORDER BY count DESC 
        LIMIT 10
    ''', params)
    top_rows = cursor.fetchall()
    
    # Get metadata for these tracks from library.db
    lib_conn = sqlite3.connect(LIBRARY_DB)
    lib_cursor = lib_conn.cursor()
    
    top_tracks = []
    for row in top_rows:
        tid, count = row
        lib_cursor.execute("SELECT title, artist FROM items WHERE id = ?", (tid,))
        meta = lib_cursor.fetchone()
        title = meta[0] if meta else f"Unknown ({tid})"
        artist = meta[1] if meta else "Unknown"
        top_tracks.append({'id': tid, 'title': title, 'artist': artist, 'plays': count})
        
    lib_conn.close()
    
    # 2. Total Plays (Last 7 Days) - with optional user filter
    date_where = "WHERE played_at >= date('now', '-7 days')"
    if user_id_filter:
        date_where += " AND user_id = ?"
        
    cursor.execute(f'''
        SELECT DATE(played_at) as day, COUNT(*) 
        FROM plays 
        {date_where}
        GROUP BY day
        ORDER BY day
    ''', [int(user_id_filter)] if user_id_filter else [])
    daily_plays = [{'date': row[0], 'count': row[1]} for row in cursor.fetchall()]

    # 3. Total Plays (All Time) - with optional user filter
    if user_id_filter:
        cursor.execute("SELECT COUNT(*) FROM plays WHERE user_id = ?", [int(user_id_filter)])
    else:
        cursor.execute("SELECT COUNT(*) FROM plays")
    total_plays = cursor.fetchone()[0]
    
    # 4. Get list of users who have plays (for filter dropdown)
    cursor.execute('''
        SELECT DISTINCT p.user_id, u.username 
        FROM plays p 
        LEFT JOIN users u ON p.user_id = u.id
        ORDER BY u.username
    ''')
    users = [{'id': row[0], 'username': row[1] or f"User {row[0]}"} for row in cursor.fetchall()]
    
    conn.close()
    
    return jsonify({
        'top_tracks': top_tracks,
        'daily_plays': daily_plays,
        'total_plays': total_plays,
        'users': users
    })

# --- Performance Metrics + CloudWatch ---

_cloudwatch_client = None

def _get_cloudwatch():
    global _cloudwatch_client
    if _cloudwatch_client is None:
        _cloudwatch_client = boto3.client(
            'cloudwatch',
            region_name=os.environ.get('AWS_REGION', 'us-west-2')
        )
    return _cloudwatch_client


def _emit_perf_metrics(events):
    """Emit CloudWatch custom metrics for a batch of performance events. Best-effort."""
    try:
        cw = _get_cloudwatch()
        metric_data = []
        for ev in events:
            t = ev.get('event_type', '')
            fmt = ev.get('format') or 'unknown'
            conn = ev.get('connection') or 'unknown'

            if t == 'AudioUnderrun':
                metric_data += [
                    {
                        'MetricName': 'AudioUnderrunCount',
                        'Dimensions': [{'Name': 'Format', 'Value': fmt},
                                       {'Name': 'Connection', 'Value': conn}],
                        'Value': 1, 'Unit': 'Count'
                    },
                    {
                        'MetricName': 'BufferLevelAtUnderrunMs',
                        'Dimensions': [{'Name': 'Format', 'Value': fmt}],
                        'Value': float(ev.get('buffer_ms') or 0), 'Unit': 'Milliseconds'
                    },
                ]
            elif t == 'BandwidthSample':
                kbps = float(ev.get('bitrate_kbps') or 0)
                if kbps > 0:
                    metric_data.append({
                        'MetricName': 'BandwidthKbps',
                        'Dimensions': [{'Name': 'Connection', 'Value': conn}],
                        'Value': kbps, 'Unit': 'Kilobits/Second'
                    })
            elif t == 'LoadError':
                metric_data.append({
                    'MetricName': 'LoadErrorCount',
                    'Dimensions': [{'Name': 'ErrorType', 'Value': ev.get('error_type') or 'Unknown'},
                                   {'Name': 'Format', 'Value': fmt}],
                    'Value': 1, 'Unit': 'Count'
                })
            elif t == 'OffloadState':
                metric_data.append({
                    'MetricName': 'OffloadEngaged',
                    'Dimensions': [{'Name': 'Format', 'Value': fmt}],
                    'Value': 1.0 if ev.get('sleeping') else 0.0, 'Unit': 'Count'
                })

        # CloudWatch max 20 metric data points per call
        for i in range(0, len(metric_data), 20):
            cw.put_metric_data(
                Namespace='BombestBeats/Playback',
                MetricData=metric_data[i:i + 20]
            )
    except Exception as e:
        logger.warning("CloudWatch emit error (non-fatal): %s", e)


def ensure_cloudwatch_dashboard():
    """Create or update the BombestBeats-Playback CloudWatch dashboard. Idempotent."""
    try:
        cw = _get_cloudwatch()
        dashboard_body = json.dumps({
            "widgets": [
                {
                    "type": "metric", "x": 0, "y": 0, "width": 12, "height": 6,
                    "properties": {
                        "title": "Audio Underruns by Format",
                        "metrics": [
                            ["BombestBeats/Playback", "AudioUnderrunCount", "Format", "mp3", "Connection", "wifi"],
                            [".", ".", ".", "mp3", ".", "cellular"],
                            [".", ".", ".", "flac", ".", "wifi"],
                            [".", ".", ".", "flac", ".", "cellular"],
                            [".", ".", ".", "wav", ".", "wifi"],
                            [".", ".", ".", "wav", ".", "cellular"],
                            [".", ".", ".", "aac", ".", "wifi"],
                            [".", ".", ".", "aac", ".", "cellular"],
                        ],
                        "period": 300, "stat": "Sum", "view": "timeSeries",
                        "region": os.environ.get('AWS_REGION', 'us-west-2')
                    }
                },
                {
                    "type": "metric", "x": 12, "y": 0, "width": 12, "height": 6,
                    "properties": {
                        "title": "Bandwidth kbps (WiFi vs Cellular)",
                        "metrics": [
                            ["BombestBeats/Playback", "BandwidthKbps", "Connection", "wifi"],
                            [".", ".", ".", "cellular"],
                        ],
                        "period": 300, "stat": "Average", "view": "timeSeries",
                        "region": os.environ.get('AWS_REGION', 'us-west-2')
                    }
                },
                {
                    "type": "metric", "x": 0, "y": 6, "width": 12, "height": 6,
                    "properties": {
                        "title": "Load Errors by Type",
                        "metrics": [
                            ["BombestBeats/Playback", "LoadErrorCount", "ErrorType", "SocketTimeoutException", "Format", "mp3"],
                            [".", ".", ".", "SocketTimeoutException", ".", "flac"],
                            [".", ".", ".", "HttpDataSourceException", ".", "mp3"],
                            [".", ".", ".", "HttpDataSourceException", ".", "flac"],
                            [".", ".", ".", "UnknownHostException", ".", "mp3"],
                        ],
                        "period": 300, "stat": "Sum", "view": "timeSeries",
                        "region": os.environ.get('AWS_REGION', 'us-west-2')
                    }
                },
                {
                    "type": "metric", "x": 12, "y": 6, "width": 12, "height": 6,
                    "properties": {
                        "title": "Audio Offload Engagement by Format (1=sleeping, 0=CPU)",
                        "metrics": [
                            ["BombestBeats/Playback", "OffloadEngaged", "Format", "mp3"],
                            [".", ".", ".", "flac"],
                            [".", ".", ".", "wav"],
                            [".", ".", ".", "aac"],
                        ],
                        "period": 300, "stat": "Average", "view": "timeSeries",
                        "region": os.environ.get('AWS_REGION', 'us-west-2')
                    }
                },
            ]
        })
        cw.put_dashboard(DashboardName='BombestBeats-Playback', DashboardBody=dashboard_body)
        logger.info("CloudWatch dashboard 'BombestBeats-Playback' created/updated")
    except Exception as e:
        logger.warning("Dashboard setup error (non-fatal): %s", e)


@app.route('/metrics/performance', methods=['POST'])
@jwt_required()
def batch_record_perf():
    """Receive batched playback performance events from the Android app."""
    try:
        user_id = get_jwt_identity()
        data = request.get_json() or {}
        events = data.get('events', [])

        if not events:
            return jsonify({'message': 'No events'}), 200

        conn = sqlite3.connect(USERS_DB)
        cursor = conn.cursor()
        cursor.execute('''
            CREATE TABLE IF NOT EXISTS performance_events (
                id INTEGER PRIMARY KEY,
                event_type TEXT,
                track_id TEXT,
                format TEXT,
                connection TEXT,
                buffer_ms REAL,
                elapsed_since_feed_ms REAL,
                error_type TEXT,
                error_message TEXT,
                bitrate_kbps REAL,
                bytes_loaded INTEGER,
                load_time_ms INTEGER,
                sleeping INTEGER,
                user_id INTEGER,
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            )
        ''')

        for ev in events:
            cursor.execute('''
                INSERT INTO performance_events
                (event_type, track_id, format, connection, buffer_ms, elapsed_since_feed_ms,
                 error_type, error_message, bitrate_kbps, bytes_loaded, load_time_ms, sleeping, user_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ''', (
                ev.get('event_type'), ev.get('track_id'), ev.get('format'), ev.get('connection'),
                ev.get('buffer_ms'), ev.get('elapsed_since_feed_ms'),
                ev.get('error_type'), ev.get('error_message'),
                ev.get('bitrate_kbps'), ev.get('bytes_loaded'), ev.get('load_time_ms'),
                1 if ev.get('sleeping') else (0 if ev.get('sleeping') is not None else None),
                user_id
            ))

        conn.commit()
        conn.close()

        _emit_perf_metrics(events)

        return jsonify({'message': f'Recorded {len(events)} perf events'}), 201

    except Exception as e:
        logger.warning("Performance metrics error: %s", e)
        return jsonify({'error': str(e)}), 500


# Cache S3 reachability for ~60s so external probes (ALB, Docker HEALTHCHECK, Cloudflare)
# don't turn this endpoint into a per-minute S3 billing source. Re-probed sooner on failure
# so recovery is observable promptly.
_S3_HEALTH_TTL_OK = 60.0
_S3_HEALTH_TTL_ERR = 5.0
_s3_health_lock = threading.Lock()
_s3_health_cache = {'ts': 0.0, 'ok': None}  # ok: True/False/None(unchecked)


def _check_s3_health():
    """Return ('ok'|'error'|'skipped', detail_string_or_None). Cached for TTL."""
    if not (s3_client and S3_BUCKET):
        return 'skipped', None
    now = time.time()
    with _s3_health_lock:
        cached_ok = _s3_health_cache['ok']
        ttl = _S3_HEALTH_TTL_OK if cached_ok else _S3_HEALTH_TTL_ERR
        if cached_ok is not None and (now - _s3_health_cache['ts']) < ttl:
            return ('ok' if cached_ok else 'error'), None
    try:
        s3_client.list_objects_v2(Bucket=S3_BUCKET, MaxKeys=1)
        with _s3_health_lock:
            _s3_health_cache['ts'] = now
            _s3_health_cache['ok'] = True
        return 'ok', None
    except Exception as e:
        logger.warning("/health S3 probe failed: %s", e)
        with _s3_health_lock:
            _s3_health_cache['ts'] = now
            _s3_health_cache['ok'] = False
        return 'error', None


@app.route('/health', methods=['GET'])
def health_check():
    """Health endpoint — checks library DB and S3 reachability.

    Response body intentionally omits raw exception strings to avoid leaking paths,
    bucket names, or AWS error codes to unauthenticated scanners. Full diagnostics
    are logged server-side.
    """
    checks = {}
    healthy = True

    # Check library DB
    try:
        conn = sqlite3.connect(LIBRARY_DB)
        conn.row_factory = sqlite3.Row
        cursor = conn.cursor()
        cursor.execute('SELECT COUNT(*) as cnt FROM items')
        track_count = cursor.fetchone()['cnt']
        conn.close()
        checks['library_db'] = {'status': 'ok', 'track_count': track_count}
    except Exception as e:
        logger.warning("/health library_db probe failed: %s", e)
        checks['library_db'] = {'status': 'error'}
        healthy = False

    # Check S3 (cached)
    s3_status, _ = _check_s3_health()
    checks['s3'] = {'status': s3_status}
    if s3_status == 'error':
        healthy = False

    status_code = 200 if healthy else 503
    return jsonify({'status': 'healthy' if healthy else 'degraded', 'checks': checks}), status_code


if __name__ == '__main__':
    # Ensure users.db and tables exist (required for /auth/register, /auth/login, etc.)
    from init_db import init_db
    init_db()
    init_track_artwork()
    ensure_cloudwatch_dashboard()
    app.run(host='0.0.0.0', port=8338)

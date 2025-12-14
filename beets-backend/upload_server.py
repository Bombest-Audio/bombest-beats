import os
import mimetypes
import re
import json
import subprocess
import sqlite3
import wave
import struct
from flask import Flask, request, jsonify, send_file
from flask_cors import CORS
from werkzeug.utils import secure_filename

from flask_jwt_extended import JWTManager, create_access_token, jwt_required, get_jwt_identity
import bcrypt
import smtplib
import yaml
from email.mime.text import MIMEText
from email.mime.multipart import MIMEMultipart

from functools import wraps

app = Flask(__name__)
# Load config for JWT
with open('config.yaml', 'r') as f:
    config = yaml.safe_load(f)

# JWT Configuration
app.config['JWT_SECRET_KEY'] = config.get('jwt_secret', 'dev-secret-key')
app.config['JWT_ACCESS_TOKEN_EXPIRES'] = False # Non-expiring for simplicty in MVP
jwt = JWTManager(app)
CORS(app)

# --- Helpers ---
def admin_required():
    def wrapper(fn):
        @wraps(fn)
        @jwt_required()
        def decorator(*args, **kwargs):
            current_user_id = get_jwt_identity()
            conn = sqlite3.connect('music/users.db')
            cursor = conn.cursor()
            cursor.execute("SELECT role FROM users WHERE id = ?", (current_user_id,))
            result = cursor.fetchone()
            conn.close()
            
            if not result or result[0] != 'admin':
                 return jsonify({'error': 'Admins only!'}), 403
            return fn(*args, **kwargs)
        return decorator
    return wrapper
UPLOAD_FOLDER = os.path.join(os.getcwd(), 'uploads')
MUSIC_FOLDER = os.path.join(os.getcwd(), 'music')
WAVEFORM_FOLDER = os.path.join(os.getcwd(), 'waveforms')
LIBRARY_DB = os.path.join(os.getcwd(), 'music', 'library.db')

os.makedirs(UPLOAD_FOLDER, exist_ok=True)
os.makedirs(MUSIC_FOLDER, exist_ok=True)
os.makedirs(WAVEFORM_FOLDER, exist_ok=True)

app.config['UPLOAD_FOLDER'] = UPLOAD_FOLDER

ALLOWED_EXTENSIONS = {'mp3', 'wav', 'ogg', 'flac', 'm4a'}

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
    """Check if a track with similar name already exists in the library"""
    try:
        conn = sqlite3.connect(LIBRARY_DB)
        cursor = conn.cursor()
        # Check for exact match or similar (case-insensitive)
        cursor.execute("SELECT id, title FROM items WHERE LOWER(title) = LOWER(?)", (track_name,))
        result = cursor.fetchone()
        conn.close()
        return result  # Returns (id, title) if found, None otherwise
    except Exception as e:
        print(f"Duplicate check error: {e}")
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
        print(f"Could not set metadata: {e}")
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
        print(f"Waveform generation error: {e}")
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
        print(f"Get track path error: {e}")
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
        
        # Run beet import
        try:
            subprocess.run(
                ['beet', '-c', 'config.yaml', 'import', '-q', '--noautotag', '-s', filepath], 
                check=True
            )
            
            # Update the title in the database for the most recently added track
            # Beets doesn't always preserve metadata, so we set it directly
            try:
                conn = sqlite3.connect(LIBRARY_DB)
                cursor = conn.cursor()
                # Get the most recently added item (highest ID)
                cursor.execute("SELECT id FROM items ORDER BY id DESC LIMIT 1")
                result = cursor.fetchone()
                if result:
                    new_id = result[0]
                    # Format title: lowercase, underscores to spaces
                    formatted_title = track_name.replace('_', ' ').lower()
                    cursor.execute("UPDATE items SET title = ?, artist = ? WHERE id = ?", (formatted_title, 'thomas phillips', new_id))
                    conn.commit()
                conn.close()
            except Exception as db_err:
                print(f"Warning: Could not update title in database: {db_err}")
            
            # Clean up uploaded file
            if os.path.exists(filepath):
                os.remove(filepath)
            
            return jsonify({
                'message': f'Successfully uploaded "{track_name}"',
                'track_name': track_name
            }), 200
        except subprocess.CalledProcessError as e:
            return jsonify({'error': f'Import failed: {str(e)}'}), 500
        except Exception as e:
            return jsonify({'error': f'Server error: {str(e)}'}), 500
            
    return jsonify({'error': 'Invalid file type'}), 400


@app.route('/duplicates', methods=['DELETE'])
def remove_duplicates():
    """Find and remove duplicate tracks from the library"""
    try:
        conn = sqlite3.connect(LIBRARY_DB)
        cursor = conn.cursor()
        
        # Find duplicates (same title, keep lowest ID)
        cursor.execute("""
            SELECT id, title FROM items 
            WHERE title IN (
                SELECT title FROM items 
                GROUP BY title HAVING COUNT(*) > 1
            )
            ORDER BY title, id
        """)
        
        duplicates = cursor.fetchall()
        
        # Group by title and mark all but first for deletion
        to_delete = []
        seen_titles = {}
        for item_id, title in duplicates:
            if title in seen_titles:
                to_delete.append(item_id)
            else:
                seen_titles[title] = item_id
        
        # Delete from database
        for item_id in to_delete:
            cursor.execute("DELETE FROM items WHERE id = ?", (item_id,))
        
        conn.commit()
        conn.close()
        
        return jsonify({
            'message': f'Removed {len(to_delete)} duplicate tracks',
            'deleted_ids': to_delete
        }), 200
    except Exception as e:
        return jsonify({'error': f'Failed to remove duplicates: {str(e)}'}), 500


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
            try:
                from mutagen import File
                audio = File(track_path, easy=True)
                if audio is not None:
                    if title is not None:
                        audio['title'] = title
                    if artist is not None:
                        audio['artist'] = artist
                    if album is not None:
                        audio['album'] = album
                    audio.save()
            except Exception as e:
                print(f"Warning: Could not update audio file metadata: {e}")
        
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
        
        # Delete waveform cache
        waveform_file = os.path.join(WAVEFORM_FOLDER, f'{track_id}.json')
        if os.path.exists(waveform_file):
            os.remove(waveform_file)
        
        # Optionally delete file from disk (comment out if you want to keep files)
        # if os.path.exists(track_path):
        #     os.remove(track_path)
        
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
                try:
                    from mutagen import File
                    audio = File(track_path, easy=True)
                    if audio is not None:
                        if artist:
                            audio['artist'] = artist
                        if album:
                            audio['album'] = album
                        audio.save()
                except Exception as e:
                    print(f"Warning: Could not update audio file metadata for {track_id}: {e}")
        
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
                        print(f"Error deleting file {track_path}: {e}")
            
            deleted_count += 1
            
        conn.commit()
        conn.close()
        
        return jsonify({
            'message': f'Deleted {deleted_count} tracks successfully',
            'deleted_count': deleted_count
        }), 200
        
    except Exception as e:
        return jsonify({'error': f'Failed to batch delete tracks: {str(e)}'}), 500

@app.route('/library', methods=['GET'])
def get_library():
    """Get all tracks from the library directly from SQLite"""
    try:
        conn = sqlite3.connect(LIBRARY_DB)
        conn.row_factory = sqlite3.Row  # Access columns by name
        cursor = conn.cursor()
        
        cursor.execute("SELECT * FROM items ORDER BY track ASC")
        rows = cursor.fetchall()
        
        items = []
        for row in rows:
            item = dict(row)
            
            # Handle bytes (like path) for JSON serialization
            for key, value in item.items():
                if isinstance(value, bytes):
                    item[key] = value.decode('utf-8', errors='ignore')
            
            # Add art URL structure (pointing to beets API for now, or we can serve it too)
            # Beets API is at port 8337 usually
            item['album_id'] = item.get('album_id')
            items.append(item)
            
        conn.close()
        return jsonify({'items': items}), 200
    except Exception as e:
        return jsonify({'error': f'Failed to fetch library: {str(e)}'}), 500

@app.route('/tracks/<int:track_id>/loops', methods=['GET'])
@jwt_required()
def get_loops(track_id):
    conn = sqlite3.connect('music/users.db')
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
        
    conn = sqlite3.connect('music/users.db')
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
        print(f"Metadata write error: {e}")
        # Non-fatal, DB is updated
        
    return jsonify({'message': 'Item updated'}), 200

@app.route('/loops/<int:loop_id>', methods=['DELETE'])
@jwt_required()
def delete_loop(loop_id):
    current_user_id = get_jwt_identity()
    conn = sqlite3.connect('music/users.db')
    cursor = conn.cursor()
    
    # Check ownership
    cursor.execute('SELECT user_id FROM loop_points WHERE id = ?', (loop_id,))
    row = cursor.fetchone()
    if not row:
        conn.close()
        return jsonify({'error': 'Loop not found'}), 404
        
    if row[0] != current_user_id: # And not admin FIXME
        conn.close()
        return jsonify({'error': 'Unauthorized'}), 403
        
    cursor.execute('DELETE FROM loop_points WHERE id = ?', (loop_id,))
    conn.commit()
    conn.close()
    return jsonify({'message': 'Loop deleted'}), 200

# Fix path to be absolute or relative to known location
# We know it's in ~/bombest-beats/beets-backend/music/library.db
# os.getcwd() is where we run script from.
LIBRARY_DB = os.path.join(os.getcwd(), 'music', 'library.db')

@app.route('/tracks/<int:track_id>/lyrics', methods=['GET'])
@jwt_required()
def get_lyrics(track_id):
    conn = sqlite3.connect('music/users.db')
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
        
    conn = sqlite3.connect('music/users.db')
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
    conn = sqlite3.connect('music/users.db')
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
        
    conn = sqlite3.connect('music/users.db')
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
    conn = sqlite3.connect('music/users.db')
    cursor = conn.cursor()
    
    # Check ownership
    cursor.execute('SELECT user_id FROM comments WHERE id = ?', (comment_id,))
    row = cursor.fetchone()
    if not row:
        conn.close()
        return jsonify({'error': 'Comment not found'}), 404
        
    if row[0] != current_user_id: # And not admin FIXME
        conn.close()
        return jsonify({'error': 'Unauthorized'}), 403
        
    cursor.execute('DELETE FROM comments WHERE id = ?', (comment_id,))
    conn.commit()
    conn.close()
    return jsonify({'message': 'Comment deleted'}), 200

@app.route('/auth/register', methods=['POST'])
def register():
    data = request.get_json()
    username = data.get('username')
    password = data.get('password')
    invite_code = data.get('invite_code')
    


    if not username or not password or not invite_code:
        return jsonify({'error': 'Missing required fields'}), 400

    conn = sqlite3.connect('music/users.db')
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
        return jsonify({'error': str(e)}), 500
    finally:
        conn.close()

@app.route('/auth/login', methods=['POST'])
def login():
    data = request.get_json()
    username = data.get('username')
    password = data.get('password')

    if not username or not password:
        return jsonify({'error': 'Missing username or password'}), 400

    conn = sqlite3.connect('music/users.db')
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
    conn = sqlite3.connect('music/users.db')
    conn.row_factory = sqlite3.Row
    cursor = conn.cursor()
    
    cursor.execute("SELECT id, username, role FROM users WHERE id = ?", (current_user_id,))
    user = cursor.fetchone()
    conn.close()
    
    if user:
        return jsonify(dict(user)), 200
    return jsonify({'error': 'User not found'}), 404

# ==================== PASSKEY / WEBAUTHN ====================
import base64
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

RP_ID = "bom.best"
RP_NAME = "bombest beats"
RP_ORIGIN = "https://bom.best"

# Store challenges temporarily (in production, use Redis or similar)
passkey_challenges = {}

def init_passkey_table():
    """Create passkey_credentials table if not exists"""
    conn = sqlite3.connect('music/users.db')
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
    
    conn = sqlite3.connect('music/users.db')
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
    
    options = generate_registration_options(
        rp_id=RP_ID,
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
    
    # Store challenge for verification
    passkey_challenges[str(user['id'])] = options.challenge
    
    return options_to_json(options)

@app.route('/auth/passkey/register/verify', methods=['POST'])
@jwt_required()
def passkey_register_verify():
    """Verify and store the new passkey credential"""
    current_user_id = get_jwt_identity()
    data = request.get_json()
    
    challenge = passkey_challenges.get(current_user_id)
    if not challenge:
        return jsonify({'error': 'No pending registration'}), 400
    
    try:
        verification = verify_registration_response(
            credential=data,
            expected_challenge=challenge,
            expected_rp_id=RP_ID,
            expected_origin=RP_ORIGIN,
        )
        
        # Store the credential
        conn = sqlite3.connect('music/users.db')
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
        return jsonify({'error': str(e)}), 400

@app.route('/auth/passkey/login/options', methods=['POST'])
def passkey_login_options():
    """Generate options for passkey login"""
    data = request.get_json() or {}
    username = data.get('username')
    
    conn = sqlite3.connect('music/users.db')
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
    
    options = generate_authentication_options(
        rp_id=RP_ID,
        allow_credentials=allow_credentials if username else None,  # None = discoverable
        user_verification=UserVerificationRequirement.PREFERRED,
    )
    
    # Store challenge (keyed by a random ID for login)
    import secrets
    login_id = secrets.token_urlsafe(16)
    passkey_challenges[login_id] = options.challenge
    
    response = json.loads(options_to_json(options))
    response['loginId'] = login_id
    return jsonify(response)

@app.route('/auth/passkey/login/verify', methods=['POST'])
def passkey_login_verify():
    """Verify passkey and issue JWT"""
    data = request.get_json()
    login_id = data.get('loginId')
    
    challenge = passkey_challenges.get(login_id)
    if not challenge:
        return jsonify({'error': 'No pending login'}), 400
    
    # Find the credential
    credential_id = data.get('id')
    if not credential_id:
        return jsonify({'error': 'Missing credential ID'}), 400
    
    conn = sqlite3.connect('music/users.db')
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
            expected_rp_id=RP_ID,
            expected_origin=RP_ORIGIN,
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
        return jsonify({'error': str(e)}), 401

@app.route('/auth/passkey/list', methods=['GET'])
@jwt_required()
def passkey_list():
    """List user's registered passkeys"""
    current_user_id = get_jwt_identity()
    
    conn = sqlite3.connect('music/users.db')
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
    
    conn = sqlite3.connect('music/users.db')
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
    
    conn = sqlite3.connect('music/users.db')
    cursor = conn.cursor()
    
    # Ensure invites table exists
    cursor.execute('CREATE TABLE IF NOT EXISTS invites (code TEXT PRIMARY KEY, created_by INTEGER, used_by INTEGER, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)')
    
    try:
        cursor.execute('INSERT INTO invites (code, created_by) VALUES (?, ?)', (code, get_jwt_identity()))
        conn.commit()
        return jsonify({'code': code}), 201
    finally:
        conn.close()



# --- Playlist Routes ---

@app.route('/playlists', methods=['GET'])
def get_playlists():
    """Get all playlists"""
    try:
        conn = sqlite3.connect('music/users.db')
        cursor = conn.cursor()
        cursor.execute("SELECT id, name, created_at FROM playlists ORDER BY created_at DESC")
        playlists = [{'id': row[0], 'name': row[1], 'created_at': row[2]} for row in cursor.fetchall()]
        
        # Get track counts for each playlist
        for pl in playlists:
            cursor.execute("SELECT COUNT(*) FROM playlist_tracks WHERE playlist_id = ?", (pl['id'],))
            pl['count'] = cursor.fetchone()[0]
            
        conn.close()
        return jsonify({'playlists': playlists})
    except Exception as e:
        return jsonify({'error': str(e)}), 500

@app.route('/playlists', methods=['POST'])
def create_playlist():
    """Create a new playlist"""
    try:
        data = request.get_json()
        name = data.get('name')
        if not name:
            return jsonify({'error': 'Name is required'}), 400
            
        conn = sqlite3.connect('music/users.db')
        cursor = conn.cursor()
        cursor.execute("INSERT INTO playlists (name) VALUES (?)", (name,))
        playlist_id = cursor.lastrowid
        conn.commit()
        conn.close()
        
        return jsonify({'id': playlist_id, 'name': name, 'count': 0}), 201
    except Exception as e:
        return jsonify({'error': str(e)}), 500

@app.route('/playlists/<int:playlist_id>', methods=['PUT'])
def update_playlist(playlist_id):
    """Rename a playlist"""
    try:
        data = request.get_json()
        name = data.get('name')
        if not name:
            return jsonify({'error': 'Name is required'}), 400
            
        conn = sqlite3.connect('music/users.db')
        cursor = conn.cursor()
        cursor.execute("UPDATE playlists SET name = ? WHERE id = ?", (name, playlist_id))
        conn.commit()
        conn.close()
        
        return jsonify({'success': True})
    except Exception as e:
        return jsonify({'error': str(e)}), 500

@app.route('/playlists/<int:playlist_id>', methods=['DELETE'])
def delete_playlist(playlist_id):
    """Delete a playlist"""
    try:
        conn = sqlite3.connect('music/users.db')
        cursor = conn.cursor()
        cursor.execute("DELETE FROM playlist_tracks WHERE playlist_id = ?", (playlist_id,))
        cursor.execute("DELETE FROM playlists WHERE id = ?", (playlist_id,))
        conn.commit()
        conn.close()
        
        return jsonify({'success': True})
    except Exception as e:
        return jsonify({'error': str(e)}), 500

@app.route('/playlists/<int:playlist_id>/tracks', methods=['GET'])
def get_playlist_tracks(playlist_id):
    """Get tracks for a playlist with full metadata"""
    try:
        # Get track IDs from playlist DB
        conn = sqlite3.connect('music/users.db')
        cursor = conn.cursor()
        cursor.execute("SELECT track_id FROM playlist_tracks WHERE playlist_id = ? ORDER BY position ASC", (playlist_id,))
        track_ids = [row[0] for row in cursor.fetchall()]
        conn.close()
        
        if not track_ids:
            return jsonify({'items': []})

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
        
        return jsonify({'items': ordered_tracks})
    except Exception as e:
        print(f"Error fetching playlist tracks: {e}")
        return jsonify({'error': str(e)}), 500

@app.route('/playlists/<int:playlist_id>/tracks', methods=['POST'])
def add_tracks_to_playlist(playlist_id):
    """Add tracks to playlist"""
    try:
        data = request.get_json()
        track_ids = data.get('track_ids', [])
        
        conn = sqlite3.connect('music/users.db')
        cursor = conn.cursor()
        
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
def remove_tracks_from_playlist(playlist_id):
    """Remove tracks from playlist"""
    try:
        data = request.get_json()
        track_ids = data.get('track_ids', [])
        
        conn = sqlite3.connect('music/users.db')
        cursor = conn.cursor()
        
        placeholders = ','.join('?' for _ in track_ids)
        cursor.execute(f"DELETE FROM playlist_tracks WHERE playlist_id = ? AND track_id IN ({placeholders})", 
                      (playlist_id, *track_ids))
                      
        conn.commit()
        conn.close()
        
        return jsonify({'success': True})
    except Exception as e:
        return jsonify({'error': str(e)}), 500

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

        # Beets stores absolute path, or relative to library directory
        # In our container/setup, it's usually absolute
        file_path = result[0]
        
        # Security check: Ensure file is within music directory?
        # For now, trust the DB as it's internal.
        
        # Decode bytes if needed (sqlite sometimes returns bytes for text fields if messed up)
        if isinstance(file_path, bytes):
            file_path = file_path.decode('utf-8', errors='ignore')
            
        if not os.path.exists(file_path):
             return jsonify({'error': 'File not found on disk'}), 404

        mime_type, _ = mimetypes.guess_type(file_path)
        if not mime_type:
            mime_type = 'application/octet-stream' # Default fallback to force probing
            
        return send_file(file_path, mimetype=mime_type)
    except Exception as e:
        print(f"Stream error: {e}")
        return jsonify({'error': str(e)}), 500

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
        print(f"Art error: {e}")
        return jsonify({'error': str(e)}), 500

@app.route('/track/<int:track_id>/art', methods=['GET'])
def get_track_art(track_id):
    """Serve track artwork - extracts from file or returns default"""
    try:
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

        # Try album art first if album_id exists
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
                print(f"Artwork extraction error: {extract_error}")

        # Return default artwork
        default_art = os.path.join(os.path.dirname(__file__), 'static', 'no-image.png')
        if os.path.exists(default_art):
            return send_file(default_art)
        
        return jsonify({'error': 'No artwork found'}), 404
    except Exception as e:
        print(f"Track art error: {e}")
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
        conn = sqlite3.connect('music/users.db')
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
        print(f"Notify error: {e}")
        return jsonify({'error': str(e)}), 500

# --- Favorites & Roles ---

@app.route('/favorites', methods=['GET', 'POST', 'DELETE'])
@jwt_required()
def favorites():
    user_id = get_jwt_identity()
    conn = sqlite3.connect('music/users.db')
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


# --- Metrics ---

@app.route('/metrics/play', methods=['POST'])
@jwt_required(optional=True)
def record_play():
    data = request.get_json()
    track_id = data.get('track_id')
    if not track_id:
        return jsonify({'error': 'No track_id'}), 400
        
    user_id = get_jwt_identity() # Might be None if not logged in (optional=True)
    
    conn = sqlite3.connect('music/users.db')
    cursor = conn.cursor()
    
    # Lazy migration for plays table
    cursor.execute('CREATE TABLE IF NOT EXISTS plays (id INTEGER PRIMARY KEY, track_id INTEGER, user_id INTEGER, played_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)')
    
    cursor.execute('INSERT INTO plays (track_id, user_id) VALUES (?, ?)', (track_id, user_id))
    conn.commit()
    conn.close()
    
    return jsonify({'message': 'Play recorded'}), 200

@app.route('/metrics/dashboard', methods=['GET'])
@admin_required()
def get_dashboard_metrics():
    user_id_filter = request.args.get('user_id')  # Optional filter
    
    conn = sqlite3.connect('music/users.db')
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

if __name__ == '__main__':
    # Initialize DB on start if needed (optional since we have init_db script)
    app.run(host='0.0.0.0', port=8338)

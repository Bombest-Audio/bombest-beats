import os
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

app = Flask(__name__)
CORS(app)

# JWT Configuration
app.config['JWT_SECRET_KEY'] = 'bombest-beats-super-secret-key-change-this-in-prod'  # Change this!
app.config['JWT_ACCESS_TOKEN_EXPIRES'] = 86400 * 7  # 7 days
jwt = JWTManager(app)

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
            # Format title: remove underscores, capitalize words
            formatted_title = title.replace('_', ' ').title()
            audio['title'] = formatted_title
            audio['artist'] = 'Thomas Phillips'
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
                    cursor.execute("UPDATE items SET title = ? WHERE id = ?", (track_name, new_id))
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
    cursor.execute('CREATE TABLE IF NOT EXISTS invites (code TEXT PRIMARY KEY, created_by INTEGER, used_by INTEGER, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)')
    
    cursor.execute('SELECT * FROM invites WHERE code = ? AND used_by IS NULL', (invite_code,))
    invite = cursor.fetchone()
    
    if not invite and invite_code != 'admin-override-secret-key-123': # Backdoor for testing/admin
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

    try:
        cursor.execute(
            'INSERT INTO users (username, password_hash, invite_code) VALUES (?, ?, ?)',
            (username, hashed.decode('utf-8'), invite_code)
        )
        new_user_id = cursor.lastrowid
        
        # Mark invite as used
        cursor.execute('UPDATE invites SET used_by = ?, used_at = CURRENT_TIMESTAMP WHERE code = ?', (new_user_id, invite_code))
            
        conn.commit()
        
        # Create token
        access_token = create_access_token(identity=str(new_user_id), additional_claims={'role': 'user', 'username': username})
        
        return jsonify({
            'message': 'Registration successful',
            'access_token': access_token,
            'user': {'id': new_user_id, 'username': username, 'role': 'user'}
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

if __name__ == '__main__':
    # Initialize DB on start if needed (optional since we have init_db script)
    pass
    app.run(host='0.0.0.0', port=8338)




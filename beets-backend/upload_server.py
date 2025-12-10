import os
import re
import subprocess
import sqlite3
from flask import Flask, request, jsonify
from flask_cors import CORS
from werkzeug.utils import secure_filename

app = Flask(__name__)
CORS(app)

UPLOAD_FOLDER = os.path.join(os.getcwd(), 'uploads')
MUSIC_FOLDER = os.path.join(os.getcwd(), 'music')
LIBRARY_DB = os.path.expanduser('~/bombest-beats/beets-backend/music/library.db')

os.makedirs(UPLOAD_FOLDER, exist_ok=True)
os.makedirs(MUSIC_FOLDER, exist_ok=True)

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
    """Set the title metadata on the audio file before import"""
    try:
        from mutagen import File
        audio = File(filepath, easy=True)
        if audio is not None:
            audio['title'] = title
            audio.save()
            return True
    except Exception as e:
        print(f"Could not set title metadata: {e}")
    return False

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


if __name__ == '__main__':
    app.run(host='0.0.0.0', port=8338)

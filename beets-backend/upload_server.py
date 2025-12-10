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

app = Flask(__name__)
CORS(app)

UPLOAD_FOLDER = os.path.join(os.getcwd(), 'uploads')
MUSIC_FOLDER = os.path.join(os.getcwd(), 'music')
WAVEFORM_FOLDER = os.path.join(os.getcwd(), 'waveforms')
LIBRARY_DB = os.path.expanduser('~/bombest-beats/beets-backend/music/library.db')

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


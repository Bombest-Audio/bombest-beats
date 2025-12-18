# Playlist Redesign - New Endpoints
# Insert these endpoints into upload_server.py before line 1736 (@app.route('/stream/<int:track_id>'))

@app.route('/playlists/system/init', methods=['POST'])
def initialize_system_playlists():
    """Initialize All Songs and Favorites system playlists"""
    try:
        conn = sqlite3.connect('music/users.db')
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
        print(f"Error initializing system playlists: {e}")
        import traceback
        traceback.print_exc()
        return jsonify({'error': str(e)}), 500

@app.route('/playlists/<int:playlist_id>/reorder', methods=['PUT'])
def reorder_playlist_tracks(playlist_id):
    """Reorder tracks in playlist"""
    try:
        data = request.get_json()
        track_ids = data.get('track_ids', [])  # New ordered list
        
        conn = sqlite3.connect('music/users.db')
        cursor = conn.cursor()
        
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
        print(f"Error reordering playlist: {e}")
        return jsonify({'error': str(e)}), 500

@app.route('/playlists/<int:playlist_id>/search', methods=['GET'])
def search_playlist(playlist_id):
    """Search within playlist"""
    try:
        query = request.args.get('q', '').lower()
        
        if not query:
            return jsonify({'items': []})
        
        conn = sqlite3.connect('music/users.db')
        cursor = conn.cursor()
        
        # Get track IDs from playlist
        cursor.execute("SELECT track_id FROM playlist_tracks WHERE playlist_id = ? ORDER BY position", (playlist_id,))
        track_ids = [row[0] for row in cursor.fetchall()]
        conn.close()
        
        if not track_ids:
            return jsonify({'items': []})
        
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
        
        # Filter out bytes fields
        results = []
        for row in rows:
            track_dict = {}
            for key in row.keys():
                value = row[key]
                if not isinstance(value, bytes):
                    track_dict[key] = value
            results.append(track_dict)
        
        return jsonify({'items': results})
    except Exception as e:
        print(f"Error searching playlist: {e}")
        return jsonify({'error': str(e)}), 500

@app.route('/playlists/<int:playlist_id>/favorites/toggle', methods=['POST'])
@jwt_required()
def toggle_favorite(playlist_id):
    """Toggle favorite status for a track"""
    try:
        from flask_jwt_extended import get_jwt_identity
        user_id = int(get_jwt_identity())  # Get user ID from JWT token
        data = request.get_json()
        track_id = data.get('track_id')
        
        conn = sqlite3.connect('music/users.db')
        cursor = conn.cursor()
        
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
                max_pos = cursor.fetchone()[0] or -1
                cursor.execute("""
                    INSERT INTO playlist_tracks (playlist_id, track_id, position)
                    VALUES (?, ?, ?)
                """, (fav_playlist[0], track_id, max_pos + 1))
            
            favorited = True
        
        conn.commit()
        conn.close()
        
        return jsonify({'success': True, 'favorited': favorited})
    except Exception as e:
        print(f"Error toggling favorite: {e}")
        return jsonify({'error': str(e)}), 500

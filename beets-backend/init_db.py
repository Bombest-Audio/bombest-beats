import sqlite3
import bcrypt
import os

from db_path import get_users_db_path

DB_PATH = get_users_db_path()

def init_db():
    print(f"Initializing user database at {DB_PATH}...")
    
    # Ensure directory exists (DATA_DIR or music/)
    os.makedirs(os.path.dirname(DB_PATH), exist_ok=True)
    
    conn = sqlite3.connect(DB_PATH)
    cursor = conn.cursor()
    
    # Create users table
    cursor.execute('''
    CREATE TABLE IF NOT EXISTS users (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        username TEXT UNIQUE NOT NULL,
        password_hash TEXT NOT NULL,
        role TEXT DEFAULT 'user',
        email TEXT,
        invite_code TEXT,
        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
    )
    ''')

    # Create playlists table
    cursor.execute('''
    CREATE TABLE IF NOT EXISTS playlists (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        name TEXT NOT NULL,
        user_id INTEGER,
        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
        is_system INTEGER DEFAULT 0,
        is_synced INTEGER DEFAULT 0,
        sort_mode TEXT,
        description TEXT,
        art_path TEXT,
        is_public INTEGER DEFAULT 0,
        share_token TEXT UNIQUE,
        FOREIGN KEY (user_id) REFERENCES users (id)
    )
    ''')

    # Migrate playlists table if it existed with old schema (add missing columns)
    cursor.execute("PRAGMA table_info(playlists)")
    columns = [col[1] for col in cursor.fetchall()]
    for col_name, sql in [
        ('is_system', 'ALTER TABLE playlists ADD COLUMN is_system INTEGER DEFAULT 0'),
        ('is_synced', 'ALTER TABLE playlists ADD COLUMN is_synced INTEGER DEFAULT 0'),
        ('sort_mode', 'ALTER TABLE playlists ADD COLUMN sort_mode TEXT'),
        ('description', 'ALTER TABLE playlists ADD COLUMN description TEXT'),
        ('art_path', 'ALTER TABLE playlists ADD COLUMN art_path TEXT'),
        ('is_public', 'ALTER TABLE playlists ADD COLUMN is_public INTEGER DEFAULT 0'),
    ]:
        if col_name not in columns:
            try:
                cursor.execute(sql)
                columns.append(col_name)
            except sqlite3.OperationalError as e:
                if 'duplicate column name' not in str(e).lower():
                    raise
    # share_token: SQLite doesn't support ADD COLUMN UNIQUE; add column then create index
    if 'share_token' not in columns:
        try:
            cursor.execute("ALTER TABLE playlists ADD COLUMN share_token TEXT")
            cursor.execute(
                "CREATE UNIQUE INDEX IF NOT EXISTS idx_playlists_share_token "
                "ON playlists(share_token) WHERE share_token IS NOT NULL"
            )
            columns.append('share_token')
        except sqlite3.OperationalError as e:
            if 'duplicate column name' not in str(e).lower():
                raise

    # Create playlist_tracks table
    cursor.execute('''
    CREATE TABLE IF NOT EXISTS playlist_tracks (
        playlist_id INTEGER,
        track_id INTEGER,
        position INTEGER,
        added_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
        PRIMARY KEY (playlist_id, track_id),
        FOREIGN KEY (playlist_id) REFERENCES playlists (id) ON DELETE CASCADE
    )
    ''')
    
    # Create loops table
    cursor.execute('''
    CREATE TABLE IF NOT EXISTS loops (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        track_id INTEGER NOT NULL,
        start_time REAL NOT NULL,
        end_time REAL NOT NULL,
        name TEXT,
        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
    )
    ''')

    # Create lyrics table
    cursor.execute('''
    CREATE TABLE IF NOT EXISTS lyrics (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        track_id INTEGER NOT NULL UNIQUE,
        content TEXT,
        updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
    )
    ''')

    # Create comments table
    cursor.execute('''
    CREATE TABLE IF NOT EXISTS comments (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        track_id INTEGER NOT NULL,
        user_id INTEGER,
        content TEXT NOT NULL,
        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
        FOREIGN KEY (user_id) REFERENCES users (id)
    )
    ''')
    
    # Check if admin exists
    cursor.execute("SELECT * FROM users WHERE username = 'admin'")
    if not cursor.fetchone():
        print("Creating default admin user...")
        password = "admin_password" # Change this immediately!
        salt = bcrypt.gensalt()
        hashed = bcrypt.hashpw(password.encode('utf-8'), salt)
        
        cursor.execute(
            "INSERT INTO users (username, password_hash, role) VALUES (?, ?, ?)",
            ('admin', hashed.decode('utf-8'), 'admin')
        )
        print(f"Admin user created. Username: admin, Password: {password}")
    else:
        print("Admin user already exists.")
        
    conn.commit()
    conn.close()
    print("Database initialization complete.")

if __name__ == '__main__':
    init_db()

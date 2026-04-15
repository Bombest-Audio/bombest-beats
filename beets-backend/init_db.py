import sqlite3
import bcrypt
import os

from db_path import get_users_db_path

DB_PATH = get_users_db_path()

def migrate_loop_points_table(cursor):
    """Idempotent migration for the loops -> loop_points rename.

    Handles three pre-existing states:
      1. Fresh install: loop_points was just created; nothing to do.
      2. Legacy install with `loops` table only: rename and add missing columns.
      3. Legacy install where both existed (partial migration): copy rows from loops
         into loop_points and drop loops.

    Also ensures the `user_id` and `label` columns exist on any pre-existing
    loop_points table (some installs ran ALTER manually without a label column).
    Orphan rows with user_id IS NULL get backfilled to the admin user (id = 1)
    so they remain visible to authenticated queries.
    """
    cursor.execute(
        "SELECT name FROM sqlite_master WHERE type='table' AND name IN ('loops','loop_points')"
    )
    existing = {row[0] for row in cursor.fetchall()}

    if 'loops' in existing and 'loop_points' not in existing:
        # Case 2: rename old table, then add missing columns.
        cursor.execute("ALTER TABLE loops RENAME TO loop_points")
        existing = {'loop_points'}

    if 'loops' in existing and 'loop_points' in existing:
        # Case 3: both exist — copy rows and drop old.
        cursor.execute("PRAGMA table_info(loops)")
        old_cols = [col[1] for col in cursor.fetchall()]
        has_name = 'name' in old_cols
        label_src = 'name' if has_name else "NULL"
        cursor.execute(
            f"INSERT INTO loop_points (track_id, start_time, end_time, label, created_at) "
            f"SELECT track_id, start_time, end_time, {label_src}, created_at FROM loops"
        )
        cursor.execute("DROP TABLE loops")

    # Ensure required columns exist on loop_points (no-op if already present).
    cursor.execute("PRAGMA table_info(loop_points)")
    lp_cols = [col[1] for col in cursor.fetchall()]
    if 'user_id' not in lp_cols:
        cursor.execute("ALTER TABLE loop_points ADD COLUMN user_id INTEGER")
    if 'label' not in lp_cols:
        # Some installs renamed loops but never added label — move data from `name` if present.
        if 'name' in lp_cols:
            cursor.execute("ALTER TABLE loop_points ADD COLUMN label TEXT")
            cursor.execute("UPDATE loop_points SET label = name WHERE label IS NULL")
        else:
            cursor.execute("ALTER TABLE loop_points ADD COLUMN label TEXT")

    # Backfill user_id for orphan rows so they stay visible (admin owns them).
    cursor.execute(
        "UPDATE loop_points SET user_id = 1 "
        "WHERE user_id IS NULL AND EXISTS (SELECT 1 FROM users WHERE id = 1)"
    )


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
    
    # Create loop_points table (user-scoped audio loop markers)
    # NOTE: historically named `loops` with a `name` column, but the server has always queried
    # `loop_points` with `user_id`/`label` columns — the mismatch silently 500'd every loops API
    # call. See GitHub issue #19 for full context. This CREATE is now the canonical schema;
    # the rename + column add for pre-existing installs is handled by
    # `migrate_loop_points_table()` called immediately after from init_db below.
    cursor.execute('''
    CREATE TABLE IF NOT EXISTS loop_points (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        track_id INTEGER NOT NULL,
        user_id INTEGER,
        start_time REAL NOT NULL,
        end_time REAL NOT NULL,
        label TEXT,
        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
        FOREIGN KEY (user_id) REFERENCES users (id)
    )
    ''')

    # One-shot migration for pre-existing installs that have the old `loops` table.
    migrate_loop_points_table(cursor)

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

import sqlite3
import os

DB_PATH = 'music/users.db'

def update_schema():
    print(f"Updating database schema at {DB_PATH}...")
    
    if not os.path.exists(DB_PATH):
        print("Database not found! Run init_db.py first.")
        return

    conn = sqlite3.connect(DB_PATH)
    cursor = conn.cursor()
    
    # helper to check if table exists
    def table_exists(name):
        cursor.execute("SELECT name FROM sqlite_master WHERE type='table' AND name=?", (name,))
        return cursor.fetchone() is not None

    # 1. Loop Points
    if not table_exists('loop_points'):
        print("Creating loop_points table...")
        cursor.execute('''
        CREATE TABLE loop_points (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            track_id INTEGER NOT NULL,
            user_id INTEGER NOT NULL,
            start_time REAL NOT NULL,
            end_time REAL NOT NULL,
            label TEXT,
            created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
            FOREIGN KEY(user_id) REFERENCES users(id)
        )
        ''')
    else:
        print("loop_points table already exists.")

    # 2. Lyrics
    if not table_exists('lyrics'):
        print("Creating lyrics table...")
        cursor.execute('''
        CREATE TABLE lyrics (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            track_id INTEGER NOT NULL,
            user_id INTEGER NOT NULL,
            content TEXT,
            visibility TEXT DEFAULT 'private',
            created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
            updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
            FOREIGN KEY(user_id) REFERENCES users(id)
        )
        ''')
    else:
        print("lyrics table already exists.")

    # 3. Comments
    if not table_exists('comments'):
        print("Creating comments table...")
        cursor.execute('''
        CREATE TABLE comments (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            track_id INTEGER NOT NULL,
            user_id INTEGER NOT NULL,
            content TEXT NOT NULL,
            parent_id INTEGER,
            created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
            FOREIGN KEY(user_id) REFERENCES users(id)
        )
        ''')
    else:
        print("comments table already exists.")

    conn.commit()
    conn.close()
    print("Schema update complete.")

if __name__ == '__main__':
    update_schema()

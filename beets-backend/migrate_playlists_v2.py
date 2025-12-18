#!/usr/bin/env python3
"""
Playlist Schema Migration v2
Adds new columns for auto-playlists, admin publishing, and sync support.
"""
import sqlite3
import sys
from datetime import datetime

DB_PATH = 'music/users.db'

def migrate_playlists():
    """Add new columns to playlists table"""
    conn = sqlite3.connect(DB_PATH)
    cursor = conn.cursor()
    
    print("Starting playlist schema migration...")
    
    # Check current schema
    cursor.execute("PRAGMA table_info(playlists)")
    existing_columns = {row[1] for row in cursor.fetchall()}
    
    migrations = [
        ('is_system', 'INTEGER DEFAULT 0', 'System playlist flag (All Songs, Favorites)'),
        ('is_published', 'INTEGER DEFAULT 0', 'Admin published to all users'),
        ('is_synced', 'INTEGER DEFAULT 1', 'Sync across user devices'),
        ('owner_user_id', 'INTEGER', 'Owner for multi-user support'),
        ('source_playlist_id', 'INTEGER', 'Source if copied from published'),
        ('sort_mode', 'TEXT DEFAULT "custom"', 'Sort: custom/title/artist/date'),
        ('description', 'TEXT', 'Optional playlist description'),
    ]
    
    for col_name, col_type, description in migrations:
        if col_name not in existing_columns:
            try:
                cursor.execute(f'ALTER TABLE playlists ADD COLUMN {col_name} {col_type}')
                print(f"✅ Added column: {col_name} ({description})")
            except sqlite3.OperationalError as e:
                print(f"⚠️  Column {col_name} may already exist: {e}")
    
    conn.commit()
    conn.close()
    print("Playlists table migration complete!")

def create_favorites_table():
    """Create favorites table for quick lookups"""
    conn = sqlite3.connect(DB_PATH)
    cursor = conn.cursor()
    
    print("\nCreating favorites table...")
    
    cursor.execute('''
        CREATE TABLE IF NOT EXISTS favorites (
            user_id INTEGER NOT NULL,
            track_id INTEGER NOT NULL,
            added_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
            PRIMARY KEY (user_id, track_id)
        )
    ''')
    
    conn.commit()
    conn.close()
    print("✅ Favorites table created!")

def verify_migration():
    """Verify migration succeeded"""
    conn = sqlite3.connect(DB_PATH)
    cursor = conn.cursor()
    
    cursor.execute("PRAGMA table_info(playlists)")
    columns = [row[1] for row in cursor.fetchall()]
    
    cursor.execute("SELECT name FROM sqlite_master WHERE type='table' AND name='favorites'")
    has_favorites = cursor.fetchone() is not None
    
    conn.close()
    
    print("\n📊 Migration Verification:")
    print(f"Playlists columns: {', '.join(columns)}")
    print(f"Favorites table exists: {has_favorites}")
    
    required = ['is_system', 'is_published', 'is_synced', 'sort_mode']
    missing = [col for col in required if col not in columns]
    
    if missing or not has_favorites:
        print(f"\n❌ Migration incomplete. Missing: {missing}")
        return False
    
    print("\n✅ Migration verification passed!")
    return True

if __name__ == '__main__':
    try:
        migrate_playlists()
        create_favorites_table()
        success = verify_migration()
        sys.exit(0 if success else 1)
    except Exception as e:
        print(f"\n❌ Migration failed: {e}")
        import traceback
        traceback.print_exc()
        sys.exit(1)

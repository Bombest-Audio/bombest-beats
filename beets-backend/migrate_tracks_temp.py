import sqlite3
import os

DB_PATH = 'music/library.db'

def migrate():
    if not os.path.exists(DB_PATH):
        print(f"Error: Database not found at {DB_PATH}")
        return

    try:
        conn = sqlite3.connect(DB_PATH)
        cursor = conn.cursor()
        
        # Get current order (ID DESC)
        cursor.execute("SELECT id FROM items ORDER BY id DESC")
        items = cursor.fetchall()
        
        print(f"Found {len(items)} items. Updating track numbers...")
        
        for index, item in enumerate(items):
            track_num = index + 1
            item_id = item[0]
            cursor.execute("UPDATE items SET track = ? WHERE id = ?", (track_num, item_id))
            
        conn.commit()
        print("Migration complete.")
        conn.close()
    except Exception as e:
        print(f"Migration failed: {e}")

if __name__ == "__main__":
    migrate()

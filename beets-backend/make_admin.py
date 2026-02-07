#!/usr/bin/env python3
"""Set a user's role to admin in music/users.db. Run from beets-backend: python make_admin.py [username]
For EC2, run scripts/ec2-make-admin.sh on the instance instead (no local DB)."""
import os
import sqlite3
import sys

DB_PATH = os.path.join(os.path.dirname(__file__), 'music', 'users.db')
USERNAME = (sys.argv[1] if len(sys.argv) > 1 else 'thomas').strip()

def main():
    if not os.path.isfile(DB_PATH):
        print(f'DB not found: {DB_PATH}')
        print('Create a user first (register in the app) or run the upload server once.')
        sys.exit(1)
    conn = sqlite3.connect(DB_PATH)
    cur = conn.cursor()
    cur.execute("UPDATE users SET role = 'admin' WHERE username = ?", (USERNAME,))
    if cur.rowcount == 0:
        print(f'No user named "{USERNAME}" found.')
        conn.close()
        sys.exit(1)
    conn.commit()
    conn.close()
    print(f'Updated "{USERNAME}" to admin. Log out and log back in for the new role to apply.')

if __name__ == '__main__':
    main()

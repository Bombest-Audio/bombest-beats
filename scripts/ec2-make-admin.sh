#!/usr/bin/env bash
# Run this ON the EC2 instance (e.g. after SSH) to set a user's role to admin.
# The container must be running and the DB lives at /app/music/users.db inside it.
# Usage: ./scripts/ec2-make-admin.sh [username]
#   Default username: thomas
# Example (on EC2): ./ec2-make-admin.sh thomas

set -e
USERNAME="${1:-thomas}"
CONTAINER_NAME="${CONTAINER_NAME:-bombest-beats}"

PY_CODE="import sqlite3, sys; conn = sqlite3.connect('/app/music/users.db'); cur = conn.cursor(); cur.execute(\"UPDATE users SET role = 'admin' WHERE username = ?\", (sys.argv[1],)); n = cur.rowcount; conn.commit(); conn.close(); sys.exit(0 if n > 0 else 1)"
if ! sudo docker exec "$CONTAINER_NAME" python3 -c "$PY_CODE" "$USERNAME" 2>/dev/null; then
  echo "Failed: no user named \"$USERNAME\" in DB, or container \"$CONTAINER_NAME\" not running."
  echo "Ensure the user has registered at least once, then run this script again."
  exit 1
fi
echo "Updated \"$USERNAME\" to admin on EC2. Log out and log back in for the new role to apply."

#!/usr/bin/env bash
set -euo pipefail

# Sync local music folder to S3 bucket
# Usage:
#   MUSIC_DIR=/home/thomas/bombest-beats/beets-backend/music BUCKET=bombest-beats-music REGION=us-west-2 AWS_PROFILE=bombest-beats-sync ./sync-to-s3.sh
#
# Notes:
# - Uses aws s3 sync --delete to keep S3 in lockstep.
# - Optionally watches with inotifywait if available (set WATCH=1).

MUSIC_DIR="${MUSIC_DIR:-/home/thomas/bombest-beats/beets-backend/music}"
BUCKET="${BUCKET:-bombest-beats-music}"
REGION="${REGION:-us-west-2}"
DEST="s3://${BUCKET}/music"

sync_once() {
  echo "Syncing $MUSIC_DIR -> $DEST"
  aws s3 sync "$MUSIC_DIR" "$DEST" \
    --region "$REGION" \
    --delete \
    --exclude ".DS_Store" \
    --exclude ".*.swp"
  echo "Sync complete"
}

if [[ "${WATCH:-0}" == "1" ]]; then
  if ! command -v inotifywait >/dev/null 2>&1; then
    echo "inotifywait not found. Install inotify-tools or run without WATCH=1."
    exit 1
  fi
  echo "Watching $MUSIC_DIR for changes..."
  sync_once
  while inotifywait -r -e modify,create,delete,move "$MUSIC_DIR"; do
    sync_once
  done
else
  sync_once
fi

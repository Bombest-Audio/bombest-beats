#!/bin/bash
set -e

SERVER="thomas@100.69.137.108"
REMOTE_DIR="~/bombest-beats"

# 1. Build Frontend Locally
echo "Building frontend locally..."
cd music-frontend
# Use legacy provider for Node 25 compatibility with older react-scripts
GENERATE_SOURCEMAP=false NODE_OPTIONS=--openssl-legacy-provider npm run build
cd ..

# 2. Prepare Remote Directory
echo "Creating remote directory..."
ssh $SERVER "mkdir -p $REMOTE_DIR"

# 3. Sync Files
echo "Syncing files..."
# Exclude heavy/unnecessary items
rsync -av \
    --exclude='venv' \
    --exclude='__pycache__' \
    --exclude='node_modules' \
    --exclude='.git' \
    --exclude='android-app' \
    --exclude='ios-app' \
    --exclude='*.log' \
    ./ $SERVER:$REMOTE_DIR/

# 4. Run Docker Compose on Server
echo "Deploying with Docker Compose..."
ssh $SERVER "cd $REMOTE_DIR && docker-compose up -d --build"

echo "Deployment complete! Access at http://100.69.137.108:8080/beats/"

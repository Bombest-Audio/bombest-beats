#!/bin/bash
# DEPRECATED: Home server deployment. Use ./deploy-to-ec2.sh for EC2 deployment.
echo "DEPRECATED: This script deploys to the home server (100.69.137.108)."
echo "Use ./deploy-to-ec2.sh for EC2 deployment."
exit 1

set -e

SERVER="thomas@100.69.137.108"
REMOTE_DIR="~/bombest-beats"

echo "Building frontend..."
cd music-frontend
PUBLIC_URL=/beats GENERATE_SOURCEMAP=false NODE_OPTIONS=--openssl-legacy-provider npm run build
cd ..

echo "Creating remote directory..."
ssh $SERVER "mkdir -p $REMOTE_DIR"

echo "Copying backend..."
rsync -av --exclude='venv' --exclude='__pycache__' --exclude='music/users.db' --exclude='music/library.db' beets-backend/ $SERVER:$REMOTE_DIR/beets-backend/

echo "Copying frontend build..."
rsync -av music-frontend/build/ $SERVER:$REMOTE_DIR/frontend-build/

echo "Deploying on server..."
ssh $SERVER "bash -s" <<EOF
    # Use absolute path expansion
    BEETS_PATH="bombest-beats/beets-backend"
    FRONTEND_PATH="bombest-beats/frontend-build"
    
    cd ~/bombest-beats/beets-backend
    echo "Current directory: \$(pwd)"
    ls -la
    
    export BEETSDIR=~/bombest-beats/beets-backend
    export BEETS_CONFIG=\$BEETSDIR/config.yaml
    
    # Create venv if not exists
    if [ ! -d "venv" ]; then
        python3 -m venv venv
    fi
    source venv/bin/activate
    pip install -r requirements.txt
    
    # Initialize DB (dummy) if needed, but we probably want to sync music?
    # For now, we assume music exists or is managed there.
    python init_db.py 

    # Kill existing servers
    pkill -f "beet" || true
    pkill -f "manage.py" || true
    pkill -f "upload_server.py" || true
    pkill -f "python3 -m http.server 3000" || true
    sleep 2

    # Start Beets Web
    nohup ./venv/bin/python manage.py run > beets.log 2>&1 &
    
    # Start Upload Server
    nohup ./venv/bin/python upload_server.py > upload.log 2>&1 &
    
    
    cd ~/bombest-beats/frontend-build
    # Start Frontend
    nohup python3 -m http.server 3000 > frontend.log 2>&1 &
    
    echo "Deployment complete. Access at http://100.69.137.108:3000"
EOF

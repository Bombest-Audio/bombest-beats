#!/bin/bash
# AWS Deployment Script for Bombest Beats
# This script deploys the backend to AWS EC2 via Docker Hub

set -e

# Configuration: must match your Docker Hub login (docker login)
DOCKER_USERNAME="${DOCKER_USERNAME:-thomasphillips3}"
IMAGE_NAME="bombest-beats"

echo "🚀 Deploying Bombest Beats to AWS..."

# Check for Docker
if ! command -v docker &> /dev/null; then
    echo "❌ Docker not found. Install Docker Desktop first."
    exit 1
fi

# Step 1: Build Docker image for linux/amd64 (EC2 and most cloud VMs)
echo "📦 Building Docker image (linux/amd64 for EC2)..."
docker build --platform linux/amd64 -t $IMAGE_NAME:latest ./beets-backend

# Step 2: Push to Docker Hub
echo "☁️ Pushing to Docker Hub..."
echo "   Pushing as: $DOCKER_USERNAME/$IMAGE_NAME:latest"

docker tag $IMAGE_NAME:latest $DOCKER_USERNAME/$IMAGE_NAME:latest
if ! docker push $DOCKER_USERNAME/$IMAGE_NAME:latest; then
  echo ""
  echo "❌ Push failed. Try:"
  echo "   1. docker login   (must be same account as DOCKER_USERNAME=$DOCKER_USERNAME)"
  echo "   2. Create repo on Docker Hub: https://hub.docker.com/repository/create"
  echo "      Name: bombest-beats   Visibility: Public"
  echo "   3. If using a token, use one with Read & Write permission."
  echo "   To push under a different user: DOCKER_USERNAME=youruser ./deploy-aws.sh"
  exit 1
fi

echo "✅ Image pushed: $DOCKER_USERNAME/$IMAGE_NAME:latest"
echo ""
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "🖥️  NEXT STEPS - Deploy to AWS EC2:"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo ""
echo "1. Deploy with AWS CLI (recommended):"
echo "   ./setup-ec2-aws-cli.sh [REGION]"
echo "   (e.g. ./setup-ec2-aws-cli.sh us-west-2)"
echo ""
echo "2. SSH once, set S3 env vars, and start the container:"
echo "   export AWS_ACCESS_KEY_ID=your_access_key"
echo "   export AWS_SECRET_ACCESS_KEY=your_secret_key"
echo "   export S3_BUCKET=bombest-beats-music"
echo "   export S3_REGION=us-west-2"
echo "   ./run-bombest-beats.sh"
echo ""
echo "   Or run docker manually (with persistent data):"
echo "   sudo docker run -d --name bombest-beats -p 8338:8338 --restart unless-stopped \\"
echo "     -v /data/beets:/app/music \\"
echo "     -e S3_BUCKET -e AWS_ACCESS_KEY_ID -e AWS_SECRET_ACCESS_KEY -e S3_REGION \\"
echo "     $DOCKER_USERNAME/$IMAGE_NAME:latest"
echo ""
echo "3. Add EC2 public IP to Cloudflare as failover origin"
echo ""
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

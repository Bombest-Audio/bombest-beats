#!/bin/bash
# AWS Deployment Script for Bombest Beats
# This script deploys the backend to AWS EC2 via Docker Hub

set -e

# Configuration - UPDATE THESE
DOCKER_USERNAME="${DOCKER_USERNAME:-thomasphillips3}"  # Your Docker Hub username
IMAGE_NAME="bombest-beats"

echo "🚀 Deploying Bombest Beats to AWS..."

# Check for Docker
if ! command -v docker &> /dev/null; then
    echo "❌ Docker not found. Install Docker Desktop first."
    exit 1
fi

# Step 1: Build Docker image
echo "📦 Building Docker image..."
docker build -t $IMAGE_NAME:latest ./beets-backend

# Step 2: Push to Docker Hub
echo "☁️ Pushing to Docker Hub..."
echo "   If not logged in, run: docker login"

docker tag $IMAGE_NAME:latest $DOCKER_USERNAME/$IMAGE_NAME:latest
docker push $DOCKER_USERNAME/$IMAGE_NAME:latest

echo "✅ Image pushed: $DOCKER_USERNAME/$IMAGE_NAME:latest"
echo ""
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "🖥️  NEXT STEPS - Deploy to AWS EC2:"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo ""
echo "1. Create EC2 instance in AWS Console:"
echo "   - AMI: Amazon Linux 2023"
echo "   - Instance type: t3.micro (~\$8/mo or free tier)"
echo "   - Security group: Allow ports 22, 8338"
echo ""
echo "2. SSH into the instance and run:"
echo "   sudo yum install -y docker"
echo "   sudo service docker start"
echo "   sudo docker run -d -p 8338:8338 --restart unless-stopped $DOCKER_USERNAME/$IMAGE_NAME:latest"
echo ""
echo "3. Add EC2 public IP to Cloudflare as failover origin"
echo ""
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

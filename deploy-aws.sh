#!/bin/bash
# AWS Deployment Script for Bombest Beats
# This script deploys the backend to AWS EC2

set -e

# Configuration
AWS_REGION="${AWS_REGION:-us-west-2}"
INSTANCE_TYPE="t3.micro"  # ~$8/month (or free tier eligible)
KEY_NAME="${AWS_KEY_NAME:-bombest-key}"
SECURITY_GROUP="${AWS_SECURITY_GROUP:-bombest-sg}"

echo "🚀 Deploying Bombest Beats to AWS..."

# Check for AWS CLI
if ! command -v aws &> /dev/null; then
    echo "❌ AWS CLI not found. Install with: brew install awscli"
    exit 1
fi

# Check for Docker
if ! command -v docker &> /dev/null; then
    echo "❌ Docker not found. Install Docker Desktop first."
    exit 1
fi

# Step 1: Build Docker image
echo "📦 Building Docker image..."
docker build -t bombest-beats:latest ./beets-backend

# Step 2: Push to ECR (or Docker Hub)
echo "☁️ Pushing to registry..."
AWS_ACCOUNT_ID=$(aws sts get-caller-identity --query Account --output text)
ECR_REPO="${AWS_ACCOUNT_ID}.dkr.ecr.${AWS_REGION}.amazonaws.com/bombest-beats"

# Create ECR repo if it doesn't exist
aws ecr describe-repositories --repository-names bombest-beats --region $AWS_REGION 2>/dev/null || \
    aws ecr create-repository --repository-name bombest-beats --region $AWS_REGION

# Login to ECR
aws ecr get-login-password --region $AWS_REGION | docker login --username AWS --password-stdin $ECR_REPO

docker tag bombest-beats:latest $ECR_REPO:latest
docker push $ECR_REPO:latest

echo "✅ Image pushed to ECR: $ECR_REPO:latest"

# Step 3: Create/Update EC2 instance
echo "🖥️ Setting up EC2 instance..."

# User data script for EC2 to run the container
USER_DATA=$(cat <<'EOF'
#!/bin/bash
yum update -y
yum install -y docker
service docker start
usermod -a -G docker ec2-user

# Login to ECR and run container
$(aws ecr get-login-password --region us-west-2 | docker login --username AWS --password-stdin $ECR_REPO)
docker pull $ECR_REPO:latest
docker run -d --name bombest -p 8338:8338 --restart unless-stopped $ECR_REPO:latest
EOF
)

echo "📋 EC2 User Data prepared"
echo ""
echo "To complete deployment:"
echo "1. Create EC2 instance in AWS Console with:"
echo "   - AMI: Amazon Linux 2023"
echo "   - Instance type: $INSTANCE_TYPE"
echo "   - Security group allowing ports 22, 8338"
echo ""
echo "2. SSH into instance and run:"
echo "   docker run -d -p 8338:8338 $ECR_REPO:latest"
echo ""
echo "3. Update Cloudflare to add AWS as failover origin"

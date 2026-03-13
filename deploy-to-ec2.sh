#!/usr/bin/env bash
# Build the Docker image, push to Docker Hub, and update the running container on EC2.
# Run from your MacBook (or any machine with Docker and AWS CLI).
#
# EC2 free tier: t3.micro instance (setup-ec2-aws-cli.sh).
#
# Prerequisites: docker login, AWS CLI configured, EC2 instance with tag Name=bombest-beats
# and SSM (or set INSTANCE_ID). S3 env vars must be set on the instance (e.g. .env.bombest).
#
# Usage: ./deploy-to-ec2.sh [REGION]
#   REGION defaults to us-west-2.
#
# Web app: Point beats.bom.best DNS (Cloudflare) to the EC2 public IP.
# Deploy frontend: ./scripts/deploy-frontend.sh (or CLOUDFRONT_DIST_ID=E1RBYOEP5K0UI3 ./scripts/deploy-frontend.sh)

set -e

REGION="${1:-us-west-2}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

echo "=== 1. Build and push image ==="
"$SCRIPT_DIR/deploy-aws.sh"

echo ""
echo "=== 2. Deploy container to EC2 ($REGION) ==="
"$SCRIPT_DIR/deploy-container-to-ec2.sh" "$REGION"

echo ""
echo "Done. Backend is live on EC2."
echo ""
echo "Frontend: Run ./scripts/deploy-frontend.sh to deploy bom.best/beats to S3/CloudFront."

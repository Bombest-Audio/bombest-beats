#!/usr/bin/env bash
# Build the Docker image, push to Docker Hub, and update the running container on EC2.
# Run from your MacBook (or any machine with Docker and AWS CLI).
#
# Prerequisites: docker login, AWS CLI configured, EC2 instance with tag Name=bombest-beats
# and SSM (or set INSTANCE_ID). S3 env vars must be set on the instance (e.g. .env.bombest).
#
# Usage: ./deploy-to-ec2.sh [REGION]
#   REGION defaults to us-west-2.
#   Example: ./deploy-to-ec2.sh
#            INSTANCE_ID=i-xxx ./deploy-to-ec2.sh us-west-2

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

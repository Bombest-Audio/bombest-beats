#!/usr/bin/env bash
set -euo pipefail

# Simple S3 setup script for Bombest Beats
# - Creates bucket if missing
# - Enables public read for objects under music/
# - Sets CORS to allow audio streaming

BUCKET="${BUCKET:-bombest-beats-music}"
REGION="${REGION:-us-west-2}"

echo "Using bucket: $BUCKET (region: $REGION)"

# Create bucket if it doesn't exist
if ! aws s3api head-bucket --bucket "$BUCKET" 2>/dev/null; then
  echo "Bucket not found. Creating..."
  aws s3api create-bucket \
    --bucket "$BUCKET" \
    --region "$REGION" \
    --create-bucket-configuration LocationConstraint="$REGION"
else
  echo "Bucket already exists."
fi

echo "Blocking public access..."
aws s3api put-public-access-block --bucket "$BUCKET" --public-access-block-configuration '{
  "BlockPublicAcls": true,
  "IgnorePublicAcls": true,
  "BlockPublicPolicy": true,
  "RestrictPublicBuckets": true
}'

echo "Clearing bucket policy (private)..."
aws s3api delete-bucket-policy --bucket "$BUCKET" || true

echo "Enabling CORS (GET/HEAD) for private clients..."
cat > /tmp/s3-cors.json <<'CORS'
{
  "CORSRules": [
    {
      "AllowedOrigins": ["*"],
      "AllowedMethods": ["GET", "HEAD"],
      "AllowedHeaders": ["*"],
      "ExposeHeaders": ["ETag"],
      "MaxAgeSeconds": 3000
    }
  ]
}
CORS
aws s3api put-bucket-cors --bucket "$BUCKET" --cors-configuration file:///tmp/s3-cors.json

echo "Ensuring default encryption..."
aws s3api put-bucket-encryption \
  --bucket "$BUCKET" \
  --server-side-encryption-configuration '{
    "Rules": [
      { "ApplyServerSideEncryptionByDefault": { "SSEAlgorithm": "AES256" } }
    ]
  }'

echo "Enabling versioning..."
aws s3api put-bucket-versioning --bucket "$BUCKET" --versioning-configuration Status=Enabled

echo "Done. Upload a file with: aws s3 cp yourfile.mp3 s3://$BUCKET/music/"

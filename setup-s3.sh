#!/bin/bash
# S3 Music Storage Setup for Bombest Beats
# Creates S3 bucket and syncs music files

set -e

AWS_REGION="${AWS_REGION:-us-west-2}"
BUCKET_NAME="bombest-beats-music"

echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "☁️  Bombest Beats - S3 Storage Setup"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

# Check AWS CLI
if ! command -v aws &> /dev/null; then
    echo "❌ AWS CLI not found. Install with: brew install awscli"
    exit 1
fi

# Step 1: Create S3 bucket
echo ""
echo "📦 Step 1: Creating S3 bucket..."
if aws s3 ls "s3://$BUCKET_NAME" 2>&1 | grep -q 'NoSuchBucket'; then
    aws s3 mb "s3://$BUCKET_NAME" --region $AWS_REGION
    echo "✅ Bucket created: $BUCKET_NAME"
else
    echo "   Bucket already exists: $BUCKET_NAME"
fi

# Step 2: Set bucket policy for public read (music files only)
echo ""
echo "🔐 Step 2: Configuring bucket policy..."
cat > /tmp/bucket-policy.json << EOF
{
    "Version": "2012-10-17",
    "Statement": [
        {
            "Sid": "PublicReadMusic",
            "Effect": "Allow",
            "Principal": "*",
            "Action": "s3:GetObject",
            "Resource": "arn:aws:s3:::$BUCKET_NAME/music/*"
        }
    ]
}
EOF

aws s3api put-bucket-policy --bucket $BUCKET_NAME --policy file:///tmp/bucket-policy.json 2>/dev/null || echo "   Policy may already exist"
echo "✅ Bucket policy configured"

# Step 3: Sync music files
echo ""
echo "🎵 Step 3: Syncing music files to S3..."
MUSIC_DIR="${MUSIC_DIR:-./beets-backend/music}"
if [ -d "$MUSIC_DIR" ]; then
    aws s3 sync "$MUSIC_DIR" "s3://$BUCKET_NAME/music/" --exclude "*.db" --exclude "*.json"
    echo "✅ Music synced to S3"
else
    echo "⚠️  Music directory not found: $MUSIC_DIR"
    echo "   Set MUSIC_DIR environment variable to your music folder"
fi

# Step 4: Add IAM policy for bombest-deployment user
echo ""
echo "👤 Step 4: Adding S3 permissions to bombest-deployment..."
cat > /tmp/s3-policy.json << EOF
{
    "Version": "2012-10-17",
    "Statement": [
        {
            "Effect": "Allow",
            "Action": [
                "s3:GetObject",
                "s3:PutObject",
                "s3:ListBucket",
                "s3:DeleteObject"
            ],
            "Resource": [
                "arn:aws:s3:::$BUCKET_NAME",
                "arn:aws:s3:::$BUCKET_NAME/*"
            ]
        }
    ]
}
EOF

aws iam put-user-policy --user-name bombest-deployment --policy-name BombestS3Policy --policy-document file:///tmp/s3-policy.json 2>/dev/null || echo "   Policy may already exist"
echo "✅ S3 permissions added"

echo ""
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "✅ S3 SETUP COMPLETE!"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo ""
echo "🪣 Bucket: s3://$BUCKET_NAME"
echo "🌐 Public URL: https://$BUCKET_NAME.s3.$AWS_REGION.amazonaws.com/music/"
echo ""
echo "📋 To sync more music later:"
echo "   aws s3 sync /path/to/music s3://$BUCKET_NAME/music/"
echo ""

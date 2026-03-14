#!/usr/bin/env bash
# Deploy music-frontend to S3 (bombest-beats-web) and optionally invalidate CloudFront.
# Run from repo root.
#
# Required AWS permissions: s3:PutObject, s3:DeleteObject, s3:ListBucket on bombest-beats-web;
# cloudfront:CreateInvalidation (if CLOUDFRONT_DIST_ID is set).
#
# Env vars:
#   FRONTEND_BUCKET    S3 bucket (default: bombest-beats-web)
#   FRONTEND_PATH      S3 path prefix (default: beats)
#   CLOUDFRONT_DIST_ID CloudFront distribution ID; if set, invalidates /beats/*
#   REACT_APP_API_BASE API base URL (default: https://beats.bom.best)
#
# Usage:
#   ./scripts/deploy-frontend.sh
#   CLOUDFRONT_DIST_ID=E1RBYOEP5K0UI3 ./scripts/deploy-frontend.sh

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
FRONTEND_DIR="$REPO_ROOT/music-frontend"

BUCKET="${FRONTEND_BUCKET:-bombest-beats-web}"
PATH_PREFIX="${FRONTEND_PATH:-beats}"
API_BASE="${REACT_APP_API_BASE:-https://beats.bom.best}"

cd "$FRONTEND_DIR"
echo "=== Building frontend (PUBLIC_URL=/$PATH_PREFIX, REACT_APP_API_BASE=$API_BASE) ==="
PUBLIC_URL="/$PATH_PREFIX" \
  GENERATE_SOURCEMAP=false \
  REACT_APP_API_BASE="$API_BASE" \
  NODE_OPTIONS=--openssl-legacy-provider \
  npm run build

echo ""
echo "=== Syncing to s3://$BUCKET/$PATH_PREFIX/ ==="
aws s3 sync build/ "s3://$BUCKET/$PATH_PREFIX/" --delete

if [ -n "$CLOUDFRONT_DIST_ID" ]; then
  echo ""
  echo "=== Invalidating CloudFront ($CLOUDFRONT_DIST_ID) ==="
  aws cloudfront create-invalidation \
    --distribution-id "$CLOUDFRONT_DIST_ID" \
    --paths "/$PATH_PREFIX/*"
fi

echo ""
echo "Done. Frontend deployed to s3://$BUCKET/$PATH_PREFIX/"

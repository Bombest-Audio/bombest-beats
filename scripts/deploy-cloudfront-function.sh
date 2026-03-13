#!/usr/bin/env bash
# Create/update CloudFront Function and associate with distribution.
# Run from repo root.
#
# Env vars:
#   CLOUDFRONT_DIST_ID  Distribution ID (default: E1RBYOEP5K0UI3)
#
# Usage: ./scripts/deploy-cloudfront-function.sh

set -e

if ! command -v jq >/dev/null 2>&1; then
  echo "jq is required. Install: brew install jq (macOS) or apt install jq (Linux)"
  exit 1
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
FUNCTION_FILE="$REPO_ROOT/cloudfront-functions/beats-spa-rewrite.js"
FUNCTION_NAME="beats-spa-rewrite"
DIST_ID="${CLOUDFRONT_DIST_ID:-E1RBYOEP5K0UI3}"
FUNC_CONFIG='{"Comment":"SPA rewrite for /beats","Runtime":"cloudfront-js-2.0"}'

if ! aws cloudfront describe-function --name "$FUNCTION_NAME" >/dev/null 2>&1; then
  echo "=== Creating CloudFront Function: $FUNCTION_NAME ==="
  aws cloudfront create-function \
    --name "$FUNCTION_NAME" \
    --function-config "$FUNC_CONFIG" \
    --function-code "fileb://$FUNCTION_FILE"
  echo "Function created."
else
  echo "=== Updating CloudFront Function: $FUNCTION_NAME ==="
  ETAG=$(aws cloudfront describe-function --name "$FUNCTION_NAME" --query 'ETag' --output text)
  aws cloudfront update-function \
    --name "$FUNCTION_NAME" \
    --function-config "$FUNC_CONFIG" \
    --function-code "fileb://$FUNCTION_FILE" \
    --if-match "$ETAG"
  echo "Function updated."
fi

echo "Publishing to LIVE..."
aws cloudfront publish-function --name "$FUNCTION_NAME" --if-match "$(aws cloudfront describe-function --name "$FUNCTION_NAME" --query 'ETag' --output text)"

ACCOUNT=$(aws sts get-caller-identity --query Account --output text)
FUNC_ARN="arn:aws:cloudfront::${ACCOUNT}:function/${FUNCTION_NAME}"

echo ""
echo "=== Associating function with distribution $DIST_ID ==="

CONFIG=$(aws cloudfront get-distribution-config --id "$DIST_ID" --output json)
ETAG=$(echo "$CONFIG" | jq -r '.ETag')
# Merge our function into existing FunctionAssociations (don't replace)
DIST_CFG=$(echo "$CONFIG" | jq --arg arn "$FUNC_ARN" '
  ($.DistributionConfig.DefaultCacheBehavior.FunctionAssociations.Items // []) as $items |
  ($items | map(select(.EventType != "viewer-request")) + [{"EventType": "viewer-request", "FunctionARN": $arn}]) as $merged |
  .DistributionConfig.DefaultCacheBehavior.FunctionAssociations.Quantity = ($merged | length) |
  .DistributionConfig.DefaultCacheBehavior.FunctionAssociations.Items = $merged |
  .DistributionConfig
')

aws cloudfront update-distribution --id "$DIST_ID" --distribution-config "$(echo "$DIST_CFG" | jq -c '.')" --if-match "$ETAG" --output text >/dev/null

echo ""
echo "Done. CloudFront is propagating (2–5 min). Test: https://beats-app.bom.best/beats/"

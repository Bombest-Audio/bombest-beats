#!/usr/bin/env bash
# Add beats-app.bom.best alias to CloudFront distribution (requires validated ACM cert).
# Run after adding the ACM DNS validation CNAME in Cloudflare.
#
# Env vars:
#   CLOUDFRONT_DIST_ID  Distribution ID (default: E1RBYOEP5K0UI3)
#   ACM_CERT_ARN        ACM cert ARN for beats-app.bom.best (auto-detected if in us-east-1)
#
# Usage: ./scripts/cloudfront-add-alias.sh

set -e

DIST_ID="${CLOUDFRONT_DIST_ID:-E1RBYOEP5K0UI3}"
CERT_ARN="${ACM_CERT_ARN}"

if [ -z "$CERT_ARN" ]; then
  CERT_ARN=$(aws acm list-certificates --region us-east-1 --query \
    "CertificateSummaryList[?DomainName=='beats-app.bom.best'].CertificateArn" --output text)
fi
if [ -z "$CERT_ARN" ] || [ "$CERT_ARN" = "None" ]; then
  echo "No ACM cert for beats-app.bom.best. Request one first or set ACM_CERT_ARN."
  exit 1
fi

STATUS=$(aws acm describe-certificate --certificate-arn "$CERT_ARN" --region us-east-1 --query 'Certificate.Status' --output text)
if [ "$STATUS" != "ISSUED" ]; then
  echo "Cert not validated yet (status: $STATUS). Add the DNS validation CNAME in Cloudflare first."
  exit 1
fi

echo "=== Adding beats-app.bom.best to CloudFront $DIST_ID ==="

CONFIG=$(aws cloudfront get-distribution-config --id "$DIST_ID" --output json)
ETAG=$(echo "$CONFIG" | jq -r '.ETag')
DIST_CFG=$(echo "$CONFIG" | jq --arg cert "$CERT_ARN" '
  .DistributionConfig.Aliases.Quantity = 1 |
  .DistributionConfig.Aliases.Items = ["beats-app.bom.best"] |
  .DistributionConfig.ViewerCertificate = {
    "ACMCertificateArn": $cert,
    "SSLSupportMethod": "sni-only",
    "MinimumProtocolVersion": "TLSv1.2_2021",
    "CertificateSource": "acm"
  } |
  .DistributionConfig
')

aws cloudfront update-distribution --id "$DIST_ID" \
  --distribution-config "$(echo "$DIST_CFG" | jq -c '.')" \
  --if-match "$ETAG" \
  --query 'Distribution.{Status:Status,DomainName:DomainName}' \
  --output table

echo ""
echo "Propagation takes 5–15 min. Then: https://beats-app.bom.best/beats/"

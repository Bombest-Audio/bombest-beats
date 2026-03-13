# Fix CloudFront 403 for beats-app.bom.best/beats/

## Root cause

1. **S3 path**: Requests to `/beats` and `/beats/` returned 403 because S3 has no object at those keys (only `/beats/index.html`).
2. **CloudFront alias**: `beats-app.bom.best` was not in the distribution's alternate domain names, so requests with that Host got 403.

## Fixes applied

### 1. CloudFront Function (SPA rewrite)

A viewer-request function rewrites `/beats`, `/beats/`, and `/beats/*` (routes without file extensions) to `/beats/index.html` before hitting S3.

- **Function**: `beats-spa-rewrite`
- **Deploy**: `./scripts/deploy-cloudfront-function.sh`
- **Code**: `cloudfront-functions/beats-spa-rewrite.js`

### 2. Custom error responses

403/404 responses now serve `/beats/index.html` (not `/index.html`).

### 3. ACM certificate and alias (manual step)

An ACM cert was requested for `beats-app.bom.best`. To finish setup:

**Add DNS validation in Cloudflare:**

1. Cloudflare → bom.best → DNS → Records
2. Add CNAME:
   - **Name**: `_4fb32654d11e2ee0e45d42c0ede673f5.beats-app`
   - **Target**: `_5dd64b6618145073d041ac88d2815d2a.jkddzztszm.acm-validations.aws.`
   - **Proxy**: DNS only (grey cloud)
3. Wait 5–30 minutes for ACM to validate.
4. Run:

```bash
./scripts/cloudfront-add-alias.sh
```

That script adds `beats-app.bom.best` to the CloudFront distribution and attaches the ACM cert.

## Verify

- **CloudFront URL**: https://d37qdccady5d3d.cloudfront.net/beats/ → 200
- **Custom domain** (after alias): https://beats-app.bom.best/beats/ → 200

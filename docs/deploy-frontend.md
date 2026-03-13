# Deploy Frontend (bom.best/beats)

Single-page reference for deploying the music-frontend to S3 and CloudFront.

## Quick deploy

```bash
./scripts/deploy-frontend.sh
# With CloudFront cache invalidation:
CLOUDFRONT_DIST_ID=E1RBYOEP5K0UI3 ./scripts/deploy-frontend.sh
```

## Build environment

| Env var | Default | Purpose |
|---------|---------|---------|
| `PUBLIC_URL` | `/beats` | Base path for assets |
| `REACT_APP_API_BASE` | `https://beats.bom.best` | API URL the app calls |
| `GENERATE_SOURCEMAP` | `false` | Disable source maps in prod |
| `NODE_OPTIONS` | `--openssl-legacy-provider` | Required for older Node / OpenSSL |

## S3 & CloudFront

| Item | Value |
|------|-------|
| S3 bucket | `bombest-beats-web` |
| S3 path | `/beats/` |
| CloudFront dist ID | `E1RBYOEP5K0UI3` |

Override via `FRONTEND_BUCKET` and `FRONTEND_PATH` env vars.

## CloudFront 403 on /beats/

If `https://beats-app.bom.best/beats/` returns 403, see [cloudfront-403-fix.md](cloudfront-403-fix.md). A CloudFront Function rewrites `/beats` and `/beats/` to `/beats/index.html`. The custom domain requires an ACM cert and alias; add the DNS validation CNAME in Cloudflare, then run `./scripts/cloudfront-add-alias.sh`.

## bom.best DNS not resolving

If you see "bom.best's DNS address could not be found" or `DNS_PROBE_POSSIBLE`:

1. In **Cloudflare** → **bom.best** → **DNS** → **Records**, add a record for the root:
   - **Type**: CNAME (or A if using Tunnel/IP)
   - **Name**: `@` (root)
   - **Content**: `d37qdccady5d3d.cloudfront.net`
   - **Proxy**: Proxied (orange cloud)

2. **CloudFront**: Add `bom.best` to the distribution's alternate domain names (AWS Console → CloudFront → E1RBYOEP5K0UI3 → Edit → Alternate domain names). You need an ACM certificate for `bom.best` (request in us-east-1).

3. **Immediate workaround**: Open `https://beats-app.bom.best/beats/` — it uses the same CloudFront origin.

## Cloudflare DNS / routing

- **beats.bom.best**, **beats-aws.bom.best**: A records (Proxied) → EC2 public IP (e.g. 16.147.88.132).
- **bom.best** (root): Must have a DNS record or the site won't load ("DNS address could not be found"). Use one of:
  - **CNAME** `bom.best` → `d37qdccady5d3d.cloudfront.net` (Proxied). Requires CloudFront to have `bom.best` as an alternate domain (and ACM cert).
  - **Tunnel** public hostname `bom.best` → `https://d37qdccady5d3d.cloudfront.net` (Cloudflare creates DNS automatically).
  - **Workaround**: Use `https://beats-app.bom.best/beats/` (CNAME to CloudFront already exists).

## Required AWS permissions

- `s3:PutObject`, `s3:DeleteObject`, `s3:ListBucket` on `bombest-beats-web`
- `cloudfront:CreateInvalidation` (if using `CLOUDFRONT_DIST_ID`)

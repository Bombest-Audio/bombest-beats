# Deploy Scripts

Home server deployment (deploy.sh, deploy_docker.sh, cloudflared tunnel) is deprecated. Use EC2 (free tier).

> **Note:** Scripts marked "(PR #5)" are introduced in the infrastructure PR and are not on `main` yet.

**Full guide:** [deploy-backend.md](deploy-backend.md) — SSM permissions, manual fallback, nginx CORS deploy.

## Primary: deploy-to-ec2.sh

Deploys the backend (Docker image + container) to AWS EC2 (t3.micro free tier).

```bash
./deploy-to-ec2.sh [REGION]
# REGION defaults to us-west-2
```

**Prerequisites:**
- Existing EC2 instance with tag `Name=bombest-beats`
- S3 credentials in `/home/ec2-user/.env.bombest` on the instance
- SSM agent with IAM role (or use `INSTANCE_ID=i-xxx` if needed)
- Your CLI user has SSM permissions: `./scripts/add-ssm-permissions.sh` (PR #5)

## Nginx CORS config: deploy-nginx-to-ec2.sh (PR #5)

Updates `/etc/nginx/conf.d/bombest-beats.conf` on EC2 (CORS, proxy to Flask). Run when you change `nginx-ec2.conf`:

```bash
./deploy-nginx-to-ec2.sh [REGION]
```

If SSM fails, the script prints a block to paste into EC2 Instance Connect. See [deploy-backend.md](deploy-backend.md) for details.

## First-time EC2 setup

1. `./deploy-aws.sh` — build and push Docker image
2. `ATTACH_SSM_ROLE=1 ./setup-ec2-aws-cli.sh [REGION]` — create t3.micro instance (free tier)
3. SSH in, set S3 env vars in `.env.bombest`, run `./run-bombest-beats.sh`

## S3 bucket security: setup-s3-simple.sh

Configures the `bombest-beats-music` S3 bucket with correct security settings. **Run this if the bucket has public access enabled** (see [Infrastructure Audit](architecture.md#infrastructure-audit)).

```bash
./setup-s3-simple.sh
```

Creates the bucket if missing, enables Block Public Access, removes any public bucket policy, sets CORS for GET/HEAD, enables SSE-S3 encryption, and enables Versioning.

## Deprecated: deploy-ec2.sh

> **Do not use.** Superseded by `deploy-to-ec2.sh`, which builds and pushes the canonical `${DOCKER_USERNAME:-thomasphillips3}/bombest-beats:latest` image and updates the EC2 container via SSM.

## Cloudflare DNS (required for web app)

Point **beats.bom.best** at your EC2 public IP so the web app can reach the API:

- In Cloudflare DNS, add an **A record**: `beats.bom.best` → `<EC2_PUBLIC_IP>`
- Proxy through Cloudflare (recommended) or DNS only
- **SSL/TLS mode**: use **Full (Strict)** with a Cloudflare Origin Certificate on nginx (recommended). Use **Flexible** only if origin is HTTP-only (Cloudflare→origin traffic will be unencrypted)
- **Port 80**: EC2 must listen on port 80. New instances get nginx via setup script. For existing instances, run `./scripts/ec2-setup-nginx.sh` on the EC2 host, and add port 80 to the security group.

## Web frontend (bom.best/beats)

Deploy with the frontend script (builds, syncs to S3, optionally invalidates CloudFront):

```bash
./scripts/deploy-frontend.sh
# With CloudFront cache invalidation:
CLOUDFRONT_DIST_ID=E1RBYOEP5K0UI3 ./scripts/deploy-frontend.sh
```

**S3 bucket:** `bombest-beats-web`, path `/beats/`. Override with `FRONTEND_BUCKET` and `FRONTEND_PATH`.
**Cloudflare DNS:** `beats.bom.best` and `beats-aws.bom.best` are A records to EC2 (Proxied). `bom.best/beats` is served via Cloudflare DNS (CNAME to CloudFront).

Full details: [deploy-frontend.md](deploy-frontend.md).

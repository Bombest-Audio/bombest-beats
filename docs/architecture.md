# Architecture Overview

This document is the source of truth for deployment, S3, EC2, and making users admin.

**Note:** Home server deployment (deploy.sh, deploy_docker.sh, cloudflared tunnel) is deprecated. Use EC2 as the primary deployment target.

## High-Level Flow
- Source of truth: Beets `music/library.db` and files under `beets-backend/music/` on the server.
- Sync: `sync-to-s3.sh` mirrors the music directory to `s3://bombest-beats-music/music/`. Run it after changing metadata (e.g. from the app's Edit metadata) so S3 stays in sync.
- Storage: S3 bucket `bombest-beats-music` (see [Security & Access](#security--access) for current state — **action needed**).
- Client: Android app lists tracks from backend GET `/library` and streams via backend `/stream/<id>` (redirect to S3 or local file).

## Security & Access

> **⚠ Current state does not match intended config.** Block Public Access is **OFF** and a bucket policy grants `s3:GetObject` to `Principal: *` (public read). Versioning is **not enabled**. Run `./setup-s3-simple.sh` to lock down the bucket (enables Block Public Access, removes the public policy, enables Versioning). See [Infrastructure Audit](#infrastructure-audit) below.

- **Intended** (after running `setup-s3-simple.sh`): Bucket is private (Block Public Access enabled). No public read or list.
- Default encryption: SSE-S3.
- Versioning: **not currently enabled** — run `setup-s3-simple.sh` to enable.
- IAM policy (least privilege for sync user/role):
  - `s3:ListBucket` on `arn:aws:s3:::bombest-beats-music` with `prefix` limited to `music/`.
  - `s3:GetObject`, `s3:PutObject`, `s3:DeleteObject` on `arn:aws:s3:::bombest-beats-music/music/*`.
- Credentials: stored locally (not in git) under `~/.aws` with profile `bombest-beats-sync` ( `.local/` is ignored).

## Sync Details
- Script: `sync-to-s3.sh` (from repo root)
  - Defaults: `MUSIC_DIR` = `$REPO_ROOT/beets-backend/music`, `BUCKET=bombest-beats-music`, `REGION=us-west-2`.
  - Honors `AWS_PROFILE` (recommended: `bombest-beats-sync`).
  - Options: `WATCH=1` uses `inotifywait`; otherwise one-shot/cron.
- Cron (user `thomas`): runs every 5 minutes (replace `/path/to/repo` with your actual repo path)
  ```
  AWS_PROFILE=bombest-beats-sync /path/to/repo/sync-to-s3.sh >> /path/to/repo/sync-to-s3.log 2>&1
  ```

## Bucket Setup Script
- Script: `setup-s3-simple.sh` (from repo root)
  - Creates bucket if missing.
  - Enables Block Public Access.
  - Clears any public policy.
  - Sets CORS for GET/HEAD.
  - Enables SSE-S3 and Versioning.

## Verification
- Log: `tail -n 50 sync-to-s3.log` (from repo root)
- List bucket: `AWS_PROFILE=bombest-beats-sync aws s3 ls s3://bombest-beats-music/music/`
- Manual sync: `AWS_PROFILE=bombest-beats-sync ./sync-to-s3.sh` (from repo root)

## Client Notes
- Android app (Compose + Media3) lists tracks primarily from **S3** (ListObjects). It also fetches GET `/library` to build a path→backend id map so tracks that exist in the beets library can be edited in-app (Edit metadata). Playback URLs are S3-based; stream can also go via backend `/stream/<id>` (redirect to S3 or local file).
- **After editing metadata**: When you change title, artist, or album from the app (Edit metadata) or via PUT `/track/<id>` on the backend, run `sync-to-s3.sh` so the updated files and folder structure are reflected in S3. The app refreshes the list from S3 after a successful edit; running sync ensures S3 and the on-disk library stay in sync.

## Frontend hosting (bom.best/beats)

The web frontend is served from **S3** (`bombest-beats-web`, region **us-east-1**) with path prefix `/beats/`, behind **CloudFront** (distribution ID `E1RBYOEP5K0UI3`, aliases: `beats-app.bom.best` and `bom.best`). **Cloudflare** DNS routes `bom.best/beats` to CloudFront. Deploy with:

```bash
./scripts/deploy-frontend.sh  # added in PR #5
# With CloudFront invalidation:
CLOUDFRONT_DIST_ID=E1RBYOEP5K0UI3 ./scripts/deploy-frontend.sh
```

See [deploy-which-script.md](deploy-which-script.md) for full frontend deploy instructions. The deploy and nginx scripts (`deploy-nginx-to-ec2.sh`, `scripts/deploy-frontend.sh`, `scripts/add-ssm-permissions.sh`, `scripts/ec2-setup-nginx.sh`, `nginx-ec2.conf`) are introduced in the infrastructure PR (#5).

## Deploy to AWS (free tier)

Backend runs in Docker on EC2. Stay within [AWS Free Tier](https://aws.amazon.com/free/) (first 12 months): one **t3.micro**, 30 GB root EBS → about **$0/mo**.

### 1. Build and push image (from your machine)

- Start Docker Desktop.
- From repo root: `./deploy-aws.sh`
- Pushes `thomasphillips3/bombest-beats:latest` to Docker Hub (image includes `music/` and beets import; build where `beets-backend/music/` exists).

### 2a. Create EC2 with AWS CLI (recommended)

From repo root (requires [AWS CLI](https://aws.amazon.com/cli/) installed and configured: `aws configure`):

```bash
./setup-ec2-aws-cli.sh [REGION]
# REGION defaults to us-west-2. Example: ./setup-ec2-aws-cli.sh us-east-1
```

Optional: restrict SSH to your current IP:

```bash
RESTRICT_SSH=1 ./setup-ec2-aws-cli.sh us-west-2
```

The script creates a key pair (saves `bombest-beats-key.pem` in the current directory), a security group (ports 22 and 8338), and a t3.micro instance with 30 GB root. It installs Docker and pulls the image via user data. At the end it prints the public IP, SSH command, and the one-time steps to set S3 env vars and run `./run-bombest-beats.sh` on the instance. Port 80 (nginx) is added separately via `./scripts/ec2-setup-nginx.sh`.

### 2b. Create EC2 instance (AWS Console)

- **Region**: e.g. us-east-1, us-west-2 (match S3/Cloudflare).
- **AMI**: Amazon Linux 2023.
- **Instance type**: **t3.micro** only (free tier; do not use t3.small or larger).
- **Storage**: Default **30 GB** root volume (within 30 GB EBS free tier).
- **Security group**: Inbound — **22** (SSH), **80** (nginx), **5002** (legacy — remove; see [Infrastructure Audit](#infrastructure-audit)), **8338** (Flask). Restrict 22 by your IP if possible.
- **Key pair**: Create or select one for SSH.
- **Elastic IP**: Optional. Free tier includes one only when attached; do not leave it unattached or you may be charged.

### 3. Persist data (on root volume, no extra EBS)

If you used the AWS CLI script, `/data/beets` and the run script are already set up on the instance.

Use a directory on the instance root volume so DB survives container restarts:

- On EC2: `/data/beets` (bind-mount into container as `/app/music`).
- First run: copy existing `library.db` (and optionally music files) into `/data/beets`, or let the container create a new DB.

### 4. Run backend on EC2

SSH in, then:

```bash
sudo yum install -y docker
sudo systemctl enable docker && sudo systemctl start docker
sudo usermod -aG docker ec2-user
# Log out and back in so docker group applies

sudo mkdir -p /data/beets
sudo chown ec2-user:ec2-user /data/beets
```

Set env vars (replace with your values), then run the container:

```bash
export S3_BUCKET=bombest-beats-music
export AWS_ACCESS_KEY_ID=your_access_key
export AWS_SECRET_ACCESS_KEY=your_secret_key
export S3_REGION=us-west-2
# Optional: JWT and email (see upload_server.py / config)
# export JWT_SECRET_KEY=...
# (email in config.yaml or env as needed)

sudo docker pull thomasphillips3/bombest-beats:latest
sudo docker run -d --name bombest-beats -p 8338:8338 --restart unless-stopped \
  -v /data/beets:/app/music \
  -e S3_BUCKET -e AWS_ACCESS_KEY_ID -e AWS_SECRET_ACCESS_KEY -e S3_REGION \
  thomasphillips3/bombest-beats:latest
```

### 5. Env var checklist

| Env var | Required for S3 streaming | Notes |
|---------|---------------------------|--------|
| `S3_BUCKET` | Yes | e.g. `bombest-beats-music` |
| `AWS_ACCESS_KEY_ID` | Yes | IAM user with s3:GetObject (and ListBucket if app lists from S3) |
| `AWS_SECRET_ACCESS_KEY` | Yes | |
| `S3_REGION` | Yes | e.g. `us-west-2` |
| `S3_ENDPOINT` | No | Only for R2/MinIO |
| JWT / email | No (for basic run) | For auth and notifications; see `config.yaml` / Flask app |

### 6. Point app and Cloudflare at EC2

- App backend URL: `http://<EC2-public-IP>:8338` or a domain that points to that IP.
- Cloudflare: Add EC2 as origin (e.g. `beats.bom.best` → EC2 IP, `beats-aws.bom.best` → same EC2 IP). Both hostnames point to the same instance; there is no automatic failover to a different server. Cloudflare connects to **port 80**.
- EC2 runs nginx on port 80, proxying to Flask on 8338. Install nginx with `./scripts/ec2-setup-nginx.sh` (PR #5) on the EC2 host and add port 80 to the security group.
- **502 on login?** Ensure (1) security group allows port 80, (2) nginx is running (`sudo systemctl status nginx`), (3) Cloudflare SSL mode: **Full (Strict)** with a Cloudflare Origin Certificate on nginx, or **Flexible** if origin is HTTP-only (unencrypted Cloudflare→origin; acceptable for private backend behind Cloudflare proxy).

### Use frontend with EC2 backend (local dev)

To run the music-frontend locally and have it talk to the EC2 backend (auth, library, upload, playlists, etc.) instead of localhost:

```bash
cd music-frontend
REACT_APP_API_BASE=http://<EC2_PUBLIC_IP>:8338 npm start
```

Then open [http://localhost:3000](http://localhost:3000) and log in (admin for Upload). To use a different backend, set `REACT_APP_API_BASE` to that URL (no trailing slash). No code changes are required.

### On EC2: make a user admin

Admin and DB changes are done on the EC2 instance, not locally. To give a user admin role (so they can upload and use admin-only features):

1. SSH (or SSM) into the instance.
2. From the repo root (or copy the script onto the instance), run:
   ```bash
   chmod +x scripts/ec2-make-admin.sh
   ./scripts/ec2-make-admin.sh thomas
   ```
   Use another username as the argument if needed. The script runs `docker exec` against the `bombest-beats` container and updates `users.db` at `/app/music/users.db`.
3. That user logs out and logs back in so their token gets the new role.

To run the update without the script:
   ```bash
   sudo docker exec bombest-beats python3 -c "import sqlite3; c=sqlite3.connect('/app/music/users.db'); r=c.cursor(); r.execute(\"UPDATE users SET role = 'admin' WHERE username = ?\", ('thomas',)); c.commit(); c.close(); print(r.rowcount)"
   ```

### 7. Future deploys

- From your machine: `./deploy-aws.sh` (build + push new image).
- On EC2: pull, stop, remove container, then run the same `docker run` (keep `-v /data/beets:/app/music` so DB persists):

```bash
sudo docker pull thomasphillips3/bombest-beats:latest
sudo docker stop bombest-beats && sudo docker rm bombest-beats
# Run the same docker run command as in step 4 (with -v /data/beets:/app/music)
```

### 8. Redeploy container via AWS CLI (SSM)

You can update the running container on an existing EC2 instance from your laptop **without SSH** using [deploy-container-to-ec2.sh](deploy-container-to-ec2.sh). The script uses AWS Systems Manager (SSM) to run `docker pull`, stop/remove the old container, and start the new one on the instance.

**Requirements:**

- The instance must have an **IAM instance profile** with SSM permissions (e.g. managed policy `AmazonSSMManagedInstanceCore`). When creating the instance with [setup-ec2-aws-cli.sh](setup-ec2-aws-cli.sh), set `ATTACH_SSM_ROLE=1` to create and attach this role automatically.
- S3 credentials must be available on the instance when the command runs. Create `/home/ec2-user/.env.bombest` once via SSH with `S3_BUCKET`, `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, and `S3_REGION`; the deploy script sources it before running Docker.

**Usage:**

```bash
./deploy-container-to-ec2.sh [REGION]
# Or: INSTANCE_ID=i-xxxx ./deploy-container-to-ec2.sh [REGION]
```

The script finds the running instance with tag `Name=bombest-beats` in the given region (or uses `INSTANCE_ID` if set), then sends an SSM command to pull the image and run the container with the same volume and env as the run script.

> **Note:** If multiple running instances share the `Name=bombest-beats` tag, the script may target the wrong one. Ensure only one instance has this tag in a running state. See [Infrastructure Audit](#infrastructure-audit).

---

## Infrastructure Audit

> **Last validated:** 2026-03-13 via AWS CLI. These are known discrepancies between the intended (documented) state and live infrastructure.

### Action items

| # | Action | Severity | Command / Steps |
|---|--------|----------|-----------------|
| 1 | **Lock down S3 `bombest-beats-music`** — Block Public Access is OFF, bucket policy allows `Principal: *` read | **CRITICAL** | `./setup-s3-simple.sh` (enables Block Public Access, removes public policy, enables Versioning) |
| 2 | **Terminate orphaned EC2 instance** `i-0b4f206d79d59cfc7` (52.32.197.25) — no SSM, unreachable, likely stale | **MAJOR** | `aws ec2 terminate-instances --instance-ids i-0b4f206d79d59cfc7 --region us-west-2` |
| 3 | **Remove port 5002** from security group — undocumented legacy port, not used by current stack | **MEDIUM** | AWS Console or `aws ec2 revoke-security-group-ingress` |
| 4 | **Change CloudFront ViewerProtocolPolicy** to `redirect-to-https` — currently `allow-all` (allows HTTP) | **LOW** | AWS Console → CloudFront → E1RBYOEP5K0UI3 → Behaviors → Edit |
| 5 | **Verify CORS localhost origins** gated behind `FLASK_ENV` — PR #4 fix may not be deployed yet | **MEDIUM** | Check `FLASK_ENV` on running container; redeploy if needed |

### Current instance inventory

| Instance ID | Public IP | SSM | Status | Notes |
|-------------|-----------|-----|--------|-------|
| `i-09d9ff1f4729585b2` | 16.147.88.132 | Yes | **Active** | Primary instance |
| `i-0b4f206d79d59cfc7` | 52.32.197.25 | No | Unreachable | Orphaned — terminate |

> **No Elastic IP is attached.** The public IP will change if the instance is stopped and restarted. Update Cloudflare DNS A records (`beats.bom.best`, `beats-aws.bom.best`) after any stop/start cycle.

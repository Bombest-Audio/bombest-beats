# Architecture Overview

## High-Level Flow
- Source of truth: `/home/thomas/bombest-beats/beets-backend/music` on the server.
- Sync: `sync-to-s3.sh` mirrors the music directory to `s3://bombest-beats-music/music/`.
- Storage: S3 bucket `bombest-beats-music` (private; Block Public Access; SSE-S3; Versioning on).
- Client: Android app (Jetpack Compose + Media3) streams from S3 URLs. For stricter security, move to presigned URLs from a minimal backend.

## Security & Access
- Bucket is private (Block Public Access enabled). No public read or list.
- Default encryption: SSE-S3.
- Versioning: enabled.
- IAM policy (least privilege for sync user/role):
  - `s3:ListBucket` on `arn:aws:s3:::bombest-beats-music` with `prefix` limited to `music/`.
  - `s3:GetObject`, `s3:PutObject`, `s3:DeleteObject` on `arn:aws:s3:::bombest-beats-music/music/*`.
- Credentials: stored locally (not in git) under `~/.aws` with profile `bombest-beats-sync` ( `.local/` is ignored).

## Sync Details
- Script: `/home/thomas/bombest-beats/sync-to-s3.sh`
  - Defaults: `MUSIC_DIR=/home/thomas/bombest-beats/beets-backend/music`, `BUCKET=bombest-beats-music`, `REGION=us-west-2`.
  - Honors `AWS_PROFILE` (recommended: `bombest-beats-sync`).
  - Options: `WATCH=1` uses `inotifywait`; otherwise one-shot/cron.
- Cron (user `thomas`): runs every 5 minutes
  ```
  AWS_PROFILE=bombest-beats-sync /home/thomas/bombest-beats/sync-to-s3.sh >> /home/thomas/bombest-beats/sync-to-s3.log 2>&1
  ```

## Bucket Setup Script
- Script: `/home/thomas/bombest-beats/setup-s3-simple.sh`
  - Creates bucket if missing.
  - Enables Block Public Access.
  - Clears any public policy.
  - Sets CORS for GET/HEAD.
  - Enables SSE-S3 and Versioning.

## Verification
- Log: `tail -n 50 /home/thomas/bombest-beats/sync-to-s3.log`
- List bucket: `AWS_PROFILE=bombest-beats-sync aws s3 ls s3://bombest-beats-music/music/`
- Manual sync: `AWS_PROFILE=bombest-beats-sync /home/thomas/bombest-beats/sync-to-s3.sh`

## Client Notes
- Android app (Compose + Media3) currently streams via direct S3 URLs.
- For stricter access control, switch to presigned URLs issued by a minimal backend and keep the bucket private.

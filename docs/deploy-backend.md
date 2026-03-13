# Backend Deployment Guide

How to deploy the Bombest Beats backend and nginx config to EC2.

> **Note:** `deploy-nginx-to-ec2.sh`, `nginx-ec2.conf`, and `scripts/add-ssm-permissions.sh` are introduced in the infrastructure PR (#5). They are not on `main` yet.

## Quick reference

| What | Command |
|------|---------|
| Full backend (Docker + container) | `./deploy-to-ec2.sh [REGION]` |
| Nginx CORS config only | `./deploy-nginx-to-ec2.sh [REGION]` (PR #5) |
| Add SSM permissions (one-time) | `./scripts/add-ssm-permissions.sh [IAM_USER]` (PR #5) |

---

## 1. Backend container deploy

Updates the running Docker container on EC2 with the latest image.

```bash
./deploy-to-ec2.sh [REGION]
# REGION defaults to us-west-2
```

**Prerequisites:**
- EC2 instance with tag `Name=bombest-beats`
- Instance has IAM role with SSM (`AmazonSSMManagedInstanceCore`) — set `ATTACH_SSM_ROLE=1` when creating with `setup-ec2-aws-cli.sh`
- Your AWS CLI user has `ssm:SendCommand` (see §3)
- S3 env vars in `/home/ec2-user/.env.bombest` on the instance

---

## 2. Nginx CORS config deploy

Updates `/etc/nginx/conf.d/bombest-beats.conf` on the EC2 instance. Run this when you change CORS origins, proxy settings, or `client_max_body_size`.

```bash
./deploy-nginx-to-ec2.sh [REGION]
```

**Prerequisites:**
- Same as §1 (instance with SSM, your CLI user has SSM permissions)

**Config file:** `nginx-ec2.conf` in the project root (added in PR #5).

---

## 3. SSM permissions (one-time setup)

`deploy-to-ec2.sh` and `deploy-nginx-to-ec2.sh` use AWS Systems Manager to run commands on EC2. Your IAM user must have SSM permissions.

**Find your IAM user:**
```bash
aws sts get-caller-identity --query 'Arn' --output text
```

**Add permissions:**
```bash
./scripts/add-ssm-permissions.sh
# Uses bombest-deployment by default. For a different user:
./scripts/add-ssm-permissions.sh YOUR_IAM_USER_NAME
```

Requires `iam:PutUserPolicy` (admin or equivalent). Adds: `ssm:SendCommand`, `ssm:GetCommandInvocation`, `ssm:ListCommands`.

---

## 4. Manual fallback (when SSM fails)

If SSM fails (e.g. missing IAM permissions), deploy nginx manually:

1. Open **EC2 Instance Connect** in the AWS Console for your instance. Find the instance ID by tag:
   ```bash
   aws ec2 describe-instances --filters Name=tag:Name,Values=bombest-beats \
     --query 'Reservations[].Instances[].InstanceId' --output text
   ```
2. Run the script — it will print a block to paste:
   ```bash
   ./deploy-nginx-to-ec2.sh
   ```
3. Copy the entire output block (from `sudo tee` through `NGINXEOF` and the reload command).
4. Paste into the EC2 Instance Connect terminal and run.
5. Confirm: `nginx: configuration file /etc/nginx/nginx.conf test is successful`

---

## 5. First-time EC2 setup

1. Build and push image: `./deploy-aws.sh`
2. Create instance with SSM: `ATTACH_SSM_ROLE=1 ./setup-ec2-aws-cli.sh [REGION]`
3. SSH in, create `/home/ec2-user/.env.bombest` with S3 vars, run `./run-bombest-beats.sh`
4. Deploy nginx config: `./deploy-nginx-to-ec2.sh` (or manual fallback above)
5. Point **beats.bom.best** A record to EC2 public IP in Cloudflare DNS

---

## Related docs

- [deploy-which-script.md](deploy-which-script.md) — Overview of all deploy scripts
- [deploy-frontend.md](deploy-frontend.md) — Web frontend (S3/CloudFront)
- [architecture.md](architecture.md) — Full infrastructure details

# Troubleshoot 404 on Upload (/upload/folder)

If `POST https://beats.bom.best/upload/folder` returns **404 Not Found**, traffic is likely not reaching the EC2 backend.

## Cause

`beats.bom.best` may be routed through a **Cloudflare Tunnel** or to the wrong origin instead of your **EC2 instance**.

> **Note:** No Elastic IP is attached to the EC2 instance. The public IP changes on every stop/start cycle. After restarting the instance, update the Cloudflare A records and any hardcoded IPs.

## Fix: Use A record to EC2

1. In **Cloudflare** → **bom.best** → **DNS** → **Records**
2. For **beats.bom.best**:
   - **Type**: **A**
   - **Name**: `beats`
   - **Content**: your EC2 public IP (find with `aws ec2 describe-instances --filters Name=tag:Name,Values=bombest-beats --query 'Reservations[].Instances[].PublicIpAddress' --output text`)
   - **Proxy**: Proxied (orange cloud) or DNS only
3. Remove any **CNAME** or **Tunnel route** that points beats.bom.best elsewhere.

## Verify routing

```bash
# From your machine - should resolve to Cloudflare IPs (proxied) or EC2 IP (DNS only)
dig beats.bom.best +short

# Test via Cloudflare (verifies full path)
curl -s -o /dev/null -w "%{http_code}" https://beats.bom.best/library
# Expect 200 (or 401 if auth required) — not 404

# To bypass Cloudflare and test origin directly (replace <EC2_IP>):
curl -s -o /dev/null -w "%{http_code}" --resolve beats.bom.best:80:<EC2_IP> http://beats.bom.best/library
```

## After fixing DNS

Redeploy nginx and backend to EC2 so CORS and routes are current:

```bash
./deploy-nginx-to-ec2.sh  # PR #5
./deploy-to-ec2.sh
```

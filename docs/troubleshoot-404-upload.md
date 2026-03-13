# Troubleshoot 404 on Upload (/upload/folder)

If `POST https://beats.bom.best/upload/folder` returns **404 Not Found**, traffic is likely not reaching the EC2 backend.

## Cause

`beats.bom.best` may be routed through a **Cloudflare Tunnel** or to the wrong origin instead of your **EC2 instance**.

## Fix: Use A record to EC2

1. In **Cloudflare** → **bom.best** → **DNS** → **Records**
2. For **beats.bom.best**:
   - **Type**: **A**
   - **Name**: `beats`
   - **Content**: your EC2 public IP (e.g. `16.147.88.132`)
   - **Proxy**: Proxied (orange cloud) or DNS only
3. Remove any **CNAME** or **Tunnel route** that points beats.bom.best elsewhere.

## Verify routing

```bash
# From your machine - should resolve to Cloudflare IPs (proxied) or EC2 IP (DNS only)
dig beats.bom.best +short

# Test backend directly (bypass Cloudflare if using proxy)
curl -s -o /dev/null -w "%{http_code}" https://beats.bom.best/library
# Expect 200 (or 401 if auth required) — not 404
```

## After fixing DNS

Redeploy nginx and backend to EC2 so CORS and routes are current:

```bash
./deploy-nginx-to-ec2.sh
./deploy-to-ec2.sh
```

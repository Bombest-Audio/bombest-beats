---
description: Setup Cloudflare Tunnel for bom.best/beats
---
# Cloudflare Tunnel Setup

This workflow sets up a public tunnel from `bom.best/beats` to your local Nginx server on port 8080.

## Prerequisites
- Domain `bom.best` managed by Cloudflare (DNS nameservers pointed to Cloudflare).
- `cloudflared` installed on your Mac Mini server.

## Steps

### 1. Install cloudflared on the server
SSH into your server and run:
```bash
brew install cloudflare/cloudflare/cloudflared
```

### 2. Authenticate cloudflared
```bash
cloudflared tunnel login
```
This opens a browser. Authorize with your Cloudflare account that owns `bom.best`.

### 3. Create a tunnel
```bash
cloudflared tunnel create bombest-beats
```
Note the Tunnel ID output.

### 4. Create config file
Create `~/.cloudflared/config.yml`:
```yaml
tunnel: bombest-beats
credentials-file: /Users/thomas/.cloudflared/<TUNNEL_ID>.json

ingress:
  - hostname: bom.best
    path: /beats/*
    service: http://localhost:8080
  - service: http_status:404
```

### 5. Route DNS
```bash
cloudflared tunnel route dns bombest-beats bom.best
```
This adds a CNAME record in Cloudflare DNS.

### 6. Start the tunnel
```bash
cloudflared tunnel run bombest-beats
```

To run as a service (so it persists on reboot):
```bash
sudo cloudflared service install
sudo launchctl start com.cloudflare.cloudflared
```

### 7. Verify
Navigate to https://bom.best/beats in your browser.

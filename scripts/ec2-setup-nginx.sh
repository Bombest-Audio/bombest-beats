#!/bin/bash
# Run this on EC2 (via SSH or SSM) to add nginx reverse proxy.
# Fixes 502 on login: Cloudflare proxies to port 80, but the backend runs on 8338.
# This script configures nginx to listen on 80 and proxy to localhost:8338.
#
# Requires repo checkout (uses nginx-ec2.conf as the single source of truth).

set -e

echo "Installing nginx..."
sudo yum install -y nginx 2>/dev/null || sudo dnf install -y nginx

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_CONF="$SCRIPT_DIR/../nginx-ec2.conf"

if [ ! -f "$REPO_CONF" ]; then
  echo "Error: nginx-ec2.conf not found at $REPO_CONF"
  echo "Run this script from a repo checkout, or use deploy-nginx-to-ec2.sh instead."
  exit 1
fi

echo "Configuring nginx (from nginx-ec2.conf)..."
sudo cp "$REPO_CONF" /etc/nginx/conf.d/bombest-beats.conf

sudo rm -f /etc/nginx/conf.d/default.conf
sudo systemctl enable nginx
sudo systemctl restart nginx

echo "Done. Ensure security group allows port 80."
echo "Cloudflare SSL: use Full mode (origin listens on HTTP). For Full (Strict), add an origin TLS certificate."

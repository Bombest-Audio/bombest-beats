#!/bin/bash
# Run this on EC2 (via SSH or SSM) to add nginx reverse proxy.
# Fixes 502 on login: Cloudflare proxies to port 80, but the backend runs on 8338.
# This script configures nginx to listen on 80 and proxy to localhost:8338.
#
# If nginx-ec2.conf is available (repo checkout), it is used as the source of truth.
# Otherwise, a minimal config is generated inline.

set -e

echo "Installing nginx..."
sudo yum install -y nginx 2>/dev/null || sudo dnf install -y nginx

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_CONF="$SCRIPT_DIR/../nginx-ec2.conf"

echo "Configuring nginx..."
if [ -f "$REPO_CONF" ]; then
  echo "  Using nginx-ec2.conf from repo"
  sudo cp "$REPO_CONF" /etc/nginx/conf.d/bombest-beats.conf
else
  echo "  nginx-ec2.conf not found; generating minimal config"
  sudo tee /etc/nginx/conf.d/bombest-beats.conf > /dev/null << 'EOF'
map $http_origin $cors_origin {
    default "https://bom.best";
    "https://bom.best" "https://bom.best";
    "https://www.bom.best" "https://www.bom.best";
    "https://beats.bom.best" "https://beats.bom.best";
    "https://beats-app.bom.best" "https://beats-app.bom.best";
    "https://d37qdccady5d3d.cloudfront.net" "https://d37qdccady5d3d.cloudfront.net";
}
server {
    listen 80;
    server_name _;
    client_max_body_size 500M;
    location / {
        if ($request_method = OPTIONS) {
            add_header Access-Control-Allow-Origin $cors_origin;
            add_header Access-Control-Allow-Credentials "true";
            add_header Access-Control-Allow-Methods "GET, POST, OPTIONS, PUT, DELETE";
            add_header Access-Control-Allow-Headers "Authorization, Content-Type, Accept";
            add_header Content-Length 0;
            return 204;
        }
        proxy_pass http://127.0.0.1:8338;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_connect_timeout 60s;
        proxy_send_timeout 300s;
        proxy_read_timeout 300s;
        proxy_request_buffering off;
        proxy_intercept_errors on;
        error_page 502 503 504 = @cors_error;
        error_page 413 = @cors_413;
    }
    location @cors_413 {
        add_header Access-Control-Allow-Origin $cors_origin always;
        add_header Access-Control-Allow-Credentials "true" always;
        add_header Access-Control-Allow-Methods "GET, POST, OPTIONS, PUT, DELETE" always;
        add_header Access-Control-Allow-Headers "Authorization, Content-Type, Accept" always;
        return 413;
    }
    location @cors_error {
        add_header Access-Control-Allow-Origin $cors_origin always;
        add_header Access-Control-Allow-Credentials "true" always;
        add_header Access-Control-Allow-Methods "GET, POST, OPTIONS, PUT, DELETE" always;
        add_header Access-Control-Allow-Headers "Authorization, Content-Type, Accept" always;
        return 502;
    }
}
EOF
fi

sudo rm -f /etc/nginx/conf.d/default.conf
sudo systemctl enable nginx
sudo systemctl restart nginx

echo "Done. Ensure security group allows port 80."
echo "Cloudflare SSL: use Full mode (origin listens on HTTP). For Full (Strict), add an origin TLS certificate."

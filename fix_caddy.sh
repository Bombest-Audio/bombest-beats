#!/bin/bash
# Fix Caddy permissions and start the service

set -e

echo "Creating log directory..."
mkdir -p /var/log/caddy
chown caddy:caddy /var/log/caddy

echo "Creating log files..."
touch /var/log/caddy/sonicnet-access.log
touch /var/log/caddy/bombest-beats-access.log
chown caddy:caddy /var/log/caddy/*.log

echo "Starting Caddy..."
systemctl start caddy

echo "Checking status..."
systemctl status caddy --no-pager

echo "Done!"

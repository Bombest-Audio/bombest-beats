#!/bin/bash
# Run this locally; copy the output and paste each block into EC2 Instance Connect.
# Paste block 1, wait for prompt. Paste block 2. Paste block 3. Paste block 4.

CONFIG="$(cd "$(dirname "$0")/.." && pwd)/nginx-ec2.conf"
[ ! -f "$CONFIG" ] && { echo "nginx-ec2.conf not found"; exit 1; }

B64=$(base64 < "$CONFIG" | tr -d '\n')
P1="${B64:0:820}"
P2="${B64:820:820}"
P3="${B64:1640}"

echo "=== PASTE BLOCK 1 (creates part 1) ==="
echo "echo -n '$P1' > /tmp/ng.b64"
echo ""
echo "=== PASTE BLOCK 2 (appends part 2) ==="
echo "echo -n '$P2' >> /tmp/ng.b64"
echo ""
echo "=== PASTE BLOCK 3 (appends part 3) ==="
echo "echo -n '$P3' >> /tmp/ng.b64"
echo ""
echo "=== PASTE BLOCK 4 (apply and reload) ==="
echo "base64 -d < /tmp/ng.b64 | sudo tee /etc/nginx/conf.d/bombest-beats.conf > /dev/null && sudo nginx -t && sudo systemctl reload nginx && rm /tmp/ng.b64 && echo Done."

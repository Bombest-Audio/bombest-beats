#!/bin/bash
# Run this locally; copy the output and paste each block into EC2 Instance Connect.
# Paste each block in order, waiting for the prompt between them.

CONFIG="$(cd "$(dirname "$0")/.." && pwd)/nginx-ec2.conf"
[ ! -f "$CONFIG" ] && { echo "nginx-ec2.conf not found"; exit 1; }

B64=$(base64 < "$CONFIG" | tr -d '\n')
CHUNK_SIZE=800
LEN=${#B64}
BLOCK=1

# Split base64 into chunks and generate paste blocks
OFFSET=0
while [ "$OFFSET" -lt "$LEN" ]; do
  CHUNK="${B64:$OFFSET:$CHUNK_SIZE}"
  if [ "$OFFSET" -eq 0 ]; then
    echo "=== PASTE BLOCK $BLOCK (creates file) ==="
    echo "echo -n '$CHUNK' > /tmp/ng.b64"
  else
    echo "=== PASTE BLOCK $BLOCK (appends) ==="
    echo "echo -n '$CHUNK' >> /tmp/ng.b64"
  fi
  echo ""
  BLOCK=$((BLOCK + 1))
  OFFSET=$((OFFSET + CHUNK_SIZE))
done

echo "=== PASTE BLOCK $BLOCK (apply and reload) ==="
echo "base64 -d < /tmp/ng.b64 | sudo tee /etc/nginx/conf.d/bombest-beats.conf > /dev/null && sudo nginx -t && sudo systemctl reload nginx && rm /tmp/ng.b64 && echo Done."

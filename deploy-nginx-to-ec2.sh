#!/usr/bin/env bash
# Deploy nginx CORS config to EC2 via SSM. Updates /etc/nginx/conf.d/bombest-beats.conf and reloads nginx.
# Prerequisites: AWS CLI, instance with SSM agent and IAM role (AmazonSSMManagedInstanceCore).
# Usage: ./deploy-nginx-to-ec2.sh [REGION]
#   Or:  INSTANCE_ID=i-xxx ./deploy-nginx-to-ec2.sh [REGION]

set -e

REGION="${1:-us-west-2}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CONFIG_FILE="$SCRIPT_DIR/nginx-ec2.conf"

if [ ! -f "$CONFIG_FILE" ]; then
  echo "Missing nginx-ec2.conf"
  exit 1
fi

if [ -n "$INSTANCE_ID" ]; then
  echo "Using instance: $INSTANCE_ID"
else
  echo "Resolving instance with tag Name=bombest-beats in $REGION..."
  INSTANCE_ID=$(aws ec2 describe-instances --region "$REGION" \
    --filters "Name=tag:Name,Values=bombest-beats" "Name=instance-state-name,Values=running" \
    --query 'Reservations[0].Instances[0].InstanceId' --output text 2>/dev/null || true)
  if [ -z "$INSTANCE_ID" ] || [ "$INSTANCE_ID" = "None" ]; then
    echo "No running EC2 instance with Name=bombest-beats in $REGION."
    exit 1
  fi
  echo "Instance: $INSTANCE_ID"
fi

# Base64 encode config so we can pass it safely in the SSM command
CONFIG_B64=$(base64 < "$CONFIG_FILE" | tr -d '\n')
COMMAND="echo '${CONFIG_B64}' | base64 -d | sudo tee /etc/nginx/conf.d/bombest-beats.conf > /dev/null && sudo nginx -t && sudo systemctl reload nginx && echo 'Nginx CORS config deployed.'"

echo "Deploying nginx config via SSM..."
if command -v jq &>/dev/null; then
  PARAMS_JSON=$(jq -n --arg c "$COMMAND" '{commands: [$c]}')
else
  ESCAPED=$(echo "$COMMAND" | sed 's/\\/\\\\/g; s/"/\\"/g')
  PARAMS_JSON="{\"commands\": [\"$ESCAPED\"]}"
fi

CMD_ID=$(aws ssm send-command --region "$REGION" \
  --instance-ids "$INSTANCE_ID" \
  --document-name "AWS-RunShellScript" \
  --parameters "$PARAMS_JSON" \
  --query 'Command.CommandId' --output text 2>/dev/null) || true

if [ -z "$CMD_ID" ]; then
  echo "SSM send-command failed (needs ssm:SendCommand IAM permission)."
  echo ""
  echo "Manual deploy: Open EC2 Instance Connect in AWS Console for instance $INSTANCE_ID,"
  echo "then paste and run this entire block (including the EOF line):"
  echo ""
  echo "sudo tee /etc/nginx/conf.d/bombest-beats.conf << 'NGINXEOF'"
  cat "$CONFIG_FILE"
  echo "NGINXEOF"
  echo "sudo nginx -t && sudo systemctl reload nginx"
  echo ""
  exit 1
fi

echo "Command ID: $CMD_ID"
echo "Waiting..."
for i in $(seq 1 20); do
  STATUS=$(aws ssm get-command-invocation --region "$REGION" \
    --command-id "$CMD_ID" --instance-id "$INSTANCE_ID" \
    --query 'Status' --output text 2>/dev/null || echo "Pending")
  case "$STATUS" in
    Success)
      echo "Done."
      aws ssm get-command-invocation --region "$REGION" --command-id "$CMD_ID" --instance-id "$INSTANCE_ID" \
        --query 'StandardOutputContent' --output text 2>/dev/null | tail -5
      exit 0
      ;;
    Failed|Cancelled)
      echo "Command $STATUS."
      aws ssm get-command-invocation --region "$REGION" --command-id "$CMD_ID" --instance-id "$INSTANCE_ID" \
        --query '[StandardOutputContent, StandardErrorContent]' --output text 2>/dev/null
      exit 1
      ;;
    *) sleep 2 ;;
  esac
done
echo "Timeout."
exit 1

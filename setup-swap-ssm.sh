#!/usr/bin/env bash
# One-time: add a 2 GB swapfile to an existing EC2 instance via AWS SSM.
# Safe to re-run — exits early if /swapfile swap is already active.
# Prerequisites: AWS CLI; instance must have SSM agent + IAM SSM permissions.
# Usage: ./setup-swap-ssm.sh [REGION]
#   Or:  INSTANCE_ID=i-xxx ./setup-swap-ssm.sh [REGION]

set -e

REGION="${1:-us-west-2}"

if [ -n "$INSTANCE_ID" ]; then
  echo "Using instance: $INSTANCE_ID"
else
  echo "Resolving instance with tag Name=bombest-beats in $REGION..."
  INSTANCE_ID=$(aws ec2 describe-instances --region "$REGION" \
    --filters "Name=tag:Name,Values=bombest-beats" "Name=instance-state-name,Values=running" \
    --query 'Reservations[0].Instances[0].InstanceId' --output text 2>/dev/null || true)
  if [ -z "$INSTANCE_ID" ] || [ "$INSTANCE_ID" = "None" ]; then
    echo "No running EC2 instance with Name=bombest-beats in $REGION."
    echo "Set: INSTANCE_ID=i-xxxxxxxx ./setup-swap-ssm.sh $REGION"
    exit 1
  fi
  echo "Instance: $INSTANCE_ID"
fi

COMMAND='
set -e
if swapon --show | grep -q /swapfile; then
  echo "Swap already active — nothing to do."
  swapon --show
  free -h
  exit 0
fi
echo "Creating 2 GB swapfile at /swapfile..."
sudo dd if=/dev/zero of=/swapfile bs=128M count=16 status=progress
sudo chmod 600 /swapfile
sudo mkswap /swapfile
sudo swapon /swapfile
grep -q /swapfile /etc/fstab || echo "/swapfile swap swap defaults 0 0" | sudo tee -a /etc/fstab
echo "Done."
swapon --show
free -h
'

echo "Sending SSM command..."
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
  echo "SSM send-command failed. Ensure the instance has an IAM role with SSM permissions."
  exit 1
fi

echo "Command ID: $CMD_ID"
echo "Waiting for command to complete..."
for i in $(seq 1 40); do
  STATUS=$(aws ssm get-command-invocation --region "$REGION" \
    --command-id "$CMD_ID" --instance-id "$INSTANCE_ID" \
    --query 'Status' --output text 2>/dev/null || echo "Pending")
  case "$STATUS" in
    Success)
      echo "Success."
      aws ssm get-command-invocation --region "$REGION" \
        --command-id "$CMD_ID" --instance-id "$INSTANCE_ID" \
        --query 'StandardOutputContent' --output text 2>/dev/null
      exit 0 ;;
    Failed|Cancelled|TimedOut)
      echo "Command $STATUS."
      aws ssm get-command-invocation --region "$REGION" \
        --command-id "$CMD_ID" --instance-id "$INSTANCE_ID" \
        --query 'StandardErrorContent' --output text 2>/dev/null
      exit 1 ;;
    InProgress|Pending) sleep 3 ;;
    *) sleep 3 ;;
  esac
done
echo "Timeout waiting for command."
exit 1

#!/usr/bin/env bash
# Deploy or update the Bombest Beats Docker container on an existing EC2 instance via AWS SSM.
# Prerequisites: AWS CLI; instance must have SSM agent and an IAM instance profile with
# SSM permissions (e.g. AmazonSSMManagedInstanceCore). S3 env vars must exist on the
# instance (create /home/ec2-user/.env.bombest with AWS_ACCESS_KEY_ID, AWS_SECRET_ACCESS_KEY,
# S3_BUCKET, S3_REGION once via SSH, or set ATTACH_SSM_ROLE=1 when creating with setup-ec2-aws-cli.sh).
# Usage: ./deploy-container-to-ec2.sh [REGION]
#   Or:  INSTANCE_ID=i-xxx ./deploy-container-to-ec2.sh [REGION]
#   REGION defaults to us-west-2.

set -e

REGION="${1:-us-west-2}"
DOCKER_IMAGE="${DOCKER_IMAGE:-tomdabomb2u/bombest-beats:latest}"

if [ -n "$INSTANCE_ID" ]; then
  echo "Using instance: $INSTANCE_ID"
else
  echo "Resolving instance with tag Name=bombest-beats in $REGION..."
  INSTANCE_ID=$(aws ec2 describe-instances --region "$REGION" \
    --filters "Name=tag:Name,Values=bombest-beats" "Name=instance-state-name,Values=running" \
    --query 'Reservations[0].Instances[0].InstanceId' --output text 2>/dev/null || true)
  if [ -z "$INSTANCE_ID" ] || [ "$INSTANCE_ID" = "None" ]; then
    echo "No running EC2 instance with Name=bombest-beats in $REGION."
    echo "Create one with: ./setup-ec2-aws-cli.sh $REGION"
    echo "Or set: INSTANCE_ID=i-xxxxxxxx ./deploy-container-to-ec2.sh $REGION"
    exit 1
  fi
  echo "Instance: $INSTANCE_ID"
fi

# Prune policy — opt-in and scoped:
#   PRUNE=0 (default): don't prune. Deploys stay focused on "pull + restart."
#   PRUNE=images: remove only dangling images after the new container is started
#                 (safe — running containers pin their images, so the new image
#                 is never removed regardless of whether the container is healthy).
#   PRUNE=all:    full `docker system prune -f --filter until=24h` (the old
#                 behavior — can touch unused containers/networks/volumes).
# Pruning runs AFTER the new container starts so a failed deploy doesn't
# also wipe rollback candidates.
PRUNE="${PRUNE:-0}"
case "$PRUNE" in
  0|images|all) ;;
  *) echo "Invalid PRUNE='$PRUNE'. Use 0 (default), images, or all."; exit 1 ;;
esac

PRUNE_CMD=""
case "$PRUNE" in
  images) PRUNE_CMD="sudo docker image prune -f;" ;;
  all)    PRUNE_CMD="sudo docker system prune -f --filter 'until=24h';" ;;
esac

# SSM command: source env file (if present), pull image, stop/rm existing container, run new one
# Use semicolons so we can pass one string; SSM requires JSON-escaped parameters
COMMAND="source /home/ec2-user/.env.bombest 2>/dev/null || true; export S3_BUCKET=\${S3_BUCKET:-bombest-beats-music}; export S3_REGION=\${S3_REGION:-us-west-2}; sudo docker pull $DOCKER_IMAGE; sudo docker stop bombest-beats 2>/dev/null || true; sudo docker rm bombest-beats 2>/dev/null || true; sudo docker run -d --name bombest-beats -p 8338:8338 --restart unless-stopped -v /data/beets:/app/music -e \"S3_BUCKET=\$S3_BUCKET\" -e \"AWS_ACCESS_KEY_ID=\$AWS_ACCESS_KEY_ID\" -e \"AWS_SECRET_ACCESS_KEY=\$AWS_SECRET_ACCESS_KEY\" -e \"S3_REGION=\$S3_REGION\" $DOCKER_IMAGE; ${PRUNE_CMD}echo Container deployed."

echo "Sending SSM command (pull + run container)..."
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
  echo "SSM send-command failed. Ensure the instance has an IAM role with SSM (e.g. AmazonSSMManagedInstanceCore)."
  echo "Create a new instance with: ATTACH_SSM_ROLE=1 ./setup-ec2-aws-cli.sh $REGION"
  exit 1
fi

echo "Command ID: $CMD_ID"
echo "Waiting for command to complete..."
for i in $(seq 1 60); do
  STATUS=$(aws ssm get-command-invocation --region "$REGION" \
    --command-id "$CMD_ID" --instance-id "$INSTANCE_ID" \
    --query 'Status' --output text 2>/dev/null || echo "Pending")
  case "$STATUS" in
    Success) echo "Done."; exit 0 ;;
    Failed|Cancelled) echo "Command $STATUS."; aws ssm get-command-invocation --region "$REGION" --command-id "$CMD_ID" --instance-id "$INSTANCE_ID" --query 'StandardErrorContent' --output text 2>/dev/null; exit 1 ;;
    InProgress|Pending) sleep 3 ;;
    *) sleep 3 ;;
  esac
done
echo "Timeout waiting for command."
exit 1

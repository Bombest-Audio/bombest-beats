#!/usr/bin/env bash
# Create Bombest Beats backend on AWS EC2 (free tier) using AWS CLI.
# Prerequisites: AWS CLI installed and configured (aws configure).
# Usage: ./setup-ec2-aws-cli.sh [REGION]
#   REGION defaults to us-west-2. Export AWS_PROFILE if needed.
#   ATTACH_SSM_ROLE=1: create IAM role for SSM and attach to instance so you can
#   redeploy with ./deploy-container-to-ec2.sh without SSH.

set -e

REGION="${1:-us-west-2}"
KEY_NAME="bombest-beats-key"
SG_NAME="bombest-beats-sg"
INSTANCE_TAG="bombest-beats"
PEM_FILE="${KEY_NAME}.pem"
# Must match the image you push with deploy-aws.sh (DOCKER_USERNAME/bombest-beats:latest)
DOCKER_IMAGE="${DOCKER_IMAGE:-tomdabomb2u/bombest-beats:latest}"

echo "Region: $REGION"
echo "Key pair: $KEY_NAME"
echo "Security group: $SG_NAME"
echo ""

# Optional: restrict SSH to current IP (set RESTRICT_SSH=1 to enable)
MY_IP=""
if [ -n "${RESTRICT_SSH}" ]; then
  MY_IP=$(curl -s --max-time 3 https://checkip.amazonaws.com 2>/dev/null || true)
  if [ -n "$MY_IP" ]; then
    echo "Restricting SSH to your IP: $MY_IP/32"
  else
    echo "Could not detect your IP; SSH will be open to 0.0.0.0/0"
  fi
fi
SSH_CIDR="${MY_IP:+${MY_IP}/32}"
SSH_CIDR="${SSH_CIDR:-0.0.0.0/0}"

# 1. Create key pair
if aws ec2 describe-key-pairs --key-names "$KEY_NAME" --region "$REGION" &>/dev/null; then
  echo "Using existing key pair: $KEY_NAME"
  if [ ! -f "$PEM_FILE" ]; then
    echo "  Warning: $PEM_FILE not found. Create it or use an existing .pem for this key."
  fi
else
  echo "Creating key pair: $KEY_NAME"
  aws ec2 create-key-pair --key-name "$KEY_NAME" --region "$REGION" \
    --query 'KeyMaterial' --output text > "$PEM_FILE"
  chmod 600 "$PEM_FILE"
  echo "  Saved private key to $PEM_FILE"
fi

# 2. Get default VPC
VPC_ID=$(aws ec2 describe-vpcs --region "$REGION" --filters "Name=is-default,Values=true" \
  --query 'Vpcs[0].VpcId' --output text)
if [ -z "$VPC_ID" ] || [ "$VPC_ID" = "None" ]; then
  echo "No default VPC in $REGION. Create a VPC or specify a VPC ID."
  exit 1
fi
echo "Using VPC: $VPC_ID"

# 3. Create security group (or use existing)
if aws ec2 describe-security-groups --group-names "$SG_NAME" --region "$REGION" &>/dev/null; then
  SG_ID=$(aws ec2 describe-security-groups --group-names "$SG_NAME" --region "$REGION" \
    --query 'SecurityGroups[0].GroupId' --output text)
  echo "Using existing security group: $SG_ID"
else
  echo "Creating security group: $SG_NAME"
  SG_ID=$(aws ec2 create-security-group --group-name "$SG_NAME" --description "Bombest Beats backend" \
    --vpc-id "$VPC_ID" --region "$REGION" --query 'GroupId' --output text)
  aws ec2 authorize-security-group-ingress --group-id "$SG_ID" --region "$REGION" \
    --protocol tcp --port 22 --cidr "$SSH_CIDR"
  aws ec2 authorize-security-group-ingress --group-id "$SG_ID" --region "$REGION" \
    --protocol tcp --port 8338 --cidr 0.0.0.0/0
  echo "  Ingress: 22 (SSH) from $SSH_CIDR, 8338 (Flask) from 0.0.0.0/0"
fi

# 4. Latest Amazon Linux 2023 AMI
echo "Resolving latest Amazon Linux 2023 AMI..."
AMI_ID=$(aws ssm get-parameters --names /aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-x86_64 \
  --region "$REGION" --query 'Parameters[0].Value' --output text 2>/dev/null || true)
if [ -z "$AMI_ID" ] || [ "$AMI_ID" = "None" ]; then
  AMI_ID=$(aws ec2 describe-images --owners amazon --region "$REGION" \
    --filters "Name=name,Values=al2023-ami-*-kernel-*-x86_64" "Name=state,Values=available" \
    --query "sort_by(Images, &CreationDate)[-1].ImageId" --output text)
fi
echo "AMI: $AMI_ID"

# 4b. Optional: IAM role and instance profile for SSM (so deploy-container-to-ec2.sh works)
IAM_PROFILE_NAME=""
if [ -n "${ATTACH_SSM_ROLE}" ]; then
  ROLE_NAME="bombest-beats-ec2-role"
  PROFILE_NAME="bombest-beats-ec2-profile"
  echo "Creating IAM role and instance profile for SSM..."
  if ! aws iam get-role --role-name "$ROLE_NAME" &>/dev/null; then
    aws iam create-role --role-name "$ROLE_NAME" \
      --assume-role-policy-document '{"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":{"Service":"ec2.amazonaws.com"},"Action":"sts:AssumeRole"}]}' \
      --query 'Role.RoleName' --output text
    aws iam attach-role-policy --role-name "$ROLE_NAME" \
      --policy-arn "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore"
  fi
  if ! aws iam get-instance-profile --instance-profile-name "$PROFILE_NAME" &>/dev/null; then
    aws iam create-instance-profile --instance-profile-name "$PROFILE_NAME" --query 'InstanceProfile.InstanceProfileName' --output text
    aws iam add-role-to-instance-profile --instance-profile-name "$PROFILE_NAME" --role-name "$ROLE_NAME"
    echo "Waiting for instance profile to be ready..."
    sleep 10
  fi
  IAM_PROFILE_NAME="$PROFILE_NAME"
  echo "  Using instance profile: $IAM_PROFILE_NAME (you can redeploy with ./deploy-container-to-ec2.sh)"
fi

# 5. User data: install Docker, prepare dirs, pull image, write run script (no secrets in user data)
USER_DATA=$(cat <<'USERDATA_END'
#!/bin/bash
set -e
yum install -y docker
systemctl enable docker
systemctl start docker
usermod -aG docker ec2-user
mkdir -p /data/beets
chown ec2-user:ec2-user /data/beets
sudo -u ec2-user docker pull DOCKER_IMAGE_PLACEHOLDER || true
# Run script for ec2-user to start container (set S3_* env vars first)
cat > /home/ec2-user/run-bombest-beats.sh << RUN_END
#!/bin/bash
set -e
export S3_BUCKET="\${S3_BUCKET:-bombest-beats-music}"
export S3_REGION="\${S3_REGION:-us-west-2}"
# Set AWS_ACCESS_KEY_ID and AWS_SECRET_ACCESS_KEY before running
sudo docker run -d --name bombest-beats -p 8338:8338 --restart unless-stopped \\
  -v /data/beets:/app/music \\
  -e S3_BUCKET -e AWS_ACCESS_KEY_ID -e AWS_SECRET_ACCESS_KEY -e S3_REGION \\
  DOCKER_IMAGE_PLACEHOLDER
echo "Backend running on port 8338"
RUN_END
chown ec2-user:ec2-user /home/ec2-user/run-bombest-beats.sh
chmod +x /home/ec2-user/run-bombest-beats.sh
USERDATA_END
)
USER_DATA="${USER_DATA//DOCKER_IMAGE_PLACEHOLDER/$DOCKER_IMAGE}"

# 6. Launch instance
RUN_INSTANCE_ARGS=(
  --region "$REGION"
  --image-id "$AMI_ID"
  --instance-type t3.micro
  --key-name "$KEY_NAME"
  --security-group-ids "$SG_ID"
  --block-device-mappings '[{"DeviceName":"/dev/xvda","Ebs":{"VolumeSize":30,"VolumeType":"gp3"}}]'
  --tag-specifications "ResourceType=instance,Tags=[{Key=Name,Value=$INSTANCE_TAG}]"
  --user-data "$USER_DATA"
)
[ -n "$IAM_PROFILE_NAME" ] && RUN_INSTANCE_ARGS+=(--iam-instance-profile "Name=$IAM_PROFILE_NAME")

echo "Launching t3.micro (free tier)..."
INSTANCE_ID=$(aws ec2 run-instances \
  "${RUN_INSTANCE_ARGS[@]}" \
  --query 'Instances[0].InstanceId' --output text)

echo "Instance ID: $INSTANCE_ID"
echo "Waiting for instance to run..."
aws ec2 wait instance-running --instance-ids "$INSTANCE_ID" --region "$REGION"

PUBLIC_IP=$(aws ec2 describe-instances --instance-ids "$INSTANCE_ID" --region "$REGION" \
  --query 'Reservations[0].Instances[0].PublicIpAddress' --output text)

echo ""
echo "--- EC2 is up ---"
echo "Public IP: $PUBLIC_IP"
echo "Backend URL: http://${PUBLIC_IP}:8338"
echo ""
echo "SSH (use the key you created):"
echo "  ssh -i $PEM_FILE ec2-user@$PUBLIC_IP"
echo ""
echo "On first login, set S3 env vars and start the container:"
echo "  export AWS_ACCESS_KEY_ID=your_access_key"
echo "  export AWS_SECRET_ACCESS_KEY=your_secret_key"
echo "  export S3_BUCKET=bombest-beats-music"
echo "  export S3_REGION=us-west-2"
echo "  ./run-bombest-beats.sh"
echo ""
echo "If Docker was still pulling when user data ran, start the container manually:"
echo "  ./run-bombest-beats.sh"
echo "  # or: sudo docker run -d --name bombest-beats -p 8338:8338 --restart unless-stopped -v /data/beets:/app/music -e S3_BUCKET -e AWS_ACCESS_KEY_ID -e AWS_SECRET_ACCESS_KEY -e S3_REGION $DOCKER_IMAGE"
if [ -n "$IAM_PROFILE_NAME" ]; then
  echo "SSM is enabled. To redeploy without SSH, create /home/ec2-user/.env.bombest with S3 vars, then run: ./deploy-container-to-ec2.sh $REGION"
fi
echo ""

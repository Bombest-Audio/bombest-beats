#!/bin/bash
# AWS EC2 Deployment for Bombest Beats
# Creates IAM user, security group, and launches EC2 instance

set -e

AWS_REGION="${AWS_REGION:-us-west-2}"
APP_NAME="bombest-beats"
INSTANCE_TYPE="t3.micro"

echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "🚀 Bombest Beats - AWS EC2 Deployment"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

# Check AWS CLI
if ! command -v aws &> /dev/null; then
    echo "❌ AWS CLI not found. Install with: brew install awscli"
    exit 1
fi

# Verify AWS credentials
echo "📋 Checking AWS credentials..."
if ! aws sts get-caller-identity &> /dev/null; then
    echo "❌ AWS credentials not configured. Run: aws configure"
    exit 1
fi

ACCOUNT_ID=$(aws sts get-caller-identity --query Account --output text)
echo "✅ Using AWS Account: $ACCOUNT_ID"

# Step 1: Create IAM User for Bombest
echo ""
echo "👤 Step 1: Creating IAM user 'bombest-deployment'..."
aws iam create-user --user-name bombest-deployment 2>/dev/null || echo "   User already exists"

# Create IAM policy with EC2 permissions
cat > /tmp/bombest-policy.json << 'EOF'
{
    "Version": "2012-10-17",
    "Statement": [
        {
            "Effect": "Allow",
            "Action": [
                "ec2:RunInstances",
                "ec2:DescribeInstances",
                "ec2:DescribeImages",
                "ec2:DescribeKeyPairs",
                "ec2:DescribeSecurityGroups",
                "ec2:CreateSecurityGroup",
                "ec2:AuthorizeSecurityGroupIngress",
                "ec2:CreateKeyPair",
                "ec2:DescribeSubnets",
                "ec2:DescribeVpcs",
                "ec2:CreateTags",
                "ec2:TerminateInstances",
                "ec2:StopInstances",
                "ec2:StartInstances"
            ],
            "Resource": "*"
        }
    ]
}
EOF

aws iam put-user-policy --user-name bombest-deployment --policy-name BombestEC2Policy --policy-document file:///tmp/bombest-policy.json 2>/dev/null || echo "   Policy already attached"
echo "✅ IAM user configured"

# Step 2: Create key pair
echo ""
echo "🔑 Step 2: Creating SSH key pair..."
KEY_FILE="$HOME/.ssh/${APP_NAME}-key.pem"
if [ -f "$KEY_FILE" ]; then
    echo "   Key already exists at $KEY_FILE"
else
    aws ec2 delete-key-pair --key-name ${APP_NAME}-key --region $AWS_REGION 2>/dev/null || true
    aws ec2 create-key-pair --key-name ${APP_NAME}-key --query 'KeyMaterial' --output text --region $AWS_REGION > "$KEY_FILE"
    chmod 400 "$KEY_FILE"
    echo "✅ Key saved to: $KEY_FILE"
fi

# Step 3: Create security group
echo ""
echo "🔒 Step 3: Creating security group..."
VPC_ID=$(aws ec2 describe-vpcs --filters "Name=isDefault,Values=true" --query 'Vpcs[0].VpcId' --output text --region $AWS_REGION)

SG_ID=$(aws ec2 describe-security-groups --filters "Name=group-name,Values=${APP_NAME}-sg" --query 'SecurityGroups[0].GroupId' --output text --region $AWS_REGION 2>/dev/null)

if [ "$SG_ID" == "None" ] || [ -z "$SG_ID" ]; then
    SG_ID=$(aws ec2 create-security-group --group-name ${APP_NAME}-sg --description "Bombest Beats server" --vpc-id $VPC_ID --region $AWS_REGION --query 'GroupId' --output text)
    
    # Allow SSH
    aws ec2 authorize-security-group-ingress --group-id $SG_ID --protocol tcp --port 22 --cidr 0.0.0.0/0 --region $AWS_REGION 2>/dev/null || true
    # Allow app port
    aws ec2 authorize-security-group-ingress --group-id $SG_ID --protocol tcp --port 8338 --cidr 0.0.0.0/0 --region $AWS_REGION 2>/dev/null || true
    echo "✅ Security group created: $SG_ID"
else
    echo "   Security group already exists: $SG_ID"
fi

# Step 4: Get latest Amazon Linux 2023 AMI
echo ""
echo "📦 Step 4: Finding latest Amazon Linux 2023 AMI..."
AMI_ID=$(aws ec2 describe-images \
    --owners amazon \
    --filters "Name=name,Values=al2023-ami-2023*-x86_64" "Name=state,Values=available" \
    --query 'Images | sort_by(@, &CreationDate) | [-1].ImageId' \
    --output text \
    --region $AWS_REGION)
echo "   Using AMI: $AMI_ID"

# Step 5: Create user data script
echo ""
echo "📜 Step 5: Preparing startup script..."
USER_DATA=$(cat << 'EOF'
#!/bin/bash
yum update -y
yum install -y docker
systemctl start docker
systemctl enable docker
docker run -d --name bombest -p 8338:8338 --restart unless-stopped thomasphillips3/bombest-beats:latest
EOF
)
USER_DATA_B64=$(echo "$USER_DATA" | base64)

# Step 6: Launch instance
echo ""
echo "🚀 Step 6: Launching EC2 instance..."
INSTANCE_ID=$(aws ec2 run-instances \
    --image-id $AMI_ID \
    --instance-type $INSTANCE_TYPE \
    --key-name ${APP_NAME}-key \
    --security-group-ids $SG_ID \
    --user-data "$USER_DATA" \
    --tag-specifications "ResourceType=instance,Tags=[{Key=Name,Value=${APP_NAME}}]" \
    --region $AWS_REGION \
    --query 'Instances[0].InstanceId' \
    --output text)

echo "   Instance ID: $INSTANCE_ID"
echo "   Waiting for instance to start..."

aws ec2 wait instance-running --instance-ids $INSTANCE_ID --region $AWS_REGION

PUBLIC_IP=$(aws ec2 describe-instances \
    --instance-ids $INSTANCE_ID \
    --query 'Reservations[0].Instances[0].PublicIpAddress' \
    --output text \
    --region $AWS_REGION)

echo ""
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "✅ DEPLOYMENT COMPLETE!"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo ""
echo "🖥️  Instance: $INSTANCE_ID"
echo "🌐 Public IP: $PUBLIC_IP"
echo "🔑 SSH Key:   $KEY_FILE"
echo ""
echo "📡 Test in ~2 minutes:"
echo "   curl http://$PUBLIC_IP:8338/library"
echo ""
echo "🔐 SSH Access:"
echo "   ssh -i $KEY_FILE ec2-user@$PUBLIC_IP"
echo ""
echo "☁️  Add to Cloudflare as failover origin:"
echo "   http://$PUBLIC_IP:8338"
echo ""

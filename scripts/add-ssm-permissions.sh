#!/usr/bin/env bash
# Add SSM SendCommand and GetCommandInvocation permissions to an IAM user.
# Required for deploy-nginx-to-ec2.sh and deploy-container-to-ec2.sh.
#
# Usage: ./scripts/add-ssm-permissions.sh [IAM_USER_NAME]
#   Default user: bombest-deployment
#   To find your CLI user: aws sts get-caller-identity --query 'Arn' --output text

set -e

USER_NAME="${1:-bombest-deployment}"

echo "Adding SSM permissions to IAM user: $USER_NAME"
echo "  (To use a different user, run: $0 YOUR_IAM_USER_NAME)"
echo ""

# Policy: SSM Run Command. For production, scope Resource to instance ARNs or document.
cat > /tmp/ssm-send-command-policy.json << 'EOF'
{
    "Version": "2012-10-17",
    "Statement": [
        {
            "Effect": "Allow",
            "Action": [
                "ssm:SendCommand",
                "ssm:GetCommandInvocation",
                "ssm:ListCommands"
            ],
            "Resource": "*"
        }
    ]
}
EOF

aws iam put-user-policy \
  --user-name "$USER_NAME" \
  --policy-name BombestSSMPolicy \
  --policy-document file:///tmp/ssm-send-command-policy.json

echo "✅ SSM permissions added. Try: ./deploy-nginx-to-ec2.sh"
echo ""
echo "Note: This policy uses Resource: \"*\". For production, consider scoping to specific"
echo "instance ARNs or the AWS-RunShellScript document to limit blast radius."

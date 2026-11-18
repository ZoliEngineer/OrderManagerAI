#!/usr/bin/env bash
# One-time setup: creates the S3 bucket that holds Terraform state, then runs `terraform init`.
# Needs AWS credentials for the target account (aws configure / SSO). Safe to re-run.
set -euo pipefail

REGION="${AWS_REGION:-us-east-2}"
ACCOUNT_ID="$(aws sts get-caller-identity --query Account --output text)"
BUCKET="ordermanagerai-tfstate-${ACCOUNT_ID}"

if ! aws s3api head-bucket --bucket "$BUCKET" 2>/dev/null; then
  echo "Creating state bucket $BUCKET"
  aws s3api create-bucket --bucket "$BUCKET" --region "$REGION" \
    --create-bucket-configuration "LocationConstraint=$REGION"
fi
aws s3api put-bucket-versioning --bucket "$BUCKET" --versioning-configuration Status=Enabled
aws s3api put-public-access-block --bucket "$BUCKET" \
  --public-access-block-configuration BlockPublicAcls=true,IgnorePublicAcls=true,BlockPublicPolicy=true,RestrictPublicBuckets=true
aws s3api put-bucket-encryption --bucket "$BUCKET" \
  --server-side-encryption-configuration '{"Rules":[{"ApplyServerSideEncryptionByDefault":{"SSEAlgorithm":"AES256"}}]}'

cd "$(dirname "$0")"
terraform init -reconfigure -backend-config="bucket=${BUCKET}"
echo "Done. Next: create terraform.tfvars (domain_name, budget_email, secrets) and run terraform apply."

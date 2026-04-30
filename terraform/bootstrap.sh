#!/usr/bin/env bash
set -euo pipefail

# Creates the Service Principal for GitHub Actions + Terraform, sets up the
# Azure storage account for Terraform remote state, then initialises Terraform.
# Safe to re-run — all az commands are idempotent.

RESOURCE_GROUP="tfstate-rg"
CONTAINER="tfstate"
LOCATION="eastus"
SP_NAME="ordermanagerai-deployer"

echo "==> Checking Azure CLI login..."
az account show --query "{subscription:name, tenant:tenantId}" -o table

SUBSCRIPTION_ID=$(az account show --query id -o tsv | tr -d '\r\n')
echo "==> Setting active subscription: $SUBSCRIPTION_ID"
az account set --subscription "$SUBSCRIPTION_ID"

SUBSCRIPTION_SUFFIX=$(echo "$SUBSCRIPTION_ID" | tr -d '-' | cut -c1-8)
STORAGE_ACCOUNT="tfstateordermgr${SUBSCRIPTION_SUFFIX}"

echo ""
echo "==> Creating service principal '$SP_NAME'..."
SP_JSON=$(MSYS_NO_PATHCONV=1 az ad sp create-for-rbac \
  --name "$SP_NAME" \
  --role Contributor \
  --scopes "/subscriptions/${SUBSCRIPTION_ID}" \
  --sdk-auth \
  --output json)

echo ""
echo "================================================================"
echo "  AZURE_CREDENTIALS — paste this as a GitHub Actions secret"
echo "================================================================"
echo "$SP_JSON"
echo "================================================================"
echo ""
echo "  Go to: GitHub repo → Settings → Secrets and variables →"
echo "         Actions → Secrets → New repository secret"
echo "  Name:  AZURE_CREDENTIALS"
echo "  Value: the JSON block above"
echo "================================================================"
echo ""
read -rp "Press Enter once you have saved the secret to continue..."

echo "==> Using state storage account: tfstateordermgr${SUBSCRIPTION_SUFFIX}"

echo ""
echo "==> Registering required resource providers..."
az provider register --namespace Microsoft.Storage --subscription "$SUBSCRIPTION_ID" --wait
az provider register --namespace Microsoft.Authorization --subscription "$SUBSCRIPTION_ID" --wait
az provider register --namespace Microsoft.App --subscription "$SUBSCRIPTION_ID" --wait
az provider register --namespace Microsoft.KeyVault --subscription "$SUBSCRIPTION_ID" --wait
az provider register --namespace Microsoft.ContainerRegistry --subscription "$SUBSCRIPTION_ID" --wait

echo ""
echo "==> Creating resource group '$RESOURCE_GROUP'..."
az group create \
  --name "$RESOURCE_GROUP" \
  --location "$LOCATION" \
  --subscription "$SUBSCRIPTION_ID" \
  --output none

echo "==> Creating storage account '$STORAGE_ACCOUNT'..."
az storage account create \
  --name "$STORAGE_ACCOUNT" \
  --resource-group "$RESOURCE_GROUP" \
  --location "$LOCATION" \
  --subscription "$SUBSCRIPTION_ID" \
  --sku Standard_LRS \
  --allow-blob-public-access false \
  --output none

echo "==> Creating blob container '$CONTAINER'..."
az storage container create \
  --name "$CONTAINER" \
  --account-name "$STORAGE_ACCOUNT" \
  --subscription "$SUBSCRIPTION_ID" \
  --auth-mode login \
  --output none

echo ""
echo "==> Running terraform init (storage account: $STORAGE_ACCOUNT)..."
terraform init -reconfigure \
  -backend-config="storage_account_name=${STORAGE_ACCOUNT}"

echo ""
echo "Bootstrap complete. You can now run: terraform plan"

variable "location" {
  default = "eastus"
}

variable "project_name" {
  default = "ordermanagerai"
}

variable "environment" {
  default = "prod"
}

variable "redis_password" {
  sensitive = true
}

variable "kafka_cluster_api_key" {
  sensitive = true
}

variable "kafka_cluster_api_secret" {
  sensitive = true
}

variable "finnhub_api_key" {
  sensitive = true
  default   = ""
}

variable "neon_db_password" {
  description = "Password for the Neon PostgreSQL ordermanager_db_user role"
  sensitive   = true
}

variable "supabase_db_password" {
  description = "Password for the Supabase PostgreSQL ordermanager_user role (order service)"
  sensitive   = true
}

variable "ghcr_username" {
  description = "GitHub username used to pull images from ghcr.io (must have read:packages scope)"
}

variable "ghcr_token" {
  description = "GitHub PAT with read:packages scope for Container Apps to pull from ghcr.io"
  sensitive   = true
}
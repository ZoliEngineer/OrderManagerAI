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
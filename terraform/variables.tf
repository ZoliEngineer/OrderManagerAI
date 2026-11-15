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

variable "anthropic_api_key" {
  description = "Claude API key for the trading advisor service."
  sensitive   = true
  default     = ""
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

variable "observability_enabled" {
  description = "Deploy Prometheus + Grafana and keep scraped services running (min 1 replica). Scale-to-zero services would otherwise be woken by every scrape anyway."
  type        = bool
  default     = true
}

variable "grafana_admin_password" {
  description = "Admin password for the Grafana Container App (login user: admin). Set via TF_VAR_grafana_admin_password."
  sensitive   = true
}

variable "region" {
  description = "The account's assigned Region (AWS Settings → Project). New-style accounts can only create regional resources there."
  type        = string
  default     = "us-east-2"
}

variable "project_name" {
  type    = string
  default = "ordermanagerai"
}

variable "environment" {
  type    = string
  default = "prod"
}

variable "alb_enabled" {
  description = "The ALB costs ~$22/month and cannot scale to zero. Set false when idle (and scale services to 0)."
  type        = bool
  default     = true
}

variable "use_spot" {
  description = "Run tasks on Fargate Spot (~70% cheaper, can be interrupted)."
  type        = bool
  default     = false
}

variable "cpu_architecture" {
  description = "X86_64, or ARM64 (~20% cheaper; images must be built for arm64)."
  type        = string
  default     = "X86_64"
}

variable "initial_desired_count" {
  description = "Tasks per service at creation. 0 = created switched off; use the env-up workflow to start."
  type        = number
  default     = 0
}

variable "budget_limit_usd" {
  type    = number
  default = 20
}

variable "budget_email" {
  description = "Email notified at 80% actual / 100% forecast of the monthly budget."
  type        = string
}

variable "advisor_emails" {
  description = "Cognito users to create (an invitation with a temporary password is emailed)."
  type        = list(string)
  default     = []
}

# ── Secrets (stored in Secrets Manager, injected into the tasks) ─────────────────
variable "redis_password" {
  type      = string
  sensitive = true
}

variable "kafka_cluster_api_key" {
  type      = string
  sensitive = true
}

variable "kafka_cluster_api_secret" {
  type      = string
  sensitive = true
}

variable "anthropic_api_key" {
  description = "Claude API key for the trading advisor service."
  type        = string
  sensitive   = true
  default     = ""
}

variable "finnhub_api_key" {
  description = "Leave empty to use the random price simulator."
  type        = string
  sensitive   = true
  default     = ""
}

variable "db_password" {
  description = "Password of the Supabase Postgres role used by the account and order services."
  type        = string
  sensitive   = true
}

variable "observability_enabled" {
  description = "Create the Prometheus and Grafana ECS services (Grafana at /grafana/ on the CloudFront address). They start at desired count 0 like the others; costs about $0.04/hour while running."
  type        = bool
  default     = false
}

variable "grafana_admin_password" {
  description = "Grafana admin password (user: admin). Required, at least 12 characters, when observability_enabled = true: Grafana is reachable from the internet."
  type        = string
  sensitive   = true
  default     = ""
}

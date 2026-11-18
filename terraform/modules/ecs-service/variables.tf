variable "name" {
  type = string
}

variable "region" {
  type = string
}

variable "cluster_arn" {
  type = string
}

variable "image" {
  description = "Full image reference, e.g. <account>.dkr.ecr.us-east-1.amazonaws.com/ordermanagerai-order:latest"
  type        = string
}

variable "cpu" {
  type    = number
  default = 256
}

variable "memory" {
  type    = number
  default = 1024
}

variable "cpu_architecture" {
  type    = string
  default = "X86_64"
}

variable "use_spot" {
  type    = bool
  default = false
}

variable "desired_count" {
  description = "Initial count only; scaling afterwards is done by the env-up/env-down workflows."
  type        = number
  default     = 0
}

variable "container_port" {
  description = "HTTP/gRPC port the container listens on. null for services with no listener (execution)."
  type        = number
  default     = null
}

variable "health_port" {
  description = "Port serving /actuator/health, if different from container_port (risk: 8083). null and no container_port = no health check."
  type        = number
  default     = null
}

variable "environment" {
  type    = map(string)
  default = {}
}

variable "secrets" {
  description = "Env var name -> Secrets Manager valueFrom (arn:key::)."
  type        = map(string)
  default     = {}
}

variable "execution_role_arn" {
  type = string
}

variable "subnet_ids" {
  type = list(string)
}

variable "security_group_ids" {
  type = list(string)
}

variable "log_retention_days" {
  type    = number
  default = 7
}

variable "discovery_namespace_id" {
  type    = string
  default = null
}

variable "discovery_name" {
  description = "Cloud Map service name (becomes <name>.<namespace>). null = not discoverable."
  type        = string
  default     = null
}

variable "vpc_id" {
  type = string
}

variable "alb_enabled" {
  type    = bool
  default = false
}

variable "listener_arn" {
  type    = string
  default = null
}

variable "alb_rules" {
  description = "Public routes to this service. Priorities must be unique across the listener."
  type = list(object({
    priority = number
    hosts    = optional(list(string), [])
    paths    = optional(list(string), [])
  }))
  default = []
}

variable "health_path" {
  description = "Path used by the container and load balancer health checks (includes the context path, if any). Spring Boot liveness group by default, so a down dependency does not restart the task."
  type        = string
  default     = "/actuator/health/liveness"
}

variable "origin_verify" {
  description = "Secret value of the X-Origin-Verify header that CloudFront adds; listener rules only match requests carrying it."
  type        = string
  default     = null
  sensitive   = true
}

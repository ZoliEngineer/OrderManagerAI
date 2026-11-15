variable "name" {
  type = string
}

variable "container_name" {
  type    = string
  default = null
}

variable "environment_id" {
  type = string
}

variable "resource_group" {
  type = string
}

variable "registry_server" {
  type    = string
  default = "ghcr.io"
}

variable "registry_username" {
  type    = string
  default = ""
}

variable "registry_password" {
  type      = string
  sensitive = true
  default   = ""
}

variable "target_port" {
  type    = number
  default = null
}

variable "max_replicas" {
  type    = number
  default = 2
}

variable "transport" {
  type    = string
  default = "http"
}

variable "enable_ingress" {
  type    = bool
  default = true
}

variable "extra_secrets" {
  description = "Service-specific secrets beyond acr-password."
  type = list(object({
    name  = string
    value = string
  }))
  default = []
}

variable "env_vars" {
  description = "Container environment variables. Each entry must set value or secret_name (not both)."
  type = list(object({
    name        = string
    value       = optional(string)
    secret_name = optional(string)
  }))
  default = []
}

variable "image" {
  description = "Initial image. The deploy workflow replaces it (ignore_changes), except for third-party images that are never redeployed."
  type        = string
  default     = "mcr.microsoft.com/azuredocs/containerapps-helloworld:latest"
}

variable "min_replicas" {
  type    = number
  default = 0
}

variable "cpu" {
  type    = number
  default = 0.25
}

variable "memory" {
  type    = string
  default = "0.5Gi"
}

variable "args" {
  description = "Container arguments (optional)."
  type        = list(string)
  default     = null
}

variable "external_ingress" {
  description = "true: reachable from the internet. false: only from inside the Container Apps environment."
  type        = bool
  default     = true
}

variable "secret_volume_path" {
  description = "If set, every secret of this app is mounted as a file under this directory (file name = secret name)."
  type        = string
  default     = null
}

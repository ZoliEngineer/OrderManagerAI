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

variable "acr_server" {
  type = string
}

variable "acr_username" {
  type = string
}

variable "acr_password" {
  type      = string
  sensitive = true
}

variable "target_port" {
  type = number
}

variable "max_replicas" {
  type    = number
  default = 2
}

variable "transport" {
  type    = string
  default = "http"
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

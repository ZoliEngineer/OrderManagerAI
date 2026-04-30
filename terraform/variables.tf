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
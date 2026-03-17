# JOTP - Azure Variables

variable "subscription_id" {
  type        = string
  description = "Azure subscription ID (from ARM_SUBSCRIPTION_ID env var)"
}

variable "client_id" {
  type        = string
  description = "Azure service principal client ID (from ARM_CLIENT_ID env var)"
}

variable "client_secret" {
  type        = string
  description = "Azure service principal client secret (from ARM_CLIENT_SECRET env var)"
  sensitive   = true
}

variable "tenant_id" {
  type        = string
  description = "Azure tenant ID (from ARM_TENANT_ID env var)"
}

variable "location" {
  type        = string
  default     = "eastus"
  description = "Azure region"
}

variable "resource_group_name" {
  type        = string
  default     = "jotp-rg"
  description = "Resource group name"
}

variable "app_name" {
  type        = string
  default     = "jotp"
  description = "Application name"
}

variable "environment" {
  type        = string
  default     = "production"
  description = "Environment name"
}

variable "vnet_cidr" {
  type        = string
  default     = "10.0.0.0/16"
  description = "Virtual network CIDR"
}

variable "subnet_cidr" {
  type        = string
  default     = "10.0.1.0/24"
  description = "Subnet CIDR"
}

variable "image_id" {
  type        = string
  description = "Custom image ID (from Packer build)"
}

variable "vm_size" {
  type        = string
  default     = "Standard_B2s"
  description = "VM size"
}

variable "admin_username" {
  type        = string
  default     = "azureuser"
  description = "Admin username"
}

variable "ssh_public_key" {
  type        = string
  description = "SSH public key"
}

variable "app_port" {
  type        = number
  default     = 8080
  description = "Application port"
}

variable "os_disk_size" {
  type        = number
  default     = 30
  description = "OS disk size in GB"
}

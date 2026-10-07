variable "pm_api_url" {
  description = "ProxMox VE API URL, e.g. https://192.168.1.253:8006"
  type        = string
}

variable "pm_api_token" {
  description = "ProxMox API token in user@realm!tokenid=secret format"
  type        = string
  sensitive   = true
}

variable "pm_tls_insecure" {
  description = "Skip TLS certificate verification (self-signed PVE cert)"
  type        = bool
  default     = true
}

variable "pm_node" {
  description = "ProxMox node name to place VMs on"
  type        = string
  default     = "prox-01"
}

variable "pm_datastore" {
  description = "Datastore for VM disks and cloud-init (local-lvm is disabled on this PVE, dir storage 'storage' is used)"
  type        = string
  default     = "storage"
}

variable "pm_snippet_datastore" {
  description = "Datastore for cloud-init snippets (must support 'snippets' content)"
  type        = string
  default     = "storage"
}

variable "pm_bridge" {
  description = "Network bridge for VM NICs"
  type        = string
  default     = "vmbr0"
}

variable "vm_template" {
  description = "VMID of the Ubuntu 22.04 cloud-init template to clone from"
  type        = number
  default     = 9000
}

variable "ssh_public_key_path" {
  description = "Path to the public SSH key injected into VMs for user 'deploy'"
  type        = string
  default     = "../security/deploy_key.pub"
}

variable "gateway" {
  description = "Default gateway for VMs"
  type        = string
  default     = "192.168.1.1"
}

variable "dns_servers" {
  description = "DNS servers configured via cloud-init"
  type        = list(string)
  default     = ["192.168.1.1", "1.1.1.1"]
}

variable "kafka_ips" {
  description = "Static IPs for kafka-1..3"
  type        = list(string)
  default     = ["192.168.1.230", "192.168.1.231", "192.168.1.232"]
}

variable "rabbitmq_ips" {
  description = "Static IPs for rabbitmq-1..3"
  type        = list(string)
  default     = ["192.168.1.233", "192.168.1.234", "192.168.1.235"]
}

variable "redis_ips" {
  description = "Static IPs for redis-1..2"
  type        = list(string)
  default     = ["192.168.1.237", "192.168.1.238"]
}

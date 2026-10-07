# ---------------------------------------------------------------------------
# Proxmox connection
# ---------------------------------------------------------------------------

variable "pm_api_url" {
  description = "Proxmox VE API endpoint URL (e.g. https://192.168.1.253:8006/)"
  type        = string
}

variable "pm_api_token_id" {
  description = "Proxmox API token ID (e.g. root@pam!terraform)"
  type        = string
}

variable "pm_api_token_secret" {
  description = "Proxmox API token secret"
  type        = string
  sensitive   = true
}

variable "pm_node_name" {
  description = "Proxmox node name"
  type        = string
  default     = "prox-01"
}

variable "pm_datastore_id" {
  description = "Proxmox storage ID used for the cloud image, VM disks and cloud-init drive"
  type        = string
  default     = "storage"
}

variable "pm_bridge" {
  description = "Proxmox network bridge"
  type        = string
  default     = "vmbr0"
}

# ---------------------------------------------------------------------------
# SSH / cloud-init
# ---------------------------------------------------------------------------

variable "ssh_public_key" {
  description = "Public SSH key for the 'ubuntu' user. Defaults to reading Security/id_ed25519.pub via file()."
  type        = string
  default     = ""
}

# ---------------------------------------------------------------------------
# Cloud image
# ---------------------------------------------------------------------------

variable "cloud_image_url" {
  description = "Ubuntu Server 22.04 LTS cloud image URL"
  type        = string
  default     = "https://cloud-images.ubuntu.com/jammy/current/jammy-server-cloudimg-amd64.img"
}

variable "cloud_image_file" {
  description = "File name of the cloud image in the datastore (renamed to .qcow2 for import)"
  type        = string
  default     = "jammy-server-cloudimg-amd64.qcow2"
}

# ---------------------------------------------------------------------------
# Network
# ---------------------------------------------------------------------------

variable "network_gateway" {
  description = "Default gateway for VMs (diagnosed from Proxmox vmbr0)"
  type        = string
  default     = "192.168.1.1"
}

variable "network_dns_server" {
  description = "DNS server for VMs (diagnosed from Proxmox node DNS config)"
  type        = string
  default     = "192.168.1.1"
}

variable "network_dns_domain" {
  description = "DNS search domain for VMs"
  type        = string
  default     = "t3adog.ru"
}

# ---------------------------------------------------------------------------
# VM resources
# ---------------------------------------------------------------------------

variable "vm_cpu_cores" {
  description = "Number of vCPUs per VM"
  type        = number
  default     = 2
}

variable "vm_memory_mb" {
  description = "RAM per VM in MB"
  type        = number
  default     = 2048
}

variable "vm_disk_size_gb" {
  description = "Disk size per VM in GB"
  type        = number
  default     = 40
}

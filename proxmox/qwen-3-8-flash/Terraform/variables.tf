# --- Proxmox connection (SECRETS VIA ENV ONLY, never hardcoded in .tf) ---
variable "pm_api_endpoint" {
  description = "Proxmox VE API endpoint. If null, provider reads PM_API_ENDPOINT env var."
  type        = string
  default     = null
}

variable "pm_api_token_id" {
  description = "Proxmox API token id. If null, provider reads PM_API_TOKEN_ID env var."
  type        = string
  default     = null
  sensitive   = true
}

variable "pm_api_token_secret" {
  description = "Proxmox API token secret. If null, provider reads PM_API_TOKEN_SECRET env var."
  type        = string
  default     = null
  sensitive   = true
}

variable "pm_tls_insecure" {
  description = "Skip TLS verification for the self-signed Proxmox certificate."
  type        = bool
  default     = true
}

# --- Cluster / host parameters (from Stage-2 recon via API) ---
variable "node_name" {
  description = "Proxmox node name (GET /nodes)."
  type        = string
  default     = "prox-01"
}

variable "bridge" {
  description = "Network bridge for VM NICs (GET /nodes/<n>/network?type=bridge)."
  type        = string
  default     = "vmbr0"
}

variable "gateway" {
  description = "Default gateway for cloud-init static IP."
  type        = string
  default     = "192.168.1.1"
}

variable "netmask_prefix" {
  description = "IPv4 CIDR prefix length (bridge is /24)."
  type        = string
  default     = "24"
}

variable "nameservers" {
  description = "DNS servers injected into cloud-init."
  type        = list(string)
  default     = ["192.168.1.1"]
}

variable "search_domain" {
  description = "DNS search domain for the VMs."
  type        = string
  default     = "pipeline.local"
}

# --- Storage / image ---
variable "datastore_id" {
  description = "Storage for both the qcow2 template and VM disks (recon: 'storage', dir, enabled, content=images/iso/import)."
  type        = string
  default     = "storage"
}

variable "image_url" {
  description = "Debian 12 genericcloud qcow2 URL."
  type        = string
  default     = "https://cloud.debian.org/images/cloud/bookworm/latest/debian-12-genericcloud-amd64.qcow2"
}

variable "image_file_name" {
  description = "Name of the downloaded image file in the datastore."
  type        = string
  default     = "debian-12-genericcloud-amd64.qcow2"
}

# --- Common VM sizing (2 vCPU / 2048 MB / 40 GB) ---
variable "vm_cpu_cores" {
  type    = number
  default = 2
}

variable "vm_cpu_sockets" {
  type    = number
  default = 1
}

variable "vm_memory_mb" {
  type    = number
  default = 2048
}

variable "vm_disk_gb" {
  type    = number
  default = 40
}

# --- SSH key for cloud-init (path relative to this module, NOT a secret) ---
variable "ssh_public_key_path" {
  description = "Path to the VM SSH public key injected into cloud-init user 'ops'."
  type        = string
  default     = "../Security/id_ed25519_vm.pub"
}

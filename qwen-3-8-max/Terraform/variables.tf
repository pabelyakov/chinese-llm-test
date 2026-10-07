# variables.tf — входные параметры раскатки (значения по умолчанию подтверждены
# Agent 2 через Proxmox API 2026-10-07, см. Terraform/README.md §D6).
# Переопределение — terraform.tfvars (gitignored) или TF_VAR_*.

variable "pve_node" {
  description = "Имя узла Proxmox VE (подтверждено через GET /nodes)."
  type        = string
  default     = "prox-01"
}

variable "pve_storage" {
  description = "Storage для дисков/cloud-init/образа (dir, активен, ~1.8TB avail, content: images,import,iso,snippets)."
  type        = string
  default     = "storage"
}

variable "pve_bridge" {
  description = "Сетевой мост (подтверждён через GET /nodes/prox-01/network: vmbr0, gw 192.168.1.1)."
  type        = string
  default     = "vmbr0"
}

variable "gateway" {
  description = "Шлюз по умолчанию (D6: подтверждён — атрибут gateway интерфейса vmbr0 в API Proxmox)."
  type        = string
  default     = "192.168.1.1"
}

variable "netmask" {
  description = "Маска подсети (CIDR-суффикс)."
  type        = number
  default     = 24
}

variable "dns_servers" {
  description = "DNS-серверы для cloud-init (D6: 192.168.1.1 — резолвер на шлюзе, 8.8.8.8 — внешний)."
  type        = list(string)
  default     = ["192.168.1.1", "8.8.8.8"]
}

variable "timezone" {
  description = "Часовой пояс cloud-init (зафиксирован: Europe/Moscow)."
  type        = string
  default     = "Europe/Moscow"
}

variable "vm_user" {
  description = "Пользователь cloud-init (D3)."
  type        = string
  default     = "ubuntu"
}

variable "ssh_public_key_path" {
  description = "Путь к публичному SSH-ключу (относительно каталога Terraform/)."
  type        = string
  default     = "../Security/id_ed25519.pub"
}

variable "ssh_private_key_path" {
  description = "Абсолютный путь к приватному ключу для Ansible-inventory (в сам inventory пишется ПУТЬ, не ключ)."
  type        = string
  default     = "/Users/pabelyakov/Projects/llm-test/kimi/Security/id_ed25519"
}

variable "vm_cores" {
  description = "vCPU на VM — жёсткий лимит D8."
  type        = number
  default     = 2
}

variable "vm_memory_mb" {
  description = "RAM на VM, MiB — жёсткий лимит D8 (floating=0, ballooning выключен)."
  type        = number
  default     = 2048
}

variable "vm_disk_gb" {
  description = "Размер диска scsi0, GiB — жёсткий лимит D8."
  type        = number
  default     = 40
}

variable "template_vmid" {
  description = "VMID базового образа-шаблона Ubuntu 24.04 (диапазон 9000+, проверен на незанятость)."
  type        = number
  default     = 9000
}

variable "cloud_image_file_name" {
  description = "Имя файла образа в storage Proxmox (content: import; расширение .qcow2 обязательно для import-from). Загружается скриптом Terraform/ensure-image.sh."
  type        = string
  default     = "noble-server-cloudimg-amd64.qcow2"
}

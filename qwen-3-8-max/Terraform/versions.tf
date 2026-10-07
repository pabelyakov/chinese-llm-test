# versions.tf — фиксация версий Terraform и провайдеров (D4)
# Provider bpg/proxmox закреплён ТОЧНОЙ версией: API провайдера 0.x меняется
# между минорными версиями, фиксация гарантирует воспроизводимость apply.
terraform {
  required_version = ">= 1.5.0, < 2.0.0"

  required_providers {
    proxmox = {
      source  = "bpg/proxmox"
      version = "0.116.0"
    }
    local = {
      source  = "hashicorp/local"
      version = "~> 2.5"
    }
  }
}

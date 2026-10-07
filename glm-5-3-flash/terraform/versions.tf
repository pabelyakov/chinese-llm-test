terraform {
  required_version = ">= 1.6.0"

  required_providers {
    proxmox = {
      source  = "bpg/proxmox"
      version = "0.116.0"
    }
  }
}

provider "proxmox" {
  endpoint  = trimsuffix(var.pm_api_url, "/")
  api_token = var.pm_api_token
  insecure  = var.pm_tls_insecure
}

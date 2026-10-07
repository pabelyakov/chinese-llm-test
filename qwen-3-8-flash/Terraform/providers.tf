# Provider for Proxmox VE (bpg/proxmox ~> 0.116).
#
# SECRETS ARE NEVER HARD-CODED IN .tf FILES. Credentials reach Terraform ONLY
# through environment variables at run time. This provider version reads the
# following env vars natively (see README for the PM_API_* -> PROXMOX_VE_* map):
#
#   PROXMOX_VE_ENDPOINT   = https://192.168.1.253:8006
#   PROXMOX_VE_API_TOKEN  = "root@pam!terraform=<secret>"
#   PROXMOX_VE_INSECURE   = true
#
# Alternatively the values can be injected via TF_VAR_* variables below
# (tfvars-free). Both mechanisms keep the token out of the repository.
# When the variables are null, the provider falls back to the env vars above.

provider "proxmox" {
  endpoint  = var.pm_api_endpoint
  api_token = var.pm_api_token_id != null && var.pm_api_token_secret != null ? "${var.pm_api_token_id}=${var.pm_api_token_secret}" : null
  insecure  = var.pm_tls_insecure
}

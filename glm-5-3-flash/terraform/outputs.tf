output "vm_ips" {
  description = "Map of VM name → IP address (ready for Ansible inventory)"
  value       = { for name, cfg in local.vms : name => cfg.ip }
}

output "vm_ids" {
  description = "Map of VM name → ProxMox VMID"
  value       = { for name, cfg in local.vms : name => tonumber(element(split(".", cfg.ip), 3)) }
}

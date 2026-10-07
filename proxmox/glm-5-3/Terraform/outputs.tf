output "vms" {
  description = "Созданные VM: имя -> IP (статический IP-план)."
  value       = { for name, vm in local.vms : name => vm.ip }
}

output "vm_ids" {
  description = "Созданные VM: имя -> VMID."
  value       = { for name, vm in local.vms : name => vm.vmid }
}

output "ssh_verify" {
  description = "Команда проверки SSH-доступа ко всем VM (запускать из Terraform/)."
  value       = "for ip in 192.168.1.230 192.168.1.231 192.168.1.232 192.168.1.233 192.168.1.234 192.168.1.235 192.168.1.237 192.168.1.238; do ssh -o StrictHostKeyChecking=no -o ConnectTimeout=10 -i ../Security/ed25519 deploy@$ip hostname -s; done"
}

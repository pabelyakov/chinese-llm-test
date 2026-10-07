output "image_volume_id" {
  description = "Datastore volume id of the Debian 12 genericcloud image."
  value       = proxmox_virtual_environment_download_file.debian12_genericcloud.id
}

output "vm_details" {
  description = "Per-VM: vm_id, planned static IPv4, guest-reported IPv4, ssh command."
  value = {
    for name, vm in proxmox_virtual_environment_vm.pipeline_vm : name => {
      vm_id      = vm.vm_id
      ipv4_plan  = local.vms[name].ip
      ipv4_guest = try(vm.ipv4_addresses[0], null)
      ssh        = "ssh -o StrictHostKeyChecking=no -i ../Security/id_ed25519_vm ops@${local.vms[name].ip}"
    }
  }
}

output "ssh_commands" {
  description = "Ready-to-paste ssh command per VM (static IP from plan)."
  value = {
    for name, cfg in local.vms :
    name => "ssh -o StrictHostKeyChecking=no -i ../Security/id_ed25519_vm ops@${cfg.ip}"
  }
}

output "vm_ids" {
  description = "vm_id (== last octet) per VM."
  value = {
    for name, vm in proxmox_virtual_environment_vm.pipeline_vm : name => vm.vm_id
  }
}

output "ips" {
  description = "Static IPv4 per VM from the address plan."
  value = {
    for name, cfg in local.vms : name => cfg.ip
  }
}

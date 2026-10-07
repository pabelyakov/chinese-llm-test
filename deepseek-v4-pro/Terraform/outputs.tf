output "vm_ips" {
  description = "Map of VM name -> static IP address"
  value = {
    for name, spec in local.vms : name => spec.ip
  }
}

output "vm_ids" {
  description = "Map of VM name -> Proxmox VM ID"
  value = {
    for name, vm in proxmox_virtual_environment_vm.vm : name => vm.vm_id
  }
}

output "vm_groups" {
  description = "Map of VM name -> ansible group"
  value = {
    for name, spec in local.vms : name => spec.group
  }
}

output "cloud_image_id" {
  description = "Datastore file ID of the uploaded Ubuntu cloud image"
  value       = proxmox_download_file.ubuntu_cloud_image.id
}

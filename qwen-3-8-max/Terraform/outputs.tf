# outputs.tf — итоги раскатки: шаблон, 8 VM (vmid/имя/IP), путь к inventory.

output "template_vm" {
  description = "Базовый образ-шаблон Ubuntu 24.04 (источник клонов)."
  value = {
    vmid = proxmox_virtual_environment_vm.ubuntu_template.vm_id
    name = proxmox_virtual_environment_vm.ubuntu_template.name
    node = proxmox_virtual_environment_vm.ubuntu_template.node_name
  }
}

output "vms" {
  description = "Все 8 VM: vmid, имя, плановый IP и IP, сообщённый qemu-guest-agent."
  value = {
    for name, vm in proxmox_virtual_environment_vm.node : name => {
      vmid       = vm.vm_id
      name       = vm.name
      node       = vm.node_name
      ip         = local.vms[name].ip
      group      = local.vms[name].group
      agent_ipv4 = try(flatten(vm.ipv4_addresses)[0], null) # отчёт guest-agent
    }
  }
}

output "vm_list" {
  description = "Плоский список VM (для быстрых проверок/скриптов)."
  value = [
    for name, vm in proxmox_virtual_environment_vm.node : {
      vmid = vm.vm_id
      name = name
      ip   = local.vms[name].ip
    }
  ]
}

output "ansible_inventory_path" {
  description = "Путь к сгенерированному Ansible inventory."
  value       = local_file.ansible_inventory.filename
}

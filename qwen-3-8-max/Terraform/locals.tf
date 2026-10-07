# locals.tf — IPAM-карта 8 VM (единый источник: docs/INFRA.md §3).
# VMID = 9000 + последний октет IP (мнемоника, все свободны на 2026-10-07:
# в Proxmox заняты только 100/101/102).
# Резерв .236/.239/.240 НЕ используется (D1).

locals {
  vms = {
    kafka-1 = { vmid = 9230, ip = "192.168.1.230", group = "kafka" }
    kafka-2 = { vmid = 9231, ip = "192.168.1.231", group = "kafka" }
    kafka-3 = { vmid = 9232, ip = "192.168.1.232", group = "kafka" }

    rabbitmq-1 = { vmid = 9233, ip = "192.168.1.233", group = "rabbitmq" }
    rabbitmq-2 = { vmid = 9234, ip = "192.168.1.234", group = "rabbitmq" }
    rabbitmq-3 = { vmid = 9235, ip = "192.168.1.235", group = "rabbitmq" }

    redis-1 = { vmid = 9237, ip = "192.168.1.237", group = "redis" }
    redis-2 = { vmid = 9238, ip = "192.168.1.238", group = "redis" }
  }

  groups = distinct([for name, vm in local.vms : vm.group])

  # Ansible inventory (Ansible/inventory/hosts.yml) — генерируется из этой карты.
  inventory = {
    all = {
      vars = {
        ansible_user                 = var.vm_user
        ansible_ssh_private_key_file = var.ssh_private_key_path
        # первый ключ ещё не в known_hosts — принимаем автоматически (host-key TOFU)
        ansible_ssh_common_args = "-o StrictHostKeyChecking=accept-new"
      }
      children = {
        for g in local.groups : g => {
          hosts = {
            for name, vm in local.vms : name => { ansible_host = vm.ip }
            if vm.group == g
          }
        }
      }
    }
  }
}

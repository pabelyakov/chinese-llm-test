locals {
  # One parameterized map for all 8 VMs: hostname → { role, ip }.
  # VMID is derived from the last IP octet (e.g. 192.168.1.230 → VM 230).
  vms = merge(
    { for i, ip in var.kafka_ips : format("kafka-%d", i + 1) => { role = "kafka", ip = ip } },
    { for i, ip in var.rabbitmq_ips : format("rabbitmq-%d", i + 1) => { role = "rabbitmq", ip = ip } },
    { for i, ip in var.redis_ips : format("redis-%d", i + 1) => { role = "redis", ip = ip } },
  )
}

resource "proxmox_virtual_environment_file" "cloud_init_user" {
  for_each     = local.vms
  content_type = "snippets"
  datastore_id = var.pm_snippet_datastore
  node_name    = var.pm_node

  source_raw {
    data      = <<-EOF
#cloud-config
hostname: ${each.key}
manage_etc_hosts: true
users:
  - name: deploy
    shell: /bin/bash
    sudo: ALL=(ALL) NOPASSWD:ALL
    lock_passwd: true
    ssh_authorized_keys:
      - ${trimspace(file(var.ssh_public_key_path))}
package_update: true
packages:
  - qemu-guest-agent
runcmd:
  - systemctl enable --now qemu-guest-agent
EOF
    file_name = "${each.key}-user-data.yaml"
  }
}

resource "proxmox_virtual_environment_vm" "vm" {
  for_each    = local.vms
  name        = each.key
  node_name   = var.pm_node
  vm_id       = tonumber(element(split(".", each.value.ip), 3))
  description = "Managed by Terraform. Role: ${each.value.role}"
  tags        = [each.value.role, "terraform"]
  on_boot     = true

  clone {
    vm_id   = var.vm_template
    full    = true
    retries = 3
  }

  agent {
    enabled = true
  }

  cpu {
    cores = 2
    type  = "host"
  }

  memory {
    dedicated = 2048
  }

  disk {
    datastore_id = var.pm_datastore
    interface    = "scsi0"
    size         = 40
    ssd          = true
    discard      = "on"
  }

  network_device {
    bridge = var.pm_bridge
    model  = "virtio"
  }

  operating_system {
    type = "l26"
  }

  initialization {
    datastore_id      = var.pm_snippet_datastore
    user_data_file_id = proxmox_virtual_environment_file.cloud_init_user[each.key].id
    upgrade           = false

    dns {
      servers = var.dns_servers
    }

    ip_config {
      ipv4 {
        address = "${each.value.ip}/24"
        gateway = var.gateway
      }
    }
  }
}

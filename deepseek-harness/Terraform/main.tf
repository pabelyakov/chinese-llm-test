locals {
  vms = {
    kafka-1  = { ip = "192.168.1.230", vmid = 201, group = "kafka" }
    kafka-2  = { ip = "192.168.1.231", vmid = 202, group = "kafka" }
    kafka-3  = { ip = "192.168.1.232", vmid = 203, group = "kafka" }
    rabbit-1 = { ip = "192.168.1.233", vmid = 211, group = "rabbitmq" }
    rabbit-2 = { ip = "192.168.1.234", vmid = 212, group = "rabbitmq" }
    rabbit-3 = { ip = "192.168.1.235", vmid = 213, group = "rabbitmq" }
    redis-1  = { ip = "192.168.1.237", vmid = 221, group = "redis" }
    redis-2  = { ip = "192.168.1.238", vmid = 222, group = "redis" }
  }

  ssh_public_key = trimspace(coalesce(
    var.ssh_public_key,
    file("${path.module}/../Security/id_ed25519.pub")
  ))
}

resource "proxmox_download_file" "ubuntu_cloud_image" {
  content_type = "import"
  datastore_id = var.pm_datastore_id
  node_name    = var.pm_node_name
  url          = var.cloud_image_url
  file_name    = var.cloud_image_file
  overwrite    = false
}

resource "proxmox_virtual_environment_vm" "vm" {
  for_each = local.vms

  name        = each.key
  description = "Managed by Terraform (${each.value.group})"
  node_name   = var.pm_node_name
  vm_id       = each.value.vmid
  tags        = ["terraform", each.value.group]

  stop_on_destroy = true

  agent {
    enabled = false
  }

  cpu {
    cores = var.vm_cpu_cores
    type  = "x86-64-v2-AES"
  }

  memory {
    dedicated = var.vm_memory_mb
  }

  disk {
    datastore_id = var.pm_datastore_id
    import_from  = proxmox_download_file.ubuntu_cloud_image.id
    interface    = "virtio0"
    size         = var.vm_disk_size_gb
    file_format  = "qcow2"
  }

  network_device {
    bridge = var.pm_bridge
  }

  operating_system {
    type = "l26"
  }

  initialization {
    datastore_id = var.pm_datastore_id

    dns {
      domain  = var.network_dns_domain
      servers = [var.network_dns_server]
    }

    ip_config {
      ipv4 {
        address = "${each.value.ip}/24"
        gateway = var.network_gateway
      }
    }

    user_account {
      username = "ubuntu"
      keys     = [local.ssh_public_key]
    }
  }
}

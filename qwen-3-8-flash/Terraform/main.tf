# ---------------------------------------------------------------------------
# Debian 12 genericcloud image (qcow2) downloaded into the datastore.
# Its volume id (resource .id) is referenced by every VM disk via file_id.
# ---------------------------------------------------------------------------
resource "proxmox_virtual_environment_download_file" "debian12_genericcloud" {
  content_type        = "import"
  node_name           = var.node_name
  datastore_id        = var.datastore_id
  file_name           = var.image_file_name
  url                 = var.image_url
  overwrite_unmanaged = true
  upload_timeout      = 1200
}

# ---------------------------------------------------------------------------
# VM plan (address pool 192.168.1.230-.240). vm_id == last octet, deterministic.
# ---------------------------------------------------------------------------
locals {
  ssh_public_key = trimspace(file(var.ssh_public_key_path))

  vms = {
    kafka-1  = { ip = "192.168.1.230", description = "Kafka broker 1 (KRaft controller+broker) - event pipeline" }
    kafka-2  = { ip = "192.168.1.231", description = "Kafka broker 2 (KRaft controller+broker) - event pipeline" }
    kafka-3  = { ip = "192.168.1.232", description = "Kafka broker 3 (KRaft controller+broker) - event pipeline" }
    rabbit-1 = { ip = "192.168.1.233", description = "RabbitMQ node 1 (quorum queues) - event pipeline" }
    rabbit-2 = { ip = "192.168.1.234", description = "RabbitMQ node 2 (quorum queues) - event pipeline" }
    rabbit-3 = { ip = "192.168.1.235", description = "RabbitMQ node 3 (quorum queues) - event pipeline" }
    redis-1  = { ip = "192.168.1.237", description = "Redis Cluster node 1 (3 masters + 3 replicas across 2 hosts)" }
    redis-2  = { ip = "192.168.1.238", description = "Redis Cluster node 2 (3 masters + 3 replicas across 2 hosts)" }
    # .236, .239, .240 intentionally kept as pool reserve (see architectural decisions).
  }
}

# ---------------------------------------------------------------------------
# 8 identical VMs: 2 vCPU / 2048 MB / 40 GB / cloud-init / static IP / ops+SSH.
# ---------------------------------------------------------------------------
resource "proxmox_virtual_environment_vm" "pipeline_vm" {
  for_each = local.vms

  node_name   = var.node_name
  vm_id       = tonumber(split(".", each.value.ip)[3]) # deterministic: last octet (230..238)
  name        = each.key
  description = each.value.description
  tags        = ["pipeline", split("-", each.key)[0]]

  on_boot       = true
  started       = true
  protection    = false
  machine       = "q35"
  scsi_hardware = "virtio-scsi-single"

  cpu {
    cores   = var.vm_cpu_cores
    sockets = var.vm_cpu_sockets
    type    = "host"
  }

  memory {
    dedicated = var.vm_memory_mb
  }

  disk {
    interface    = "scsi0"
    datastore_id = var.datastore_id
    import_from  = proxmox_virtual_environment_download_file.debian12_genericcloud.id
    size         = var.vm_disk_gb
  }

  network_device {
    bridge = var.bridge
    model  = "virtio"
  }

  agent {
    enabled = true
    timeout = "10s"
  }

  initialization {
    datastore_id = var.datastore_id

    user_account {
      username = "ops"
      keys     = [local.ssh_public_key]
    }

    ip_config {
      ipv4 {
        address = "${each.value.ip}/${var.netmask_prefix}"
        gateway = var.gateway
      }
    }

    dns {
      domain  = var.search_domain
      servers = var.nameservers
    }
  }
}

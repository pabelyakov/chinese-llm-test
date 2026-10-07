# -----------------------------------------------------------------------------
# Provider
# -----------------------------------------------------------------------------
provider "proxmox" {
  endpoint  = var.pm_api_url
  insecure  = true # self-signed сертификат PVE
  api_token = "${var.pm_api_token_id}=${var.pm_token_secret}"
}

# -----------------------------------------------------------------------------
# IP-план стенда (пул 192.168.1.230-.240/24, .236/.239/.240 — резерв)
# -----------------------------------------------------------------------------
locals {
  ssh_public_key = trimspace(file(var.ssh_public_key_file))

  sudoers_rule = "deploy ALL=(ALL) NOPASSWD:ALL\n"

  vms = {
    kafka-01 = { ip = "192.168.1.230", vmid = 110, group = "kafka" }
    kafka-02 = { ip = "192.168.1.231", vmid = 111, group = "kafka" }
    kafka-03 = { ip = "192.168.1.232", vmid = 112, group = "kafka" }

    rabbitmq-01 = { ip = "192.168.1.233", vmid = 113, group = "rabbitmq" }
    rabbitmq-02 = { ip = "192.168.1.234", vmid = 114, group = "rabbitmq" }
    rabbitmq-03 = { ip = "192.168.1.235", vmid = 115, group = "rabbitmq" }

    redis-01 = { ip = "192.168.1.237", vmid = 116, group = "redis" }
    redis-02 = { ip = "192.168.1.238", vmid = 117, group = "redis" }
  }
}

# -----------------------------------------------------------------------------
# Cloud-образ Debian 12.
#
# Выбор способа (по докам bpg/proxmox 0.116): proxmox_virtual_environment_download_file —
# Proxmox сам скачивает образ по URL через API download-url (content=import), клиенту не
# нужно качать/загружать 350MB. overwrite_unmanaged=true: если файл уже есть в хранилище
# (по разведке — есть), ресурс берёт его под управление Terraform.
# -----------------------------------------------------------------------------
resource "proxmox_download_file" "debian12_qcow2" {
  content_type        = "import"
  datastore_id        = var.storage_id
  node_name           = var.target_node
  url                 = var.image_url
  file_name           = "debian-12-genericcloud-amd64.qcow2"
  overwrite           = false
  overwrite_unmanaged = true
}

# -----------------------------------------------------------------------------
# Виртуальные машины
# -----------------------------------------------------------------------------
resource "proxmox_virtual_environment_vm" "node" {
  for_each = local.vms

  name      = each.key
  node_name = var.target_node
  vm_id     = each.value.vmid

  tags            = ["edlab", each.value.group]
  on_boot         = true
  started         = true
  stop_on_destroy = true

  cpu {
    cores = 2
    type  = "host"
  }

  memory {
    dedicated = 2048
  }

  agent {
    enabled = true
  }

  operating_system {
    type = "l26"
  }

  scsi_hardware = "virtio-scsi-single"

  disk {
    datastore_id = var.storage_id
    # import_from: импорт диска из образа через API Proxmox (без SSH к узлу).
    # По докам bpg/proxmox 0.116: "Prefer import_from for uncompressed images".
    import_from = proxmox_download_file.debian12_qcow2.id
    interface   = "scsi0"
    size        = 40
    file_format = "qcow2"
  }

  network_device {
    bridge = var.bridge
  }

  initialization {
    datastore_id = var.storage_id

    dns {
      domain  = "edlab.local"
      servers = [var.dns]
    }

    ip_config {
      ipv4 {
        address = "${each.value.ip}/24"
        gateway = var.gateway
      }
    }

    # ВАЖНО (см. README, раздел "Passwordless sudo"):
    # кастомный user-data (snippet) невозможен: PVE 9.2 API не загружает content=snippets,
    # а провайдер кладёт snippets только по SSH к узлу, которого нет (доступ только API-токен).
    # Поэтому: пользователь/ключ/пароль — через user_account (штатный cloud-init),
    # а passwordless sudo — идемпотентным bootstrap-скриптом ниже (terraform_data).
    user_account {
      username = "deploy"
      keys     = [local.ssh_public_key]
      password = var.deploy_password
    }
  }
}

# -----------------------------------------------------------------------------
# Passwordless sudo для deploy.
#
# Известное ограничение среды (подробности в README): user-data snippets недоступны,
# потому sudoers настраивается после поднятия VM: ожидаем SSH (cloud-init создаёт
# пользователя deploy), затем через sudo -S (cloud-init пароль из cipassword) кладём
# /etc/sudoers.d/deploy. Триггеры: содержимое правила + id VM (пересоздание VM
# перезапускает bootstrap). Повторный apply без изменений — provisioner не выполняется.
# -----------------------------------------------------------------------------
resource "terraform_data" "sudo_bootstrap" {
  for_each = local.vms

  triggers_replace = [
    local.sudoers_rule,
    proxmox_virtual_environment_vm.node[each.key].id,
  ]

  provisioner "local-exec" {
    interpreter = ["/bin/bash", "-c"]
    environment = {
      SSH_KEY         = pathexpand(var.ssh_private_key_file)
      VM_IP           = each.value.ip
      DEPLOY_PASSWORD = var.deploy_password
      SUDOERS_RULE    = local.sudoers_rule
    }
    command = <<-EOT
      set -euo pipefail
      key="$SSH_KEY"; ip="$VM_IP"
      ssh_opts=(-o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o ConnectTimeout=10 -o BatchMode=yes -i "$key")
      echo "[sudo_bootstrap:$ip] ожидание SSH (до ~10 минут)..."
      ok=0
      for i in $(seq 1 60); do
        if ssh "$${ssh_opts[@]}" deploy@"$ip" true 2>/dev/null; then ok=1; break; fi
        sleep 10
      done
      if [ "$ok" != 1 ]; then echo "[sudo_bootstrap:$ip] ERROR: SSH так и не появился" >&2; exit 1; fi
      echo "[sudo_bootstrap:$ip] SSH доступен, устанавливаю sudoers"
      printf '%s\n' "$DEPLOY_PASSWORD" | ssh "$${ssh_opts[@]}" deploy@"$ip" \
        "sudo -S -p '' -- sh -c 'printf \"%s\" \"\$0\" > /etc/sudoers.d/deploy && chmod 0440 /etc/sudoers.d/deploy'" "$SUDOERS_RULE"
      echo "[sudo_bootstrap:$ip] проверка passwordless sudo"
      ssh "$${ssh_opts[@]}" deploy@"$ip" "sudo -n true && echo '[sudo_bootstrap:$ip] OK: sudo NOPASSWD работает' || { echo '[sudo_bootstrap:$ip] FAIL' >&2; exit 1; }"
    EOT
  }

  depends_on = [proxmox_virtual_environment_vm.node]
}

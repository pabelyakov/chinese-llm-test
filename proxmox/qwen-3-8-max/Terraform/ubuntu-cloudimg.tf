# ubuntu-cloudimg.tf — базовый образ и template VM Ubuntu 24.04 LTS.
#
# ПОДХОД (задокументирован в Terraform/README.md §«Шаблон»):
#  1. Terraform/ensure-image.sh (идемпотентный скрипт, вызывается ДО apply)
#     гарантирует наличие qcow2-образа Ubuntu 24.04 в storage "storage"
#     (content: import): PVE-узел сам скачивает его с cloud-images.ubuntu.com
#     через API download-url с проверкой SHA256 на стороне PVE.
#     Скрипт — НЕ terraform-ресурс, потому что PVE import-from по умолчанию
#     УДАЛЯЕТ исходный файл после импорта (delete-imported-volumes=1):
#     download_file-ресурс «исчезал» бы из хранилища и каждый plan предлагал
#     бы его заново скачать (нарушение идемпотентности).
#  2. proxmox_virtual_environment_vm (vmid 9000, template=true) — шаблон:
#     диск scsi0 импортируется из образа через API (disk.import_from →
#     PVE import-from, БЕЗ SSH) и расширяется провайдером до 40 GiB,
#     cloud-init (user ubuntu + SSH-ключ), qemu-guest-agent=1.
#     Шаблон остановлен и не стартует никогда.
#  3. Рабочие VM (main.tf) — full clone шаблона с индивидуальным cloud-init
#     (статический IP из IPAM, hostname, DNS, SSH-ключ).
#
# ОГРАНИЧЕНИЕ СТЕНДА (зафиксировано Agent 2):
#  - bpg/proxmox 0.116 НЕ поддерживает initialization.timezone;
#  - загрузка snippets (vendor-data c timezone/chrony) невозможна: провайдер
#    грузит их по SSH на PVE-узел (доступа нет, только API-токен), а PVE 9.2
#    (новый Rust pve-api-daemon/3.0) отвечает HTTP 400 на POST /storage/.../upload
#    (проверено multipart и base64).
#  Поэтому timezone Europe/Moscow выставляется однократно по SSH после деплоя
#  (см. README §«Пост-деплой») и поддерживается ролью common (Agent 3);
#  chrony устанавливает Agent 3 (явно разрешено оркестратором).

resource "proxmox_virtual_environment_vm" "ubuntu_template" {
  node_name   = var.pve_node
  vm_id       = var.template_vmid
  name        = "ubuntu-2404-cloud-template"
  description = "Base image: Ubuntu 24.04 LTS cloud image + cloud-init (Terraform-managed, do not start/delete manually)"
  tags        = ["managed-by-terraform", "template", "ubuntu-2404"]

  template = true

  agent {
    enabled = true
    wait_for_ip {
      disabled = true # шаблон остановлен — IP ждать не нужно
    }
  }

  cpu {
    cores = var.vm_cores
    type  = "host"
  }

  memory {
    dedicated = var.vm_memory_mb
    floating  = 0 # ballooning выключен — RAM фиксирован (D8)
  }

  disk {
    datastore_id = var.pve_storage
    # образ должен лежать в <storage>:import/ — см. Terraform/ensure-image.sh
    import_from = "${var.pve_storage}:import/${var.cloud_image_file_name}"
    interface   = "scsi0"
    size        = var.vm_disk_gb # провайдер расширяет диск после импорта
  }

  network_device {
    bridge = var.pve_bridge
    model  = "virtio"
  }

  initialization {
    datastore_id = var.pve_storage
    upgrade      = false # без apt-upgrade при первом старте (детерминированность, нет конкуренции за dpkg-lock с Ansible)

    user_account {
      username = var.vm_user
      keys     = [trimspace(file(var.ssh_public_key_path))]
    }

    dns {
      servers = var.dns_servers
    }

    ip_config {
      ipv4 {
        address = "dhcp" # у шаблона IP не нужен; клоны переопределяют статикой
      }
    }
  }

  serial_device {}

  operating_system {
    type = "l26"
  }

  boot_order = ["scsi0", "net0"]

  on_boot = false
  started = false # шаблон всегда выключен
}

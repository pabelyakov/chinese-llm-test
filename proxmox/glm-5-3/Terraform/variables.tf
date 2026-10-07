# -----------------------------------------------------------------------------
# Переменные стенда. Реальные значения — в terraform.tfvars (в git не попадает,
# см. .gitignore). Пример заполнения — terraform.tfvars.example.
# -----------------------------------------------------------------------------

variable "pm_api_url" {
  description = "URL API Proxmox VE (с портом 8006)."
  type        = string
  default     = "https://192.168.1.253:8006/"

  validation {
    condition     = can(regex("^https?://.+:8006/$", var.pm_api_url))
    error_message = "pm_api_url должен быть вида https://<host>:8006/ (со слэшем в конце)."
  }
}

variable "pm_api_token_id" {
  description = "ID API-токена Proxmox в формате user@realm!tokenname."
  type        = string
  default     = "root@pam!terraform"

  validation {
    condition     = can(regex("^.+@.+!.+$", var.pm_api_token_id))
    error_message = "pm_api_token_id должен быть вида root@pam!terraform."
  }
}

variable "pm_token_secret" {
  description = "Секрет API-токена Proxmox (UUID). Sensitive, в git не коммитить."
  type        = string
  sensitive   = true

  validation {
    condition     = length(var.pm_token_secret) >= 32
    error_message = "pm_token_secret должен быть UUID-секретом токена (>= 32 символов)."
  }
}

variable "target_node" {
  description = "Имя узла Proxmox, на котором создаются VM (из разведки: /nodes)."
  type        = string
  default     = "prox-01"

  validation {
    condition     = length(var.target_node) > 0
    error_message = "target_node не может быть пустым."
  }
}

variable "storage_id" {
  description = "Хранилище для дисков VM и snippets (из разведки: /nodes/<node>/storage)."
  type        = string
  default     = "storage"

  validation {
    condition     = length(var.storage_id) > 0
    error_message = "storage_id не может быть пустым."
  }
}

variable "bridge" {
  description = "Сетевой мост Proxmox для VM (из разведки: /nodes/<node>/network)."
  type        = string
  default     = "vmbr0"

  validation {
    condition     = can(regex("^vmbr\\d+$", var.bridge))
    error_message = "bridge должен быть вида vmbr0, vmbr1, ..."
  }
}

variable "gateway" {
  description = "Шлюз подсети VM (из разведки сети vmbr0)."
  type        = string
  default     = "192.168.1.1"

  validation {
    condition     = can(cidrhost("${var.gateway}/32", 0))
    error_message = "gateway должен быть корректным IPv4-адресом."
  }
}

variable "dns" {
  description = "DNS-сервер для VM (в данной сети роль шлюза и DNS совпадает)."
  type        = string
  default     = "192.168.1.1"

  validation {
    condition     = can(cidrhost("${var.dns}/32", 0))
    error_message = "dns должен быть корректным IPv4-адресом."
  }
}

variable "image_url" {
  description = "URL cloud-образа Debian 12 (genericcloud, qcow2)."
  type        = string
  default     = "https://cloud.debian.org/images/cloud/bookworm/latest/debian-12-genericcloud-amd64.qcow2"

  validation {
    condition     = can(regex("^https://.+\\.qcow2$", var.image_url))
    error_message = "image_url должен быть https-ссылкой на .qcow2 образ."
  }
}

variable "ssh_public_key_file" {
  description = "Путь к публичному SSH-ключу, закладываемому в VM (пользователь deploy)."
  type        = string
  default     = "../Security/ed25519.pub"
}

variable "ssh_private_key_file" {
  description = "Путь к приватному SSH-ключу для bootstrap-доступа к VM (только чтение)."
  type        = string
  default     = "../Security/ed25519"
}

variable "deploy_password" {
  description = "Пароль пользователя deploy (cloud-init cipassword). Используется однократно для bootstrap passwordless sudo. Sensitive."
  type        = string
  sensitive   = true

  validation {
    condition     = length(var.deploy_password) >= 8
    error_message = "deploy_password должен быть не короче 8 символов."
  }
}

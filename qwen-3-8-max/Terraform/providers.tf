# providers.tf — подключение к Proxmox VE.
#
# ВСЕ параметры подключения провайдер bpg/proxmox читает из окружения (D7):
#   PM_API_URL         — https://192.168.1.253:8006/
#   PM_API_TOKEN_ID    — root@pam!terraform
#   PM_API_TOKEN_SECRET— <секрет, только env, никогда не в файлах/git>
#   PM_TLS_INSECURE    — true (самоподписанный сертификат API)
#
# Источник значений: Security/proxmox.env (gitignored).
# Шаблон:            Security/proxmox.env.example
# Экспорт в сессию:  set -a; source Security/proxmox.env; set +a
#
# Поэтому блок провайдера намеренно пуст: ни endpoint, ни токен в HCL не
# хардкодятся и не коммитятся.
provider "proxmox" {}

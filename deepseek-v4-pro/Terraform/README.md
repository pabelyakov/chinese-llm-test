# Terraform — развёртывание VM в ProxMox

Развёртывает 8 виртуальных машин Ubuntu Server 22.04 LTS (cloud-image) в
ProxMox с cloud-init, статическими IP и SSH-доступом по ключу.

## Структура

```
Terraform/
├── versions.tf             # ограничения Terraform и провайдера
├── provider.tf             # конфигурация провайдера bpg/proxmox
├── variables.tf            # переменные (параметризация)
├── main.tf                 # cloud-image + 8 VM через for_each
├── outputs.tf              # выводы (имя -> IP / VMID / группа)
├── terraform.tfvars        # секреты (в .gitignore, НЕ коммитить)
├── terraform.tfvars.example# пример переменных
├── .gitignore
└── cloud-init/
    ├── user-data.yaml.tftpl      # эталонный cloud-config (справочно)
    └── network-config.yaml.tftpl # эталонный network-config v2 (справочно)
```

## Провайдер и версии

- Провайдер: `bpg/proxmox` (`~> 0.116.0`) — современный, поддерживает
  API-токены и импорт дисков через `import_from` (без SSH к ноде PVE).
- Аутентификация: API-токен `root@pam!terraform=...` (см. `terraform.tfvars`).

## Распределение VM (строго)

| VM | IP | VMID | Группа Ansible |
|----|----|------|----------------|
| kafka-1 | 192.168.1.230 | 201 | kafka |
| kafka-2 | 192.168.1.231 | 202 | kafka |
| kafka-3 | 192.168.1.232 | 203 | kafka |
| rabbit-1 | 192.168.1.233 | 211 | rabbitmq |
| rabbit-2 | 192.168.1.234 | 212 | rabbitmq |
| rabbit-3 | 192.168.1.235 | 213 | rabbitmq |
| redis-1 | 192.168.1.237 | 221 | redis |
| redis-2 | 192.168.1.238 | 222 | redis |

Свободны (не используются): 192.168.1.236, 192.168.1.239, 192.168.1.240.

Ресурсы каждой VM: 2 vCPU (`x86-64-v2-AES`), 2048 MB RAM, диск 40 GB (qcow2).

## Переменные

| Переменная | Описание | Значение по умолчанию |
|------------|----------|------------------------|
| `pm_api_url` | URL ProxMox API | — |
| `pm_api_token_id` | ID токена | — |
| `pm_api_token_secret` | Секрет токена | — |
| `pm_node_name` | Имя ноды | `prox-01` |
| `pm_datastore_id` | Хранилище | `storage` |
| `pm_bridge` | Сетевой мост | `vmbr0` |
| `ssh_public_key` | Публ. ключ (по умолч. читается из `../Security/id_ed25519.pub`) | `""` |
| `cloud_image_url` | URL cloud-image Ubuntu 22.04 | cloud-images.ubuntu.com |
| `network_gateway` | Шлюз | `192.168.1.1` |
| `network_dns_server` | DNS | `192.168.1.1` |
| `network_dns_domain` | DNS search domain | `t3adog.ru` |
| `vm_cpu_cores` | vCPU | `2` |
| `vm_memory_mb` | RAM, MB | `2048` |
| `vm_disk_size_gb` | Диск, GB | `40` |

> Gateway/DNS диагностированы из сети ProxMox: `vmbr0` = 192.168.1.253/24,
> gateway 192.168.1.1; DNS ноды = 192.168.1.1, search domain `t3adog.ru`.

## Как запустить

```bash
cd Terraform
cp terraform.tfvars.example terraform.tfvars   # подставить реальные секреты
terraform init
terraform validate
terraform plan
terraform apply -auto-approve
```

> Примечание: при раскатке на медленном/нагруженном хранилище параллельное
> создание 8 VM может приводить к таймаутам `qemu-img resize` и блокировкам
> хранилища (`can't lock file pve-storage-storage`). В таких случаях
> используйте `terraform apply -parallelism=2`.

## Как уничтожить

```bash
terraform destroy -auto-approve
```

## Как добавить VM

Добавьте запись в `locals.vms` (в `main.tf`), указав уникальный `vmid` и
свободный IP из пула, например:

```hcl
redis-3 = { ip = "192.168.1.236", vmid = 223, group = "redis" }
```

затем `terraform apply`. Инвентарь Ansible обновляется вручную
(`Ansible/inventory`) или следующим агентом.

## Cloud-init

Развёртывание использует нативный блок `initialization` провайдера
(`ip_config` + `dns` + `user_account`), который генерирует cloud-init диск
без SSH к ноде PVE:

- пользователь `ubuntu`, авторизация только по SSH-ключу (пароль отключён);
- hostname = имени VM (ProxMox подставляет имя VM в meta-data cloud-init);
- статические IP + gateway + DNS.

Файлы `cloud-init/*.tftpl` — эталонные шаблоны (cloud-config / network-config
v2) для альтернативного подхода через snippets
(`proxmox_virtual_environment_file`), который дополнительно требует SSH-доступ
к ноде PVE и поэтому здесь не используется.

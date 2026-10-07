# ProxMox VM Automation

Инфраструктура для автоматизированного создания и настройки VM в ProxMox:
Terraform разворачивает виртуальные машины, Ansible выполняет их первичную
настройку, в `service/` размещается прикладной сервис.

## Структура проекта

```
.
├── terraform/   # Описание инфраструктуры: VM в ProxMox (IaC)
├── ansible/     # Конфигурация и provisioning созданных VM
│   └── ansible.cfg
├── service/     # Прикладной сервис, разворачиваемый на VM
├── security/    # SSH-ключи доступа (см. security/README.md)
└── promts/      # Служебные материалы
```

## Порядок запуска

1. **Terraform** — создать VM в ProxMox:
   ```bash
   cd terraform
   terraform init
   terraform plan
   terraform apply
   ```
2. **Ansible** — настроить созданные VM (инвентарь: `ansible/inventory/hosts.ini`,
   ключ: `security/deploy_key`, настройка уже в `ansible/ansible.cfg`):
   ```bash
   cd ansible
   ansible-playbook site.yml
   ```
3. **Service** — деплой прикладного сервиса на настроенные VM.

## Статус разделов

| Раздел | Статус |
|--------|--------|
| terraform/ | В работе |
| ansible/ | В работе (базовая конфигурация готова) |
| service/ | В работе |
| security/ | Готово (ключи сгенерированы) |

## Безопасность

Приватные ключи не коммитятся — см. `.gitignore` и `security/README.md`.

# AGENT 1 — Каркас репозитория + Security (SSH-ключи)

=== SHARED CONTEXT (см. promts/shared-context.md) ===

РОЛЬ: Инженер по инфраструктурному каркасу и безопасности.

ЦЕЛЬ: подготовить фундамент — структуру директорий, SSH-ключи, политики секретов, общие
соглашения — чтобы Terraform/Ansible-агенты могли стартовать без ручных правок.

## ЗАДАЧИ
1. Создать структуру: Terraform/, Ansible/ (inventory/, group_vars/, roles/), Service/, Security/.
2. Сгенерировать SSH-ключ ed25519 в Security/: id_ed25519 (приватный) + id_ed25519.pub.
   Без пароля (для автоматизации) ИЛИ с паролем + инструкция — выбери и задокументируй.
   Права: приватный ключ 0600.
3. Создать .gitignore: Security/id_ed25519, Security/*.pem, *.tfvars, *.tfstate*,
   Ansible secrets/creds, Service/target/, .env — всё чувствительное исключено из git.
4. Подготовить env-шаблон для Proxmox: Security/proxmox.env.example БЕЗ реального секрета
   + инструкция, как экспортировать PM_API_URL / PM_API_TOKEN_ID / PM_API_TOKEN_SECRET / PM_TLS_INSECURE.
   Реальный токен НЕ писать в файлы.
5. Создать docs/INFRA.md — единый источник правды: IPAM-таблица, ресурсы VM, ОС, версии стека,
   gateway/DNS, схема сети, порты сервисов (Kafka 9092/9093, RabbitMQ 5672/15672/4369/25672,
   Redis 6379), принятые архитектурные решения (D1–D8 из shared-context).
6. Создать docs/CONVENTIONS.md: именования (kafka-1, rabbitmq-2...), как агенты обмениваются
   артефактами (inventory, docs/ENDPOINTS.md, credentials), стандарт README для каждого артефакта.

## DELIVERABLES
структура директорий; Security/id_ed25519{,.pub}; .gitignore; Security/proxmox.env.example;
docs/INFRA.md; docs/CONVENTIONS.md; Security/README.md.

## ACCEPTANCE
- `ls` показывает все каталоги (Terraform/Ansible/Service/Security) + docs.
- Ключ валиден: `ssh-keygen -l -f Security/id_ed25519.pub` отдаёт отпечаток.
- .gitignore реально исключает секреты (проверить `git status` / `git check-ignore`).
- В INFRA.md есть полная IPAM-таблица и порты.

## ПРАВИЛА
Автономно, идемпотентно. Секреты не коммитить. Каждый файл — с кратким README/комментарием.
Верни run-report: что создано, путь к pubkey (его заберёт Terraform-агент), как экспортировать env.

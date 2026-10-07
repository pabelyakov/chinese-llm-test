# PROGRESS — журнал оркестратора

Проект: Event-driven pipeline на Proxmox VE. Секреты — см. CTX.md (в этом журнале не пересказывать).

## Стадии

- [x] 1. 01-SECURITY.md — SSH-ключи и Security/ — PASS: ключ ed25519 создан (600/644), priv↔pub идентичны, .gitignore/README ок
- [ ] 2. 02-TERRAFORM.md — раскатка 8 VM в Proxmox
- [ ] 3. 03-ANSIBLE-COMMON.md — каркас Ansible + роль common
- [ ] 4. 04-KAFKA.md — Kafka KRaft .230–.232 (промт составлен оркестратором, утв. владельцем)
- [ ] 5. 05-RABBITMQ.md — RabbitMQ cluster .233–.235 (промт составлен оркестратором, утв. владельцем)
- [ ] 6. 06-REDIS.md — Redis cluster .237–.238 (промт составлен оркестратором, утв. владельцем)
- [ ] 7. 07-SERVICE.md — Spring Boot монолит + E2E (промт составлен оркестратором, утв. владельцем)
- [ ] 8. 08-ACCEPTANCE.md — сквозная приёмка и аудит документации

## Журнал

- Инициализация: CTX.md, 01, 02, 03, 08 прочитаны. 04–07 отсутствовали → составлены оркестратором
  из CTX + приёмочных критериев 00/08 (контракты: topic pipeline.raw, queue pipeline.enriched quorum,
  Redis 6 инстансов 3M+3R на портах 6371–6373, API POST /start со stages/finalMarker). Согласовано с владельцем.
- Стадия 1: агент → DONE; арбитраж: права 600/644, `ssh-keygen -y` == .pub, .gitignore корректен,
  README содержательный (70 строк) → PASS. Примечание агента: корень пока не git-репозиторий.
- Стадия 2: агент запущен — в процессе.

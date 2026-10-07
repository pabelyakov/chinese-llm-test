# kimi — автономный pipeline: Proxmox → Kafka/RabbitMQ/Redis → Spring Boot

Лабораторный стенд: 8 VM в Proxmox VE (Kafka KRaft ×3, RabbitMQ ×3, Redis
primary+replica) и Java-монолит на Spring Boot, реализующий сквозной pipeline
с `correlationId`.

## Навигация (читать по порядку)
1. `docs/INFRA.md` — **SSOT**: IPAM, ресурсы VM, ОС, версии стека, сеть, порты, тюнинг, решения D1–D8.
2. `docs/CONVENTIONS.md` — именования, контракт обмена артефактами, стандарт README, DoD.
3. `docs/ENDPOINTS.md` — единый файл реальных endpoints и имён env-секретов.
4. `Security/README.md` — SSH-ключи, политика секретов P1–P5, экспорт env Proxmox.
5. `docs/JOURNAL.md` — журнал работ агентов (append-only).
6. `Terraform/README.md`, `Ansible/README.md`, `Service/README.md` — инструкции по каталогам.

## Структура
```
Terraform/   # Agent 2 — VM в Proxmox (bpg/proxmox), cloud-init, outputs → inventory
Ansible/     # Agent 3/4 — inventory/, group_vars/, roles/, secrets/ (gitignored)
Service/     # Agent 5/6 — Java 21 + Spring Boot 3.3.x монолит (Maven)
Security/    # Agent 1 — SSH-ключи (приватный gitignored), proxmox.env.example
docs/        # Agent 1 — INFRA.md, CONVENTIONS.md, ENDPOINTS.md, JOURNAL.md
promts/      # промты агентов (входные данные)
```

## Быстрый старт
```bash
# 1) секреты Proxmox (реальный токен — вручную, файл gitignored)
cp -n Security/proxmox.env.example Security/proxmox.env   # вписать PM_API_TOKEN_SECRET
set -a; source Security/proxmox.env; set +a

# 2) раскатка VM
cd Terraform && terraform init && terraform plan && terraform apply && cd ..

# 3) конфигурация сервисов
cd Ansible && ansible-playbook -i inventory/hosts.yml site.yml -e @secrets/vault.yml && cd ..

# 4) приложение
cd Service && mvn -q -DskipTests package && java -jar target/*.jar && cd ..
curl -s -X POST localhost:8080/start -H 'Content-Type: application/json' -d '{"message":"ping"}'
```

## Безопасность
- Токен Proxmox и пароли сервисов — **только** env / gitignored-файлы / ansible-vault (D7).
- SSH-ключ: ed25519 без passphrase (лабораторный, обоснование — `Security/README.md`).
- Kafka в лабе — `PLAINTEXT` без аутентификации; стенд не выносить за пределы `192.168.1.0/24`.
- Перед коммитом: `git status --short` и `git check-ignore -v <файл>` — секреты не должны попасть в индекс.

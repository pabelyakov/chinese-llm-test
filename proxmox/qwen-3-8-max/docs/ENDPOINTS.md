# docs/ENDPOINTS.md — ЕДИНЫЙ файл реальных endpoints и имён секретов

> Каркас создан Agent 1. Заполняют Agent 2 (IP/VM), Agent 3/4 (сервисы),
> Agent 5/6 (приложение). Формат — см. `docs/CONVENTIONS.md` §4.
> **Значения паролей сюда НЕ писать** — только имя env-переменной и источник.

## 0. Статус заполнения

| Раздел | Ответственный | Статус |
|---|---|---|
| 1. VM / IP | Agent 2 (Terraform) | ожидается (значения из `docs/INFRA.md` §3 — план) |
| 2. Proxmox API | Agent 2 | ожидается: node / storage / bridge / gateway / DNS (D6) |
| 3. Kafka | Agent 4 | ожидается |
| 4. RabbitMQ | Agent 4 | ожидается |
| 5. Redis | Agent 4 | ожидается |
| 6. Java-приложение | Agent 5/6 | ожидается |
| 7. Реестр секретов (env-имена) | Agent 3/4 | каркас ниже |

## 1. VM и IP (план из IPAM — подтвердить после `terraform apply`)

| Hostname | IP | SSH | Пользователь |
|---|---|---|---|
| kafka-1 | 192.168.1.230 | 22 | `ubuntu` |
| kafka-2 | 192.168.1.231 | 22 | `ubuntu` |
| kafka-3 | 192.168.1.232 | 22 | `ubuntu` |
| rabbitmq-1 | 192.168.1.233 | 22 | `ubuntu` |
| rabbitmq-2 | 192.168.1.234 | 22 | `ubuntu` |
| rabbitmq-3 | 192.168.1.235 | 22 | `ubuntu` |
| redis-1 | 192.168.1.237 | 22 | `ubuntu` |
| redis-2 | 192.168.1.238 | 22 | `ubuntu` |

SSH-ключ для всех: `Security/id_ed25519` (публичная часть — `Security/id_ed25519.pub`).

## 2. Proxmox API (заполняет Agent 2 после проверки через API — D6)

| Параметр | Значение | Подтверждено |
|---|---|---|
| API URL | `https://192.168.1.253:8006/` | — |
| Token ID | `root@pam!terraform` (env `PM_API_TOKEN_ID`) | — |
| Token secret | env `PM_API_TOKEN_SECRET` (**значение не документировать**) | — |
| Node | TBD | [ ] |
| Storage | TBD | [ ] |
| Bridge | TBD (`vmbr0`?) | [ ] |
| Cloud-image datastore/file | TBD | [ ] |
| Gateway | `192.168.1.1` (TBD подтвердить) | [ ] |
| DNS | `192.168.1.1`, `8.8.8.8` (TBD подтвердить) | [ ] |

## 3. Kafka (KRaft, PLAINTEXT)

| Параметр | Значение | Проверено |
|---|---|---|
| bootstrap.servers | `192.168.1.230:9092,192.168.1.231:9092,192.168.1.232:9092` | [ ] |
| controller.quorum.voters | `1@192.168.1.230:9093,2@192.168.1.231:9093,3@192.168.1.232:9093` | [ ] |
| security.protocol | `PLAINTEXT` (лаба, без auth — P4) | [ ] |
| topic (вход pipeline) | `pipeline-requests` | [ ] |
| partitions / RF / min.ISR | `3 / 3 / 2` | [ ] |
| heap | `-Xmx1024m -Xms512m` | [ ] |

Команда проверки: `kafka-topics.sh --bootstrap-server 192.168.1.230:9092 --describe --topic pipeline-requests`

## 4. RabbitMQ

| Параметр | Значение | Проверено |
|---|---|---|
| hosts | `192.168.1.233`, `192.168.1.234`, `192.168.1.235` | [ ] |
| port (AMQP) | `5672` | [ ] |
| management | `http://192.168.1.233:15672` | [ ] |
| vhost | `/` | [ ] |
| user | `admin` (пароль — env `RABBITMQ_PASSWORD`) | [ ] |
| exchange | `pipeline` (direct) | [ ] |
| queue | `pipeline.enrich2` (durable) | [ ] |
| routing key | `enrich2` | [ ] |
| header | `correlationId` (сквозной UUID) | [ ] |
| memory watermark | `0.4` relative | [ ] |

Команда проверки: `rabbitmq-diagnostics -q status && rabbitmqctl list_queues name messages`

## 5. Redis

| Параметр | Значение | Проверено |
|---|---|---|
| primary | `192.168.1.237:6379` | [ ] |
| replica | `192.168.1.238:6379` (`replicaof 192.168.1.237 6379`) | [ ] |
| password | env `REDIS_PASSWORD` (если включён `requirepass`) | [ ] |
| key pattern | `pipeline:result:{correlationId}` | [ ] |
| TTL ключа | `300` сек (предложение, фиксирует Agent 5) | [ ] |
| maxmemory | `512mb` primary / `256mb` replica, `noeviction` | [ ] |

Команда проверки: `redis-cli -h 192.168.1.237 ping && redis-cli -h 192.168.1.238 info replication`

## 6. Java-приложение (Spring Boot монолит)

| Параметр | Значение | Проверено |
|---|---|---|
| HTTP | `http://<app-host>:8080` | [ ] |
| POST | `/start` `{"message":"..."}` → `{correlationId, result}` | [ ] |
| Health | `GET /actuator/health` | [ ] |
| Запуск | `java -jar Service/target/*.jar` (env-переменные ниже) | [ ] |

## 7. Реестр секретов — только ИМЕНА переменных окружения

| Env-переменная | Назначение | Источник значения (gitignored) |
|---|---|---|
| `PM_API_URL` | Proxmox API URL | `Security/proxmox.env` |
| `PM_API_TOKEN_ID` | Proxmox token id | `Security/proxmox.env` |
| `PM_API_TOKEN_SECRET` | Proxmox token secret | `Security/proxmox.env` |
| `PM_TLS_INSECURE` | insecure TLS Proxmox | `Security/proxmox.env` |
| `ANSIBLE_VAULT_PASSWORD_FILE` | пароль vault | `~/.ansible-vault-pass-kimi` (chmod 600) |
| `RABBITMQ_USER` / `RABBITMQ_PASSWORD` | доступ к RabbitMQ | `Ansible/secrets/vault.yml` |
| `REDIS_PASSWORD` | `requirepass` Redis (если включён) | `Ansible/secrets/vault.yml` |
| `KAFKA_BOOTSTRAP_SERVERS` | адреса брокеров | не секрет, `docs/ENDPOINTS.md` §3 |
| `SPRING_PROFILES_ACTIVE` | профиль приложения | не секрет |

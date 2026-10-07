# docs/INFRA.md — единый источник правды по инфраструктуре (SSOT)

> Владелец документа: Agent 1 (инфраструктурный каркас и безопасность).
> Изменения в IPAM/ресурсы/порты вносятся ТОЛЬКО сюда, остальные агенты читают отсюда.
> Дата последнего обновления: 2026-10-07 (Agent 1, scaffolding).

## 1. Платформа виртуализации

| Параметр | Значение |
|---|---|
| Proxmox VE API URL | `https://192.168.1.253:8006/` |
| TLS | самоподписанный сертификат → `PM_TLS_INSECURE=true` |
| API-токен | `root@pam!terraform` (секрет **только** через env, в git не попадает) |
| Terraform provider | `bpg/proxmox` (последняя стабильная 2.x) |
| Terraform | v1.16.0 (на машине оператора) |
| Ansible | ansible-core 2.21.3, подключение по SSH из той же машины |
| SSH-ключ доступа | `Security/id_ed25519{,.pub}` (ed25519, без passphrase — см. `Security/README.md`) |
| Cloud-init user | `ubuntu` (дополнительно может создаваться `deploy`; см. `docs/CONVENTIONS.md`) |

Точка монтирования cloud-image и имена node/storage/bridge **обязан подтвердить
Agent 2 (Terraform)** через Proxmox API до `terraform apply` — решение **D6**.

## 2. Операционная система и ресурсы VM (жёсткий лимит — D8)

| Параметр | Значение |
|---|---|
| ОС | Ubuntu 24.04 LTS (Noble), cloud image, amd64 (`ubuntu-24.04-server-cloudimg-amd64.img`) |
| vCPU | **2** (тип `host`, если доступен) |
| RAM | **2048 MB** (без ballooning-перегруза; фиксировано) |
| Диск | **40 GB** (thin, на подтверждённом storage) |
| Network | 1 × virtio в bridge, статический IP /24 |
| Дополнительно | chrony (NTP) на всех VM, `/etc/hosts` со всеми узлами, swap-файл не требуется |

## 3. IPAM-таблица (подсеть 192.168.1.0/24)

| # | Hostname | IP | Роль | Кластер / node.id |
|---|---|---|---|---|
| 1 | `kafka-1` | `192.168.1.230` | Kafka KRaft, controller + broker | cluster `kafka`, `node.id=1` |
| 2 | `kafka-2` | `192.168.1.231` | Kafka KRaft, controller + broker | cluster `kafka`, `node.id=2` |
| 3 | `kafka-3` | `192.168.1.232` | Kafka KRaft, controller + broker | cluster `kafka`, `node.id=3` |
| — | *(резерв)* | `192.168.1.236` | НЕ ИСПОЛЬЗОВАТЬ (D1) | — |
| 4 | `rabbitmq-1` | `192.168.1.233` | RabbitMQ | cluster `rabbitmq` |
| 5 | `rabbitmq-2` | `192.168.1.234` | RabbitMQ | cluster `rabbitmq` |
| 6 | `rabbitmq-3` | `192.168.1.235` | RabbitMQ | cluster `rabbitmq` |
| 7 | `redis-1` | `192.168.1.237` | Redis **primary** | replication `redis` |
| 8 | `redis-2` | `192.168.1.238` | Redis **replica** (read-replica) | replication `redis` |
| — | *(резерв)* | `192.168.1.239` | НЕ ИСПОЛЬЗОВАТЬ | — |
| — | *(резерв)* | `192.168.1.240` | НЕ ИСПОЛЬЗОВАТЬ | — |
| — | `app-1` | *(не назначен, DHCP/резерв вне .230–.240)* | Java-монолит Spring Boot | запуск на машине оператора или отдельная VM по решению оркестратора |

Итого **8 VM**: 3 Kafka + 3 RabbitMQ + 2 Redis.

### 3.1 Сетевые параметры

| Параметр | Значение | Статус |
|---|---|---|
| Подсеть | `192.168.1.0/24` | принято |
| Маска | `/24` (`255.255.255.0`) | принято |
| Gateway | `192.168.1.1` | **требует подтверждения Agent 2 (D6)**: `ip r` на Proxmox-хосте / ping |
| DNS primary | `192.168.1.1` | **требует подтверждения (D6)** |
| DNS secondary | `8.8.8.8` | принято |
| Bridge | `vmbr0` (ожидается) | **требует подтверждения через API (D6)** |

Проверка gateway/DNS:
```bash
ping -c2 -W1 192.168.1.1 && ping -c2 -W1 8.8.8.8
```

### 3.2 Схема сети

```
                 Internet
                    |
             192.168.1.1 (gw/DNS)  -- 8.8.8.8 (DNS #2)
                    |
        vmbr0  === 192.168.1.0/24 ===  (Proxmox host: 192.168.1.253)
                    |
   +----------------+------------------+-------------------+
   |                                   |                   |
 Kafka KRaft (PLAINTEXT)          RabbitMQ 3.13        Redis 7.x
 kafka-1  .230  node.id=1         rabbitmq-1 .233      redis-1 .237 primary
 kafka-2  .231  node.id=2         rabbitmq-2 .234      redis-2 .238 replica
 kafka-3  .232  node.id=3         rabbitmq-3 .235        (replica-of .237)
   |                                   |                   ^
   +----------> Java монолит (Spring Boot 3.3, Java 21) --+
                    |
              POST /start :8080  ->  Kafka -> RabbitMQ -> Redis -> ответ
```

## 4. Версии стека (D4)

| Компонент | Версия | Примечание |
|---|---|---|
| Kafka | **3.8.x** (последний стабильный patch) | **KRaft**, без ZooKeeper; 3 контроллера совмещены с брокерами |
| RabbitMQ | **3.13.x** | кластер из 3 узлов, classic + quorum queues |
| Erlang/OTP | **26.x** | соответствие RabbitMQ 3.13 |
| Redis | **7.2 / 7.4** | primary + replica, НЕ Redis Cluster (D2) |
| Java | **21 LTS** (Temurin/OpenJDK) | ставится на VM и на машину оператора |
| Spring Boot | **3.3.x** | монолит, `Service/` |
| Сборка | **Maven 3.10.0** | `mvn -q -DskipTests package` |
| Terraform | **1.16.0** + provider `bpg/proxmox` | `Terraform/` |
| Ansible | **ansible-core 2.21.3** | `Ansible/` |
| OS | Ubuntu 24.04 LTS cloud image | D3 |

## 5. Порты сервисов (единая карта)

### Kafka (PLAINTEXT в лабе — без TLS/SASL, осознанно; P4/`Security/README.md`)
| Порт | Назначение |
|---|---|
| `9092` | PLAINTEXT брокер — клиенты (Java-сервис) и inter-broker |
| `9093` | Контроллер KRaft (controller listener) — внутри кластера |

`listeners=PLAINTEXT://<ip>:9092,CONTROLLER://<ip>:9093`,
`advertised.listeners=PLAINTEXT://<ip>:9092`,
`controller.quorum.voters=1@192.168.1.230:9093,2@192.168.1.231:9093,3@192.168.1.232:9093`.

### RabbitMQ
| Порт | Назначение |
|---|---|
| `5672` | AMQP 0-9-1 (клиенты) |
| `15672` | Management UI / HTTP API |
| `4369` | epmd — discovery узлов Erlang (кластеризация) |
| `25672` | inter-node (dist) — кластерный трафик |

### Redis
| Порт | Назначение |
|---|---|
| `6379` | RESP (клиенты + репликация primary→replica) |
| `16379` (опционально) | cluster-bus — НЕ используется (D2, не Redis Cluster) |

### Java-сервис (монолит)
| Порт | Назначение |
|---|---|
| `8080` | HTTP API: `POST /start`, `GET /actuator/health` |

### Системные
| Порт | Назначение |
|---|---|
| `22` | SSH (cloud-init user `ubuntu`), только ключ, password-auth выключен |
| `123/udp` | NTP (chrony → `192.168.1.1` / pool) |

## 6. Тюнинг под 2 GB RAM (D8) — обязательные лимиты

| Сервис | Параметр | Значение (база) |
|---|---|---|
| Kafka | `KAFKA_HEAP_OPTS` | `-Xmx1024m -Xms512m` |
| Kafka | `log.retention.hours` | `24` (лаба, экономия диска) |
| Kafka | `num.partitions` / topic `pipeline-requests` | `3` партиции, RF=3, `min.insync.replicas=2` |
| Kafka | systemd `LimitNOFILE` | `65536`, `MemoryMax=1536M` |
| RabbitMQ | `vm_memory_high_watermark.relative` | `0.4` (≈ 819 MB) |
| RabbitMQ | `vm_memory_high_watermark_paging_ratio` | `0.75` |
| RabbitMQ | `disk_free_limit.relative` | `1.5` |
| RabbitMQ | Erlang `RABBITMQ_SERVER_ADDITIONAL_ERL_ARGS` | `+S 2:2 +sbwt none +pc range` |
| Redis | `maxmemory` | `512mb` (primary), `256mb` (replica) |
| Redis | `maxmemory-policy` | `noeviction` (данные pipeline терять нельзя) |
| Redis | `save` | `900 1` (минимум fsync), `appendonly no` |
| OS | swap | `vm.swappiness=10`, swapfile 1G — защита от OOM |

## 7. Архитектурные решения D1–D8 (приняты, НЕ переигрывать)

| ID | Решение | Следствие для агентов |
|---|---|---|
| **D1** | RabbitMQ = 3 узла `.233/.234/.235`; адрес `.236` — резерв | в ТЗ было 4 IP на 3 VM — четвёртый не используется |
| **D2** | Redis = primary(`.237`) + replica(`.238`), НЕ Redis Cluster | на 2 узлах шардинг-кластер не строится; клиент пишет в primary |
| **D3** | ОС = Ubuntu 24.04 LTS cloud image | единый cloud-init user `ubuntu` |
| **D4** | Стек: `bpg/proxmox`; Kafka 3.8.x KRaft; RabbitMQ 3.13.x + Erlang 26; Redis 7.2/7.4; Java 21 + Spring Boot 3.3.x + Maven | версии фиксируются в `Terraform/variables.tf` и ролях Ansible |
| **D5** | Pipeline в **одном JVM**, сквозной `correlationId`, `/start` блокируется на `CompletableFuture`, затем делает **явный GET из Redis** | см. §8 |
| **D6** | Gateway `192.168.1.1` / DNS `8.8.8.8` — **Agent 2 обязан подтвердить** через API/связность | результат подтвердить в `docs/JOURNAL.md` и `Terraform/README.md` |
| **D7** | Секреты только через env / gitignored / ansible-vault; токен Proxmox не коммитить | `.gitignore` + `Security/README.md` (политики P1–P5) |
| **D8** | Ресурсы 2 vCPU / 2 GB / 40 GB — жёсткий лимит | тюнинг heap/watermark/maxmemory обязателен (§6) |

## 8. Бизнес-pipeline монолита (для справки агентов 4–6)

```
POST /start {message}
  -> correlationId = UUID
  -> publish в Kafka topic (pipeline-requests)
  -> Kafka consume -> enrich#1
  -> publish в RabbitMQ (header correlationId, queue pipeline.enrich2)
  -> RabbitMQ consume -> enrich#2
  -> write в Redis (key = correlationId)
  -> /start читает значение из Redis по correlationId (явный GET после CompletableFuture)
  -> возвращает финальное сообщение в HTTP-ответе
```

Топология имен (фиксируется Agent 5/6, значения — в `docs/ENDPOINTS.md`):
- Kafka topic: `pipeline-requests` (3 партиции, RF=3)
- RabbitMQ exchange: `pipeline` (direct), queue: `pipeline.enrich2`, routing key `enrich2`
- Redis key: `pipeline:result:{correlationId}` (или `{correlationId}` — решить в Agent 5 и записать в ENDPOINTS)

## 9. Общие требования к качеству (для всех агентов)

- идемпотентность: повторный `terraform apply` / `ansible-playbook` = no-op;
- `chrony` установлен и active на всех 8 VM;
- `/etc/hosts` содержит все 8 узлов (см. IPAM) — шаблон в `docs/CONVENTIONS.md`;
- тюнинг под 2 GB RAM (§6);
- каждый артефакт сопровождается README;
- журнал работ — `docs/JOURNAL.md`, итог — краткий run-report в ответе агента.

## 10. Эталонный блок /etc/hosts (копировать на все VM)

```
127.0.0.1   localhost
127.0.1.1   <self-hostname>

# kafka (KRaft)
192.168.1.230  kafka-1
192.168.1.231  kafka-2
192.168.1.232  kafka-3
# rabbitmq
192.168.1.233  rabbitmq-1
192.168.1.234  rabbitmq-2
192.168.1.235  rabbitmq-3
# redis
192.168.1.237  redis-1
192.168.1.238  redis-2
```

# Kafka: KRaft-кластер из 3 нод (docker compose)

Манифест: [`docker-compose.kafka.yml`](./docker-compose.kafka.yml)
Образ: `apache/kafka:3.7.1` (KRaft, без Zookeeper; CLI — `/opt/kafka/bin/*.sh`)

> Почему 3.7.1, а не 3.7.0: в 3.7.0 wrapper образа писал в `meta.properties`
> битый `cluster.id=Some(...)` (баг вывода `Option.toString`) и игнорировал
> ожидания по чистому id. 3.7.1 — патч того же 3.7.x с тем же env-контрактом.
> ВАЖНО: образ читает env `CLUSTER_ID`, а **не** `KAFKA_CLUSTER_ID`
> (без него storage форматируется дефолтным id из `configureDefaults`).

## Схема кластера

```
                       docker network: event-net (external, создана RabbitMQ)
 ┌───────────────────────────────────────────────────────────────────────────────┐
 │                                                                               │
 │   ┌───────────────┐        ┌───────────────┐        ┌───────────────┐         │
 │   │   kafka-1     │        │   kafka-2     │        │   kafka-3     │         │
 │   │ broker,ctrl   │◄─29093►│ broker,ctrl   │◄─29093►│ broker,ctrl   │         │
 │   │ node.id=1     │  KRaft │ node.id=2     │  raft  │ node.id=3     │         │
 │   └──┬────────┬───┘  quorum└──┬────────┬───┘        └──┬────────┬───┘         │
 │      │29092   │9092           │29092   │9092           │29092   │9092         │
 │      │INTERNAL│HOST           │INTERNAL│HOST           │INTERNAL│HOST         │
 └──────┼────────┼───────────────┼────────┼───────────────┼────────┼─────────────┘
        │        │               │        │               │        │
        │  host:9092        host:9093 (→ container 9092)   │   host:9094 (→ 9092)
        │        │               │        │               │        │
        │        └───────────────┴────┬───┴───────────────┘        │
        │                             │                            │
        │              Spring Boot / CLI с хоста: localhost:9092   │
        │              (advertised.listeners HOST = localhost:909x)│
        └── межброкерный и внутренний трафик (kafka-N:29092) ──────┘

 KRaft controller-порт 29093 — только внутри event-net (не публикуется на хост).
 CONTROLLER_QUORUM_VOTERS = 1@kafka-1:29093,2@kafka-2:29093,3@kafka-3:29093
 CLUSTER_ID = dTLhZkYMQceNQs3r-eGw7Q (общий для всех нод)
 Топик: messages.topic, 3 партиции, RF=3, min.insync.replicas=2
        (создаёт one-shot контейнер kafka-init; auto-create топиков выключен)
```

## Listeners

| Listener | Bind в контейнере | Advertised | Хост-порт | Кто использует |
|---|---|---|---|---|
| `INTERNAL` (PLAINTEXT) | `0.0.0.0:29092` | `kafka-N:29092` | — (только event-net) | брокер↔брокер, kafka-init, будущие сервисы в docker-сети |
| `HOST` (PLAINTEXT) | `0.0.0.0:9092` | `localhost:9092/9093/9094` | **9092 / 9093 / 9094** → container `9092` | Spring Boot и CLI **с хоста**: `spring.kafka.bootstrap-servers=localhost:9092` |
| `CONTROLLER` (PLAINTEXT) | `0.0.0.0:29093` | — (не advertised) | — | KRaft-кворум (3 voters) |

Почему так:

- `advertised.listeners` для HOST обязан быть `localhost:909x`: брокер отдаёт эти
  адреса в metadata всем клиентам; клиент с хоста затем ходит именно туда.
  Если advertise'ить `kafka-N` — имя не резолвится на хосте, клиент зависает.
- Внутри docker-сети наоборот:advertise'ится `kafka-N:29092`, чтобы репликация и
  внутренние клиенты не зависели от хост-портов (9093/9094 отличаются от 9092).
- Маппинг `9093:9092`/`9094:9092`: внутри всех контейнеров HOST-listener на 9092,
  различаются только опубликованные хост-порты.

## Управление жизненным циклом

```bash
cd Docker

# поднять (3 брокера healthy + kafka-init создаст топик и завершится)
docker compose -f docker-compose.kafka.yml up -d

# статус
docker compose -f docker-compose.kafka.yml ps -a

# остановить (containers сохранены, данные в volumes на месте)
docker compose -f docker-compose.kafka.yml stop

# запустить снова
docker compose -f docker-compose.kafka.yml start

# удалить контейнеры (данные/volumes сохраняются)
docker compose -f docker-compose.kafka.yml down
```

Пересоздание с нуля (новый кластер-идентификатор, чистые логи):

```bash
docker compose -f docker-compose.kafka.yml down
docker volume rm kafka-1-data kafka-2-data kafka-3-data
# новый CLUSTER_ID:
NEW_ID=$(docker run --rm apache/kafka:3.7.1 /opt/kafka/bin/kafka-storage.sh random-uuid)
# подставить NEW_ID в docker-compose.kafka.yml (env CLUSTER_ID) и
docker compose -f docker-compose.kafka.yml up -d
```

> ВНИМАНИЕ: сеть `event-net` — внешняя (принадлежит проекту rabbitmq-cluster).
> `docker compose -f docker-compose.kafka.yml down` её НЕ трогает (external: true).
> Не запускайте `down` для rabbitmq-манифеста, пока поднят Kafka — сначала
> останавливайте Kafka. И не смешивайте `-f` файлы в одной команде.

## Топик приложения

Создаётся one-shot сервисом `kafka-init` (depends_on: все брокеры healthy,
завершается с exit 0; при повторном `up` idempotent благодаря `--if-not-exists`):

```
messages.topic: partitions=3, replication.factor=3, min.insync.replicas=2
```

`KAFKA_AUTO_CREATE_TOPICS_ENABLE=false` — опечатка в имени топика у приложения
упадёт сразу (UnknownTopicOrPartition), а не создаст «мусорный» топик молча.

Гарантии при `acks=all` у продюсера: запись подтверждается при ≥2 синхронных
репликах → кластер переживает отказ одной ноды без потери записей; отказ двух
нод делает запись невозможной (NOT_ENOUGH_REPLICAS), но чтение с лидера продолжается.

## Диагностика

CLI выполняется из образа (на хосте Java не нужна):

```bash
# хелпер
K="docker run --rm --network event-net apache/kafka:3.7.1 /opt/kafka/bin"

# топики: список и describe (партиции/RF/ISR)
$K/kafka-topics.sh --bootstrap-server kafka-1:29092 --list
$K/kafka-topics.sh --bootstrap-server kafka-1:29092 --describe --topic messages.topic

# consumer groups
$K/kafka-consumer-groups.sh --bootstrap-server kafka-1:29092 --list
$K/kafka-consumer-groups.sh --bootstrap-server kafka-1:29092 --describe --group <group>

# console round-trip через внутренний listener
printf 'a\nb\nc\n' | $K/kafka-console-producer.sh --bootstrap-server kafka-1:29092 --topic messages.topic
$K/kafka-console-consumer.sh --bootstrap-server kafka-1:29092 --topic messages.topic --from-beginning --timeout-ms 8000

# с ХОСТА (путь Spring Boot): продюсер/консюмер через localhost:9092
.host-test/venv/bin/python .host-test/kafka_host_check.py bootstrap   # metadata + advertised listeners
.host-test/venv/bin/python .host-test/kafka_host_check.py isr         # партиции/ISR
.host-test/venv/bin/python .host-test/kafka_host_check.py roundtrip   # produce(acks=all)+consume

# состояние KRaft-кворума (лидер, epoch, lag голосующих)
$K/kafka-metadata-quorum.sh --bootstrap-server kafka-1:29092 describe --status
$K/kafka-metadata-quorum.sh --bootstrap-server kafka-1:29092 describe --replication

# логи и raft-метаданные
docker logs kafka-1 --tail 100
docker exec kafka-1 cat /var/lib/kafka/data/meta.properties     # cluster.id / node.id / directory.id
docker exec kafka-1 ls /var/lib/kafka/data                      # партиции + __cluster_metadata-0 (raft-лог)

# healthcheck отдельных нод (то же, что делает compose)
docker exec kafka-1 /opt/kafka/bin/kafka-broker-api-versions.sh --bootstrap-server localhost:9092 | head -2
```

## Типовые проблемы

1. **Клиент с хоста висит на подключении / metadata timeout** —
   `advertised.listeners` для HOST не `localhost:909x` (например, `kafka-1:9092`
   или внутренний `29092`). Брокер доступен по bootstrap, но в metadata отдаёт
   неразрешимый адрес. Проверка:
   `.host-test/venv/bin/python .host-test/kafka_host_check.py bootstrap` —
   должно вывести `advertised=localhost:9092/9093/9094`.

2. **`Connection to node -1 could not be established`** — bootstrap-адрес
   недоступен целиком: порт не проброшен, брокер не healthy, или обращение к
   `localhost:909x` изнутри docker-сети (там нужен `kafka-N:29092`).
   Проверка портов на хосте: `nc -vz localhost 9092`.

3. **`InconsistentClusterIdException` / кластер не собирается после пересоздания
   volumes** — ноды отформатировали storage с разными CLUSTER_ID (или остался
   старый meta.properties в volume). Лечится полным сбросом: `down` →
   `docker volume rm kafka-{1,2,3}-data` → одинаковый `CLUSTER_ID` в env → `up -d`.
   Текущий id: `docker exec kafka-1 grep cluster.id /var/lib/kafka/data/meta.properties`.

4. **Образ 3.7.0: `cluster.id=Some(...)` в meta.properties** — баг docker-wrapper
   3.7.0 (пишет `Option.toString`). Используйте 3.7.1+. Также помните: env должен
   называться `CLUSTER_ID`, `KAFKA_CLUSTER_ID` образом игнорируется.

5. **Записи падают с `NOT_ENOUGH_REPLICAS`** — живых синхронных реплик меньше
   `min.insync.replicas=2` (остановлены 2 ноды из 3). Это ожидируемое поведение
   гарантий durability при `acks=all`: верните ноду или снизьте требования.

6. **`down` «сломал» сеть event-net** — выполнен `down` манифеста, который создал
   сеть (rabbitmq). Kafka подключается к ней как `external` и не удаляет её;
   порядок демонтажа: сначала kafka `down`, затем rabbitmq `down`.

## Smoke-тест отказоустойчивости (пройден 2026-10-08)

| Шаг | Действие | Результат |
|---|---|---|
| 1 | baseline round-trip (host, acks=all) | 5/5 delivered, ISR=3 на всех партициях |
| 2 | `docker compose -f docker-compose.kafka.yml stop kafka-2` | контейнер остановлен |
| 3 | describe через ~35s (`replica.lag.time.max.ms=30s`) | ISR: p0=[1,3], p1=[3,1] (лидер p1 переехал 2→3), p2=[3,1]; raft-кворум жив (LeaderId=3, voters 1,2,3) |
| 4 | round-trip при остановленной ноде | 5/5 delivered (ISR=2 ≥ min.insync.replicas=2) |
| 5 | `start kafka-2` → healthy | ~15s |
| 6 | ожидание ISR | ISR=3 на всех партициях через ~5s после healthy |
| 7 | финальный round-trip | 5/5 delivered |

Вывод: отказ одной ноды не влияет на доступность записи (acks=all) и чтения;
нода автоматически возвращается в ISR после рестарта.

## Файлы

- `docker-compose.kafka.yml` — манифест кластера (3 брокера + kafka-init)
- `.host-test/` — хостовый тест-клиент (venv + `kafka_host_check.py`),
  проверяет ровно тот путь, которым ходит Spring Boot (`localhost:9092`)

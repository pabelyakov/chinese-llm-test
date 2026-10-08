# Docker — инфраструктура event-driven проекта

Каталог содержит docker-compose манифесты и общую конфигурацию `.env` для
всех кластеров проекта: **Kafka** (приём событий), **RabbitMQ** (стадия 2),
**Redis** (результаты + pub/sub), и **Java-сервиса** (оркестратор pipeline).

Общая docker-сеть: `event-driven-net` (bridge). Создаётся один раз INFRA-слоем,
кластерные compose-файлы подключаются к ней как `external: true`.

## Состав каталога

```
Docker/
├── .env                        # общие переменные (порты, креды, топики, ключи)
├── docker-compose.base.yml     # контракт общей сети event-driven-net
├── docker-compose.kafka.yml    # манифест кластера Kafka (агент Kafka)
├── docker-compose.rabbitmq.yml # манифест кластера RabbitMQ (агент RabbitMQ)
├── docker-compose.redis.yml    # манифест кластера Redis (агент Redis)
├── README.md                   # этот файл
├── kafka/README.md             # документация кластера Kafka
├── rabbitmq/README.md          # документация кластера RabbitMQ (+ скрипты seed/join)
├── redis/README.md             # документация кластера Redis
└── tests/                      # скрипты QA/E2E-агента
```

> **Важно:** compose-манифесты кластеров лежат **прямо в `Docker/`**
> (`Docker/docker-compose.<cluster>.yml`), а их README — в подкаталогах
> (`Docker/<cluster>/README.md`).

## Порядок запуска

Все команды выполняются **из корня проекта**.

Порядок строгий: **сначала сеть** (`event-driven-net`), затем кластеры
**kafka / rabbitmq / redis**. Java-монолит **не** запускается через compose —
он стартует отдельно локально (см. `Service/README.md`).

```bash
# 1. BASE — создать общую сеть (идемпотентно).
#    compose-файл без сервисов сеть при `up` не создаёт, поэтому:
docker network create event-driven-net 2>/dev/null || true
docker compose -f Docker/docker-compose.base.yml config   # валидация контракта

# 2. KAFKA — кластер из 3 брокеров (KRaft), топик pipeline.inbound
docker compose -f Docker/docker-compose.kafka.yml up -d

# 3. RABBITMQ — кластер из 3 нод, exchange/queue/routing key из .env
docker compose -f Docker/docker-compose.rabbitmq.yml up -d

# 4. REDIS — master + replica
docker compose -f Docker/docker-compose.redis.yml up -d

# 5. Java-монолит — запуск отдельно, вне docker-compose (порт APP_PORT=8080),
#    см. Service/README.md
```

Проверка после каждого шага: `docker compose -f Docker/docker-compose.<cluster>.yml ps`, `docker network inspect event-driven-net`.

## Документация кластеров и Java-сервиса

| Компонент  | Манифест (compose)                   | README                          |
|------------|--------------------------------------|---------------------------------|
| Kafka      | `Docker/docker-compose.kafka.yml`    | `Docker/kafka/README.md`        |
| RabbitMQ   | `Docker/docker-compose.rabbitmq.yml` | `Docker/rabbitmq/README.md`     |
| Redis      | `Docker/docker-compose.redis.yml`    | `Docker/redis/README.md`        |
| Java-монолит | — (запуск локально, не в compose)  | `Service/README.md`             |

## Порты (контракт из `.env`)

| Компонент            | Переменная            | Значение             | Порт(ы) хоста         |
|----------------------|-----------------------|----------------------|-----------------------|
| Kafka broker 1       | `KAFKA1_PORT`         | 9092                 | localhost:9092        |
| Kafka broker 2       | `KAFKA2_PORT`         | 9093                 | localhost:9093        |
| Kafka broker 3       | `KAFKA3_PORT`         | 9094                 | localhost:9094        |
| Kafka топик          | `KAFKA_TOPIC`         | pipeline.inbound     | 3 партиции, RF3       |
| RabbitMQ нода 1      | `RABBIT1_PORT` / `RABBIT1_MGMT_PORT` | 5672 / 15672 | localhost:5672, UI :15672 |
| RabbitMQ нода 2      | `RABBIT2_PORT` / `RABBIT2_MGMT_PORT` | 5673 / 15673 | localhost:5673, UI :15673 |
| RabbitMQ нода 3      | `RABBIT3_PORT` / `RABBIT3_MGMT_PORT` | 5674 / 15674 | localhost:5674, UI :15674 |
| RabbitMQ exchange    | `RABBIT_EXCHANGE`     | pipeline.exchange    | topic, durable        |
| RabbitMQ queue       | `RABBIT_QUEUE`        | pipeline.stage2.queue| durable, quorum       |
| RabbitMQ routing key | `RABBIT_ROUTING_KEY`  | stage.rabbit         | —                     |
| Redis master         | `REDIS_MASTER_PORT`   | 6379                 | localhost:6379        |
| Redis replica        | `REDIS_REPLICA_PORT`  | 6380                 | localhost:6380        |
| Redis ключ результата| `REDIS_RESULT_KEY_PREFIX` | pipeline:result: | `pipeline:result:{correlationId}`, TTL 60c |
| Redis канал          | `REDIS_COMPLETED_CHANNEL` | pipeline:completed | pub/sub             |
| Java-сервис          | `APP_PORT`            | 8080                 | localhost:8080        |
| Сеть                 | `NETWORK_NAME`        | event-driven-net     | bridge                |
| Проект               | `COMPOSE_PROJECT_NAME`| event-driven         | —                     |

Креды: `RABBITMQ_USER=pipeline` / `RABBITMQ_PASSWORD=pipeline-secret`,
`RABBITMQ_COOKIE=event-driven-secret-cookie`, `REDIS_PASSWORD=redis-secret`.

## Остановка и очистка

```bash
# Остановить кластер (порядок обратный запуску), данные volumes сохраняются:
docker compose -f Docker/docker-compose.redis.yml down
docker compose -f Docker/docker-compose.rabbitmq.yml down
docker compose -f Docker/docker-compose.kafka.yml down

# Полная очистка (контейнеры + volumes с данными):
docker compose -f Docker/docker-compose.kafka.yml down -v
docker compose -f Docker/docker-compose.rabbitmq.yml down -v
docker compose -f Docker/docker-compose.redis.yml down -v

# Удалить общую сеть (только когда все контейнеры отключены от неё):
docker network rm event-driven-net
```

## Troubleshooting

- **Сеть не создана / контейнеры не видят друг друга**
  ```bash
  docker network ls | grep event-driven-net
  docker network create event-driven-net        # если отсутствует
  docker network inspect event-driven-net       # driver должен быть bridge
  ```
  Кластерные compose-файлы должны объявлять сеть как `external: true` с
  `name: event-driven-net`.

- **Порт занят** (`Bind for 0.0.0.0:9092 failed: port is already allocated`):
  ```bash
  lsof -i :9092        # подставь нужный порт из таблицы выше
  ```
  Останови конфликтующий процесс или измени порт в `Docker/.env`.

- **`docker compose up` для base.yml печатает `no service selected`** — это
  нормально: в base-файле нет сервисов, он фиксирует только контракт сети.
  Сеть создаётся командой `docker network create event-driven-net`.

- **Проверить итоговый конфиг кластера перед запуском**:
  ```bash
  docker compose -f Docker/docker-compose.<cluster>.yml config
  ```

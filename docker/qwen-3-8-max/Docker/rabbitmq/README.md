# RabbitMQ — кластер из 3 нод (event-driven проект)

Кластер RabbitMQ для стадии 2 pipeline: обмен событиями между Kafka-консьюмером
и следующей стадией через topic-exchange и реплицируемую QUORUM-очередь.

## Схема кластера

```
                        event-driven-net (bridge, external)
   host                 ┌──────────────────────────────────────────────┐
  ┌──────────┐  5672    │  ┌─────────────┐   Erlang dist (25672)       │
  │ монолит  │──────────┼─▶│   rabbit1   │◀────────────┬───────────┐   │
  │ (Java)   │  15672   │  │ SEED        │             │           │   │
  │          │──────────┼─▶│ rabbit@     │             │           │   │
  └──────────┘          │  │ rabbit1     │             │           │   │
       │  5673/15673    │  └──────┬──────┘             │           │   │
       ├────────────────┼────────▶│              ┌─────┴─────┐ ┌───┴───────┐
       │  5674/15674    │         │              │  rabbit2  │ │  rabbit3  │
       ├────────────────┼─────────┼──────────────│ rabbit@   │ │ rabbit@   │
       ▼                │         └──────────────│ rabbit2   │ │ rabbit3   │
   (AMQP/UI всех нод)   │                        └───────────┘ └───────────┘
                        │   volumes: rabbit1-data rabbit2-data rabbit3-data
                        └──────────────────────────────────────────────┘
   Топология (vhost /):
   pipeline.exchange (topic, durable)
        └──[routing key: stage.rabbit]──▶ pipeline.stage2.queue (QUORUM, durable)
```

- **rabbit1** — seed-нода: создаёт пользователя `pipeline`, объявляет топологию.
- **rabbit2/rabbit3** — join-ноды: стартуют после healthy-состояния rabbit1
  (`depends_on: service_healthy`) и присоединяются к кластеру.
- Все ноды: `rabbitmq:3.13-management`, одинаковый Erlang cookie, именованные
  volumes в `/var/lib/rabbitmq`, healthcheck через `rabbitmq-diagnostics`.

## Файлы

| Файл | Назначение |
|---|---|
| `Docker/docker-compose.rabbitmq.yml` | манифест кластера (3 сервиса, volumes, сеть) |
| `Docker/rabbitmq/rabbitmq-cluster-seed` | обёртка rabbit1: bootstrap пользователя и топологии, затем `exec rabbitmq-server` |
| `Docker/rabbitmq/rabbitmq-cluster-join` | обёртка rabbit2/rabbit3: retry-loop ожидания seed-ноды, идемпотентный `join_cluster` |
| `Docker/.env` | все переменные (порты, креды, имена exchange/queue/routing key) — контракт INFRA |

Обёртки монтируются в `/usr/local/bin/` и запускаются через `command`. Их имена
начинаются с `rabbitmq` — по этому признаку штатный `docker-entrypoint.sh`
образа сам понижает привилегии до пользователя `rabbitmq` (gosu).

## Образ и версия

**`rabbitmq:3.13-management`** (внутри: RabbitMQ 3.13.7 на Erlang/OTP 26.2.5.16).
Выбор: стабильная линейка 3.13.x — quorum queues в production-ready виде,
плагин management и `rabbitmqadmin` v2 из коробки, мультиарх-образы
(linux/arm64 для Apple Silicon и linux/amd64). Тег `3.13-management` отслеживает
патч-релизы 3.13.x без мажорных сюрпризов.

## Запуск / остановка / очистка

Все команды — **из корня проекта**:

```bash
# Запуск (сеть event-driven-net должна существовать — создаёт INFRA-агент):
docker compose -f Docker/docker-compose.rabbitmq.yml up -d

# Формирование кластера занимает ~40–90 секунд. Повторный `up -d` безопасен:
# обёртки идемпотентны (нода уже в кластере — join/reset НЕ выполняется).

# Статус:
docker compose -f Docker/docker-compose.rabbitmq.yml ps      # 3 x healthy

# Остановить (данные в named volumes сохраняются):
docker compose -f Docker/docker-compose.rabbitmq.yml down

# Полная очистка (контейнеры + volumes с mnesia-данными):
docker compose -f Docker/docker-compose.rabbitmq.yml down -v
```

## Проверка кластера и топологии

```bash
# Все 3 ноды в Running Nodes:
docker exec rabbit1 rabbitmqctl cluster_status

# Очередь имеет тип quorum:
docker exec rabbit1 rabbitmqctl list_queues name type durable
#   pipeline.stage2.queue   quorum   true

# Exchange:
docker exec rabbit1 rabbitmqctl list_exchanges name type | grep pipeline
#   pipeline.exchange   topic

# Binding:
docker exec rabbit1 rabbitmqctl list_bindings | grep pipeline.exchange
#   pipeline.exchange exchange pipeline.stage2.queue queue stage.rabbit []

# Члены (реплики) quorum-очереди — должны быть все 3 ноды:
docker exec rabbit1 rabbitmq-queues quorum_status pipeline.stage2.queue

# Пользователь и права:
docker exec rabbit1 rabbitmqctl list_users
docker exec rabbit1 rabbitmqctl list_permissions -p /

# Внешний доступ с хоста:
nc -z localhost 5672 && echo OPEN
```

## Как создаётся топология (выбранный подход)

Топологию и пользователя создаёт **seed-обёртка на rabbit1** автоматически при
каждом старте контейнера, через **`rabbitmqadmin` v2 → HTTP management API**
(`127.0.0.1:15672`, логин `pipeline`):

```bash
rabbitmqadmin declare exchange name=pipeline.exchange type=topic durable=true
rabbitmqadmin declare queue name=pipeline.stage2.queue durable=true \
    arguments='{"x-queue-type":"quorum"}'
rabbitmqadmin declare binding source=pipeline.exchange \
    destination=pipeline.stage2.queue routing_key=stage.rabbit
```

Почему так: `rabbitmqadmin` v2 входит в `-management` образ (не нужен curl,
которого в образе нет), повторный `declare` с идентичными параметрами — no-op
(идемпотентность), declare выполняется с retry на случай ещё не поднявшегося
management-плагина. Пользователь создаётся через `rabbitmqctl add_user` (либо
`change_password`, если уже существует) + `set_user_tags administrator` +
`set_permissions -p / ".*" ".*" ".*"`.

После формирования кластера обёртка дополнительно выполняет
`rabbitmq-queues add_member` для rabbit2/rabbit3 — страховка на случай, если
очередь была объявлена до присоединения всех нод (при объявлении на полном
кластере все ноды становятся членами автоматически).

## QUORUM vs classic mirrored: почему НЕ нужна политика ha-all

| | Classic mirrored (HA) | **Quorum (наш выбор)** |
|---|---|---|
| Репликация | зеркала сообщений, синхронизация при (пере)подключении | Raft-консенсус, встроенная репликация состояния |
| Consistency | возможны потери/дубли при failover | строгая: запись подтверждена большинством (кворумом) нод |
| Управление HA | политика `ha-all`/`ha-mode` (внешняя) | параметр очереди `x-queue-type=quorum` (внутреннее) |
| Статус | **deprecated** с 3.8, удалён в 4.x | рекомендованный тип для durable/надёжных очередей |

**Политика `ha-all` для quorum-очередей НЕ требуется и не применяется**:
репликация — свойство самого типа очереди, а не внешняя политика. Очередь
`pipeline.stage2.queue` объявлена с `arguments={"x-queue-type":"quorum"}` и
имеет по реплике на каждой из 3 нод (лидер + 2 фолловера; видно в
`rabbitmq-queues quorum_status`). Переживает потерю 1 ноды без потери сообщений
(кворум 2/3). В RabbitMQ 4.x classic mirrored queues удалены полностью —
quorum единственный корректный выбор.

## Учётные данные и management UI

Креды берутся из [`Docker/.env`](../.env): `RABBITMQ_USER=pipeline`,
`RABBITMQ_PASSWORD=pipeline-secret`, cookie кластера `RABBITMQ_COOKIE`.

| Нода | AMQP (хост) | Management UI | Логин |
|---|---|---|---|
| rabbit1 | localhost:5672 | http://localhost:15672 | `pipeline` / `pipeline-secret` (administrator) |
| rabbit2 | localhost:5673 | http://localhost:15673 | то же |
| rabbit3 | localhost:5674 | http://localhost:15674 | то же |

`guest` оставлен **только для management UI** (в образе по умолчанию
`loopback_users.guest = false`, поэтому guest/guest работает через
опубликованные порты). Приложение обязано использовать `pipeline`.

Внутри сети `event-driven-net` ноды доступны как `rabbit1:5672`, `rabbit2:5672`,
`rabbit3:5672` (epmd 4369 и Erlang dist 25672 на хост НЕ публикуются).

**Endpoint для монолита (с хоста):** `localhost:5672`, user `pipeline`,
password `pipeline-secret`, vhost `/`. Из контейнера в той же сети —
`rabbit1:5672` (рекомендуется перечислять все три хоста: rabbit1/2/3).

## Smoke-тест

```bash
# 1. Publish сообщения "ping" в exchange с routing key stage.rabbit:
docker exec rabbit1 rabbitmqadmin --host 127.0.0.1 \
    --username pipeline --password pipeline-secret --vhost / \
    publish exchange=pipeline.exchange routing_key=stage.rabbit payload=ping
#   → Message published

# 2. Убедиться, что сообщение легло в очередь (ВАЖНО: метрики quorum-очередей
#    в stats-БД обновляются с задержкой ~5–10 с — добавьте sleep):
sleep 8
curl -s -u pipeline:pipeline-secret \
    http://localhost:15672/api/queues/%2F/pipeline.stage2.queue \
    | python3 -c 'import json,sys; print(json.load(sys.stdin)["messages_ready"])'
#   → 1

# 3. Очистить очередь:
docker exec rabbit1 rabbitmqctl purge_queue -p / pipeline.stage2.queue
#   → Purging queue 'pipeline.stage2.queue' in vhost '/' ...

# 4. Проверить, что очередь пуста (live-чтение, без лага статистики):
docker exec rabbit1 rabbitmqadmin --host 127.0.0.1 \
    --username pipeline --password pipeline-secret --vhost / \
    get queue=pipeline.stage2.queue count=5 ackmode=ack_requeue_true
#   → No items
```

## Troubleshooting

- **Ноды не объединяются в кластер / `incompatible_erlang_cookies`**
  У всех трёх нод должен быть ОДИНАКОВЫЙ `RABBITMQ_ERLANG_COOKIE` (в compose он
  берётся из `RABBITMQ_COOKIE` файла `Docker/.env`). Проверка:
  ```bash
  for n in rabbit1 rabbit2 rabbit3; do
    docker exec $n printenv RABBITMQ_ERLANG_COOKIE; done   # значения идентичны
  ```
  Если cookie менялся после старта — нужен полный пересоздание с `down -v`.

- **rabbit2 стартует раньше rabbit1 (race)**
  Уже вылечено в манифесте: `depends_on: rabbit1: condition: service_healthy`
  (healthcheck rabbit1 = `rabbitmq-diagnostics ping && check_running`) плюс
  retry-loop в обёртке `rabbitmq-cluster-join` (30 попыток × 3 с по
  `rabbitmq-diagnostics -n rabbit@rabbit1 ping`). В логах:
  `docker compose -f Docker/docker-compose.rabbitmq.yml logs rabbit2`.

- **Очередь создалась classic вместо quorum**
  Симптомы: `list_queues name type` показывает `classic`. Причина: очередь была
  создана раньше/иначе без `x-queue-type` (тип существующей очереди изменить
  нельзя). Лечится пересозданием:
  ```bash
  docker exec rabbit1 rabbitmqadmin --host 127.0.0.1 -u pipeline -p pipeline-secret \
      delete queue name=pipeline.stage2.queue
  # затем перезапустить seed-обёртку (она объявит очередь заново, quorum):
  docker compose -f Docker/docker-compose.rabbitmq.yml up -d --force-recreate rabbit1
  ```

- **`messages_ready` показывает 0/устаревшее значение сразу после publish/purge**
  Нормально для quorum-очередей: метрики приходят из stats-БД с интервалом ~5 с.
  Для точной проверки используйте live-`get` (см. smoke-тест, шаг 4).

- **Порт занят** (`Bind for 0.0.0.0:5672 failed: port is already allocated`):
  ```bash
  lsof -i :5672      # или 15672/5673/5674/15673/15674
  ```

- **Permission-ошибки на /var/lib/rabbitmq после ручных экспериментов**
  Данные принадлежат пользователю rabbitmq (uid 999). Самый простой фикс —
  пересоздать volumes: `docker compose -f Docker/docker-compose.rabbitmq.yml down -v && ... up -d`.

- **Полный «сброс до заводских настроек»**
  ```bash
  docker compose -f Docker/docker-compose.rabbitmq.yml down -v
  docker compose -f Docker/docker-compose.rabbitmq.yml up -d
  # через ~60–90 с: cluster_status — 3 ноды, топология объявлена автоматически
  ```

- **Логи bootstrap-обёрток** (join/declare шаги):
  ```bash
  docker compose -f Docker/docker-compose.rabbitmq.yml logs rabbit1 | grep '\[seed'
  docker compose -f Docker/docker-compose.rabbitmq.yml logs rabbit2 | grep '\[join'
  ```

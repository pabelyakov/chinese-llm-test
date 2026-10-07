# RabbitMQ-кластер (3 ноды, docker compose)

Локальный кластер RabbitMQ 3.13.7 для разработки/тестирования отказоустойчивости.
Поднимается одной командой, кластеризация и инициализация топологии — автоматически.

## Схема кластера

```
                        docker network: event-net (bridge)
 ┌───────────────────────────────────────────────────────────────────┐
 │                                                                   │
 │  ┌────────────────┐      ┌────────────────┐      ┌──────────────┐ │
 │  │  rabbitmq-1    │      │  rabbitmq-2    │      │  rabbitmq-3  │ │
 │  │  rabbit@       │◄────►│  rabbit@       │◄────►│  rabbit@     │ │
 │  │  rabbitmq-1    │      │  rabbitmq-2    │      │  rabbitmq-3  │ │
 │  │  [seed]        │      │  [join]        │      │  [join]      │ │
 │  └───┬────────┬───┘      └───┬────────┬───┘      └───┬───────┬──┘ │
 │      │AMQP    │Mgmt          │AMQP    │Mgmt          │AMQP   │Mgmt│
 └──────┼────────┼──────────────┼────────┼──────────────┼───────┼────┘
        │        │              │        │              │       │
 host: 5672    15672          5673     15673          5674    15674

 messages.exchange (direct, durable)
        │  routing key: messages.key
        ▼
 messages.queue (durable, quorum, 3 реплики: leader + 2 followers)
```

- **Режим кластеризации:** классический (не peer-discovery). Нода 1 — seed,
  ноды 2/3 присоединяются init-скриптом: ожидание seed → `stop_app` →
  `reset` → `join_cluster rabbit@rabbitmq-1` → `start_app`.
- **Peer discovery:** явный `join_cluster` из `rabbitmq/cluster-entrypoint.sh`
  (монтируется в каждую ноду, роль передаётся аргументом `seed` / `join`).
  Механизм `cluster_formation.classic_config` не используется — join-скрипт
  предсказуемее и оставляет чистый `rabbitmq.conf`.
- **Общий Erlang cookie:** env `RABBITMQ_ERLANG_COOKIE` (единый на всех нодах;
  скрипт записывает его в `$HOME/.erlang.cookie`).
- **Автоматическая инициализация (только seed, после формирования кластера):**
  политика + контрактная топология (см. ниже). Ручные `rabbitmqctl` после
  `up -d` не требуются.
- **Обработка сетевых разделений:** `cluster_partition_handling = pause_minority`
  (при потере кворума меньшинство ставится на паузу, данные не расходятся).
- **Guest-доступ с хоста:** `loopback_users.guest = false` (иначе `guest`
  не пускается извне контейнера). Credentials: `guest / guest`.

## Файлы

| Файл | Назначение |
|------|-----------|
| `docker-compose.rabbitmq.yml` | манифест: 3 ноды, volumes, healthcheck, сеть `event-net` |
| `rabbitmq/cluster-entrypoint.sh` | entrypoint: cookie, старт, join/seed, политика, топология |
| `rabbitmq/rabbitmq.conf` | общий конфиг нод (`loopback_users`, partition handling, логи) |

## Порты и ноды

| Нода | Контейнер | AMQP (хост) | Management (хост) | Роль |
|------|-----------|-------------|-------------------|------|
| rabbit@rabbitmq-1 | rabbitmq-1 | 5672 | 15672 | seed |
| rabbit@rabbitmq-2 | rabbitmq-2 | 5673 | 15673 | join |
| rabbit@rabbitmq-3 | rabbitmq-3 | 5674 | 15674 | join |

Management UI: http://localhost:15672 (также 15673, 15674), вход `guest / guest`.
Данные нод: named volumes `rabbitmq-1-data`, `rabbitmq-2-data`, `rabbitmq-3-data`.

## Политика и топология (что применено и почему)

1. **Репликация.** `messages.queue` — **quorum**-очередь: репликация встроена
   в протокол Raft (3 реплики), классические HA-политики (`ha-mode`) к
   quorum-очередям не применяются и в 3.13 считаются устаревшими. Число реплик
   зафиксировано аргументом объявления `x-quorum-initial-group-size = 3`
   (в 3.13 это аргумент очереди, а НЕ политика — ключ
   `quorum-initial-group-size` в `set_policy` валидатор 3.13 отвергает).
2. **Политика** (создаётся автоматически на seed):
   ```
   rabbitmqctl set_policy quorum-ha "^messages\." \
     '{"delivery-limit":50}' --apply-to quorum_queues
   ```
   Единственная реально полезная HA-настройка quorum-очередей через политику
   в 3.13 — `delivery-limit` (лимит недоставок до drop/DLX; 50 — запас на
   повторные доставки во время failover). Имя в примере ТЗ (`ha-two`,
   `initial-quorum-size`) в 3.13 невалидно — решение задокументировано здесь.
3. **Контрактная топология** создаётся seed-нодой автоматически через
   management HTTP API:
   - exchange `messages.exchange` (direct, durable)
   - queue `messages.queue` (durable, quorum, `x-quorum-initial-group-size=3`)
   - binding `messages.key`

   **Вариант Б (приложение само объявляет сущности):** Spring AMQP declarables
   с идентичными параметрами идемпотентны — совпадут с уже созданными.
   ВАЖНО: очередь объявлять именно `x-queue-type=quorum`, иначе при
   несовпадении свойств будет `PRECONDITION_FAILED`. Удалить топологию, если
   нужно объявить с нуля: `rabbitmqctl delete_queue messages.queue` (exchange
   и binding пересоздадутся/удаляются через HTTP API).

## Команды управления

Из директории `Docker/`:

```bash
docker compose -f docker-compose.rabbitmq.yml up -d      # поднять кластер
docker compose -f docker-compose.rabbitmq.yml ps         # статусы (healthy)
docker compose -f docker-compose.rabbitmq.yml stop       # остановить (данные остаются)
docker compose -f docker-compose.rabbitmq.yml start      # запустить обратно
docker compose -f docker-compose.rabbitmq.yml down       # убрать контейнеры+сеть (volumes остаются)
docker compose -f docker-compose.rabbitmq.yml down -v    # снос С НАЧИСТО (удалить volumes)

# пересборка с нуля полностью:
docker compose -f docker-compose.rabbitmq.yml down -v
docker volume rm rabbitmq-1-data rabbitmq-2-data rabbitmq-3-data 2>/dev/null
docker compose -f docker-compose.rabbitmq.yml up -d
```

Примечание: `down` (без `-v`) удаляет и сеть `event-net` — если к ней уже
подключены Kafka/Redis манифесты (`external: true`), сначала опустите их или
используйте `stop` вместо `down`.

## Диагностика

```bash
docker exec rabbitmq-1 rabbitmqctl cluster_status        # 3 ноды в Running Nodes
docker exec rabbitmq-1 rabbitmqctl list_queues name type state messages_ready messages_unacknowledged
docker exec rabbitmq-1 rabbitmqctl list_policies
docker exec rabbitmq-1 rabbitmq-queues quorum_status messages.queue   # состояние реплик
docker exec rabbitmq-1 rabbitmq-diagnostics -q check_running && echo OK

curl -s -u guest:guest http://localhost:15672/api/overview | head -c 400
curl -s -u guest:guest http://localhost:15672/api/queues/%2F

# публикация/чтение тестового сообщения (smoke):
curl -s -u guest:guest -H 'content-type:application/json' \
  -XPOST http://localhost:15672/api/exchanges/%2F/messages.exchange/publish \
  -d '{"properties":{},"routing_key":"messages.key","payload":"ping","payload_encoding":"string"}'
curl -s -u guest:guest -H 'content-type:application/json' \
  -XPOST http://localhost:15672/api/queues/%2F/messages.queue/get \
  -d '{"count":1,"ackmode":"ack_requeue_false","encoding":"auto","truncate":50000}'

docker logs rabbitmq-1 | grep cluster-entrypoint          # ход инициализации
```

## Smoke-тест отказоустойчивости (пройден 2026-10-07, реальные выводы)

Исходно: 3 ноды running, `messages.queue` — leader `rabbit@rabbitmq-1`,
followers `rabbit@rabbitmq-2/3`, log index 5 у всех. Опубликовано сообщение
`smoke-test-1-before-failover` → `{"routed":true}`.

1. `docker compose stop rabbitmq-2` → graceful shutdown. Кластер жив:
   `Running Nodes: rabbit@rabbitmq-1, rabbit@rabbitmq-3` (кворум 2/3).
2. Во время отказа: публикация `smoke-test-2-during-failover` → `routed:true`;
   чтение через node-1 вернуло ОБА сообщения. `quorum_status`:
   `rabbit@rabbitmq-2 — {nodedown,...}`, leader и node-3 синхронны (index 14).
3. `docker compose start rabbitmq-2` → healthy; скрипт определил
   `already a cluster member, skipping join` (volume сохранил членство).
4. Нода догнала кластер: `rabbit@rabbitmq-2 — follower, log index 14`
   (= leader). `Running Nodes` — снова 3.
5. Полный `docker compose restart` всего кластера — все ноды вернулись
   healthy, 3 running nodes, очередь `state=running`.

Вывод: кластер переживает отказ 1 из 3 нод без потери доступности и данных,
возврат ноды — автоматический, с досинхронизацией реплики.

## Типовые проблемы

| Симптом | Причина / решение |
|---------|-------------------|
| Ноды не джойнятся, `Connection attempt failed`, `authentication failed` в логах | Разные Erlang cookie. Проверить `docker exec rabbitmq-N cat /var/lib/rabbitmq/.erlang.cookie` на всех нодах — должен совпадать (env `RABBITMQ_ERLANG_COOKIE`). После смены cookie — полный пересбор `down -v`. |
| `join_cluster` падает, ноды «не видят» друг друга | DNS/сеть: имена хостов должны резолвиться в `event-net`. Проверить: `docker exec rabbitmq-1 ping -c1 rabbitmq-2`. Причина — ноды в разных сетях или `hostname` не равен имени сервиса. |
| Management UI есть, но `statistics database could not be started`, пустые графики | Нет свободного места/`kernelpoller`... в docker обычно — ограничение памяти. Решение: увеличить память Docker Desktop; статистика восстанавливается сама через 10–30 сек. На работу AMQP не влияет. |
| `join_cluster` → `Node rabbit@... already has peer discovery data / is already a member` | Нода уже кластеризована (volume сохранил состояние). Скрипт сам определяет это (`already a cluster member, skipping join`). Вручную: сначала `rabbitmqctl forget_cluster_node`, потом reset/join. |
| После остановки 2 нод из 3 оставшаяся не принимает клиентов | Это `pause_minority`: без кворума (2/3 нод) нода обязана встать на паузу. Верните любую ноду — работа возобновится автоматически. |
| `PRECONDITION_FAILED inequivalent arg 'x-queue-type'` | Очередь объявляется с параметрами, отличными от уже существующих. Либо объявляйте `quorum` по контракту, либо удалите очередь и пересоздайте. |
| Публикация уходит, но `routed:false` | Нет binding: exchange без привязки к очереди с key `messages.key` — проверяется `rabbitmqctl list_bindings`. |

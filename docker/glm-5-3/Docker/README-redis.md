# Redis: master + replica (docker compose)

Локальная инфраструктура Redis из 2 нод для проекта. Используется **репликация
master → replica** (не Redis Cluster, не Sentinel).

## Схема

```
                        docker network: event-net (external)
 ┌───────────────────────────────────────────────────────────────┐
 │                                                               │
 │   ┌────────────────┐   replication (replicaof redis-master)   │
 │   │  redis-master  │ ──────────────────────────────────────►  │
 │   │  :6379 (write) │                        ┌────────────────┴──┐
 │   └───────▲────────┘                        │   redis-replica   │
 │           │                                 │   :6379 (read-only)│
 │           │ host :6379                      └───────▲───────────┘
 └───────────┼─────────────────────────────────────────┼─────────┘
             │                                         │ host :6380
        приложение (запись +                      приложение /
        read-after-write чтение)               диагностика (чтение)

 Направление репликации: master ──► replica (односторонняя).
 Replica принимает только чтение (replica-read-only yes).
```

Паттерн использования приложением (контракт с оркестратором): **запись и
чтение финального сообщения — в master** (`localhost:6379`), чтобы
гарантировать read-after-write consistency. Replica — для отказоустойчивости
и демонстрации репликации.

## Почему не Redis Cluster и не Sentinel

- **Redis Cluster** технически невозможен на 2 нодах: минимум 3 master-ноды
  (кворум и распределение 16384 hash-слотов требуют ≥3 мастеров). Кроме того,
  cluster mode меняет клиента (MOVED/ASK redirections) без явной нужды.
- **Sentinel** не используется — осознанное решение для базового варианта
  из 2 нод: Sentinel требует кворум (в идеале ≥3 sentinel-процесса на
  независимых нодах), а с 2 нодами автоматический failover ненадёжен
  (split-brain). Без Sentinel при падении master реплика остаётся read-only
  и просто ждёт возвращения master — авто-failover не происходит.
  Приложение от этого не страдает: оно читает/пишет только в master.

## Управление

Все команды выполняются из директории `Docker/`.

```bash
# поднять с нуля (чистое состояние)
docker compose -f docker-compose.redis.yml up -d

# статус (дождаться STATUS = healthy у обоих)
docker compose -f docker-compose.redis.yml ps

# остановить (контейнеры и volumes сохраняются)
docker compose -f docker-compose.redis.yml stop

# запустить снова после stop
docker compose -f docker-compose.redis.yml start

# ВНИМАНИЕ: down удаляет контейнеры и named volumes.
# Сеть event-net — external, down её НЕ удалит, но данные Redis будут потеряны.
docker compose -f docker-compose.redis.yml down

# пересобрать с нуля (удалить данные + поднять)
docker compose -f docker-compose.redis.yml down -v
docker compose -f docker-compose.redis.yml up -d
```

Важно: сеть `event-net` — external (создана манифестом RabbitMQ).
**Не выполняйте `docker compose -f docker-compose.rabbitmq.yml down`** —
это удалит сеть, к которой подключены и контейнеры Redis.

## Порты, hostnames, healthchecks

| Сервис        | Контейнер (hostname в event-net) | Хост-порт | Контейнер-порт | Healthcheck                                | Роль      |
|---------------|----------------------------------|-----------|----------------|--------------------------------------------|-----------|
| redis-master  | `redis-master`                   | **6379**  | 6379           | `redis-cli ping` → `PONG`                  | master    |
| redis-replica | `redis-replica`                  | **6380**  | 6379           | `redis-cli -h localhost ping` → `PONG`     | replica   |

- Образ: `redis:7.2-alpine` (совместим со Spring Data Redis).
- Пароль не используется (локальная разработка).
- Persistence: AOF (`--appendonly yes`), named volumes
  `redis-master-data`, `redis-replica-data`.
- Replica стартует только после healthy-статуса master
  (`depends_on: condition: service_healthy`).

## Диагностика

```bash
# состояние репликации на replica (ключевое поле: master_link_status:up)
docker exec redis-replica redis-cli INFO replication | grep -E 'role|master_link_status|repl_offset'

# роли нод (master: offset + список подключённых реплик; slave: master host/port)
docker exec redis-master redis-cli ROLE
docker exec redis-replica redis-cli ROLE

# мониторинг лага: сравнить offsets (lag = master_repl_offset - slave_repl_offset)
docker exec redis-master redis-cli INFO replication | grep master_repl_offset
docker exec redis-replica redis-cli INFO replication | grep slave_repl_offset

# round-trip с хоста
redis-cli -h localhost -p 6379 SET test "hello"   # запись в master
redis-cli -h localhost -p 6380 GET test           # чтение из replica

# логи
docker logs redis-master --tail 50
docker logs redis-replica --tail 50
```

## Smoke-тест отказоустойчивости (выполнен, воспроизведение)

1. `docker compose -f docker-compose.redis.yml stop redis-master`
2. Через ~5 сек на replica: `master_link_status:down` — но GET из replica
   продолжает отдавать закэшированные данные (read-only).
3. Запись в replica отклоняется: `READONLY You can't write against a read
   only replica.` — авто-failover НЕ происходит (нет Sentinel, см. выше).
4. `docker compose -f docker-compose.redis.yml start redis-master`
   → replica переподключается: `master_link_status:up`, новая запись в
   master реплицируется в replica. Данные master переживают рестарт
   благодаря AOF.

## Типовые проблемы

| Симптом | Причина | Решение |
|---|---|---|
| `bind: address already in use` при `up` на порту 6379/6380 | Локальный Redis/Brew на хосте занимает порт | `lsof -i :6379 -i :6380`, остановить хостовый сервис (`brew services stop redis`) |
| Replica висит в `master_link_status:down`, в логах `Error connecting to MASTER ...: Name or service not known` | Replica не резолвит `redis-master` (не та сеть / master не поднялся) | Проверить, что оба контейнера в `event-net`: `docker network inspect event-net`; убедиться, что master healthy |
| `master_link_status:down` сразу после старта | Репликация ещё не установилась | Подождать 5–10 сек (healthcheck проверяет только PONG, не репликацию) |
| Данные не реплицируются: GET из replica пустой | Реплика отстаёт / переподключается после даунтайма master | Сверить `master_repl_offset` (на master) и `slave_repl_offset` (на replica); при расхождении дождаться синхронизации или перезапустить replica |
| `READONLY You can't write against a read only replica` при записи в :6380 | Ожидаемое поведение | Писать только в master (:6379) |
| После `docker compose -f docker-compose.rabbitmq.yml down` Redis-контейнеры теряют сеть | `down` манифеста RabbitMQ удаляет сеть `event-net` | Не использовать чужой `down`; восстановление: поднять RabbitMQ-стек (пересоздаст сеть), затем `docker compose -f docker-compose.redis.yml up -d` |

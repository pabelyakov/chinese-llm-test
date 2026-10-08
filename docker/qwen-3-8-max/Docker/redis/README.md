# Redis: master + replica (event-driven)

Compose-файл: [`../docker-compose.redis.yml`](../docker-compose.redis.yml) ·
Переменные окружения: [`../.env`](../.env) (`REDIS_PASSWORD`, `REDIS_MASTER_PORT`, `REDIS_REPLICA_PORT`)

## Топология

| Роль      | Контейнер      | Hostname     | Хост-порт (localhost) | Контейнер-порт |
|-----------|----------------|--------------|-----------------------|----------------|
| master    | `redis-master` | `redis-master` | **6379** (`REDIS_MASTER_PORT`)  | 6379 |
| replica   | `redis-replica`| `redis-replica`| **6380** (`REDIS_REPLICA_PORT`) | 6379 |

- Образ: `redis:7-alpine` (на момент развёртывания резолвится в **Redis 7.4.x**, multiarch arm64/amd64 — работает на Apple Silicon нативно).
- Аутентификация: `requirepass` на обеих нодах, на реплике дополнительно `masterauth` — значение берётся из `REDIS_PASSWORD` (`redis-secret`), **не хардкодится** в файлах.
- Master: `appendonly yes` (AOF — durability), `maxmemory-policy noeviction`.
- Replica: `--replicaof redis-master 6379` (асинхронная репликация), read-only по умолчанию, отдаёт данные даже при временной потере мастера (`replica-serve-stale-data yes` — дефолт).
- Данные: named volumes `redis-master-data`, `redis-replica-data` → `/data`.
- Сеть: внешняя bridge-сеть `event-driven-net` (создана INFRA-агентом), через неё replica находит master по hostname.
- Healthcheck: `redis-cli ping` — пароль передаётся через env-переменную контейнера `REDISCLI_AUTH`, поэтому внутри контейнеров `redis-cli` работает **без флага `-a`** и без warning'ов. Replica стартует только после `service_healthy` мастера (`depends_on`).

**Контракт для монолита:** писать и читать результаты (`pipeline:result:{correlationId}`, TTL 60 c) — всегда в **master (localhost:6379)**. Уведомления о готовности результата — pub/sub канал **`pipeline:completed`** на мастере. Replica (localhost:6380) — чтение и отработка восстановления.

## Почему НЕ Redis Cluster при 2 нодах (важно)

Настоящий Redis Cluster (`--cluster-enabled yes`) требует **минимум 3 мастер-ноды**:

- кластер держит 16384 hash-слота, и для автоматического failover нужен **кворум мастеров** (большинство). При 2 мастерах потеря любого из них оставляет 1 из 2 — кворума нет, кластер уходит в состояние `CLUSTERDOWN` и **не работает вовсе** (автоматическое переключение невозможно по определению).
- Redis Sentinel (автоматический failover для master/replica) — это ещё ≥3 отдельных процесса-сентинеля, т.е. снова кворум из 3+ нод.

Поэтому при 2 нодах единственная **корректная** топология — классический **master + replica** с асинхронной репликацией и **ручным** переключением. Это осознанное ограничение демо-стенда, а не ошибка конфигурации.

**Путь развития:** добавить 3-ю ноду (минимум) → включить `--cluster-enabled yes` на всех трёх → `redis-cli --cluster create` с распределением 16384 слотов → получить автоматический failover и шардирование. Приложению потребуется cluster-aware клиент (например, Lettuce/Jedis в режиме cluster) и редиректы `MOVED`/`ASK`.

## Команды (из корня репозитория)

```bash
# Старт
docker compose -f Docker/docker-compose.redis.yml up -d

# Статус (обе ноды должны быть healthy)
docker compose -f Docker/docker-compose.redis.yml ps

# Останов (данные в volumes сохраняются)
docker compose -f Docker/docker-compose.redis.yml down

# Полный сброс (удалить контейнеры И данные)
docker compose -f Docker/docker-compose.redis.yml down -v

# Логи
docker compose -f Docker/docker-compose.redis.yml logs -f redis-master redis-replica
```

## Как проверить репликацию

```bash
# На мастере: role:master, connected_slaves:1, slave0:...state=online
docker exec redis-master redis-cli -a redis-secret --no-auth-warning info replication

# На реплике: role:slave, master_link_status:up
docker exec redis-replica redis-cli -a redis-secret --no-auth-warning info replication

# Smoke-тест: запись в master -> чтение из replica
docker exec redis-master  redis-cli -a redis-secret --no-auth-warning set smoke:key "hello"
docker exec redis-replica redis-cli -a redis-secret --no-auth-warning get smoke:key   # -> hello
docker exec redis-master  redis-cli -a redis-secret --no-auth-warning del smoke:key
```

Репликация **асинхронная**: `get` на реплике сразу после `set` может вернуть старое значение/`nil` — подождите ~100–500 мс или повторите. Лаг виден в `info replication` (`lag=`, `offset=`).

Pub/sub (канал приложения, keyspace notifications НЕ нужны — монолит использует явный канал):

```bash
# В одном терминале — подписчик:
docker exec -it redis-master redis-cli subscribe pipeline:completed
# В другом — публикация (вернёт число подписчиков):
docker exec redis-master redis-cli publish pipeline:completed "correlation-id-123"
```

## Ручной failover (promote replica) и последствия

```bash
# 1. Превратить реплику в самостоятельный мастер:
docker exec redis-replica redis-cli -a redis-secret --no-auth-warning replicaof no one

# 2. (Опционально) переподчинить старый мастер новой роли — запись теперь в 6380:
docker exec redis-master redis-cli -a redis-secret --no-auth-warning replicaof redis-replica 6379
```

Последствия, о которых нужно помнить:

- **Автоматики нет.** Монолит настроен на `localhost:6379`; после promote приложение надо переключать на `localhost:6380` (или выполнить шаг 2, оставив точку записи на 6379 — но тогда роль мастера «переезжает» на контейнер реплики).
- **Потеря неотреплицированных записей.** Асинхронная репликация означает окно: записи, подтверждённые мастером, но не успевшие дойти до реплики (`master_repl_offset` > offset реплики), при promote **теряются**.
- **Риск split-brain.** Если старый мастер жив и продолжает принимать записи (шаг 2 не выполнен), две ноды расходятся; при обратном переподчинении данные «проигравшей» стороны затираются состоянием нового мастера.
- Возврат к обычной топологии: `docker exec redis-replica redis-cli -a redis-secret --no-auth-warning replicaof redis-master 6379` (реплика снова подчиняется мастеру; её данные будут синхронизированы/перезалиты с мастера).

## Политика maxmemory: `noeviction` — обоснование

Ключи `pipeline:result:{correlationId}` — **результаты пайплайна**: если Redis начнёт вытеснять ключи (allkeys-lru и т.п.), монолит получит «успешный» pipeline, но не найдёт результат, и данные потеряются **молча**. С `noeviction` при исчерпании `maxmemory` запись вернёт явную ошибку `OOM command not allowed...` — проблему видно сразу в логах приложения. Объём данных ограничен сам: у каждого результата TTL 60 c. `maxmemory` намеренно не задан (лимит — память контейнера/хоста); при желании задайте `--maxmemory` вместе с `noeviction`.

## Troubleshooting

| Симптом | Причина / лечение |
|---|---|
| Replica не подключается: `master_link_status:down`, в логах `Unable to AUTH to MASTER` | Неверный `masterauth`/`requirepass` — сверьте `REDIS_PASSWORD` в `Docker/.env`; после правки: `docker compose -f Docker/docker-compose.redis.yml up -d --force-recreate` |
| `Error starting userland proxy: ... bind: address already in use` (6379/6380) | Порт занят: `lsof -i :6379` / `lsof -i :6380`. Освободите процесс или поменяйте `REDIS_MASTER_PORT`/`REDIS_REPLICA_PORT` в `.env` |
| `MISCONF Redis is configured to save RDB snapshots, but it's currently unable to persist` | Redis не может писать на диск: место (`df -h`, `docker system df`) или права на volume. Лечение: освободить место; диагностика `docker exec redis-master redis-cli -a redis-secret --no-auth-warning config get dir`; временно снять блокировку записи `config set stop-writes-on-bgsave-error no` (не заменяет починку диска) |
| Контейнер `unhealthy`, в логах healthcheck пусто | Внутри контейнера `redis-cli ping` должен давать `PONG` без `-a` (используется `REDISCLI_AUTH`). Проверьте: `docker exec redis-master redis-cli ping`; детали: `docker inspect --format '{{json .State.Health}}' redis-master` |
| Replica отдаёт `nil` для свежего ключа | Асинхронная репликация — повторите через несколько сотен мс; лаг: `info replication` на мастере |
| Сеть не найдена: `network event-driven-net declared as external, but could not be found` | Сеть не создана: `docker network create event-driven-net` (контракт INFRA) |

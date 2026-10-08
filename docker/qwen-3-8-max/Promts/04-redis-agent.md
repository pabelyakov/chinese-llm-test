# ПРОМТ: Агент кластера Redis (REDIS)

## Роль

Ты — агент, отвечающий за Redis из **2 нод** в локальном docker compose.
Ты работаешь полностью автономно: пишешь манифест, запускаешь, диагностируешь,
исправляешь и повторяешь до здорового состояния.

Корень проекта — текущая директория. Твоя зона ответственности:
`Docker/docker-compose.redis.yml`, `Docker/redis/**`. Чужие файлы не правь.
Сеть `event-driven-net` и `Docker/.env` созданы INFRA-агентом; переменные:
REDIS_PASSWORD=redis-secret, REDIS_MASTER_PORT=6379, REDIS_REPLICA_PORT=6380.

## Важное архитектурное ограничение (обязательно отрази в документации)

Настоящий **Redis Cluster** (режим `--cluster-enabled yes`) требует минимум
**3 мастер-ноды** (для покрытия 16384 слотов и кворума). При заданных 2 нодах
корректная топология — **master + replica** (асинхронная репликация, ручное
переключение). Это осознанное решение: монолит всегда пишет/читает master
(порт 6379), replica (6380) — для чтения и отработки сценария восстановления.
Задокументируй это ограничение и путь развития (добавить 3-ю ноду → включить
cluster mode) в README.

## Технические требования

1. Сервисы: `redis-master` (хост-порт 6379) и `redis-replica` (хост-порт 6380).
   Образ `redis:7-alpine` (или новее стабильный 7.x — задокументируй).
2. Аутентификация: `requirepass` = `REDIS_PASSWORD` на обеих нодах;
   на реплике дополнительно `masterauth` = тот же пароль. Пароль бери из `.env`,
   не хардкодь в манифесте.
3. Репликация: replica запускается с `--replicaof redis-master 6379`
   (или `replicaof` в redis.conf — выбери и задокументируй подход);
   `appendonly yes` на мастере (durability), named volumes для данных.
4. Healthcheck: `redis-cli -a $REDIS_PASSWORD ping` (учти, что redis-cli ругается
   на пароль в CLI — используй `REDISCLI_AUTH` env или `--no-auth-warning`);
   `depends_on` replica от master с `condition: service_healthy`.
5. Настройки, полезные для пайплайна: `maxmemory-policy noeviction` на мастере
   (результаты пайплайна не должны вытесняться; обоснуй в README),
   keyspace notifications не обязательны — приложение использует pub/sub-канал
   `pipeline:completed` явно.

## Самодиагностика (обязательные проверки, приложи вывод)

- `docker compose -f Docker/docker-compose.redis.yml ps` — обе ноды healthy.
- `docker exec redis-master redis-cli -a "$REDIS_PASSWORD" --no-auth-warning info replication`
  → `role:master`, `connected_slaves:1`, slave в состоянии `online`.
- Запись/чтение через мастер с хоста:
  - `docker exec -i redis-master redis-cli -a "$REDIS_PASSWORD" --no-auth-warning set smoke:key "hello"`
  - `docker exec -i redis-replica redis-cli -a "$REDIS_PASSWORD" --no-auth-warning get smoke:key` → `hello`
    (дождись репликации; при необходимости повтори — зафиксируй факт асинхронности).
- Проверка pub/sub: в одном процессе подпишись (`redis-cli psubscribe 'pipeline:*'`),
  в другом опубликуй тестовое сообщение — приложи вывод (если сложно выполнить
  в один проход, опиши проверку в README и выполни упрощённый вариант).
- Удали smoke-ключ после проверок.
- Типовые ошибки (в README): replica не подключается → неверный masterauth/пароль;
  порт занят → `lsof -i :6379`; `MISCONF Redis is configured to save RDB snapshots`
  → права на volume / диск.

## Документация

- `Docker/redis/README.md`: топология (master/replica, порты), почему не Redis
  Cluster при 2 нодах, команды запуска/остановки/очистки, как проверить репликацию,
  как вручную сделать failover (promote replica: `docker exec redis-replica redis-cli
  -a ... replicaof no one` — и последствия), политика maxmemory, troubleshooting.
- inline-комментарии в compose-файле.

## Критерии готовности (проверь каждый командой)

- [ ] 2 ноды healthy; `info replication`: master + 1 online replica.
- [ ] Аутентификация по паролю работает на обеих нодах.
- [ ] Запись на master видна на replica (smoke-тест пройден, ключ удалён).
- [ ] `Docker/redis/README.md` написан, ограничение про Redis Cluster задокументировано.

## Формат отчёта оркестратору

```
СТАТУС: SUCCESS | FAILED
Файлы: <список>
Образ и версия Redis: <...>
Доказательства: <ps / info replication / set-get smoke-тест>
Endpoint'ы для монолита: master=localhost:6379, replica=localhost:6380, пароль в Docker/.env
Ограничения: master/replica вместо Redis Cluster (обоснование в README)
```

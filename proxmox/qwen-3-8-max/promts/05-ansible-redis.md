# AGENT 5 — Ansible: Redis primary/replica

=== SHARED CONTEXT (см. promts/shared-context.md) ===
ВХОД: inventory; common-роль уже применена Agent3. Зависит от common-роли и /etc/hosts.

РОЛЬ: Инженер Ansible (Redis).

ЦЕЛЬ: развернуть Redis на 237 (primary) и 238 (replica) с репликацией, паролем и персистентностью.

УЧТИ решение D2: на 2 узлах честный Redis Cluster (шардинг) не поднимается — делаем primary/replica
и явно документируем ограничение (для HA-фейловера нужен Sentinel и ≥3 узла).

## ЗАДАЧИ
1. РОЛЬ redis (группа redis):
   - Установить Redis 7.2/7.4 (официальный репозиторий redis или apt).
   - redis.conf: bind к IP узла, protected-mode yes, requirepass (gitignored/vault),
     maxmemory (например 512mb) + maxmemory-policy (noeviction или allkeys-lru — обосновать),
     appendonly yes (AOF), порт 6379.
   - Primary (237): обычная конфигурация источника.
   - Replica (238): `replicaof 192.168.1.237 6379`, masterauth = пароль, replica-read-only yes.
   - systemd redis-server (enable+start). Firewall: 6379 (16379 cluster-bus не нужен).
2. Записать в docs/ENDPOINTS.md: primary 237:6379, replica 238:6379, пароль (путь к секрету),
   TTL/политика для ключей correlationId (согласовать с Agent6, например TTL 60s).
3. ДИАГНОСТИКА: `redis-cli -h 192.168.1.237 -a <pass> ping` = PONG; `INFO replication` на primary
   показывает подключённую replica 238 (state=online); записать ключ на primary -> прочитать на
   replica (проверка репликации). При ошибках — читать /var/log/redis, journalctl, проверять
   bind/firewall/пароль/masterauth; исправлять.

## DELIVERABLES
roles/redis/*, плейбук site-redis.yml, group_vars/redis (секреты отдельно), обновлённый
docs/ENDPOINTS.md, README роли (переменные, запуск, health-check, ограничение D2, траблшутинг).

## ACCEPTANCE
- `ansible-playbook site-redis.yml` идемпотентен.
- redis-server active на обоих узлах.
- INFO replication: replica подключена и синхронизирована.
- SET на primary -> GET на replica работает; требуется пароль (без пароля — отказ).

## ПРАВИЛА
Автономно, идемпотентно, пароль не коммитить. Верни run-report: статус репликации, endpoints, путь к секрету.

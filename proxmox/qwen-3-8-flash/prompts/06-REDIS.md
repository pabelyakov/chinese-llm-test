# Промт 6 — Агент «Redis»

(запускать после блока CTX.md; составлен оркестратором из CTX + критериев 00/08 по согласованию с владельцем)

```text
РОЛЬ: Redis-инженер, работает через Ansible (роль redis в существующем каркасе Ansible/).
ЗАДАЧА: Redis 7.x CLUSTER-mode на двух VM redis-1 (192.168.1.237), redis-2 (192.168.1.238):
6 инстансов = 3 master + 3 replica, replica ВСЕГДА на другой VM относительно своего master
(см. архитектурное решение 2 в CTX — это настоящий cluster-mode, не репликация двух нод).

КОНТРАКТ (использует агент 7):
- порты инстансов: 6371/6372/6373 на каждой VM (cluster ports 6371–6373 + bus 16371–16373);
- cluster nodes: 192.168.1.237:6371,192.168.1.237:6372,192.168.1.237:6373,
  192.168.1.238:6371,192.168.1.238:6372,192.168.1.238:6373;
- requirepass+masterauth: общий пароль → Security/redis/credentials.env (600, gitignore, в отчёте «см. Security/»).

ШАГИ:
1. Ansible/roles/redis/ — структура вручную (tasks/handlers/defaults/vars/templates/meta/README.md).
2. redis-server 7.x из репозитория Debian bookworm; конфиг per-port (template, iterate 6371-6373):
   cluster-enabled yes, cluster-config-file nodes-<port>.conf, cluster-node-timeout 5000,
   dir /var/lib/redis/<port>, protected-mode yes, requirepass/masterauth (из vars_lookup файла Security/),
   appendonly yes, maxmemory 512mb, maxmemory-policy allkeys-lru, logfile/working dir под пользователем redis.
3. systemd template unit redis-cluster@<port>.service на обеих VM, enabled+started (6 инстансов).
4. Создание кластера ОДИН раз, идемпотентно: если на любом порту CLUSTER INFO уже cluster_state:ok — не трогать;
   иначе redis-cli --cluster create <6 endpoints> --cluster-replicas 1, порядок endpoints задать так,
   чтобы каждая replica досталась VM, отличной от VM своего master (проверить выводом распределения).
5. site.yml: для группы [redis] roles: [common, redis]; README роли + обновление Ansible/README.md.

ACCEPTANCE (фактические выводы команд):
- redis-cli --cluster check 192.168.1.237:6371 → cluster_state:ok, slots 16384/16384, 3 master + 3 replica;
- redis-cli -c -a <pass> SET k1 v1; GET k1 → round-trip через cluster client;
- CLUSTER SHARDS/INFO → совпадает mapping master/replica по VM (вывод в отчёт);
- все 6 инстансов systemd active; swap off (проверка роли common не сломана);
- пароль только в Security/; повторный site.yml — без критичных изменений.
```

# Промт 6 — Роль Ansible: Redis master + replica + Sentinel (2 ноды)

```text
Ты – DevOps-инженер. Рабочая директория: /Users/pabelyakov/Projects/llm-test/z-ai/ansible. Базовая настройка выполнена. Инвентарь: группа [redis_master] redis-1=192.168.1.237, [redis_replica] redis-2=192.168.1.238.

ЗАДАЧА: написать роль roles/redis и развернуть Redis 7.x: master на redis-1, replica на redis-2, Sentinel на обеих нодах.

АРХИТЕКТУРНОЕ ОГРАНИЧЕНИЕ (зафиксируй в README): с 2 нодами Sentinel-quorum=2 защищает от отказа реплики, но при split-brain не даёт полной гарантии. Для стенда допустимо; production-рекомендация (3 sentinel) описана в README.

1. Установка Redis 7.x из официального репозитория packages.redis.io (apt). Версию зафиксируй в vars.
2. redis.conf (template, параметры по группам):
   - master: bind 0.0.0.0, port 6379, requirepass <пароль>, masterauth <пароль> (для будущего failover), appendonly yes, maxmemory 512mb, maxmemory-policy allkeys-lru
   - replica: то же + replicaof 192.168.1.237 6379
   - Пароль: сгенерируй, положи в ansible-vault (или inventory с no_log), выведи в отчёте — он нужен монолиту.
   - protected-mode yes + пароль; UFW-порты 6379 и 26379 уже открыты.
3. Sentinel: sentinel.conf на ОБЕИХ нодах: sentinel monitor mymaster 192.168.1.237 6379 2, sentinel auth-pass, down-after-milliseconds 5000, failover-timeout 10000. Отдельный systemd-юнит redis-sentinel.
4. Разверни: master → replica → sentinels. Системные юниты: redis-server, redis-sentinel, both enabled.
5. ВЕРИФИКАЦИЯ:
   - redis-cli -h 192.168.1.237 -a <pass> INFO replication → role:master, connected_slaves:1.
   - SET/GET roundtrip через master, затем GET через replica (READONLY ок).
   - Тест failover: redis-cli -h redis-2 -p 26379 SENTINEL failover mymaster → после него role на нодах должны поменяться местами; задокументируй результат, затем верни master обратно (ещё один failover).
6. Документация roles/redis/README.md: топология, порты, команды диагностики (INFO replication, SENTINEL masters, SENTINEL get-master-addr-by-name), failover-инструкция, оговорка о quorum=2 и production-схеме.

При отказах — логи /var/log/redis/, journalctl. Диагностируй и чини сам.

КРИТЕРИИ ПРИЁМКИ: replication healthy (1 replica connected), roundtrip успешен, управляемый failover отработал в обе стороны, systemd-сервисы активны.
Отчитайся: INFO replication summary, пароль, результат failover-теста, текущий master.
```

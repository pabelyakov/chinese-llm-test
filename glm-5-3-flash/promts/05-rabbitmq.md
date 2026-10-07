# Промт 5 — Роль Ansible: кластер RabbitMQ (3 ноды)

```text
Ты – DevOps-инженер. Рабочая директория: /Users/pabelyakov/Projects/llm-test/z-ai/ansible. Базовая настройка выполнена (роль common). Инвентарь: inventory/hosts.ini, группа [rabbitmq] = rabbitmq-1..3 → 192.168.1.233-235. Порт 22 и служебные порты группы открыты в UFW.

ЗАДАЧА: написать роль roles/rabbitmq и развернуть классический кластер RabbitMQ из 3 нод.

1. Установка: официальный репозиторий RabbitMQ (team.rabbitmq.com script) либо пакетный из Ubuntu 22.04 — выбери версию не ниже 3.10 с совместимым Erlang/OTP 25+; зафиксируй версии в vars/defaults.
2. Erlang cookie: один общий cookie (генерируй на rabbitmq-1 через vault/vars, кладись на все ноды) в /var/lib/rabbitmq/.erlang.cookie, владелец rabbitmq:rabbitmq, права 400. Одинаковый на всех нодах — обязательно.
3. /etc/rabbitmq/rabbitmq.conf (template):
   - listeners.tcp.default = 5672, management.tcp.port = 15672
   - cluster_formation.peer_discovery_backend = classic_config
   - cluster_formation.classic_config.nodes.1 = rabbit@rabbitmq-1 (и .2, .3 — имена по hostname)
   - vm_memory_high_watermark.relative = 0.6 (память ограничена 2 GB)
   - disk_free_limit.absolute = 1GB
   - default_user/default_pass: сгенерируй администратора appuser/<случайный пароль>, зафиксируй в ansible-vault (или в inventory с no_log) — пароль понадобится монолиту, выведи его в отчёте.
4. /etc/rabbitmq/rabbitmq-env.conf: NODENAME=rabbit@<hostname>, MANAGEMENT-плагин + rabbitmq_management включи через enabled_plugins (management).
5. Хостнеймы: убедись, что ноды резолвят имена друг друга через /etc/hosts (должно быть готово после common — проверь).
6. Разверни: rabbitmq-1 стартует, затем 2 и 3 (cookie до первого старта!). rabbitmqctl join_cluster rabbit@rabbitmq-1 (node 2,3), стартуй app.
7. Создай пользователя appuser с тегами administrator, права на vhost /, удали/запри guest.
8. ВЕРИФИКАЦИЯ:
   - rabbitmqctl cluster_status на каждой ноде: 3 running nodes, одинаковый partition handling.
   - Создай очередь pipeline.rabbit (classic, ha не обязателен для стенда, но задокументируй quorum-альтернативу).
   - Roundtrip: rabbitmqadmin publish → get (или через python/pika) с appuser.
   - Проверь web-UI: curl -u appuser http://192.168.1.233:15672/api/overview → 200.
9. Документация roles/rabbitmq/README.md: топология кластера, порты, команды (cluster_status, list_queues, healthcheck), troubleshooting (cookie mismatch, partition).

При отказах — journalctl -u rabbitmq-server, диагностируй и чинить сам. Перезапуск нод — поочерёдный.

КРИТЕРИИ ПРИЁМКИ: cluster_status показывает 3 ноды, roundtrip-тест успешен, appuser работает в management API.
Отчитайся: cluster_status summary, имя/пароль appuser (для конфигурации монолита), результат roundtrip.
```

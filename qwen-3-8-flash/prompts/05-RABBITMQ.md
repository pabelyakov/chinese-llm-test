# Промт 5 — Агент «RabbitMQ»

(запускать после блока CTX.md; составлен оркестратором из CTX + критериев 00/08 по согласованию с владельцем)

```text
РОЛЬ: RabbitMQ-инженер, работает через Ansible (роль rabbitmq в существующем каркасе Ansible/).
ЗАДАЧА: развернуть кластер RabbitMQ 3.13.x на VM rabbit-1/rabbit-2/rabbit-3 (192.168.1.233–.235),
кворум-очередь конвейера, сервисный пользователь; подтвердить publish/consume.

КОНТРАКТ (зафиксирован, далее используют агенты 6–7):
- очередь: pipeline.enriched, durable, x-queue-type=quorum, vhost /;
- AMQP 5672, management 15672; сервисный пользователь pipeline (пароль — см. Security/, не хардкодить).

ШАГИ:
1. Ansible/roles/rabbitmq/ — структура вручную (tasks/handlers/defaults/vars/meta/templates/files/README.md).
2. Пакеты: официальные репозитории Erlang + RabbitMQ (deb.packages.cloudsmith.io / pkg rabbitmq) для Debian 12;
   версия 3.13.x.
3. Erlang cookie: сгенерировать ОДИН раз, хранить Security/rabbitmq/erlang.cookie (права 400, в .gitignore),
   раскатать на /var/lib/rabbitmq/.erlang.cookie всех узлов (до старта/перезапуск).
4. Кластеризация: rabbit-1 — диск; rabbit-2, rabbit-3 → stop-app + reset + join_cluster к rabbit-1 + start-app;
   идемпотентность: перед join проверять cluster_status (уже в кластере — пропустить).
5. Плагин rabbitmq_management на всех узлах; limits: memory_high_watermark 0.6; systemd enabled+started.
6. Пользователь pipeline с сгенерированным паролем → Security/rabbitmq/credentials.env (600, gitignore, в отчёте
   писать «см. Security/»); полный доступ на vhost /; guest-доступ с сети запретить.
7. Очередь pipeline.enriched (quorum, durable) — декларация через definition policy или rabbitmqadmin
   (management API, localhost) — идемпотентно.
8. site.yml: для группы [rabbitmq] roles: [common, rabbitmq]; README роли + обновление Ansible/README.md.

ACCEPTANCE (фактические выводы команд по ssh ops@.233…):
- rabbitmqctl cluster_status → 3 узла в running nodes;
- rabbitmqctl list_queues name type → pipeline.enriched, quorum;
- list_queues name slave_pids / management API → реплики очереди на всех 3 узлах;
- publish/consume через amqp localhost с пользователем pipeline: {"probe":1} → получено;
- credentials в Security/rabbitmq/ с корректными правами; в Ansible/ токена/пароля нет (grep);
- повторный site.yml — без критичных изменений.
```

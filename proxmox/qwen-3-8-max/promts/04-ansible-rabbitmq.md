# AGENT 4 — Ansible: RabbitMQ cluster

=== SHARED CONTEXT (см. promts/shared-context.md) ===
ВХОД: inventory; common-роль уже применена Agent3. Зависит от общей common-роли и /etc/hosts.

РОЛЬ: Инженер Ansible (RabbitMQ).

ЦЕЛЬ: развернуть 3-узловой кластер RabbitMQ (233-235) с quorum-очередями, management UI,
vhost и пользователем для приложения.

## ЗАДАЧИ
1. РОЛЬ rabbitmq (группа rabbitmq):
   - Установить Erlang 26 и RabbitMQ 3.13.x из официальных репозиториев (rabbitmq.com/Cloudsmith).
   - Единый erlang cookie на всех узлах (сгенерировать, хранить в gitignored/vault).
   - nodename rabbit@<hostname>; гарантировать резолвинг имён через /etc/hosts (кластеризация
     чувствительна к DNS/именам).
   - Скластеризовать: на 234/235 -> stop_app, join_cluster к 233, start_app (идемпотентно:
     не джойнить уже входящие узлы). Порядок старта через systemd (зависимости).
   - management-плагин (15672).
   - Политика HA/quorum: quorum queues по умолчанию (или ha-mode all), зафиксировать.
   - Тюнинг под 2 GB: vm_memory_high_watermark (например 0.4), disk_free_limit.
   - Firewall: 4369, 5672, 15672, 25672 между узлами.
   - Создать vhost (например `/pipeline`), пользователя app + пароль (gitignored/vault), права.
   - Зафиксировать exchange/queue/routing key для пайплайна (согласовать с Agent6, например
     exchange `enrich`, queue `enrich-redis`, routing key `enrich`).
2. Записать в docs/ENDPOINTS.md: хосты 233-235:5672, vhost, user, management 15672,
   имя exchange/queue/routing key. Секреты — отдельным gitignored-файлом/vault, в ENDPOINTS только путь.
3. ДИАГНОСТИКА: `rabbitmqctl cluster_status` (все 3 узла running), `rabbitmq-diagnostics status`,
   проверка quorum/management UI (curl), тест publish/consume через rabbitmqadmin или CLI.
   При ошибках — читать /var/log/rabbitmq, journalctl, проверять cookie/hosts/порты; исправлять.

## DELIVERABLES
roles/rabbitmq/*, плейбук site-rabbitmq.yml, group_vars/rabbitmq (секреты отдельно),
обновлённый docs/ENDPOINTS.md, README роли (переменные, запуск, health-check, траблшутинг).

## ACCEPTANCE
- `ansible-playbook site-rabbitmq.yml` идемпотентен.
- cluster_status показывает 3 узла, все running.
- vhost и app-пользователь созданы, права выданы; management UI отвечает.
- publish/consume smoke-тест проходит.

## ПРАВИЛА
Автономно, идемпотентно, cookie/пароли не коммитить. Верни run-report: статус кластера,
endpoints, путь к файлу с credentials.

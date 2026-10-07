# AGENT 3 — Ansible: common-роль + Kafka KRaft

=== SHARED CONTEXT (см. promts/shared-context.md) ===
ВХОД ОТ Agent2: Ansible/inventory/hosts.yml, group_vars/all.yml, доступ по SSH (Security/id_ed25519).

РОЛЬ: Инженер Ansible (общая база + Kafka KRaft).

ЦЕЛЬ: создать переиспользуемую common-роль для ВСЕХ узлов и развернуть 3-узловой Kafka
KRaft-кластер (230-232), создать топик для пайплайна.

## ЗАДАЧИ
1. Инфраструктура Ansible: ansible.cfg (inventory, private_key_file, host_key_checking по политике),
   требования (collections). Проверить связь: `ansible all -m ping`.
2. РОЛЬ common (применяется ко ВСЕМ узлам — её используют и Agent 4/5): базовые пакеты,
   chrony (включить/синхронизировать), /etc/hosts со всеми узлами по IPAM, hostname,
   отключить swap (или настроить), базовые sysctl/ulimit для Kafka, firewall (ufw) — открыть
   только нужные порты между узлами, SSH-харднинг.
3. РОЛЬ kafka_kraft (группа kafka):
   - Установить Java (OpenJDK 17/21) и Kafka 3.8.x (из официального архива/tarball).
   - Режим combined (controller+broker), node.id = 1/2/3 по IP (230->1, 231->2, 232->3).
   - Сгенерировать cluster UUID, `kafka-storage.sh format` (идемпотентно: не форматировать
     уже отформатированный log.dirs).
   - server.properties: controller.quorum.voters=1@230:9093,2@231:9093,3@232:9093;
     listeners=PLAINTEXT://:9092,CONTROLLER://:9093; advertised.listeners=PLAINTEXT://<ip>:9092;
     offsets.topic.replication.factor=3, transaction.state.log.replication.factor=3,
     default.replication.factor=3, min.insync.replicas=2, log.dirs.
   - Тюнинг под 2 GB: KAFKA_HEAP_OPTS=-Xmx768M -Xms256M (обосновать), оставить память под page cache.
   - systemd-юнит kafka.service (enable+start, авто-рестарт).
   - Firewall: 9092, 9093 между узлами.
   - Создать топик пайплайна (например `pipeline-in`, RF=3, partitions=3) через kafka-topics.sh.
4. ДИАГНОСТИКА: journalctl -u kafka, проверка `kafka-topics.sh --bootstrap-server 192.168.1.230:9092
   --list` и `--describe`, проверка quorum (логи контроллера), тест produce/consume. При ошибках —
   читать логи, проверять формат storage, voters, порты, время; исправлять, перезапускать.

## DELIVERABLES
Ansible/ansible.cfg, roles/common/*, roles/kafka_kraft/*, плейбук site-kafka.yml, group_vars/kafka;
фиксация endpoints в docs/ENDPOINTS.md (bootstrap-servers=230:9092,231:9092,232:9092, имя топика);
README для каждой роли (назначение, переменные, запуск, проверка здоровья, траблшутинг).

## ACCEPTANCE
- `ansible-playbook site-kafka.yml` проходит идемпотентно (2-й запуск — 0 критичных changed).
- kafka.service active на всех 3 узлах; кластер видит кворум контроллеров.
- Топик существует с RF=3 (показать `--describe`).
- Produce/consume smoke-тест проходит.
- common применена (ping всех узлов проходит).

## ПРАВИЛА
Автономно, идемпотентно, секреты не коммитить. Верни run-report: состояние кластера,
bootstrap-servers, имя топика, подтверждение что common-роль применена ко всем узлам.

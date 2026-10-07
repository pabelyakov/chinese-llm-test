# Промт 4 — Агент «Kafka»

(запускать после блока CTX.md; составлен оркестратором из CTX + критериев 00/08 по согласованию с владельцем)

```text
РОЛЬ: Kafka-инженер, работает через Ansible (роль kafka в существующем каркасе Ansible/).
ЗАДАЧА: развернуть кластер Kafka 3.8.x KRaft (combined broker+controller режим) на VM
kafka-1/kafka-2/kafka-3 (192.168.1.230–.232); создать топик конвейера; подтвердить produce/consume.

КОНТРАКТ (зафиксирован, далее используют агенты 5–7):
- брокеры слушают PLAINTEXT на 9092, контроллеры на 9093; bootstrap = 192.168.1.230:9092,192.168.1.231:9092,192.168.1.232:9092;
- топик конвейера: pipeline.raw, 3 партиции, RF=3, min.insync.replicas=2.

ШАГИ:
1. Ansible/roles/kafka/ — структура вручную (tasks/handlers/defaults/vars/meta/templates/README.md).
2. Роль: OpenJDK 21 (apt openjdk-21-jre-headless); Kafka 3.8.x tgz с archive.apache.org (например kafka_2.13-3.8.1)
   в /opt/kafka, выделенный пользователь kafka, каталог данных /var/lib/kafka/data.
3. templates/server.properties per-host:
   process.roles=broker,controller; node.id=1|2|3 (по порядковому индексу в группе kafka);
   controller.quorum.voters=1@192.168.1.230:9093,2@192.168.1.231:9093,3@192.168.1.232:9093;
   listeners=PLAINTEXT://0.0.0.0:9092,CONTROLLER://0.0.0.0:9093; advertised.listeners=PLAINTEXT://<IP_хоста>:9092;
   inter.broker.listener.name=PLAINTEXT; controller.listener.names=CONTROLLER;
   default.replication.factor=3; min.insync.replicas=2; offsets.topic/transaction.state.log RF=3;
   auto.create.topics.enable=false; num.partitions=3; log.retention.hours=168.
4. Инициализация_storage.idempotently: cluster.id сгенерировать/сохранить один раз (persist локально в Ansible/),
   kafka-storage.sh format выполнять только если meta.properties отсутствует; systemd unit kafka.service
   (User=kafka, Restart=on-failure, After=network-online.target), enabled+started; rolling-перезапуск по одному узлу.
5. Создать топик pipeline.raw (3p/RF3) с bootstrap .230:9092 — идемпотентно (exists-проверка).
6. Подключить роль в site.yml: для группы [kafka] roles: [common, kafka] (не ломать идемпотентность полного прогона).
7. Обновить Ansible/README.md (новая роль) + README роли по стандарту
   (назначение / prerequisites / запуск / верификация / troubleshooting: cluster.id, quorum voters, KRaft миграция).

ACCEPTANCE (в отчёте — фактические выводы команд, выполнить с control host или по ssh ops@<IP>):
- kafka-broker-api-versions --bootstrap-server 192.168.1.230:9092 → перечислены 3 брокера;
- kafka-metadata.sh --snapshot ... / describe cluster → 3 узла, KRaft, контроллер назначен;
- kafka-topics.sh --describe --topic pipeline.raw → PartitionCount 3, ReplicationFactor 3;
- echo-тест: echo "ping-<ts>" | kafka-console-producer --topic pipeline.raw;
  kafka-console-consumer --from-beginning --timeout-ms 10000 → строка получена;
- systemctl is-active kafka → active на всех трёх;
- повторный ansible-playbook site.yml → без критичных изменений.
```

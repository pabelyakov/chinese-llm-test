# Промт 4 — Роль Ansible: кластер Apache Kafka (KRaft, 3 ноды)

```text
Ты – DevOps-инженер. Рабочая директория: /Users/pabelyakov/Projects/llm-test/z-ai/ansible. Базовая настройка хостов выполнена (роль common). Инвентарь: inventory/hosts.ini, группа [kafka] = 192.168.1.230-232, node_id 1/2/3.

ЗАДАЧА: написать роль roles/kafka и развернуть кластер Kafka 3.7.x в KRaft-режиме (combined mode: broker+controller, БЕЗ ZooKeeper).

ОГРАНИЧЕНИЯ: 2 GB RAM на VM → heap строго 1 GB (KAFKA_HEAP_OPTS="-Xms1g -Xmx1g").

1. Скачивание: официальные дистрибутивы с downloads.apache.org / archive.apache.org (kafka_2.13-3.7.x, версия в vars с возможностью переопределения). Проверка sha512. Установка в /opt/kafka (symlink), владелец — системный пользователь kafka.
2. Конфиг /opt/kafka/config/server.properties (template с переменными):
   - process.roles=broker,controller
   - node.id={{ node_id }} (1,2,3)
   - controller.quorum.voters=1@192.168.1.230:9093,2@192.168.1.231:9093,3@192.168.1.232:9093
   - listeners=PLAINTEXT://0.0.0.0:9092,CONTROLLER://0.0.0.0:9093
   - advertised.listeners=PLAINTEXT://<ip ноды>:9092
   - listener.security.protocol.map=CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT
   - inter.broker.listener.name=PLAINTEXT, controller.listener.names=CONTROLLER
   - log.dirs=/var/lib/kafka, num.partitions=3, default.replication.factor=3, min.insync.replicas=2, offsets.topic.replication.factor=3, transaction.state.log.replication.factor=3, transaction.state.log.min.isr=2
   - auto.create.topics.enable=true (для стенда)
3. Java: OpenJDK 17 (apt, temurin не обязателен).
4. Systemd-юнит kafka.service (Type=simple, User=kafka, EnvironmentFile с KAFKA_HEAP_OPTS, Restart=on-failure, лимит nofile=65536).
5. Формат storage: kafka-storage.sh format -t <генерируй uuid один раз через kafka-storage.sh random-uuid на контроллере и клади в var> --cluster-id --config server.properties — на КАЖДОЙ ноде, идемпотентно (пропускать если meta.properties существует).
6. Разверни на всех 3 нодах (по очереди: kafka-1 → дождись лидера → остальные), включи и запусти сервис.
7. ВЕРИФИКАЦИЯ:
   - kafka-broker-api-versions / kafka-metadata-quorum describe: leader + 3 синхронизированных реплики.
   - Создай топик app-events (3 партиции, RF=3), докажи describe показывает Isr=1,2,3.
   - Консольно отправь сообщение и прочитай его (kafka-console-producer/consumer) — roundtrip успешен.
8. Документация roles/kafka/README.md: схема KRaft-кворума, параметры, команды диагностики (quorum describe, list/describe topics, consume), как добавить ноду, troubleshooting (типовые ошибки: несовпадение cluster-id, advertised.listeners).

При отказах — смотри journalctl -u kafka, чини сам. ВАЖНО: перезапуск кластера = поочерёдный, не одновременный.

КРИТЕРИИ ПРИЁМКИ: 3 брокера в кворуме, roundtrip-тест сообщений успешен, systemd-сервисы активны.
Отчитайся: вывод describe quorum/topic, результат produce/consume-теста.
```

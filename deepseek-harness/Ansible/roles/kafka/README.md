# Ansible role: kafka

Разворачивает кластер **Apache Kafka 3.x в режиме KRaft** (без ZooKeeper) из 3 брокеров
на Ubuntu 22.04, каждому брокеру назначается роль `broker,controller` (комбинированный режим).

## Что делает роль

- Устанавливает OpenJDK 17 (headless).
- Скачивает Apache Kafka (по умолчанию `3.9.1`, бинарник для Scala 2.13) с проверкой SHA-512,
  кэширует архив на управляющей машине и копирует его на хосты.
- Создаёт системного пользователя/группу `kafka`.
- Генерирует `server.properties` из шаблона:
  - уникальный `node.id` каждой ноды (маппинг `kafka_nodes`);
  - `controller.quorum.voters` для всех трёх нод;
  - `listeners` / `advertised.listeners` на статический IP ноды
    (`PLAINTEXT` на 9092, `CONTROLLER` на 9093).
- Форматирует KRaft-хранилище (`kafka-storage.sh format`) **только при первой настройке**
  (проверяется наличие `meta.properties`, дополнительно передаётся `--ignore-formatted`).
- Устанавливает systemd-юнит `kafka.service` и включает автозапуск.

## Переменные

Все значения по умолчанию находятся в `defaults/main.yml`. Основные:

| Переменная | По умолчанию | Описание |
|---|---|---|
| `kafka_version` | `3.9.1` | Версия Kafka |
| `kafka_scala_version` | `2.13` | Вариант сборки Scala |
| `kafka_user` / `kafka_group` | `kafka` | Системный пользователь/группа |
| `kafka_home` | `/opt/kafka` | Симлинк на каталог установки |
| `kafka_data_dir` | `/var/lib/kafka` | Каталог данных (`log.dirs`) |
| `kafka_log_dir` | `/var/log/kafka` | Каталог логов сервера |
| `kafka_config_dir` | `/etc/kafka` | Каталог конфигурации |
| `kafka_port` | `9092` | Порт брокера (PLAINTEXT) |
| `kafka_controller_port` | `9093` | Порт контроллера (KRaft) |
| `kafka_cluster_id` | (UUID) | Единый cluster ID на кластер |
| `kafka_heap_opts` | `-Xmx512M -Xms512M` | Heap брокера (VM ~2 GB RAM) |
| `kafka_nodes` | `kafka-1:1, kafka-2:2, kafka-3:3` | Маппинг `hostname -> node.id` |
| `kafka_controller_quorum_fetch_timeout_ms` | `30000` | Таймаут fetch между контроллерами |
| `kafka_controller_quorum_election_timeout_ms` | `30000` | Таймаут выборов лидера кворума |
| `kafka_controller_quorum_election_backoff_max_ms` | `15000` | Макс. backoff между выборами |
| `kafka_controller_quorum_append_linger_ms` | `50` | Батчинг записей в metadata-лог |
| `kafka_broker_session_timeout_ms` | `30000` | Таймаут сессии брокера |
| `kafka_broker_heartbeat_interval_ms` | `10000` | Интервал heartbeat брокера |
| `kafka_replica_lag_time_max_ms` | `60000` | Допустимый лаг реплики в ISR |

`kafka_cluster_id` — это не секрет (хранится в открытом виде в `meta.properties`),
но он должен быть **одинаковым на всех нодах кластера** и не меняться между прогонами.
Если нужно пересоздать кластер — смените `kafka_cluster_id` и удалите `kafka_data_dir` на всех нодах.

> **Примечание про хранение:** таймауты KRaft-кворума и лага реплик подняты по умолчанию,
> т.к. тестовые VM используют медленное виртуализованное хранилище (fsync ~0.5–5 с).
> На нормальном диске их можно вернуть к значениям Kafka по умолчанию
> (`fetch.timeout.ms=2000`, `election.timeout.ms=1000`, `replica.lag.time.max.ms=30000`).

## Запуск

```bash
cd Ansible
ansible-playbook -i inventory --syntax-check playbooks/kafka.yml   # проверка синтаксиса
ansible-playbook -i inventory playbooks/kafka.yml                   # применить роль
```

Роль идемпотентна: повторный прогон не переформатирует хранилище и не перезапускает
брокеров без изменения конфигурации.

## Проверка состояния кластера

Адрес для клиентов (bootstrap servers):

```
192.168.1.230:9092,192.168.1.231:9092,192.168.1.232:9092
```

Примеры диагностики (выполняются на любой ноде кластера):

```bash
# список брокеров и их версий
sudo -u kafka /opt/kafka/bin/kafka-broker-api-versions.sh --bootstrap-server localhost:9092

# статус KRaft-кворума
sudo -u kafka /opt/kafka/bin/kafka-metadata-quorum.sh --bootstrap-server localhost:9092 describe --status

# создать топик с репликацией 3
sudo -u kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 \
  --create --topic test --partitions 3 --replication-factor 3

# проверить, что все 3 брокера в ISR
sudo -u kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 \
  --describe --topic test

# отправить сообщение
echo 'hello kafka' | sudo -u kafka /opt/kafka/bin/kafka-console-producer.sh \
  --bootstrap-server localhost:9092 --topic test

# прочитать сообщение
sudo -u kafka /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 --topic test --from-beginning --max-messages 1
```

Сервис: `sudo systemctl status kafka` / `sudo journalctl -u kafka -f`.

## Добавление / удаление ноды

**Добавить ноду:**
1. Добавить хост в группу `[kafka]` в `Ansible/inventory`.
2. Добавить маппинг `hostname: node.id` в `kafka_nodes` (`defaults/main.yml`) с новым
   уникальным `node.id`.
3. Запустить `ansible-playbook -i inventory playbooks/kafka.yml`.
   Новая нода отформатирует своё хранилище тем же `kafka_cluster_id` и присоединится к кворуму.

**Удалить ноду:** убрать хост из инвентаря и запустить плейбук, затем удалить ноду из
кворума контроллеров при необходимости (см. документацию Kafka по
`kafka-metadata-quorum.sh` / dynamic quorum membership). Важно поддерживать большинство
контроллеров (quorum majority): для 3 нод минимум 2 контроллера должны быть доступны.

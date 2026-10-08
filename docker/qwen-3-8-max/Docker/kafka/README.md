# Kafka — кластер из 3 нод (KRaft, без ZooKeeper)

Compose-манифест: **`Docker/docker-compose.kafka.yml`**
Образ: **`apache/kafka:3.9.2`** (официальный образ Apache Kafka, multi-arch: `linux/arm64` + `linux/amd64`)

Контракт для монолита (запускается на хосте):

```
bootstrap.servers=localhost:9092,localhost:9093,localhost:9094
топик: pipeline.inbound   (partitions=3, replication-factor=3, min.insync.replicas=2)
```

---

## 1. Архитектура

### 1.1 KRaft combined mode

Три ноды, каждая выполняет **обе роли**: `process.roles=broker,controller`.
ZooKeeper отсутствует. Метаданные кластера хранятся во внутреннем топике
`__cluster_metadata` и реплицируются кворумом контроллеров (Raft):

| Нода     | container_name | node.id | Роль              | Каталог данных (volume)        |
|----------|----------------|---------|-------------------|--------------------------------|
| `kafka1` | kafka1         | 1       | broker+controller | `event-driven-kafka1-data`     |
| `kafka2` | kafka2         | 2       | broker+controller | `event-driven-kafka2-data`     |
| `kafka3` | kafka3         | 3       | broker+controller | `event-driven-kafka3-data`     |

Кворум: `controller.quorum.voters=1@kafka1:9093,2@kafka2:9093,3@kafka3:9093`.
Для работы кластера нужны **минимум 2 из 3** нод — остановка одной не прерывает
приём/выдачу сообщений (проверено, см. раздел 7).

`cluster.id` — **один и тот же** для всех трёх нод, фиксирован в манифесте:

```
CLUSTER_ID=c3qzPlyMQWSBOsHSOzuf-g     # base64 UUID, сгенерирован kafka-storage.sh random-uuid
```

Образ `apache/kafka` сам форматирует хранилище этим `cluster.id` при первом
старте (`KafkaDockerWrapper setup`), поэтому ручной `kafka-storage.sh format` не нужен.

### 1.2 Listener'ы и порты

У каждой ноды **три** listener'а. Внутри контейнеров порты одинаковы у всех нод
(контейнеры изолированы), наружу проброшен только `PLAINTEXT_HOST`:

| Listener       | Порт в контейнере | Кто использует                          | Проброшен на хост?          |
|----------------|-------------------|-----------------------------------------|-----------------------------|
| `CONTROLLER`   | 9093              | Raft-кворум контроллеров                | нет (только `event-driven-net`) |
| `PLAINTEXT`    | 29092             | межброкерная репликация + клиенты-контейнеры | нет                     |
| `PLAINTEXT_HOST` | 9092            | клиент на хосте (монолит, QA-скрипты)   | **да**                      |

Маппинг host-портов (значения берутся из `Docker/.env`):

```
localhost:9092 -> kafka1:9092 (PLAINTEXT_HOST)   advertised: PLAINTEXT_HOST://localhost:9092
localhost:9093 -> kafka2:9092 (PLAINTEXT_HOST)   advertised: PLAINTEXT_HOST://localhost:9093
localhost:9094 -> kafka3:9092 (PLAINTEXT_HOST)   advertised: PLAINTEXT_HOST://localhost:9094
```

> **Важно:** host-порт 9093 (kafka2) и контроллер-порт 9093 — это РАЗНЫЕ вещи.
> Контроллер-порт 9093 существует только внутри сети контейнеров и наружу не
> публикуется, поэтому пересечения нет.

advertised.listeners каждой ноды:

```
kafka1: PLAINTEXT://kafka1:29092,PLAINTEXT_HOST://localhost:9092
kafka2: PLAINTEXT://kafka2:29092,PLAINTEXT_HOST://localhost:9093
kafka3: PLAINTEXT://kafka3:29092,PLAINTEXT_HOST://localhost:9094
```

`inter.broker.listener.name=PLAINTEXT`, `controller.listener.names=CONTROLLER`,
`listener.security.protocol.map=CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT,PLAINTEXT_HOST:PLAINTEXT`.

### 1.3 Конфигурация репликации

| Параметр                                   | Значение | Зачем                                       |
|--------------------------------------------|----------|---------------------------------------------|
| `offsets.topic.replication.factor`         | 3        | оффсеты групп живут на всех брокерах         |
| `transaction.state.log.replication.factor` | 3        | топик транзакций — 3 реплики                 |
| `transaction.state.log.min.isr`            | 2        | запись транзакций при 2 живых репликах       |
| `min.insync.replicas`                      | 2        | `acks=all` проходит при потере одной ноды    |
| `default.replication.factor`               | 3        | новые топики по умолчанию RF=3               |
| `num.partitions`                           | 3        | новые топики по умолчанию на 3 партиции      |

### 1.4 Порядок старта и healthcheck

Healthcheck каждой ноды (реальный ответ брокера, а не «процесс жив»):

```
/opt/kafka/bin/kafka-broker-api-versions.sh --bootstrap-server localhost:29092
interval=10s timeout=20s retries=30 start_period=30s
```

`depends_on` настроен так, чтобы **не создать взаимную блокировку**: одна нода
не может собрать кворум из трёх, поэтому `kafka1`/`kafka2` стартуют параллельно
(`service_started`), а `kafka3` ждёт `condition: service_healthy` от обеих —
к этому моменту кворум уже сформирован.

---

## 2. Запуск / остановка / полная очистка

Все команды — **из корня проекта**. `Docker/.env` подхватывается автоматически
(project directory = каталог compose-файла).

```bash
# Общая сеть должна существовать (создаёт INFRA-агент):
docker network create event-driven-net 2>/dev/null || true

# Поднять кластер
docker compose -f Docker/docker-compose.kafka.yml up -d

# Статус (только ноды kafka — в проекте event-driven есть и чужие контейнеры)
docker compose -f Docker/docker-compose.kafka.yml ps kafka1 kafka2 kafka3

# Пересоздать после правки манифеста
docker compose -f Docker/docker-compose.kafka.yml up -d --force-recreate

# Остановить (данные в volumes СОХРАНЯЮТСЯ)
docker compose -f Docker/docker-compose.kafka.yml down

# ПОЛНАЯ очистка: контейнеры + volumes с сегментами и метаданными KRaft
docker compose -f Docker/docker-compose.kafka.yml down -v
```

> **Не используйте `--remove-orphans`**: compose-проект `event-driven` общий для
> всех кластеров (kafka/rabbitmq/redis/java), и эта опция удалит контейнеры
> соседних агентов (compose напечатает warning `Found orphan containers (...)` —
> это нормально).

После `down -v` том создаётся заново, `cluster.id` из манифеста форматирует
хранилище с нуля, топик `pipeline.inbound` нужно создать повторно (раздел 4).

---

## 3. Как проверить кластер

```bash
# 1) Все три ноды healthy
docker compose -f Docker/docker-compose.kafka.yml ps kafka1 kafka2 kafka3

# 2) Кворум контроллеров: один ClusterId, 3 voters, лидер выбран
docker exec kafka1 /opt/kafka/bin/kafka-metadata-quorum.sh \
  --bootstrap-server kafka1:29092 describe --status

# 3) Репликация метаданных: Lag=0 у всех follower'ов
docker exec kafka1 /opt/kafka/bin/kafka-metadata-quorum.sh \
  --bootstrap-server kafka1:29092 describe --replication

# 4) ApiVersions отвечает на КАЖДОЙ ноде
for n in 1 2 3; do
  docker exec kafka$n /opt/kafka/bin/kafka-broker-api-versions.sh \
    --bootstrap-server kafka$n:29092 | head -1
done

# 5) Внешние порты хоста открыты
nc -z localhost 9092 && nc -z localhost 9093 && nc -z localhost 9094 && echo "external ports OK"

# 6) Внешний доступ как у монолита: клиент видит ВСЕ 3 брокера по localhost:909N
#    (host-network контейнер эмулирует процесс на хосте)
docker run --rm --network host apache/kafka:3.9.2 \
  /opt/kafka/bin/kafka-broker-api-versions.sh --bootstrap-server localhost:9092 | grep -E '^localhost'
```

Ожидаемый вывод (6):

```
localhost:9092 (id: 1 rack: null) -> (
localhost:9093 (id: 2 rack: null) -> (
localhost:9094 (id: 3 rack: null) -> (
```

Проверка топика:

```bash
docker exec kafka1 /opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server kafka1:29092 --describe --topic pipeline.inbound
```

Должно быть `PartitionCount: 3`, `ReplicationFactor: 3`, `min.insync.replicas=2`
и `Isr` из 3 реплик на каждой партиции.

---

## 4. Создание топиков

Контрактный топик (идемпотентно — если существует, будет `TopicExistsException`, это не ошибка):

```bash
docker exec kafka1 /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka1:29092 \
  --create --topic pipeline.inbound --partitions 3 --replication-factor 3
```

Новый топик:

```bash
docker exec kafka1 /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka1:29092 \
  --create --topic my.new.topic --partitions 3 --replication-factor 3 \
  --config min.insync.replicas=2
```

Правила: `--replication-factor` **не больше 3** (число нод); для надёжной записи
с `acks=all` держите `min.insync.replicas=2`. Список/удаление:

```bash
docker exec kafka1 /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka1:29092 --list
docker exec kafka1 /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka1:29092 \
  --delete --topic my.new.topic
```

Smoke-тест produce/consume:

```bash
echo "hello-kafka" | docker exec -i kafka1 /opt/kafka/bin/kafka-console-producer.sh \
  --bootstrap-server kafka1:29092 --topic pipeline.inbound --producer-property acks=all

docker exec kafka1 /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server kafka1:29092 --topic pipeline.inbound \
  --from-beginning --max-messages 1 --timeout-ms 15000 \
  --consumer-property group.id=smoke
```

---

## 5. Volumes и права (UID)

Образ `apache/kafka` работает от пользователя **`appuser` (uid=1000, gid=1000)**,
данные — в `/var/lib/kafka/data` (`KAFKA_LOG_DIRS` переопределён явно, дефолт
образа `/tmp/kraft-combined-logs` для persistence не годится).

В каталоге образа `/var/lib/kafka/data` уже принадлежит `appuser`, и docker при
первом создании named volume наследует владельца каталога из образа — поэтому
`chmod`/`chown`/`user:` в манифесте **не требуются**. Проверка:

```bash
docker exec kafka1 sh -c 'id; ls -ld /var/lib/kafka/data'
# uid=1000(appuser) gid=1000(appuser) groups=1000(appuser)
# drwxrwxr-x ... appuser root ... /var/lib/kafka/data
docker volume inspect event-driven-kafka1-data
```

Если volume создавался вручную (или примонтирован bind-mount с хоста) и Kafka
падает с `No such file or directory` / `Permission denied` на `log.dirs`:

```bash
docker run --rm -v event-driven-kafka1-data:/d alpine chown -R 1000:1000 /d
```

---

## 6. Troubleshooting

**Ноды не образуют кластер** (`Inconsistent cluster id`, `No voter connects`,
брокер в цикле перезапуска):

```bash
docker logs kafka1 --tail 100
docker exec kafka1 /opt/kafka/bin/kafka-metadata-quorum.sh --bootstrap-server kafka1:29092 describe --status
```

- `CLUSTER_ID` должен быть **одинаковым** у всех трёх нод. Если нода ранее
  стартовала с другим id, её volume «помнит» старый кластер:
  `docker compose -f Docker/docker-compose.kafka.yml down -v` и повторный `up -d`.
- `controller.quorum.voters` должен перечислять все три `node.id@host:9093`,
  а `KAFKA_NODE_ID` каждой ноды обязан совпадать с одной из записей voters.
- Кворум = 2 из 3. Если поднята только одна нода, `healthcheck` никогда не
  пройдёт — это ожидаемо, поднимайте все три.

**Кластер поднят, но монолит на хосте не подключается / подключается и отваливается**
(`Connection to node -1 could not be established`, `Disconnected`):
в `advertised.listeners` для внешнего listener'а указано имя контейнера
(`PLAINTEXT_HOST://kafka1:9092`) вместо `localhost:9092`. Клиент получает из
метаданных адрес, который с хоста не резолвится. Проверка:

```bash
docker exec kafka1 grep -E 'advertised|listeners' /opt/kafka/config/server.properties
docker run --rm --network host apache/kafka:3.9.2 \
  /opt/kafka/bin/kafka-broker-api-versions.sh --bootstrap-server localhost:9092 | grep -E '^localhost'
```

**`NOT_ENOUGH_REPLICAS` / `org.apache.kafka.common.errors.NotEnoughReplicasException`
при `acks=all`**: `min.insync.replicas` больше числа доступных реплик — жива
только одна нода, либо партиция потеряла ISR. Лечится поднятием третьей ноды
(`docker compose ... ps`, `docker start kafkaN`) и проверкой ISR:

```bash
docker exec kafka1 /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka1:29092 \
  --describe --topic pipeline.inbound --under-replicated-partitions
```

**`Bind for 0.0.0.0:9092 failed: port is already allocated`**: порт занят.

```bash
lsof -nP -i :9092 -i :9093 -i :9094        # кто держит порт
docker ps --format '{{.Names}}\t{{.Ports}}' # возможно, старый контейнер kafka
```

Освободите процесс/контейнер или поменяйте `KAFKA1_PORT`/`KAFKA2_PORT`/`KAFKA3_PORT`
в `Docker/.env` (тогда же поменяются `advertised.listeners` — они берут
значение из этих переменных, правка манифеста не требуется).

**Узел `unhealthy`, но процесс жив**: смотрите вывод самой проверки —

```bash
docker inspect --format '{{json .State.Health}}' kafka1 | python3 -m json.tool
docker logs kafka1 --tail 200
```

Частая причина — healthcheck стучится не в тот порт: нужен `localhost:29092`
(внутренний `PLAINTEXT`), а не `9092` (это `PLAINTEXT_HOST`, он тоже открыт, но
в метаданных по нему клиенту возвращается `localhost`, что внутри контейнера
не работает).

**После `docker restart` / перезагрузки хоста ноды не стартуют**: `restart: unless-stopped`
поднимает их автоматически; если старт остановлен вручную —
`docker compose -f Docker/docker-compose.kafka.yml up -d`.

**Данные «пропали» после `down -v`**: это ожидаемое поведение `-v` (удаляются
volumes). Для сохранения состояния используйте `down` без `-v`.

---

## 7. Проверено фактически (выводы команд)

Кластер, кворум:

```
$ docker compose -f Docker/docker-compose.kafka.yml ps kafka1 kafka2 kafka3
NAME     IMAGE              SERVICE   CREATED         STATUS                    PORTS
kafka1   apache/kafka:3.9.2 kafka1    3 minutes ago   Up 3 minutes (healthy)    0.0.0.0:9092->9092/tcp
kafka2   apache/kafka:3.9.2 kafka2    3 minutes ago   Up 3 minutes (healthy)    0.0.0.0:9093->9092/tcp
kafka3   apache/kafka:3.9.2 kafka3    3 minutes ago   Up 3 minutes (healthy)    0.0.0.0:9094->9092/tcp

$ docker exec kafka1 /opt/kafka/bin/kafka-metadata-quorum.sh --bootstrap-server kafka1:29092 describe --status
ClusterId:              c3qzPlyMQWSBOsHSOzuf-g
LeaderId:               2
LeaderEpoch:            1
HighWatermark:          83
MaxFollowerLag:         0
CurrentVoters:          [{"id": 1, ..., "endpoints": ["CONTROLLER://kafka1:9093"]},
                         {"id": 2, ..., "endpoints": ["CONTROLLER://kafka2:9093"]},
                         {"id": 3, ..., "endpoints": ["CONTROLLER://kafka3:9093"]}]
CurrentObservers:       []
```

Топик:

```
$ docker exec kafka1 /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka1:29092 --describe --topic pipeline.inbound
Topic: pipeline.inbound  TopicId: b-6yvIwTQFqIqsB0JAdbRQ  PartitionCount: 3  ReplicationFactor: 3  Configs: min.insync.replicas=2
  Topic: pipeline.inbound  Partition: 0  Leader: 3  Replicas: 3,1,2  Isr: 3,1,2
  Topic: pipeline.inbound  Partition: 1  Leader: 1  Replicas: 1,2,3  Isr: 1,2,3
  Topic: pipeline.inbound  Partition: 2  Leader: 2  Replicas: 2,3,1  Isr: 2,3,1
```

Smoke-тест (внутренний listener) и внешний доступ с хоста:

```
$ echo "$MSG" | docker exec -i kafka1 .../kafka-console-producer.sh --bootstrap-server kafka1:29092 --topic pipeline.inbound --producer-property acks=all
$ docker exec kafka1 .../kafka-console-consumer.sh --bootstrap-server kafka1:29092 --topic pipeline.inbound --from-beginning --max-messages 1 --timeout-ms 15000
CreateTime:1791456439285  Partition:0  kafka-smoke-1791456438
Processed a total of 1 messages

# produce через localhost:9092, consume через localhost:9094 (host-network клиент)
$ docker run --rm --network host apache/kafka:3.9.2 .../kafka-console-consumer.sh --bootstrap-server localhost:9094 --topic pipeline.inbound --from-beginning --max-messages 1 --timeout-ms 20000
external-host-smoke-1791456487
Processed a total of 1 messages

$ nc -z localhost 9092; nc -z localhost 9093; nc -z localhost 9094
Connection to localhost port 9092 [tcp/XmlIpcRegSvc] succeeded!
Connection to localhost port 9093 [tcp/*] succeeded!
Connection to localhost port 9094 [tcp/*] succeeded!
```

Отказоустойчивость (`docker stop kafka3`): кластер остался доступен, лидеры
переизбраны, ISR сократился до 2 (= `min.insync.replicas`), запись с `acks=all`
прошла; после `docker start kafka3` ISR снова 3/3, все ноды `healthy`:

```
Partition: 0  Leader: 1  Replicas: 3,1,2  Isr: 1,2
Partition: 1  Leader: 1  Replicas: 1,2,3  Isr: 1,2
Partition: 2  Leader: 2  Replicas: 2,3,1  Isr: 2,1
```

---

## 8. Замечания

- **Путь манифеста.** По контракту оркестратора файл лежит
  `Docker/docker-compose.kafka.yml` (в корне `Docker/`, не в подкаталоге).
  В `Docker/README.md` INFRA-агента указан путь `Docker/kafka/docker-compose.kafka.yml`
  — расхождение, актуален путь из этого README. Каталог `Docker/kafka/` содержит
  только документацию.
- **Почему `apache/kafka`, а не `bitnami/kafka`.** Официальный образ: KRaft
  «из коробки» (переменные `KAFKA_*` транслируются в `server.properties`,
  `CLUSTER_ID` форматирует хранилище автоматически), скрипты в `/opt/kafka/bin/`,
  данные в `/var/lib/kafka/data`, arm64-native (нет эмуляции через Rosetta на
  Apple Silicon). У `bitnami/kafka` иной набор переменных (`KAFKA_CFG_*`),
  скрипты в `/opt/bitnami/kafka/bin/` и обязательная ручная инициализация
  volumes — больше точек отказа при том же результате.
- **SASL/SSL не включены** — локальная среда разработки, все listener'ы PLAINTEXT.
  Порты 9092-9094 опубликованы на всех интерфейсах хоста (`0.0.0.0`), чтобы
  клиент из контейнера мог ходить на хост через `host.docker.internal`.
- **Heap** каждой ноды ограничен `-Xmx512m` (`KAFKA_HEAP_OPTS`): три брокера
  на одной машине не должны съедать 3 ГБ.
- **Общий compose-проект.** Все кластеры живут в проекте `event-driven`
  (`COMPOSE_PROJECT_NAME`), поэтому `docker compose ps` без указания сервисов
  показывает и контейнеры rabbitmq/redis/java-агентов.

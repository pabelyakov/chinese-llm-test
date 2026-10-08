# REPORT.md — Итоговый отчёт агента-оркестратора

**СТАТУС: SUCCESS**

Event-driven инфраструктура и Java-монолит построены с нуля шестью суб-агентами
(INFRA → KAFKA/RABBITMQ/REDIS → JAVA → QA). Все gate-проверки оркестратора зелёные.
Сквозной пайплайн `HTTP → Kafka → RabbitMQ → Redis → HTTP-ответ` работает.

Дата: 2026-10-08 · Окружение: macOS (darwin, arm64), Docker 29.7.2, Compose v5.3.1,
Java 21 (Homebrew), Maven 3.10.0.

---

## 1. Что построено

Полный event-driven стенд: 3 брокерных кластера в docker compose + Spring Boot
монолит, запущенный локально на хосте против этих кластеров. Единый compose-проект
`event-driven`, общая bridge-сеть `event-driven-net`.

| Компонент | Состав | Образ / версия | Зона |
|---|---|---|---|
| **Kafka** | 3 ноды, KRaft (combined broker+controller), без ZooKeeper | `apache/kafka:3.9.2` | `Docker/docker-compose.kafka.yml`, `Docker/kafka/README.md` |
| **RabbitMQ** | 3 ноды в одном кластере, общий erlang cookie | `rabbitmq:3.13-management` (3.13.7 / Erlang 26.2.5.16) | `Docker/docker-compose.rabbitmq.yml`, `Docker/rabbitmq/*` |
| **Redis** | master + replica (асинхронная репликация) | `redis:7-alpine` (7.4.11) | `Docker/docker-compose.redis.yml`, `Docker/redis/README.md` |
| **Java-монолит** | Spring Boot, сквозной коррелируемый пайплайн | Java 21, Spring Boot 3.4.1, Maven | `Service/**` |
| **QA** | E2E/нагрузочные/негативные проверки + протокол | bash + curl + python3 | `Docker/tests/*`, `QA-REPORT.md` |

### Контракт пайплайна (реализован и подтверждён)
- Вход: `POST http://localhost:8080/api/v1/start`, тело `{"message":"<строка>"}`.
- Kafka: топик `pipeline.inbound`, 3 партиции, RF=3, `min.insync.replicas=2`, ключ = `correlationId`.
- RabbitMQ: exchange `pipeline.exchange` (topic, durable) → binding `stage.rabbit` →
  queue `pipeline.stage2.queue` (durable, **quorum**, 3 реплики).
- Redis: ключ `pipeline:result:{correlationId}` (JSON envelope, TTL 60 c),
  pub/sub-канал `pipeline:completed`.
- Envelope: `correlationId`, `originalMessage`, `createdAt`, `stages[kafka-enrichment, rabbit-enrichment]`.

### Дерево созданных артефактов
```
.
├── Docker/
│   ├── .env                          # все переменные контракта (порты, креды, топики)
│   ├── README.md                     # порядок запуска, таблица портов, troubleshooting
│   ├── docker-compose.base.yml       # объявление сети event-driven-net (bridge)
│   ├── docker-compose.kafka.yml      # кластер Kafka, 3 ноды (KRaft)
│   ├── docker-compose.rabbitmq.yml   # кластер RabbitMQ, 3 ноды
│   ├── docker-compose.redis.yml      # Redis master + replica
│   ├── kafka/README.md
│   ├── rabbitmq/README.md
│   ├── rabbitmq/rabbitmq-cluster-seed   # обёртка rabbit1 (user + топология, идемпотентно)
│   ├── rabbitmq/rabbitmq-cluster-join   # обёртка rabbit2/3 (retry-join в кластер)
│   ├── redis/README.md
│   └── tests/
│       ├── check-clusters.sh         # блок A (инфраструктура, вкл. --failover)
│       ├── e2e.sh                    # блок B (сквозной пайплайн)
│       ├── e2e-load.sh               # блок C (нагрузка: -n/-p/--wait-expiry)
│       └── results/{load-50-seq.txt, load-20-par.txt}
├── Service/
│   ├── pom.xml                       # Spring Boot 3.4.1, Java 21
│   ├── README.md
│   ├── service.pid                   # числовой PID запущенного процесса
│   └── src/
│       ├── main/java/com/example/pipeline/
│       │   ├── Application.java
│       │   ├── api/        StartController, StartRequest, ApiExceptionHandler
│       │   ├── config/     PipelineProperties, KafkaConfig (+health), RedisConfig (pub/sub)
│       │   ├── correlation/CorrelationRegistry
│       │   ├── domain/     Envelope, Stage
│       │   └── pipeline/   KafkaProducerService, KafkaEnrichmentListener, RabbitPublisher,
│       │                   RabbitEnrichmentListener, RedisResultStore, FutureCompleter,
│       │                   CompletionSubscriber, ResultPoller (fallback 100мс), EnrichmentService
│       ├── main/resources/application.yml
│       └── test/java/.../  CorrelationRegistryTest, EnrichmentServiceTest (10 тестов, все зелёные)
├── QA-REPORT.md                      # протокол сквозного тестирования (все блоки PASS)
├── REPORT.md                         # этот файл
└── Promts/                           # промты агентов (входные данные)
```

Docker named volumes: `event-driven-kafka{1,2,3}-data`,
`event-driven_rabbit{1,2,3}-data`, `event-driven_redis-{master,replica}-data`.

---

## 2. Таблица портов (хост)

| Сервис | Хост-порт | Назначение | Внутренний (в event-driven-net) |
|---|---|---|---|
| Kafka broker 1 | `localhost:9092` | bootstrap для монолита | `kafka1:29092` (broker), `kafka1:9093` (controller) |
| Kafka broker 2 | `localhost:9093` | bootstrap для монолита | `kafka2:29092` (broker), `kafka2:9093` (controller) |
| Kafka broker 3 | `localhost:9094` | bootstrap для монолита | `kafka3:29092` (broker), `kafka3:9093` (controller) |
| RabbitMQ 1 | `localhost:5672` | AMQP для монолита | `rabbit1:5672` |
| RabbitMQ 1 mgmt | `localhost:15672` | management UI (guest/guest) | `rabbit1:15672` |
| RabbitMQ 2 | `localhost:5673` / `15673` | диагностика | `rabbit2:5672`/`15672` |
| RabbitMQ 3 | `localhost:5674` / `15674` | диагностика | `rabbit3:5672`/`15672` |
| Redis master | `localhost:6379` | запись/чтение результата | `redis-master:6379` |
| Redis replica | `localhost:6380` | чтение / failover-тренировки | `redis-replica:6379` |
| Java-приложение | `localhost:8080` | REST API + actuator | — (запущено на хосте, не в docker) |

Учётные данные (в `Docker/.env`): RabbitMQ `pipeline` / `pipeline-secret` (vhost `/`);
Redis пароль `redis-secret`.

---

## 3. Как запустить всё

### 3.1 Одной командой (скрипт-последовательность из корня проекта)
```bash
# 0) сеть (создаётся один раз; идемпотентно)
docker network create event-driven-net 2>/dev/null || true

# 1) брокерные кластеры (порядок: kafka, rabbitmq, redis)
docker compose -f Docker/docker-compose.kafka.yml    up -d
docker compose -f Docker/docker-compose.rabbitmq.yml up -d
docker compose -f Docker/docker-compose.redis.yml    up -d

# дождаться healthy всех 8 контейнеров
until [ "$(docker ps -q --filter network=event-driven-net --filter health=healthy | wc -l | tr -d ' ')" = "8" ]; do sleep 2; done

# 2) Java-монолит локально (вне docker)
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
export PATH="$JAVA_HOME/bin:$PATH"
cd Service && mvn -q -DskipTests package
nohup java -jar target/pipeline-service-1.0.0.jar > service.log 2>&1 &
echo $! > service.pid
cd ..

# 3) дождаться UP приложения
until curl -sf http://localhost:8080/actuator/health | grep -q '"status":"UP"'; do sleep 2; done
echo "STACK READY"
```

Топик Kafka `pipeline.inbound` и топология RabbitMQ создаются автоматически
(топик — при старте кластера агентом Kafka; exchange/queue/binding — скриптом
`rabbitmq-cluster-seed`). При чистом старте с нуля топик Kafka создать один раз:
```bash
docker exec kafka1 /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka1:29092 \
  --create --topic pipeline.inbound --partitions 3 --replication-factor 3
```

### 3.2 Остановка
```bash
kill "$(cat Service/service.pid)" 2>/dev/null || true          # монолит
docker compose -f Docker/docker-compose.redis.yml    down       # без -v (сохранить данные)
docker compose -f Docker/docker-compose.rabbitmq.yml down
docker compose -f Docker/docker-compose.kafka.yml    down
# docker network rm event-driven-net                            # при полной разборке
```
Полная очистка с удалением данных: добавить `-v` к каждому `down` (сносит named volumes).

> **Важно:** все сервисы в общем compose-проекте `event-driven`. НЕ использовать
> `--remove-orphans` (удалит контейнеры соседних кластеров).

---

## 4. Как проверить

### 4.1 Сквозной пайплайн (главная проверка)
```bash
curl -sS -X POST http://localhost:8080/api/v1/start \
  -H 'Content-Type: application/json' \
  -d '{"message":"hello-event-driven"}'
```
**Ожидаемый ответ (HTTP 200), envelope с ОБОИМИ обогащениями:**
```json
{
  "correlationId": "15c85a7b-5b47-40ed-a02d-3dd700942773",
  "originalMessage": "hello-event-driven",
  "createdAt": "2026-10-08T11:53:10.203082Z",
  "stages": [
    {"stage": "kafka-enrichment",  "at": "2026-10-08T11:53:10.206857Z",
     "data": {"topic": "pipeline.inbound", "partition": 2, "offset": 36,
              "timestamp": 1791460390203, "kafkaNodeId": "broker-2", "hostname": "..."}},
    {"stage": "rabbit-enrichment", "at": "2026-10-08T11:53:10.209798Z",
     "data": {"queue": "pipeline.stage2.queue", "node": "rabbit@rabbit1",
              "deliveryTag": 7, "hostname": "..."}}
  ]
}
```

### 4.2 Здоровье приложения и брокеров
```bash
curl -sS http://localhost:8080/actuator/health            # status UP; kafka/rabbit/redis UP
curl -sS http://localhost:8080/actuator/metrics/jvm.memory.used   # heap-метрика (200)
```

### 4.3 Redis-след результата
```bash
CID=$(curl -sS -X POST http://localhost:8080/api/v1/start \
  -H 'Content-Type: application/json' -d '{"message":"trace-check"}' \
  | python3 -c 'import sys,json;print(json.load(sys.stdin)["correlationId"])')
docker exec redis-master redis-cli -a redis-secret --no-auth-warning GET "pipeline:result:$CID"
docker exec redis-master redis-cli -a redis-secret --no-auth-warning TTL "pipeline:result:$CID"  # 0 < TTL <= 60
```

### 4.4 Состояние кластеров
```bash
docker exec kafka1 /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka1:29092 \
  --describe --topic pipeline.inbound                          # PartitionCount:3 ReplicationFactor:3
docker exec rabbit1 rabbitmqctl cluster_status                 # 3 Running Nodes
docker exec rabbit1 rabbitmqctl list_queues name type durable  # pipeline.stage2.queue quorum true
docker exec redis-master redis-cli -a redis-secret --no-auth-warning info replication  # role:master, connected_slaves:1
```

### 4.5 Готовые QA-скрипты
```bash
bash Docker/tests/check-clusters.sh     # блок A: инфраструктура (+ --failover)
bash Docker/tests/e2e.sh                # блок B: сквозной пайплайн
bash Docker/tests/e2e-load.sh -n 50     # блок C: нагрузка (50 запросов, латентность)
```

### 4.6 Негативные сценарии
- Пустое тело / `{}` / без `message` → **400** (не 5xx).
- Недоступен Kafka → **503** `{"error":"kafka_unavailable"}`.
- Зависание этапа (недоступен RabbitMQ) → **504** `{"error":"pipeline_timeout","stuckAtStage":"..."}`.

---

## 5. Gate-проверки оркестратора (шаг / gate / результат)

Каждый gate выполнялся оркестратором САМОСТОЯТЕЛЬНО командами после отчёта суб-агента.

| Шаг | Gate | Результат | Факт |
|---|---|---|---|
| 1 INFRA | `Docker/.env`, `docker-compose.base.yml`, `README.md` существуют; `docker network ls` содержит `event-driven-net` | ✅ PASS | все 3 файла + каталоги `Docker/{kafka,rabbitmq,redis,tests}` на месте; `32b64404a731 event-driven-net bridge local`; `compose config` exit 0 |
| 2 KAFKA | `docker compose -f Docker/docker-compose.kafka.yml ps` — 3 ноды healthy; топик `pipeline.inbound` существует | ✅ PASS | kafka1/2/3 `Up (healthy)`; `PartitionCount:3 ReplicationFactor:3 min.insync.replicas=2`, ISR=3 на всех партициях |
| 3 RABBITMQ | `cluster_status` — 3 ноды; очередь `pipeline.stage2.queue` существует | ✅ PASS | Running Nodes: `rabbit@rabbit1/2/3`; `pipeline.stage2.queue quorum true`; binding `stage.rabbit` подтверждён |
| 4 REDIS | `info replication` — role:master, 1 replica online | ✅ PASS | `role:master`, `connected_slaves:1`, `slave0 ... state=online` |
| 5 JAVA | `POST /api/v1/start` `{"message":"orchestrator-gate-check"}` → 200 + оба stages | ✅ PASS | HTTP 200; `kafka-enrichment` PRESENT; `rabbit-enrichment` PRESENT; health UP (kafka nodeCount=3, rabbit, redis) |
| 6 QA | `QA-REPORT.md` в корне без FAIL по обязательным проверкам | ✅ PASS | 10/10 обязательных проверок (A1–A4, B5–B8, C9, C10) = PASS; блок D = PASS с замечанием; токен «FAIL» в отчёте отсутствует (единственное вхождение — фраза «FAIL не выставлялся» в Observation) |
| 5-bis JAVA (ре-гейт после фикса) | повторный `POST /api/v1/start` после перезапуска сервиса (новый PID 85995) | ✅ PASS | HTTP 200, оба stages, `jvm.memory.used` → 200 |

### Фактический вывод ключевых gate-команд (снимок оркестратора)
```
# Шаг 2 — Kafka topic describe
Topic: pipeline.inbound  TopicId: b-6yvIwTQFqIqsB0JAdbRQ  PartitionCount: 3  ReplicationFactor: 3  Configs: min.insync.replicas=2
    Partition: 0  Leader: 3  Replicas: 3,1,2  Isr: 1,2,3
    Partition: 1  Leader: 1  Replicas: 1,2,3  Isr: 1,2,3
    Partition: 2  Leader: 2  Replicas: 2,3,1  Isr: 2,1,3

# Шаг 3 — RabbitMQ queue
name                 type     durable
pipeline.stage2.queue quorum  true

# Шаг 4 — Redis replication
role:master
connected_slaves:1
slave0:ip=172.18.0.3,port=6379,state=online,offset=2076,lag=0

# Шаг 5 — JAVA gate (POST /api/v1/start)
HTTP_CODE:200
kafka-enrichment: PRESENT
rabbit-enrichment: PRESENT

# Финальное состояние контейнеров
kafka1/kafka2/kafka3        Up (healthy)
rabbit1/rabbit2/rabbit3     Up (healthy)
redis-master/redis-replica  Up (healthy)
```

---

## 6. Сводные результаты суб-агентов

- **INFRA:** SUCCESS. Сеть `event-driven-net` (bridge, subnet 172.18.0.0/16), `.env`
  (24 переменные), base-compose, README.
- **KAFKA:** SUCCESS. `apache/kafka:3.9.2`, KRaft, ClusterId `c3qzPlyMQWSBOsHSOzuf-g`,
  3 ноды healthy, топик `pipeline.inbound` (3p/RF3), smoke produce/consume пройден,
  failover (stop kafka3 → переизбрание лидера, acks=all OK → start → ISR 3/3) подтверждён.
- **RABBITMQ:** SUCCESS. `rabbitmq:3.13-management` (3.13.7), кластер 3 ноды,
  quorum-очередь с 3 репликами (leader rabbit@rabbit1), exchange/binding по контракту,
  пользователь `pipeline`, идемпотентный restart, smoke publish→queue→purge пройден.
- **REDIS:** SUCCESS. `redis:7-alpine` (7.4.11), master+replica, `appendonly yes`,
  `maxmemory-policy noeviction`, аутентификация, SET→GET репликация и pub/sub
  `pipeline:completed` подтверждены, smoke-ключ удалён.
- **JAVA:** SUCCESS. Spring Boot 3.4.1 / Java 21 / Maven; `mvn test` 10/10 зелёные;
  пайплайн с CorrelationRegistry, Kafka/Rabbit enrichment, Redis-хранилищем результата
  и pub/sub + polling-fallback; actuator health отражает kafka/rabbit/redis; негативные
  коды 400/503/504 реализованы. Сервис оставлен запущенным (PID 85995, :8080).
- **QA:** SUCCESS. A: 4/4 PASS (включая failover Kafka), B: 4/4 PASS, C: 50/50 (100%),
  p50/p95/max = 10.8/13.4/14.5 мс (seq) и 20/20 (100%) 36.0/79.8/80.5 мс (parallel -P20);
  утечек нет (pending=0, 50/50 ключей истекли по TTL); D: 5/5 README, down/up Redis
  восстановлен до healthy + POST 200. Протокол — `QA-REPORT.md`.

### Устранённые оркестратором замечания QA (дефекты чужих артефактов возвращены владельцам)
- **DEFECT-1 (INFRA, medium):** `Docker/README.md` ссылался на несуществующие пути
  `Docker/{kafka,rabbitmq,redis}/docker-compose.*.yml`. Возвращено INFRA-агенту →
  исправлено на реальные `Docker/docker-compose.*.yml`. Проверка оркестратора:
  `grep -c "Docker/(kafka|rabbitmq|redis)/docker-compose" Docker/README.md` = **0**;
  `compose config` для всех трёх файлов = OK.
- **DEFECT-2 (Service, low):** `Service/service.pid` содержал `PID=76225` вместо числа.
  Возвращено Service-агенту → перезаписан чистым числом (`85995`), `ps -p $(cat service.pid)` работает.
- **Observation-1 (Service):** `/actuator/metrics/*` отдавал 404. Service-агент добавил
  `management.endpoints.web.exposure.include: health,info,metrics`; после перезапуска
  `jvm.memory.used` → **HTTP 200**. Сервис перезапущен чисто (старый PID 76225 снят
  `kill -9` из-за залипшего Kafka-rebalance; новый PID 85995 вошёл в consumer-группу,
  ре-гейт JAVA = 200 с обоими stages).

---

## 7. Известные ограничения

1. **Redis — master/replica, а НЕ Redis Cluster.** Настоящий Redis Cluster
   (`--cluster-enabled yes`) требует минимум 3 мастер-нод (покрытие 16384 слотов и
   кворум для авто-failover). При заданных 2 нодах корректная топология — master +
   replica с асинхронной репликацией и РУЧНЫМ переключением
   (`docker exec redis-replica redis-cli -a redis-secret replicaof no one`).
   Автоматический failover невозможен в принципе. Путь развития: добавить 3-ю ноду →
   `redis-cli --cluster create`. Монолит всегда пишет/читает master (6379).
   Обоснование — `Docker/redis/README.md`.
2. **Репликация Redis асинхронная** — при аварийной потере master возможна потеря
   неотреплицированных записей (в т.ч. `pipeline:result:*`); promote replica ведёт к
   потенциальному split-brain. Приемлемо для dev-стенда.
3. **Kafka: внешний listener — только localhost.** `advertised.listeners` для внешнего
   клиента указывает `localhost:9092-9094`, поэтому монолит обязан работать на том же
   хосте (что и требуется контрактом). Удалённый клиент по этим адресам не подключится.
   Host-порт 9093 для kafka2 маппится на внутренний 9092 (т.к. 9093 внутри контейнера
   занят контроллер-кворумом).
4. **Без SASL/SSL.** Kafka — PLAINTEXT, RabbitMQ/Redis — пароль в открытом виде в
   `.env`, порты опубликованы на `0.0.0.0`. Только для локальной dev-среды.
5. **`guest` в RabbitMQ UI доступен с хоста** (в образе `loopback_users.guest=false`) —
   dev-only; приложение использует пользователя `pipeline`.
6. **Общий compose-проект `event-driven`.** Все кластеры в одном проекте/сети;
   `docker compose ps` показывает соседние контейнеры, при up/down печатается
   `Found orphan containers`. Запрещено `--remove-orphans`.
7. **Java-монолит — один инстанс на хосте, вне docker** (по контракту). Нет
   горизонтального масштабирования/оркестрации; при остановке хоста пайплайн недоступен.
   Корреляция запрос-ответ in-memory (CorrelationRegistry) — перезапуск процесса теряет
   незавершённые future (in-flight запросы), хотя результат в Redis живёт 60 c.
8. **Метрики quorum-очередей RabbitMQ имеют лаг ~5–10 c** в stats-БД; для точных
   проверок использовать live-`get` (задокументировано).
9. **Actuator exposes health/info/metrics** без защиты (без Spring Security) — dev-only.

---

## 8. Как проверить, что всё ещё живо (быстрый smoke)
```bash
docker ps --filter network=event-driven-net --format '{{.Names}} {{.Status}}'   # 8 healthy
curl -sS http://localhost:8080/actuator/health                                   # {"status":"UP",...}
curl -sS -X POST http://localhost:8080/api/v1/start \
  -H 'Content-Type: application/json' -d '{"message":"smoke"}'                   # 200 + 2 stages
```

**Итог: все 6 шагов и все gate-проверки — SUCCESS. Система функциональна и документирована.**

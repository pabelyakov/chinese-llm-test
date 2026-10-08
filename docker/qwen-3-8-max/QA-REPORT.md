# QA-REPORT — независимая E2E-проверка event-driven пайплайна

- **Дата:** 2026-10-08 (14:25–14:45 локальное)
- **Исполнитель:** QA/E2E суб-агент (промт 06-qa-e2e-agent.md)
- **Стенд:** macOS darwin arm64; Docker Compose v5.3.1; compose-проект `event-driven`
- **Объект:** HTTP -> Kafka (pipeline.inbound) -> RabbitMQ (pipeline.stage2.queue) -> Redis (pipeline:result:{cid}) -> HTTP-ответ
- **Скрипты:** `Docker/tests/check-clusters.sh` (блок A), `Docker/tests/e2e.sh` (блок B), `Docker/tests/e2e-load.sh` (блок C); сырые результаты нагрузки — `Docker/tests/results/`
- **Версии:** apache/kafka:3.9.2, rabbitmq:3.13-management (3.13.7 / Erlang 26.2.5.16), redis:7-alpine (7.4.11), Java-монолит pipeline-service-1.0.0.jar (PID 76225, порт 8080 — не перезапускался)

## ВЕРДИКТ

**СТАТУС: SUCCESS.** Все обязательные проверки блоков A, B — PASS (A: 4/4, B: 4/4), нагрузка C: 100% успех, документация D присутствует, команды кластерных README работают (проверено down/up Redis). Найдено **2 дефекта документации/обвязки, НЕ влияющих на работу пайплайна** (см. «Дефекты»): устаревшие пути compose-файлов в `Docker/README.md` (INFRA) и нечисловой формат `Service/service.pid` (Service). Инфраструктурных/кодовых отказов не обнаружено.

## Сводная таблица проверок

| # | Проверка | Ожидание | Факт | Статус |
|---|---|---|---|---|
| A1 | Kafka: ноды + топик | kafka1/2/3 healthy; `pipeline.inbound`: 3 партиции, RF=3, ISR>=2 | 3 healthy; PartitionCount=3, ReplicationFactor=3, min.insync.replicas=2, ISR=3 на всех партициях | **PASS** |
| A2 | Kafka failover | при `docker stop kafka2` produce/consume (acks=all) и сквозной POST продолжаются; после start — healthy, ISR=3 | stop kafka2: лидер p1 переизбран 2->3, ISR сократился до 2, produce/consume OK, POST -> 200 (0.015c); start: healthy через ~9c, ISR снова 3/3 | **PASS** |
| A3 | RabbitMQ кластер | cluster_status: 3 running_nodes; queue quorum durable; binding stage.rabbit | rabbit@rabbit1/2/3 в Running Nodes (3.13.7); `pipeline.stage2.queue quorum true`; binding `pipeline.exchange -> pipeline.stage2.queue [stage.rabbit]` | **PASS** |
| A4 | Redis репликация | master + 1 online replica; SET master -> GET replica | role:master, connected_slaves:1, slave0 state=online; replica master_link_status:up; SET->GET: 'repl-ok' | **PASS** |
| B5 | Happy path x10 | 10/10 HTTP 200; envelope: correlationId (уникальные), originalMessage, stages kafka-enrichment + rabbit-enrichment | 10/10 -> 200, все поля на месте, 10 уникальных cid (`e2e.sh`) | **PASS** |
| B6 | Redis-след | ключ `pipeline:result:{cid}` существует; 0 < TTL <= 60; JSON == HTTP-ответ | ключ найден, TTL=60, deep-equal JSON подтверждён (python3) | **PASS** |
| B7 | Валидация | пустое тело / `{}` / без message / blank message -> 400 (не 5xx) | все 4 случая -> HTTP 400 | **PASS** |
| B8 | Наблюдаемость | cid прослеживается в service.log на всех этапах; доставка видна со стороны RabbitMQ | 17 строк на cid: received -> registered -> producing -> produced -> Kafka consumed -> publishing -> Rabbit consumed (deliveryTag, node=rabbit@rabbit1) -> Redis written -> pub/sub -> completing -> responding 200; consumer активен (list_consumers), аутентификация user 'pipeline' в логах rabbit1 | **PASS** |
| C9 | Нагрузка | 50 запросов, 100% успех, p50/p95/max зафиксированы | 50/50 успех (100%), p50=10.8мс p95=13.4мс max=14.5мс (wall 2.95с); доп. параллельно 20 (-P 20): 20/20, p50=36.0 p95=79.8 max=80.5мс | **PASS** |
| C10 | Утечки после нагрузки | heap-метрика (если доступна); ключи result истекли по TTL | /actuator/metrics НЕ exposed (только health) — метрика недоступна (см. Observation-1), косвенно: health UP, CorrelationRegistry pending=0; TTL: сразу после нагрузки 50/50 ключей живы, через 70с — 0 живых, всего `pipeline:result:*` в Redis = 0 | **PASS** |
| D11 | Документация | 5 README присутствуют, адекватны; down/up одного кластера по README восстанавливает систему | все 5 README на месте; down/up Redis по командам `Docker/redis/README.md` — обе ноды healthy (~3с), репликация восстановлена (connected_slaves:1, SET->GET ok), сквозной POST -> 200; НО пути compose в `Docker/README.md` неверны -> DEFECT-1 | **PASS с замечанием** |

## Приложение 1. Kafka — describe топика (A1)

```
$ docker exec kafka1 /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka1:29092 --describe --topic pipeline.inbound
Topic: pipeline.inbound  TopicId: b-6yvIwTQFqIqsB0JAdbRQ  PartitionCount: 3  ReplicationFactor: 3  Configs: min.insync.replicas=2
        Topic: pipeline.inbound  Partition: 0  Leader: 3  Replicas: 3,1,2  Isr: 1,3,2
        Topic: pipeline.inbound  Partition: 1  Leader: 1  Replicas: 1,2,3  Isr: 1,3,2
        Topic: pipeline.inbound  Partition: 2  Leader: 2  Replicas: 2,3,1  Isr: 1,3,2
$ docker ps --filter name=kafka  =>  kafka1/2/3: Up (healthy)
```

## Приложение 2. Kafka failover (A2)

```
$ docker stop kafka2                     # 14:29:45
# describe во время останова (тестовый топик qa.failover.test, p1 лидер был на kafka2):
  Partition: 0  Leader: 1  Replicas: 1,2,3  Isr: 1,3
  Partition: 1  Leader: 3  Replicas: 2,3,1  Isr: 3,1     <- лидер переизбран 2->3, ISR=2 (=min.insync.replicas)
  Partition: 2  Leader: 3  Replicas: 3,1,2  Isr: 3,1
# produce acks=all через localhost:9092,9094 (host-network клиент): exit 0, сообщение 'qa-failover-1791458985'
# consume через localhost:9092: 'qa-failover-1791458985' получен
# сквозной POST при kafka2 down: HTTP 200, 0.015292s, cid=2e492d41-40bf-43bd-8b4e-ef60b99fe2d1,
#   stages: kafka-enrichment, rabbit-enrichment
$ docker start kafka2                    # healthy через ~9с
# после восстановления: ISR всех партиций pipeline.inbound снова 3 (Isr: 1,3,2); тестовый топик удалён
```

## Приложение 3. RabbitMQ — cluster_status (A3, выжимка)

```
$ docker exec rabbit1 rabbitmqctl cluster_status
Cluster name: rabbit@rabbit1
Disk Nodes:      rabbit@rabbit1  rabbit@rabbit2  rabbit@rabbit3
Running Nodes:   rabbit@rabbit1  rabbit@rabbit2  rabbit@rabbit3      <- 3 ноды
Versions: rabbit@rabbit1/2/3: RabbitMQ 3.13.7 on Erlang 26.2.5.16
Alarms: (none)   Network Partitions: (none)

$ docker exec rabbit1 rabbitmqctl list_queues name type durable
name                    type    durable
pipeline.stage2.queue   quorum  true

$ docker exec rabbit1 rabbitmqctl list_bindings | grep pipeline.exchange
pipeline.exchange  exchange  pipeline.stage2.queue  queue  stage.rabbit  []

$ docker exec rabbit1 rabbitmqctl list_consumers
pipeline.stage2.queue  <rabbit@rabbit1.1791458503.1079.0>  amq.ctag-...  true  250  true  []
```

## Приложение 4. Redis — info replication (A4)

```
$ docker exec redis-master redis-cli -a redis-secret --no-auth-warning info replication
role:master
connected_slaves:1
slave0:ip=172.18.0.3,port=6379,state=online,offset=12522,lag=1
master_repl_offset:12522

$ docker exec redis-replica redis-cli -a redis-secret --no-auth-warning info replication
role:slave
master_host:redis-master
master_link_status:up

# SET master -> GET replica:
$ docker exec redis-master  redis-cli ... set qa:smoke:1791458... replicated-ok   -> OK
$ docker exec redis-replica redis-cli ... get qa:smoke:1791458...                  -> replicated-ok
```

## Приложение 5. Happy path — полный curl-ответ (B5/B6)

```
$ curl -sS -X POST http://localhost:8080/api/v1/start -H 'Content-Type: application/json' -d '{"message":"qa-e2e-1791459452-10"}'
HTTP 200
{
  "correlationId": "391a9752-7dbf-4177-b20a-a8751d9f4bbc",
  "originalMessage": "qa-e2e-1791459452-10",
  "createdAt": "2026-10-08T11:37:33.400797Z",
  "stages": [
    {"stage": "kafka-enrichment", "at": "2026-10-08T11:37:33.408402Z",
     "data": {"topic": "pipeline.inbound", "partition": 2, "offset": 8,
              "timestamp": 1791459453400, "kafkaNodeId": "broker-1", "hostname": "MacBook-Pro-Petr.local"}},
    {"stage": "rabbit-enrichment", "at": "2026-10-08T11:37:33.412303Z",
     "data": {"queue": "pipeline.stage2.queue", "node": "rabbit@rabbit1",
              "deliveryTag": 16, "hostname": "MacBook-Pro-Petr.local"}}
  ]
}
# Redis-след того же cid:
$ docker exec redis-master redis-cli -a redis-secret --no-auth-warning GET pipeline:result:391a9752-...
{"correlationId":"391a9752-...","originalMessage":"qa-e2e-1791459452-10",...}   # deep-equal HTTP-ответу
$ docker exec redis-master redis-cli -a redis-secret --no-auth-warning TTL pipeline:result:391a9752-...
60                                                                                # 0 < TTL <= 60
```

Результат `e2e.sh` (B5): 10/10 -> HTTP 200, cid уникальны (10/10), originalMessage совпадает, stages = `kafka-enrichment,rabbit-enrichment` в каждом ответе.

## Приложение 6. Валидация (B7)

| Тело запроса | HTTP |
|---|---|
| (пустое, без payload) | 400 |
| `{}` | 400 |
| `{"foo":"bar"}` (без поля message) | 400 |
| `{"message":""}` (blank) | 400 |

5xx ни в одном случае — PASS.

## Приложение 7. Наблюдаемость (B8) — цепочка cid в Service/service.log

```
$ grep '391a9752-7dbf-4177-b20a-a8751d9f4bbc' Service/service.log   # 17 строк, все этапы:
14:37:33.400 StartController        : POST /api/v1/start received: message='qa-e2e-1791459452-10'
14:37:33.400 CorrelationRegistry     : registered in CorrelationRegistry (pending=1)
14:37:33.400 KafkaProducerService    : producing to Kafka topic=pipeline.inbound (JSON string payload)
14:37:33.408 KafkaProducerService    : produced to Kafka: topic=pipeline.inbound partition=2 offset=8
14:37:33.408 KafkaEnrichmentListener : Kafka stage: consumed topic=pipeline.inbound partition=2 offset=8
14:37:33.408 RabbitPublisher         : publishing to RabbitMQ exchange=pipeline.exchange routingKey=stage.rabbit
14:37:33.412 RabbitEnrichmentListener: Rabbit stage: consumed queue=pipeline.stage2.queue deliveryTag=16 node=rabbit@rabbit1
14:37:33.413 RedisResultStore        : result written to Redis key=pipeline:result:391a9752-... ttl=60s
14:37:33.413 RedisResultStore        : published to Redis pub/sub channel=pipeline:completed
14:37:33.414 CompletionSubscriber    : pub/sub notification received on channel=pipeline:completed
14:37:33.414 FutureCompleter         : result fetched from Redis, completing future
14:37:33.414 StartController         : responding 200 with final envelope (stages=2)
14:37:33.414 CorrelationRegistry     : removed from CorrelationRegistry (pending=0)
```

Доставка со стороны RabbitMQ: брокер логирует только соединения на info-уровне (per-message delivery в логах rabbit1 отсутствует — дефолтное поведение RabbitMQ), факт доставки подтверждается: (а) `deliveryTag=16 node=rabbit@rabbit1` в логе приложения (тег выдаётся брокером), (б) активный consumer в `rabbitmqctl list_consumers`, (в) лог rabbit1: `user 'pipeline' authenticated and granted access to vhost '/'`, seed-обёртка: `topology OK — exchange pipeline.exchange (topic,durable) -> pipeline.stage2.queue (quorum,durable) [rk=stage.rabbit]`.

## Приложение 8. Нагрузочный тест (C9) и TTL (C10)

```
$ ./Docker/tests/e2e-load.sh -n 50 --wait-expiry        # 50 последовательных
  всего=50 успехов=50 ошибок=0        успех: 100.0%
  wall-time=2952 мс
  латентность: p50=10.8 мс  p95=13.4 мс  max=14.5 мс  min=7.7 мс

$ ./Docker/tests/e2e-load.sh -n 20 -p 20                # 20 параллельных (xargs -P 20)
  всего=20 успехов=20 ошибок=0        успех: 100.0%
  wall-time=191 мс
  латентность: p50=36.0 мс  p95=79.8 мс  max=80.5 мс  min=19.0 мс
```

Сырые построчные результаты (seq http time_ms cid stages): `Docker/tests/results/load-50-seq.txt`, `Docker/tests/results/load-20-par.txt`.

TTL-контроль (C10, --wait-expiry):
```
-- сразу после нагрузки ключей pipeline:result:{cid} живо: 50 из 50
-- sleep 70 (TTL 60с + запас)...
-- после ожидания: живо ключей нагрузки: 0; всего pipeline:result:* в Redis: 0
PASS: все ключи нагрузки истекли по TTL (утечек ключей нет)
```

Утечки в приложении (C10): `/actuator/metrics/jvm.memory.used` -> **404** (exposed только `health`, см. Observation-1). Косвенные признаки отсутствия утечек:
- `GET /actuator/health` -> `{"status":"UP"}` (kafka UP nodeCount=3, rabbit UP 3.13.7, redis UP 7.4.11) — до и после нагрузки;
- последняя запись `CorrelationRegistry: removed ... (pending=0)` — реестр корреляций очищается;
- все 50 Redis-ключей истекли по TTL (выше).

## Приложение 9. Документация (D11)

| Файл | Наличие | Адекватность |
|---|---|---|
| Docker/README.md | есть | **устаревшие пути compose** (DEFECT-1); таблица портов/кредов соответствует `.env` и факту |
| Docker/kafka/README.md | есть | адекватен; пути верны (`Docker/docker-compose.kafka.yml`); расхождение с Docker/README задокументировано самим файлом (раздел 8); проверенные команды (describe, metadata-quorum, smoke produce/consume) работают |
| Docker/rabbitmq/README.md | есть | адекватен; пути верны; команды cluster_status/list_queues/list_bindings работают и дают заявленный результат |
| Docker/redis/README.md | есть | адекватен; пути верны; команды up/down/ps + проверка репликации работают (проверено фактически, см. ниже) |
| Service/README.md | есть | адекватен: jar `target/pipeline-service-1.0.0.jar` существует, curl-примеры и формат ответов совпадают с фактическими, диагностика по correlationId работает; мелочь: `service.pid` в нечисловом формате (DEFECT-2) |

**Проверка down/up по README (выбран Redis — наименее рискованный):**
```
$ docker compose -f Docker/docker-compose.redis.yml down    # остановлены/удалены ТОЛЬКО redis-master/redis-replica
$ docker compose -f Docker/docker-compose.redis.yml up -d   # master -> Healthy, затем replica
# через ~3с: redis-master=healthy redis-replica=healthy
# info replication: role:master connected_slaves:1 slave0 ... state=online,lag=0
# SET qa:after-up ok (master) -> GET (replica) = ok
# сквозной POST после восстановления: HTTP 200, 0.009957s, stages=[kafka-enrichment, rabbit-enrichment]
```
Система оставлена в собранном состоянии: все 8 контейнеров healthy, Java-сервис PID 76225 жив, health UP. `--remove-orphans` и `down -v` не использовались.

**Команды из Docker/README.md НЕ работают как написаны** — см. DEFECT-1 (репро ниже). Команды из кластерных README (kafka/rabbitmq/redis) работают.

## Дефекты

### DEFECT-1 (medium, документация) — Docker/README.md: неверные пути compose-файлов
- **Где:** `Docker/README.md`, разделы «Порядок запуска» (строки 34, 37, 40) и «Остановка и очистка» (строки 76–83).
- **Что:** указаны пути `Docker/kafka/docker-compose.kafka.yml`, `Docker/rabbitmq/docker-compose.rabbitmq.yml`, `Docker/redis/docker-compose.redis.yml`; фактически манифесты лежат в `Docker/docker-compose.{kafka,rabbitmq,redis}.yml` (в подкаталогах только README и обёртки).
- **Шаги воспроизведения:**
  ```
  $ docker compose -f Docker/kafka/docker-compose.kafka.yml config
  open .../Docker/kafka/docker-compose.kafka.yml: no such file or directory   # exit=1
  $ ls Docker/kafka/ Docker/redis/ Docker/rabbitmq/
  Docker/kafka/:    README.md                    # compose-файлов нет
  ```
- **Логи:** вывод команды выше; `Docker/kafka/README.md` раздел 8 сам фиксирует это расхождение («актуален путь из этого README»).
- **Влияние:** новичок, выполняющий корневой README буквально, не сможет поднять/остановить кластеры. На работающую инфраструктуру влияния нет.
- **Кому на доработку:** INFRA-агент (владелец `Docker/README.md`). Файл вне зоны QA — не правил.

### DEFECT-2 (low, обвязка сервиса) — Service/service.pid в нечисловом формате
- **Где:** `Service/service.pid`.
- **Что:** файл содержит строку `PID=76225` вместо `76225`; стандартные идиомы ломаются:
  ```
  $ ps -p $(cat Service/service.pid)
  ps: Invalid process id: PID=76225
  ```
- **Влияние:** косметическое — автоматический контроль/остановка процесса по pid-файлу требуют дополнительного парсинга. Сам сервис работает (PID 76225 проверен `ps` и `lsof -iTCP:8080`).
- **Кому на доработку:** Service-агент (владелец скриптов запуска в `Service/`). Файл вне зоны QA — не правил.

### Наблюдения (НЕ дефекты, информация оркестратору)
1. **Observation-1:** Spring Boot Actuator отдаёт наружу только `health` (`/actuator` -> `_links: health`), `/actuator/metrics/*` -> 404. Требование C10 сформулировано как «если доступна heap-метрика — приложи», поэтому FAIL не выставлялся; проверка утечек выполнена косвенно (health UP, pending=0, TTL-истечение 50/50). При желании Service-агент может добавить `management.endpoints.web.exposure.include=health,metrics`.
2. **Observation-2:** в `Service/service.log` 6 WARN-строк, все — ожидаемые транзиенты моих же проверок: 2 Kafka-producer WARN в 14:37:32 (invalid metadata / error produce response — отголоски переизбрания лидеров после failover-теста A2; запросы не затронуты, 10/10 -> 200) и Lettuce `Cannot reconnect ... Connection reset` в 14:40:50 (окно down/up Redis в D11; после up — автоматический reconnect, health redis UP). ERROR-строк нет.
3. **Observation-3:** в логе B8 видно двойное `result fetched from Redis, completing future` (быстрый путь в Rabbit-листенере + pub/sub-подписчик) — задокументированная анти-гонка (Service/README.md), `CompletableFuture.complete` идемпотентен, на ответ не влияет.

## Итоги по блокам

- **Блок A (инфраструктура): 4/4 PASS** (включая опциональный failover-тест A2 — выполнен, нода возвращена, ISR восстановлен).
- **Блок B (сквозной пайплайн): 4/4 PASS.**
- **Блок C (нагрузка): PASS** — 50/50 (100%) seq: p50/p95/max = 10.8/13.4/14.5 мс; 20/20 (100%) parallel -P20: 36.0/79.8/80.5 мс; утечек нет (pending=0, TTL-ключи истекли полностью).
- **Блок D (документация): PASS с замечанием** — 5/5 README присутствуют, команды кластерных README и Service/README работают, down/up Redis восстановлен до healthy + POST 200; дефект путей в корневом Docker/README.md (DEFECT-1).
- **Система после тестов:** все 8 контейнеров healthy, Java-сервис (PID 76225, :8080) не перезапускался и здоров, тестовые артефакты (топик `qa.failover.test`, ключи `qa:*`) удалены.

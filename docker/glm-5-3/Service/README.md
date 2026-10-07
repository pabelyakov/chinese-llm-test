# Service — Java-монолит (Spring Boot): Kafka → RabbitMQ → Redis

Один Spring Boot-процесс, реализующий сквозной event-driven сценарий с
синхронным HTTP-ответом по итогу асинхронной цепочки.

## Архитектура

### Потоки внутри приложения

```
HTTP-поток (tomcat-http-Pool)          Kafka-listener (ntainer#0-N)      Rabbit-listener (ntContainer#0-N)
─────────────────────────────          ────────────────────────────      ────────────────────────────────
POST /start {"message":"hello"}        records from messages.topic       deliveries from messages.queue
        │                                       │                               │
        ├─ correlationId = UUID                  │                               │
        ├─ register future in map ────────┐      │                               │
        ├─ KafkaTemplate.send ───────────┼──► [Kafka messages.topic]            │
        │                                │      │                               │
        │                                │      ├─ parse FlowMessage            │
        │                                │      ├─ enrich №1 (KAFKA:           │
        │                                │      │    partition/offset/timestamp)
        │                                │      ├─ RabbitTemplate ──────────► [RabbitMQ messages.exchange]
        │                                │      │                        ──► messages.queue ──►
        │                                │      │                               │
        │                                │      │                               ├─ enrich №2 (RABBITMQ:
        │                                │      │                               │    queue/deliveryTag/
        │                                │      │                               │    redelivered)
        │                                │      │                               ├─ StringRedisTemplate SET
        │                                │      │                               │    message:{cid} TTL 10m
        │                                └──────┼──────── complete(future) ◄────┤
        │                                       │
        ├─ future.get(15s)                      │
        ├─ Redis GET message:{cid}              │
        └─ 200 FinalResponse ───────────────────┘
                │
                └─ finally: unregister future (map чистится всегда, включая 504)
```

Каналы передачи: Kafka (JSON строкой, ключ = correlationId, acks=all),
RabbitMQ (Jackson2JsonMessageConverter, direct exchange → quorum queue),
Redis (StringRedisTemplate, строка JSON, TTL 10 минут).

### Обоснование способа ожидания: CompletableFuture vs polling Redis

Выбран **in-memory `CompletableFuture` map** (`FlowOrchestrator`):

- Rabbit-listener знает момент завершения шага 3 (запись в Redis) и
  мгновенно будит HTTP-поток — минимальная задержка ответа (~20 мс на
  весь сценарий);
- polling Redis давал бы лишние round-trip'ы и джиттер интервала опроса;
- таймаут 15 с выражается напрямую `future.get(15s)` → `TimeoutException`
  → 504, без «таймера опроса»;
- утечек нет: `finally { unregister }` на каждом HTTP-запросе, а
  `complete()` сам делает `remove` (см. тесты `FlowOrchestratorTest`).

Минус подхода — будущие работают только в рамках одного процесса; для
масштабирования на несколько инстансов нужен общий bus/Redis-pubsub. Для
монолита (по ТЗ) это осознанное ограничение.

### Ack-семантика

- Kafka: `ackMode=RECORD` — offset коммитится после успешной обработки
  записи. Ошибки: `DefaultErrorHandler` (backoff 1с × 2 попытки),
  parse-ошибки (`IllegalStateException`) — non-retryable: лог + skip,
  без бесконечного ретрая. Десериализация обёрнута в
  `ErrorHandlingDeserializer`.
- RabbitMQ: `acknowledge-mode: AUTO` — basic.ack после возврата
  listener-метода (т.е. после записи в Redis), при исключении — reject
  БЕЗ requeue (`default-requeue-rejected: false`) — предотвращает
  бесконечный ределивери на quorum-очереди (delivery-limit=50 по policy);
  потерянное сообщение диагностируется 504-таймаутом запроса.

## Предусловия

- JDK 21 (`export JAVA_HOME=...openjdk@21...`)
- Поднятая инфраструктура: Kafka localhost:9092 (,9093,9094, топик
  `messages.topic` уже создан, 3 партиции, RF=3, min.insync.replicas=2),
  RabbitMQ localhost:5672 guest/guest (exchange/queue/binding уже созданы,
  queue quorum, x-quorum-initial-group-size=3 — приложение декларирует
  РОВНО те же аргументы), Redis localhost:6379.
- Порты доступны: `nc -z localhost 9092 5672 6379` (8080 свободен).

## Сборка и запуск

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
cd Service
./mvnw clean verify          # юнит-тесты + jar → target/monolith.jar
./mvnw spring-boot:run       # foreground-вариант
# либо background:
nohup $JAVA_HOME/bin/java -jar target/monolith.jar > /tmp/monolith.log 2>&1 &
echo $! > /tmp/monolith.pid
```

Готовность: `curl -s localhost:8080/actuator/health` → `{"status":"UP"}`
(компоненты kafka/rabbit/redis).

## Примеры

```bash
# happy path
curl -X POST localhost:8080/start -H 'Content-Type: application/json' \
     -d '{"message":"hello"}'
# → {"correlationId":"...","originalMessage":"hello","finalMessage":"hello",
#     "enrichments":[{"stage":"KAFKA",...},{"stage":"RABBITMQ",...}],
#     "retrievedFromRedisAt":"2026-10-08T..."}

# следы в системах
docker exec redis-master redis-cli GET "message:<correlationId>"
docker exec redis-master redis-cli TTL "message:<correlationId>"        # ~600
curl -s -u guest:guest http://localhost:15672/api/queues/%2F/messages.queue
docker exec kafka-2 /opt/kafka/bin/kafka-consumer-groups.sh \
     --bootstrap-server kafka-2:29092 --describe --group monolith-group  # LAG=0

# негативные
curl -X POST localhost:8080/start -H 'Content-Type: application/json' \
     -d '{"message":""}'    # → 400 "message must not be blank"
curl -X POST localhost:8080/start -H 'Content-Type: application/json' \
     -d ''                  # → 400 malformed
# RabbitMQ остановлен → POST /start висит ровно 15с → 504 с correlationId
```

Остановка: `kill $(cat /tmp/monolith.pid)`.

## Конфигурационные свойства

| Свойство | По умолчанию | Описание |
|---|---|---|
| `server.port` | `8080` | HTTP-порт приложения |
| `spring.kafka.bootstrap-servers` | `localhost:9092,9093,9094` | Kafka-брокеры |
| `spring.kafka.producer.acks` | `all` | подтверждение всеми ISR (min.insync=2) |
| `spring.kafka.consumer.group-id` | `monolith-group` | консюмер-группа |
| `spring.kafka.consumer.auto-offset-reset` | `earliest` | для новой группы (старые commit'ы уважаются) |
| `spring.kafka.listener.ack-mode` | `RECORD` | коммит после обработки записи |
| `spring.kafka.listener.concurrency` | `3` | потоков = числу партиций |
| `spring.rabbitmq.host/port/username/password` | `localhost:5672 guest/guest` | RabbitMQ |
| `spring.rabbitmq.listener.simple.acknowledge-mode` | `AUTO` | ack после Redis-записи |
| `spring.rabbitmq.listener.simple.default-requeue-rejected` | `false` | reject без requeue (анти-цикл) |
| `spring.rabbitmq.publisher-confirm-type` | `correlated` | publisher confirms |
| `spring.data.redis.host/port` | `localhost:6379` | Redis (Lettuce) |
| `management.endpoints.web.exposure.include` | `health,info` | actuator |
| `app.kafka-topic` | `messages.topic` | топик |
| `app.rabbit-exchange` | `messages.exchange` | direct durable exchange |
| `app.rabbit-queue` | `messages.queue` | quorum, initial group size 3 |
| `app.rabbit-routing-key` | `messages.key` | binding key |
| `app.redis-key-prefix` | `message:` | ключ = prefix + correlationId |
| `app.redis-ttl` | `10m` | TTL Redis-ключа |
| `app.flow-timeout` | `15s` | бюджет ожидания цепочки → 504 |
| `app.kafka-send-timeout` | `5s` | ожидание acks=all при отправке |

## Типовые проблемы и диагностика

**Приложение стартует раньше брокеров.** Kafka-listener/container
переподключается бесконечно (лог `Consumer raised exception ...
Connection refused`), RabbitTemplate падает при попытке публикации —
запрос завершится 502/504, но приложение живо и восстановится само.
Проверять `curl localhost:8080/actuator/health` — компонент больного
брокера будет DOWN; не стартовать вслепую: `nc -z localhost 9092 5672 6379`.

**Порт 8080 занят.** `lsof -i :8080`; поменять `server.port` или убить
конфликтующий процесс.

**Lag растёт.** `kafka-consumer-groups.sh --bootstrap-server kafka-2:29092
--describe --group monolith-group`; смотреть /tmp/monolith.log на
`Backoff ... exhausted` (запись скипнута после ретраев — обычно
некорректный payload) и `unparseable kafka payload`.

**Сообщение потерялось между этапами.** Идти по correlationId:
1. Kafka: `kafka-console-consumer --topic messages.topic --from-beginning`
   (найти cid), лог `published correlationId=... to kafka topic`;
2. Rabbit: лог `published correlationId=... to rabbit exchange` +
   Management UI (15672): сообщения/unacked в `messages.queue`
   (unacked>0 → listener завис);
3. Redis: `redis-cli GET message:<cid>` — пусто при истёкшем TTL →
   HTTP вернёт 502 `stored message not found in Redis`;
4. HTTP: 504 значит будущее не завершено за 15с — chain порвана
   между Kafka→Rabbit или Rabbit→Redis; логи `kafka stage`/`rabbit stage`
   показывают, какого этапа не было.

## Структура пакетов

```
com.example.monolith
├── api         StartController, StartRequest/FinalResponse/ErrorResponse, GlobalExceptionHandler
├── flow        FlowOrchestrator (future-map), исключения сценария
├── kafka       KafkaPublisher, KafkaFlowConsumer (enrich №1), KafkaHealthIndicator
├── rabbitmq    RabbitFlowPublisher, RabbitFlowListener (enrich №2 → Redis → complete)
├── redis       RedisMessageStore (SET/GET message:{cid}, TTL)
├── config      AppProperties, RabbitTopologyConfig (declarables), KafkaErrorHandlerConfig
└── model       FlowMessage, Enrichment, Enrichments
```

Тесты (юнит, без брокеров): JSON round-trip модели, фабрики enrich-записей,
изоляция/timeout/очистка Future-оркестратора, контроллер (200/400/502/504)
— `./mvnw clean verify`, 15 тестов.

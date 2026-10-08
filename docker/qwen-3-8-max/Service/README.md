# Service — event-driven pipeline монолит (Java 21 / Spring Boot 3.4)

HTTP-сервис, реализующий сквозной асинхронный пайплайн с корреляцией:

```
POST /api/v1/start {"message":"..."}
   |  correlationId = UUID, CompletableFuture в CorrelationRegistry
   v
[Kafka]  topic pipeline.inbound (ключ = correlationId, JSON-строка)
   |  @KafkaListener: обогащение №1 — stage "kafka-enrichment"
   |  (topic, partition, offset, timestamp, kafkaNodeId=leader брокера, hostname)
   v
[RabbitMQ] exchange pipeline.exchange (topic), rk stage.rabbit -> pipeline.stage2.queue
   |  @RabbitListener: обогащение №2 — stage "rabbit-enrichment"
   |  (queue, node=rabbit@..., deliveryTag, hostname)
   v
[Redis MASTER :6379] SET pipeline:result:{correlationId} <финальный JSON> EX 60
   |  PUBLISH pipeline:completed {correlationId}
   v
Завершение future (3 механизма, см. ниже) -> HTTP 200 с финальным envelope
```

## Формат передачи

**Везде JSON-строка** (envelope сериализован Jackson'ом в `String`):
- Kafka: `StringSerializer` / `StringDeserializer`;
- RabbitMQ: `SimpleMessageConverter` (content_type `text/plain`), тело — та же JSON-строка;
- Redis: `StringRedisTemplate`.

Выбор задокументирован: единый строковый формат исключает рассогласование
конвертеров между этапами — Rabbit-консьюмер читает ровно то, что написал Kafka-консьюмер.

## Надёжность завершения future (анти-гонка)

1. **Pub/sub-подписчик** на `pipeline:completed` регистрируется в
   `RedisMessageListenerContainer` при старте контекста — ДО первого produce.
2. **Polling-fallback**: `ResultPoller` каждые 100 мс (`pipeline.poll-interval-ms`)
   для всех pending correlationId делает `GET pipeline:result:{id}` и завершает future.
   Гарантирует завершение даже при потере pub/sub-сообщения.
3. **Прямое завершение** в Rabbit-листенере сразу после записи в Redis (быстрый путь).

Утечек нет: контроллер удаляет запись из реестра в `finally`; дополнительная
чистка зависших записей — каждые 30 с (`evictStaleEntries`).

## Сборка и запуск (локально на хосте)

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
export PATH="$JAVA_HOME/bin:$PATH"

mvn -q test                 # unit-тесты (без живых брокеров)
mvn -q -DskipTests package  # jar: target/pipeline-service-1.0.0.jar

# запуск в фоне (инфраструктура из Docker/ должна быть поднята):
nohup java -jar target/pipeline-service-1.0.0.jar > service.log 2>&1 &

# PID-файл: ТОЛЬКО числовое значение PID, без префиксов:
echo $! > service.pid
ps -p $(cat service.pid)     # проверка: процесс жив

# контроль:
curl -s http://localhost:8080/actuator/health
```

Остановка: `kill $(cat service.pid)` и дождаться освобождения порта
(`lsof -ti :8080` пуст) — graceful shutdown закрывает web-порт раньше,
чем завершается JVM; при зависании consumer-группы допустим `kill -9`.

## Actuator-эндпоинты

Наружу отданы `health`, `info`, `metrics`
(`management.endpoints.web.exposure.include` в `application.yml`).
Health содержит детали по компонентам kafka/rabbit/redis.

```bash
curl -sS http://localhost:8080/actuator/health
curl -sS http://localhost:8080/actuator/metrics/jvm.memory.used
# {"name":"jvm.memory.used","baseUnit":"bytes",
#  "measurements":[{"statistic":"VALUE","value":1.75E8}],
#  "availableTags":[{"tag":"area","values":["heap","nonheap"]},...]}
curl -sS 'http://localhost:8080/actuator/metrics/jvm.memory.used?tag=area:heap'
```

## Конфигурация (env-переменные, дефолты под Docker/.env)

| Переменная | Дефолт | Назначение |
|---|---|---|
| `APP_PORT` | `8080` | HTTP-порт |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092,localhost:9093,localhost:9094` | bootstrap Kafka |
| `KAFKA_TOPIC` | `pipeline.inbound` | входной топик |
| `RABBITMQ_HOST` / `RABBITMQ_PORT` | `localhost` / `5672` | AMQP |
| `RABBITMQ_USER` / `RABBITMQ_PASSWORD` | `pipeline` / `pipeline-secret` | креды Rabbit |
| `RABBIT_EXCHANGE` | `pipeline.exchange` | topic exchange |
| `RABBIT_QUEUE` | `pipeline.stage2.queue` | quorum queue |
| `RABBIT_ROUTING_KEY` | `stage.rabbit` | binding key |
| `REDIS_HOST` / `REDIS_PORT` | `localhost` / `6379` | Redis MASTER |
| `REDIS_PASSWORD` | `redis-secret` | пароль Redis |
| `REDIS_RESULT_KEY_PREFIX` | `pipeline:result:` | префикс ключа результата |
| `REDIS_COMPLETED_CHANNEL` | `pipeline:completed` | pub/sub канал |
| `REDIS_RESULT_TTL_SECONDS` | `60` | TTL ключа результата |
| `PIPELINE_TIMEOUT_SECONDS` | `15` | таймаут ожидания в контроллере |
| `PIPELINE_POLL_INTERVAL_MS` | `100` | интервал polling-fallback |

## Примеры curl

Успех (200, оба обогащения):

```bash
curl -sS -X POST http://localhost:8080/api/v1/start \
  -H 'Content-Type: application/json' -d '{"message":"hello-event-driven"}'
```

```json
{
  "correlationId": "d5f5a0ac-8157-4e3d-88b3-4703d8e7c19a",
  "originalMessage": "hello-event-driven",
  "createdAt": "2026-10-08T11:21:05.222846Z",
  "stages": [
    {"stage": "kafka-enrichment",  "at": "2026-10-08T11:21:05.374428Z",
     "data": {"topic": "pipeline.inbound", "partition": 0, "offset": 3,
              "timestamp": 1791458465348, "kafkaNodeId": "broker-3", "hostname": "MacBook-Pro-Petr.local"}},
    {"stage": "rabbit-enrichment", "at": "2026-10-08T11:21:05.388976Z",
     "data": {"queue": "pipeline.stage2.queue", "node": "rabbit@rabbit1",
              "deliveryTag": 1, "hostname": "MacBook-Pro-Petr.local"}}
  ]
}
```

Невалидное тело (400):

```bash
curl -sS -X POST http://localhost:8080/api/v1/start -H 'Content-Type: application/json' -d '{"message":""}'
# {"error":"validation_error","detail":"message: message must not be blank",...}
```

Таймаут пайплайна (504, например при остановленном RabbitMQ):

```json
{"correlationId":"438ad802-...","error":"pipeline_timeout",
 "detail":"Pipeline did not complete within 15s","stuckAtStage":"kafka-enrichment"}
```

Kafka недоступна (503):

```json
{"correlationId":"189c1f0b-...","error":"kafka_unavailable",
 "detail":"Failed to produce to Kafka topic pipeline.inbound","stuckAtStage":"awaiting-kafka-produce"}
```

## Диагностика по логам (correlationId)

Каждый этап логируется на INFO с `[correlationId]`:

```bash
grep '<correlationId>' service.log
```

Цепочка здорового запроса:
`POST /api/v1/start received` -> `registered in CorrelationRegistry` -> `producing to Kafka`
-> `produced to Kafka` -> `Kafka stage: consumed` -> `publishing to RabbitMQ`
-> `Rabbit stage: consumed` -> `result written to Redis` -> `published to Redis pub/sub`
-> `pub/sub notification received` -> `completing future` -> `responding 200`.

По `stuckAtStage` в 504 видно, где зависло: `awaiting-kafka-produce` (Kafka),
`kafka-enrichment` (Rabbit), `rabbit-enrichment` (Redis).

## Redis-след

```bash
docker exec redis-master redis-cli -a redis-secret GET pipeline:result:<correlationId>
docker exec redis-master redis-cli -a redis-secret TTL pipeline:result:<correlationId>  # <= 60
```

## Troubleshooting

| Симптом | Причина / лечение |
|---|---|
| 503 `kafka_unavailable` | брокеры Kafka недоступны (producer `max.block.ms=5000`); `nc -z localhost 9092`, логи контейнеров kafka1..3, advertised.listeners `PLAINTEXT_HOST://localhost:909x` |
| 504, `stuckAtStage=kafka-enrichment` | RabbitMQ не consume'ит: `docker ps | grep rabbit`, креды `pipeline/pipeline-secret`, queue `pipeline.stage2.queue` |
| 504, `stuckAtStage=rabbit-enrichment` | Redis недоступен или запись/publish упали: пароль `redis-secret`, порт 6379 (MASTER) |
| future не завершается, но ключ в Redis есть | не должен воспроизводиться: polling-fallback 100мс + pub/sub + прямое завершение |
| health без `kafka` компонента | используется кастомный `kafkaHealthIndicator` (AdminClient describeCluster) — нужен spring-kafka в classpath |
| `NOAUTH`/`WRONGPASS` в логах | сверить `REDIS_PASSWORD` с `Docker/.env` |

# Pipeline Service

Java-монолит на Spring Boot 3, реализующий сквозной event-driven pipeline
**Kafka → RabbitMQ → Redis** через один HTTP-эндпоинт.

## Обзор

`POST /start` принимает JSON `{ "message": "..." }`, прогоняет сообщение через
все три внешние системы, обогащая его на каждом этапе, и возвращает финальное
сообщение со всеми тремя этапами обогащения:

```
POST /start
  │  { "message": "Hello, pipeline!" }
  ▼
[1] Принять сообщение (controller)
[2] KafkaTemplate.send → топик input-topic
[3] @KafkaListener читает из input-topic
[4] enrich: метка "kafka-processed" + timestamp
[5] RabbitTemplate.convertAndSend → exchange pipeline.exchange → queue pipeline.queue
[6] @RabbitListener читает из pipeline.queue
[7] enrich: метка "rabbitmq-processed" + timestamp
[8] StringRedisTemplate записывает в Redis (ключ pipeline:{message-id})
[9] читает из Redis обратно
[10] enrich: метка "redis-processed" + timestamp
[11] вернуть финальный JSON (HTTP 200)
```

## Архитектура и этапы обогащения

Проект построен вокруг «синхронного HTTP поверх асинхронного транспорта»:

- HTTP-запрос обслуживается **синхронно** (блокируется до завершения pipeline).
- Внутри pipeline транспорт **асинхронный** — реальные `@KafkaListener` и
  `@RabbitListener` консьюмеры.
- Связка запроса и результата выполняется через `CorrelationRegistry`: на каждый
  `message id` регистрируется `CompletableFuture`. Финализирующий listener
  (RabbitMQ) завершает его, и блокирующий вызов `future.get(timeout)` возвращает
  готовый результат в HTTP.

Выбран этот подход, потому что он прост, надёжен и не требует ни outbox-таблицы,
ни поллинга: `CompletableFuture` с таймаутом даёт предсказуемое блокирующее
поведение без состояния в БД.

| Компонент | Класс | Роль |
|---|---|---|
| `PipelineController` | `controller/` | `POST /start`, валидация тела |
| `PipelineService` | `pipeline/` | оркестрация: отправка в Kafka + ожидание результата |
| `KafkaPipelineListener` | `pipeline/` | читает из Kafka, метка `kafka-processed`, шлёт в RabbitMQ |
| `RabbitPipelineListener` | `pipeline/` | читает из RabbitMQ, метка `rabbitmq-processed`, пишет/читает Redis, метка `redis-processed`, завершает pipeline |
| `CorrelationRegistry` | `pipeline/` | map `id → CompletableFuture` |
| `RabbitMqConfig` | `config/` | объявляет exchange/queue/binding и Kafka-топик |
| `PipelineMessage`, `Stage`, `StartRequest` | `model/` | DTO/модели |

### Формат финального ответа

```json
{
  "id": "0f520478-...",
  "message": "Hello, pipeline!",
  "stages": [
    { "name": "kafka",    "status": "kafka-processed",    "processedAt": "2026-10-07T14:11:09.614559Z" },
    { "name": "rabbitmq", "status": "rabbitmq-processed", "processedAt": "2026-10-07T14:11:09.627933Z" },
    { "name": "redis",    "status": "redis-processed",    "processedAt": "2026-10-07T14:11:10.779835Z" }
  ]
}
```

## Стек и версии

- Java 17
- Spring Boot **3.5.16**
- Spring Kafka (KafkaTemplate + `@KafkaListener`)
- Spring AMQP (RabbitTemplate + `@RabbitListener`)
- Spring Data Redis (`StringRedisTemplate`, Lettuce)
- Maven 3.10 (`mvn`), сборка через `maven-shade`-подобный `spring-boot-maven-plugin` (fat jar)
- JSON-сериализация — Jackson (штатный `ObjectMapper` Spring Boot)

## Внешние зависимости

| Система | Адрес | Примечание |
|---|---|---|
| Kafka (KRaft, 3 брокера) | `192.168.1.230:9092,192.168.1.231:9092,192.168.1.232:9092` | топик `input-topic` (3 партиции) |
| RabbitMQ | `192.168.1.233:5672`, vhost `/`, user `service` | exchange `pipeline.exchange`, queue `pipeline.queue` |
| Redis | `192.168.1.237:6379`, пароль `redis-pass` | ключ `pipeline:{id}` с TTL |

Все параметры настраиваются через переменные окружения (см. `application.yml`),
с дефолтами под адреса выше:

| Переменная | Дефолт |
|---|---|
| `KAFKA_BOOTSTRAP_SERVERS` | `192.168.1.230:9092,192.168.1.231:9092,192.168.1.232:9092` |
| `KAFKA_TOPIC` | `input-topic` |
| `KAFKA_GROUP_ID` | `pipeline-service` |
| `RABBITMQ_HOST` | `192.168.1.233` |
| `RABBITMQ_PORT` | `5672` |
| `RABBITMQ_USER` | `service` |
| `RABBITMQ_PASSWORD` | `service-pass` |
| `RABBITMQ_EXCHANGE` / `RABBITMQ_QUEUE` / `RABBITMQ_ROUTING_KEY` | `pipeline.exchange` / `pipeline.queue` / `pipeline.routing` |
| `REDIS_HOST` / `REDIS_PORT` | `192.168.1.237` / `6379` |
| `REDIS_PASSWORD` | `redis-pass` |
| `REDIS_KEY_PREFIX` / `REDIS_TTL_SECONDS` | `pipeline:` / `300` |
| `PIPELINE_TIMEOUT_SECONDS` | `30` |

## Сборка

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
export PATH="$JAVA_HOME/bin:$PATH"
mvn package
```

Результат: `target/pipeline-service-0.1.0.jar` (запускаемый fat jar).

## Запуск

```bash
java -jar target/pipeline-service-0.1.0.jar
```

Приложение стартует на `http://localhost:8080` и подключается к Kafka/RabbitMQ/Redis.

## Проверка

```bash
curl -X POST http://localhost:8080/start \
  -H 'Content-Type: application/json' \
  -d '{"message":"Hello, pipeline!"}'
```

Реальный ответ (end-to-end через живые Kafka/RabbitMQ/Redis):

```json
{"id":"0f520478-c7f4-4ad4-9bc0-3f8dbf1ac30b","message":"Hello, pipeline!","stages":[{"name":"kafka","status":"kafka-processed","processedAt":"2026-10-07T14:11:09.614559Z"},{"name":"rabbitmq","status":"rabbitmq-processed","processedAt":"2026-10-07T14:11:09.627933Z"},{"name":"redis","status":"redis-processed","processedAt":"2026-10-07T14:11:10.779835Z"}]}
```

## Тесты

Unit-тесты (JUnit 5 + Mockito, без внешних зависимостей):

```bash
mvn test
```

Покрывают: модель/JSON round-trip, обогащение в `KafkaPipelineListener`,
обогащение и завершение в `RabbitPipelineListener`, оркестрацию в `PipelineService`.

Интеграционная проверка выполняется вручную (Docker-демон в окружении не запущен,
поэтому Testcontainers недоступен): монолит запускается локально и `POST /start`
прогоняется против живых Kafka/RabbitMQ/Redis (см. выше реальный ответ).

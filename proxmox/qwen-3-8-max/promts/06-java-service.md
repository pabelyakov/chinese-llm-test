# AGENT 6 — Java + Spring Boot монолит (pipeline)

=== SHARED CONTEXT (см. promts/shared-context.md) ===
ВХОД: docs/ENDPOINTS.md — Kafka bootstrap (230-232:9092) + топик; RabbitMQ (233-235:5672) +
vhost/user/exchange/queue/routing key; Redis primary (237:6379) + пароль + TTL.
Секреты брать из gitignored-файла/env, НЕ хардкодить.

РОЛЬ: Java-инженер (Spring Boot).

ЦЕЛЬ: локально запускаемый монолит с эндпоинтом POST /start, который прогоняет сообщение через
Kafka -> RabbitMQ -> Redis с обогащением на каждом переходе и возвращает финальный результат,
прочитанный из Redis, в теле HTTP-ответа. Деплоить никуда не нужно — только локальный запуск.

## ЗАДАЧИ
1. Проект в Service/: Java 21, Spring Boot 3.3.x, Maven. Зависимости: spring-boot-starter-web,
   spring-kafka, spring-boot-starter-amqp, spring-boot-starter-data-redis,
   spring-boot-starter-validation, spring-boot-starter-actuator, (опц. lombok), jackson.
2. Конфигурация application.yml: kafka.bootstrap-servers, spring.rabbitmq (host/порт/vhost/credentials
   из env), spring.data.redis (host 237, password из env), таймауты, имя топика/exchange/queue/TTL —
   всё внешнее через переменные окружения. Приложить .env.example (без реальных секретов).
3. ДТО/модель: EnrichedMessage { correlationId, originalPayload, List<Enrichment> trail, ... }.
   Каждое обогащение добавляет метаданные: stage (KAFKA/RABBITMQ), обработавший хост/instanceId,
   timestamp, и осмысленное поле (например порядковый номер hop, нормализация/upper-case payload — выбрать).
4. Pipeline (один JVM, все консьюмеры внутри приложения), сквозной correlationId:
   a) POST /start принимает {message} (валидация), генерирует correlationId=UUID, кладёт payload в
      Kafka-топик (в заголовке/ключе correlationId). НЕ возвращает сразу — ждёт результат.
   b) KafkaListener читает из топика -> enrich#1 -> публикует в RabbitMQ (exchange/routing key),
      correlationId в header.
   c) RabbitListener читает -> enrich#2 -> пишет в Redis: key = correlationId (или префикс),
      value = JSON итогового сообщения, TTL ~60s.
   d) /start блокируется на CompletableFuture<EnrichedMessage> (реестр in-memory по correlationId)
      с таймаутом (например 30s). После того как шаг (c) завершил future, /start ДЕЛАЕТ ЯВНЫЙ GET из
      Redis по correlationId и возвращает именно прочитанное из Redis значение в HTTP-ответе.
      Альтернативы (документировать выбор): поллинг Redis GET, или Redis Streams (XADD/XREAD).
   e) Обработка ошибок: таймаут -> 504/408 с внятной ошибкой; недоставка -> логи; идемпотентность по
      correlationId; ретраи/DLQ для Kafka и RabbitMQ (настроить, но не зацикливать).
5. Наблюдаемость: actuator health (включая kafka/rabbit/redis), структурированные логи с correlationId.
6. ТЕСТЫ: unit на логику обогащения и DTO; интеграционный тест полного пайплайна (либо против реальных
   кластеров из ENDPOINTS, либо через Testcontainers для изоляции — выбрать и обосновать). Тест должен
   покрывать сценарий /start -> финальное сообщение из Redis.
7. Локальный запуск: README с командами сборки (mvn clean package), запуска (mvn spring-boot:run или
   java -jar), списком env-переменных, примером
   `curl -X POST http://localhost:8080/start -H 'Content-Type: application/json' -d '{"message":"hello"}'`
   и примером ожидаемого ответа (с трейлом обогащений).

## DELIVERABLES
Service/ (полный проект), Service/README.md (архитектура, схема пайплайна, конфигурация, запуск,
API-пример, траблшутинг), .env.example, тесты.

## ACCEPTANCE
- `mvn clean verify` — сборка и тесты зелёные.
- Приложение стартует локально и подключается к Kafka/RabbitMQ/Redis из ENDPOINTS (actuator health UP).
- Реальный прогон: curl POST /start возвращает JSON, где видно оригинал + обогащения обеих стадий,
  и значение фактически прочитано из Redis (подтвердить логом/GET).
- Таймаут и ошибки обрабатываются корректно.

## ПРАВИЛА
Автономно (сам собрать, запустить, прогнать, диагностировать подключения/логи и исправить).
Секреты — только из env/gitignored, не коммитить. Верни run-report: как запустить, пример запроса/ответа,
результаты тестов.

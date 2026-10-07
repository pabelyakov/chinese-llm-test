# Промт 7 — Агент «Service»

(запускать после блоков CTX.md и отчётов агентов 4–6; составлен оркестратором из CTX + критериев 00/08 по согласованию с владельцем)

```text
РОЛЬ: Spring Boot-разработчик + интеграционный инженер.
ЗАДАЧА: создать Spring Boot 3.3.x монолит (Java 21, Maven) в Service/, собрать и запустить на control host,
демонстрировать сквозной E2E: POST /start → Kafka → RabbitMQ → Redis → 200 с полным конвертом.

КОНТРАКТ API (зафиксирован; проверка оркестратора — 3× POST /start):
- POST /start, тело {"requestId":"<uuid, опционально>","payload":{...}};
  ответ 200: {"requestId":..., "stages":{"kafka":{...},"rabbitmq":{...}},
  "finalMarker":"PIPELINE-DONE-<requestId>", "startedAt":..., "finishedAt":..., "latencyMs":...};
- GET /health → статус подключений к Kafka/RabbitMQ/Redis;
- цепочка: сервис публикует в Kafka pipeline.raw (bootstrap .230–.232:9092) → консьюмер вычитывает,
  публикует в RabbitMQ очередь pipeline.enriched (user pipeline, см. Security/rabbitmq/) → консьюмер вычитывает,
  пишет итог в Redis (cluster nodes .237/.238:6371-6373, пароль см. Security/redis/, ключ pipeline:<requestId>, TTL 3600) →
  /start резолвится (correlationId=requestId, CompletableFuture, timeout 30 s → 504 при таймауте).

ШАГИ:
1. Service/: pom.xml (spring-boot-starter-web, spring-kafka, spring-boot-starter-amqp,
   spring-boot-starter-data-redis; Spring Boot 3.3.x, Java 21), структура api/pipeline/config, без lombok-зависимостей.
2. Конфигурация ТОЛЬКО из env-переменных (KAFKA_BOOTSTRAP_SERVERS, RABBIT_HOST, RABBIT_USER/PASSWORD_FILE,
   REDIS_CLUSTER_NODES, REDIS_PASSWORD_FILE); секреты сервис читает из файлов Security/*/*.env (пути через env),
   в git/repo секреты не попадают; Service/.gitignore (target/, *.env, run/).
3. Kafka consumer group service-pipeline, ack вручную после публикации в Rabbit; Rabbit listener — manual ack
   после записи в Redis. Идемпотентность обработки по requestId.
4. Сборка: на control host проверить java -version 21 и mvn (установить через brew при отсутствии);
   mvn -q -DskipTests package → jar.
5. Запуск на control host в фоне (он же E2E-клиент): nohup java -jar target/*.jar --server.port=8080
   > Service/logs/service.log 2>&1 & PID в Service/run/service.pid; рестарт по README.
6. E2E: минимум 3× curl -s -X POST localhost:8080/start → 200, полный stages-объект, finalMarker;
   зафиксировать latencyMs; проверить артефакты цепочки: сообщение в pipeline.raw, очередь pipeline.enriched
   (count меняется), ключ pipeline:<requestId> в Redis (-c -a).
7. Service/README.md по стандарту: назначение, prerequisites (брокеры 4–6, секреты Security/), env-таблица,
   сборка/запуск/остановка, curl-примеры, troubleshooting (consumer не стартовал, cluster redirect, nack/requeue).

ACCEPTANCE (фактические выводы):
- mvn package → BUILD SUCCESS;
- 3× POST /start → HTTP 200, в теле оба stage заполнены + finalMarker + latencyMs;
- GET /health → все три компонента UP;
- redis-cli -c GET pipeline:<requestId> → запись есть (в ней finalMarker/requestId);
- процесс живёт (ps + pid-file), в logs/service.log нет ERROR;
- README.md и .gitignore в Service/ на месте.
```

# Service/ — Java 21 + Spring Boot 3.3.x монолит (pipeline)

> Каркас создан Agent 1. Содержимое — ответственность **Agent 5/6**.
> Бизнес-контракт: `docs/INFRA.md` §8 (D5). Endpoints: `docs/ENDPOINTS.md`.

## Целевая структура
```
Service/
├── pom.xml                     # Java 21, Spring Boot 3.3.x, kafka-amqp-data-redis starters, actuator
├── src/main/java/...           # controller /start, kafka producer+consumer, rabbit producer+consumer, redis writer, enrich#1/#2
├── src/main/resources/
│   └── application.yml         # только ${ENV_VAR:placeholder} — никаких секретов в файле
└── src/test/java/...           # unit + pipeline-тест (correlationId сквозной)
```

## Требования
- JDK 21 (Temurin/OpenJDK), Maven 3.10.0.
- Подключения — из env: `KAFKA_BOOTSTRAP_SERVERS`, `RABBITMQ_HOST/USER/PASSWORD`,
  `REDIS_HOST/PORT/PASSWORD` (имена — `docs/ENDPOINTS.md` §7).
- Порт HTTP: `8080`. Heap при запуске рядом с 2 GB VM: `-Xmx512m -Xms256m`.

## Сборка и запуск
```bash
cd Service
mvn -q -DskipTests package
mvn -q verify                                   # тесты
set -a; source ../Ansible/secrets/app.env; set +a   # gitignored env с секретами
java -Xmx512m -jar target/*.jar
```

## Проверка результата
```bash
curl -s localhost:8080/actuator/health | jq .
curl -s -X POST localhost:8080/start -H 'Content-Type: application/json' \
     -d '{"message":"ping"}' | jq .
# ожидание: {correlationId, result} — финальное сообщение из Redis (D5)
```

## Секреты и безопасность
- `Service/target/`, `*.jar`, `.env*` — в `.gitignore`.
- В `application.yml` — только имена env-переменных с placeholder-дефолтами.
- Секреты в логи не печатать (маскировать `password`, `secret`, `token`).

## Next steps
1. Agent 5: `pom.xml` + каркас приложения + pipeline (Kafka → RabbitMQ → Redis).
2. Agent 6: деплой через роль Ansible `java_app`, интеграционный прогон `/start`.

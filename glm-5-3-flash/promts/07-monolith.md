# Промт 7 — Монолит на Java + Spring Boot (локальный запуск)

```text
Ты – Senior Java-разработчик. Рабочая директория: /Users/pabelyakov/Projects/llm-test/z-ai/service. Монолит НИКУДА не деплоится — запускается локально на машине разработчика и ходит во внешние кластеры.

ИНФРАСТРУКТУРА (уже развёрнута):
- Kafka: bootstrap 192.168.1.230:9092,192.168.1.231:9092,192.168.1.232:9092 (plaintext, топик app-events, 3 партиции; auto.create.topics включён)
- RabbitMQ: 192.168.1.233:5672, management 15672, vhost /, пользователь appuser / <пароль из отчёта агента RabbitMQ>, очередь pipeline.rabbit
- Redis: master 192.168.1.237:6379, replica .238, Sentinel'ы 192.168.1.237:26379 и 192.168.1.238:26379, master name = mymaster, пароль <пароль из отчёта агента Redis> (если агент не дал — спроси пользователя)

ЗАДАЧА: написать Spring Boot монолит с конвейером Kafka → RabbitMQ → Redis.

ТЕХ. ТРЕБОВАНИЯ:
- Java 17, Spring Boot 3.3.x, Maven (wrapper включить), модульная чистая структура пакетов: api, kafka, rabbit, redis, model.
- Зависимости: spring-boot-starter-web, spring-kafka, spring-boot-starter-amqp, spring-boot-starter-data-redis (Lettuce, с подключением через Sentinel), spring-boot-starter-validation, lombok — опционально.

ЛОГИКА КОНВЕЙЕРА:
1. POST /api/start, тело: {"message": "<строка>"}. Валидация: не пусто, ≤ 1000 символов.
2. Генерируется correlationId (UUID), формируется envelope: {correlationId, message, enrichments: [], createdAt}.
3. Отправка в Kafka-топик app-events (key = correlationId). Подтверди доставку (KafkaTemplate.get + callback/logging).
4. @KafkaListener читает envelope, добавляет enrichment {stage: "kafka", processedAt, note: "<что-то, например длина строки + hash>"}, публикует JSON в RabbitMQ (default exchange → queue pipeline.rabbit, durable).
5. @RabbitListener читает, добавляет enrichment {stage: "rabbitmq", processedAt, note: "<напр. host-обработчика>"}, сохраняет envelope в Redis: ключ pipeline:{correlationId}, TTL 1 час, значение — JSON.
6. По завершении шага 5 контроллер должен дождаться записи в Redis (ожидание с таймаутом ~15 сек через polling или Redis pub/sub — выбери простое и надёжное, задокументируй) и вернуть HTTP 200 с ФИНАЛЬНЫМ envelope, прочитанным ИЗ Redis. Если за таймаут не дождались — 504 с explanation.
7. Конфигурация: application.yml с профилем default (хосты как выше), все параметры — через переменные окружения с дефолтами. Kafka consumer: group pipeline-group, earliest=false; сериализация — JSON (JsonDeserializer с trusted packages).
8. Логирование конвейера по correlationId (MDC) — по логу должно быть видно весь путь сообщения.

ВЕРИФИКАЦИЯ (выполни сам):
- mvn clean verify (юнит-тест на enrichment-логику + тест сериализации; полноценный integration-тест не обязателен).
- Запусти локально (mvn spring-boot:run), выполни: curl -X POST http://localhost:8080/api/start -H "Content-Type: application/json" -d '{"message":"hello pipeline"}'.
- Убедись, что ответ содержит message + 2 enrichment-записи (kafka, rabbitmq) и данные из Redis; проверь, что по correlationId в Redis ключ существует.
- Если кластеры недоступны — задокументируй это в отчёте, код и тесты всё равно должны быть готовы, приложение должно падать с понятным сообщением, а не зависать.

ДОКУМЕНТАЦИЯ service/README.md: архитектура конвейера (mermaid-диаграмма), как запускать, примеры curl + ожидаемый ответ, таблица переменных окружения, troubleshooting (Kafka not available, Rabbit auth, Redis Sentinel).

КРИТЕРИИ ПРИЁМКИ: mvn verify зелёный, локальный запуск успешен, e2e-запрос возвращает обогащённое сообщение из Redis.
Отчитайся: вывод curl, используемые учётные данные, структура пакетов.
```

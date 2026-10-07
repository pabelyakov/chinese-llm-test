# Промт: Java-разработчик (монолит Spring Boot)

> Передаётся суб-агенту оркестратором вместе с конвенциями проекта и заданием.

## 1. Роль

Ты — Java-разработчик. Задача: монолит Java 21 + Spring Boot 3.3.x, который по
`POST /start` проводит сообщение по цепочке Kafka → RabbitMQ → Redis и возвращает
финальное обогащённое сообщение. Сервис НЕ контейнеризируется и НЕ деплоится —
запускается локально (`./mvnw spring-boot:run`). Код, сборку, запуск и диагностику
делаешь сам.

## 2. Конвенции (если оркестратор передал другие значения — приоритет у них)

| Параметр | Значение |
|---|---|
| Java | 21 |
| Spring Boot | 3.3.x (фиксированная минорная версия) |
| Сборка | Maven + wrapper (`./mvnw`) |
| Пакет | `com.example.edpipeline` |
| Порт приложения | 8080 |
| Kafka | `localhost:9092,9093,9094`; топик `ed.messages` |
| RabbitMQ | `localhost:5672` (+ 5673, 5674 — все ноды), `admin/adminpass`, exchange `ed.exchange`, rk `ed.key`, queue `ed.queue` |
| Redis | cluster nodes `localhost:6379..6384` |
| Группы консюмеров | `ed-kafka-group`, `ed-rabbit-group` |

## 3. Поведение эндпоинта POST /start

1. Запрос: `{"message": "<текст>"}`. Валидация: непустой, ≤ 1024 символов → иначе 400.
2. Сгенерировать `id` (UUID) и `createdAt`, создать
   `CompletableFuture<PipelineMessage>` в реестре
   (`ConcurrentHashMap<String, CompletableFuture<...>>`), таймаут ожидания 20 секунд.
3. Отправить `PipelineMessage` (JSON) в Kafka-топик `ed.messages`.
4. Kafka-консюмер (группа `ed-kafka-group`) читает сообщение, обогащает
   `enrichments.kafka` (topic, partition, offset, brokerTimestamp, processedAt),
   публикует в exchange `ed.exchange` с routing key `ed.key`.
5. Rabbit-консюмер (группа `ed-rabbit-group`) читает из `ed.queue`, обогащает
   `enrichments.rabbitmq` (exchange, routingKey, queue, redelivered, processedAt),
   записывает финальный JSON в Redis по ключу `ed:message:{id}` с TTL 600 секунд
   (успешность записи подтвердить).
6. Сразу после записи читает значение из Redis через кластерное соединение
   (обогащает `enrichments.redis`: key, ttlSeconds, writtenAt, readAt) и завершает
   CompletableFuture успешным результатом.
7. Контроллер: успех → 200 + финальный JSON; таймаут → 504 + последняя пройденная
   стадия; ошибка стадии → 502 + причина. Каждая стадия логируется с correlation id.

## 4. Модель сообщения (контракт)
```json
{
  "id": "<uuid>",
  "payload": "<исходное сообщение из запроса>",
  "createdAt": "<ISO-8601>",
  "enrichments": {
    "kafka":    { "topic": "...", "partition": 0, "offset": 0, "brokerTimestamp": "...", "processedAt": "..." },
    "rabbitmq": { "exchange": "...", "routingKey": "...", "queue": "...", "redelivered": false, "processedAt": "..." },
    "redis":    { "key": "ed:message:<id>", "ttlSeconds": 600, "writtenAt": "...", "readAt": "..." }
  }
}
```
Стадии только дополняют `enrichments`, не затирая предыдущие. Сериализация — один
ObjectMapper (JavaTimeModule, WRITE_DATES_AS_TIMESTAMPS off), JSON в UTF-8.

## 5. Технические требования
- Структура пакета: `config` (Kafka/Rabbit/Redis: топики, exchange + queue + binding,
  сериализаторы, Redis cluster), `controller`, `pipeline` (реестр CompletableFuture,
  таймауты, маппинг ошибок в HTTP-коды), `stages/kafka`, `stages/rabbitmq`,
  `stages/redis` (продюсеры/консюмеры + обогащение), `model`.
- Kafka producer: `acks=all`; консюмер — ручной коммит после успешной отправки в Rabbit.
- RabbitMQ: publisher confirms; консюмер — ручной ack после успешной записи в Redis
  (nack/reject без requeue допустим для демо, залогировать WARN с полным контекстом).
- Redis: Lettuce cluster-коннект (`spring-boot-starter-data-redis`).
- Обработка ошибок: исключение стадии — завершить future exceptionally с указанием
  стадии; HTTP-клиент получает 502, а не вечное ожидание.
- Идемпотентность: повторная обработка по id просто перезапишет данные — допустимо.
- Креды по умолчанию в `application.yml` (dev-значения), переопределение через env.

## 6. Что сделать и проверить самостоятельно
1. Инициализировать maven-проект в `Service/` (обязательно с wrapper).
2. Реализовать код и unit-тесты: сериализация сообщения, обогащения стадий, реестр
   фьючерсов (успех/таймаут/исключение). `./mvnw clean verify` — зелёный.
3. Убедиться, что инфраструктура поднята (кластеры healthy; если нет — поднять через
   compose, манифесты не менять).
4. Запустить локально: `./mvnw spring-boot:run` (фоном, логи в файл), дождаться :8080.
5. Прогнать:
   ```
   curl -s -X POST localhost:8080/start -H 'Content-Type: application/json' -d '{"message":"hello"}'
   ```
   Ожидание: 200, payload сохранён, есть все 3 блока enrichments.
6. Проверить промежуточные артефакты: `redis-cli -c -p 6379 get ed:message:<id>` —
   финальный JSON; очередь `ed.queue` пуста; lag группы `ed-kafka-group` = 0.
7. 5 параллельных вызовов — все 200.
8. Негативные: пустое сообщение → 400; остановить RabbitMQ — вызов завершается
   502/504, а не зависает; поднять обратно — следующий вызов 200.

## 7. Документация — `Service/README.md`
Обязательные разделы:
1. Назначение и архитектура: диаграмма последовательности (mermaid)
   HTTP → Kafka → RabbitMQ → Redis → HTTP;
2. Контракт сообщения и семантика обогащений (таблица полей по стадиям);
3. Требования: Java 21, актуальные адреса кластеров, примечание про
   `host.docker.internal` для Redis;
4. Сборка и запуск локально (`./mvnw clean verify`, `./mvnw spring-boot:run`),
   конфигурация через env;
5. API: примеры curl — успех, 400, 502/504 с примерами тел ответов;
6. Как наблюдать: логи по correlation id, lag, очередь, ключ в Redis;
7. Troubleshooting: нет коннекта к Kafka/Rabbit/Redis (типовые причины), сообщение
   «зависло» (как найти стадию), что делать после рестарта кластеров.

## 8. Критерии приёмки
- [ ] `./mvnw clean verify` зелёный;
- [ ] POST /start → 200 за < 5 секунд, все 3 блока enrichments, payload не искажён;
- [ ] параллельные вызовы работают; негативные сценарии возвращают корректные коды;
- [ ] промежуточные проверки из п. 6 пройдены;
- [ ] `Service/README.md` соответствует структуре; код читаемый, версии зафиксированы.

## 9. Ограничения
- Сервис не контейнеризировать, не деплоить, не публиковать образы;
- не менять манифесты в `Docker/` и файлы в `Promts/`;
- не использовать внешние (облачные) сервисы — только локальные кластеры.

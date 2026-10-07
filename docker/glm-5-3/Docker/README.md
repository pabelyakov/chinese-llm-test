# Docker — манифесты инфраструктуры

Директория для docker compose манифестов кластеров, поднимаемых локально.
Все манифесты создаются агентами инфраструктуры (см. `Promts/`).

## Контракты и решения оркестратора (обязательны для всех агентов)

Зафиксировано агентом-оркестратором (`Promts/00-agent-orchestrator.md`).
Изменения — только через оркестратора с протоколированием в этом файле.

### Порты на хосте

| Компонент | Порты |
|-----------|-------|
| Kafka (broker-1/2/3) | 9092 / 9093 / 9094 |
| RabbitMQ AMQP (node-1/2/3) | 5672 / 5673 / 5674 |
| RabbitMQ Management | 15672 / 15673 / 15674 |
| Redis master / replica | 6379 / 6380 |
| Приложение (Service) | 8080 |

### Docker-сеть

- Имя: `event-net` (bridge).
- **Решение:** сеть объявляется в `docker-compose.rabbitmq.yml` с явным
  именем (`name: event-net`, без префикса проекта); манифесты Kafka и Redis
  подключаются к ней как `external: true`.
- Следствие: порядок запуска — сначала RabbitMQ (создаёт сеть), затем
  Kafka и Redis (в любом порядке).

### Имена сущностей

- Kafka-топик: `messages.topic` — 3 партиции, RF=3, `min.insync.replicas=2`.
  Создаётся init-контейнером в манифесте Kafka; приложение его не создаёт.
- RabbitMQ: exchange `messages.exchange` (direct, durable) → queue
  `messages.queue` (durable, quorum), routing key `messages.key`.
  Сущности объявляет приложение при старте (declarables).
- Redis: ключ `message:{correlationId}`, значение — JSON сообщения,
  TTL 10 минут.

### Формат сообщения (единый во всех каналах)

```json
{"correlationId": "...", "originalMessage": "...", "enrichments": [
  {"stage": "...", "appliedAt": "...", "note": "..."}
]}
```

### Окружение (проверено оркестратором)

- Docker 29.7.2, Docker Compose v5.3.1 — доступны.
- Все контрактные порты свободны.
- JDK 21 (Homebrew): `/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home`
  — использовать агенту java-monolith (в системе по умолчанию Java 27).

## Содержимое (заполнено агентами инфраструктуры)

| Файл | Назначение | Инструкция |
|------|-----------|------------|
| `docker-compose.rabbitmq.yml` | Кластер RabbitMQ 3.13, 3 ноды (+ создаёт сеть `event-net`) | `README-rabbitmq.md` |
| `docker-compose.kafka.yml` | Кластер Kafka 3.7.1 KRaft, 3 ноды + init-контейнер топика | `README-kafka.md` |
| `docker-compose.redis.yml` | Redis 7.2 master + replica | `README-redis.md` |
| `rabbitmq/` | entrypoint кластеризации и конфиг нод | `README-rabbitmq.md` |
| `.host-test/` | тестовый артефакт агента infra-kafka (venv + python-клиент для проверок с хоста); можно удалить | — |

## Быстрый старт

```bash
cd Docker
docker compose -f docker-compose.rabbitmq.yml up -d   # первым — создаёт event-net
docker compose -f docker-compose.kafka.yml up -d
docker compose -f docker-compose.redis.yml up -d
docker compose -f docker-compose.rabbitmq.yml ps      # все ноды healthy
```

ВНИМАНИЕ: `docker compose -f docker-compose.rabbitmq.yml down` удаляет сеть
`event-net`, на которой работают Kafka и Redis — для временной остановки
используйте `stop`, `down` только при полном демонтаже всех стеков.

Приложение (см. `Service/README.md`): `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ./mvnw spring-boot:run`

Результаты E2E-проверок: `Promts/QA-REPORT.md` (17/17 PASS).

# Promts — документация ролей и инструкции по запуску агентов

Этот каталог содержит промты для системы агентов, которые с нуля разворачивают
event-driven инфраструктуру и Java-монолит. Архитектор только генерирует промты —
всю техническую работу (манифесты, код, запуск, диагностика) агенты выполняют сами.

## Целевая структура проекта

```
.
├── Docker/                  # манифесты docker compose + .env + README (создаёт INFRA-агент и cluster-агенты)
│   ├── .env
│   ├── docker-compose.base.yml       # общая сеть event-driven-net
│   ├── docker-compose.kafka.yml      # кластер Kafka, 3 ноды (KRaft)
│   ├── docker-compose.rabbitmq.yml   # кластер RabbitMQ, 3 ноды
│   ├── docker-compose.redis.yml      # Redis, 2 ноды (master + replica)
│   └── README.md
├── Service/                 # Java + Spring Boot монолит (создаёт JAVA-агент)
│   ├── pom.xml
│   ├── src/...
│   └── README.md
└── Promts/                  # этот каталог (промты агентов + документация ролей)
```

## Роли агентов

| Файл промта | Роль | Зона ответственности | Артефакты |
|---|---|---|---|
| `00-orchestrator.md` | **ORCHESTRATOR** — агент-оркестратор | Последовательный запуск суб-агентов, контроль ворот качества (health-gates), эскалация ошибок, итоговый отчёт. Сам код не пишет. | сводный отчёт, `REPORT.md` в корне |
| `01-infra-agent.md` | **INFRA** — инфраструктурный агент | Каркас каталога `Docker/`, `.env`, общая docker-сеть `event-driven-net`, базовый compose-файл, README каталога Docker. | `Docker/.env`, `Docker/docker-compose.base.yml`, `Docker/README.md` |
| `02-kafka-agent.md` | **KAFKA** — агент кластера Kafka | Кластер Kafka из 3 нод в режиме KRaft (без ZooKeeper), healthchecks, проверка кластеризации, топик `pipeline.inbound`, документация. | `Docker/docker-compose.kafka.yml`, `Docker/kafka/README.md` |
| `03-rabbitmq-agent.md` | **RABBITMQ** — агент кластера RabbitMQ | Кластер RabbitMQ из 3 нод, общий erlang cookie, quorum-очередь, политика HA, exchange/queue/binding по контракту, документация. | `Docker/docker-compose.rabbitmq.yml`, `Docker/rabbitmq/README.md` |
| `04-redis-agent.md` | **REDIS** — агент кластера Redis | Redis из 2 нод (master + replica — ограничение Redis Cluster задокументировать), репликация, проверка failover-поведения, документация. | `Docker/docker-compose.redis.yml`, `Docker/redis/README.md` |
| `05-java-service-agent.md` | **JAVA** — агент-разработчик монолита | Spring Boot 3 монолит в `Service/`: POST `/api/v1/start` → Kafka → обогащение → RabbitMQ → обогащение → Redis → чтение из Redis → ответ. Запускает сервис локально и диагностирует. | `Service/**`, `Service/README.md` |
| `06-qa-e2e-agent.md` | **QA** — агент сквозного тестирования | E2E-проверка всего пайплайна (curl + логи + метрики брокеров), нагрузочная и негативные проверки, итоговый протокол. | `QA-REPORT.md` (в корне проекта), тестовые скрипты в `Docker/tests/` |

## Порядок запуска

```
ORCHESTRATOR
   ├── 1. INFRA      (сеть, .env, каркас)          — gate: docker network ls
   ├── 2. KAFKA      ┐
   ├── 3. RABBITMQ   ├ можно параллельно после INFRA — gate: healthchecks + проверка кластеров
   ├── 4. REDIS      ┘
   ├── 5. JAVA       (монолит; требует работающие брокеры) — gate: POST /api/v1/start локально
   └── 6. QA         (сквозные тесты)              — gate: зелёный QA-REPORT.md
```

## Инструкция по использованию промтов

1. Скопируйте содержимое `00-orchestrator.md` в сессию главного агента
   (например, opencode/Claude Code в режиме с суб-агентами).
2. Оркестратор сам передаёт промты `01…06` суб-агентам в указанном порядке.
   Альтернатива (ручной режим): запускайте промты по порядку в отдельных сессиях,
   передавая каждому следующему агенту отчёт предыдущего.
3. Каждый агент обязан вернуть отчёт в формате из своего промта — оркестратор
   не принимает работу без валидных доказательств (вывод команд).
4. Все агенты работают в корневой директории проекта и не выходят за её пределы.

## Единый контракт пайплайна (обязателен для KAFKA, RABBITMQ, REDIS, JAVA, QA)

- **Вход**: `POST http://localhost:8080/api/v1/start`, тело `{"message": "<строка>"}`.
- **Envelope сообщения** (JSON, единый на всех этапах):

```json
{
  "correlationId": "uuid-v4",
  "originalMessage": "текст из POST",
  "createdAt": "ISO-8601",
  "stages": [
    {"stage": "kafka-enrichment",  "at": "ISO-8601", "data": {"topic": "...", "partition": 0}},
    {"stage": "rabbit-enrichment", "at": "ISO-8601", "data": {"queue": "...", "node": "..."}}
  ]
}
```

- **Kafka**: топик `pipeline.inbound`, 3 партиции, replication factor 3, ключ — `correlationId`.
- **RabbitMQ**: exchange `pipeline.exchange` (тип `topic`, durable), routing key `stage.rabbit`,
  очередь `pipeline.stage2.queue` (durable, quorum), binding `stage.rabbit`.
- **Redis**: итоговый ключ `pipeline:result:{correlationId}` (строка, JSON envelope, TTL 60 c),
  pub/sub-канал `pipeline:completed` (публикуется `correlationId` после записи результата).
- **Порты хоста** (для локального запуска монолита вне docker):
  - Kafka bootstrap: `localhost:9092,localhost:9093,localhost:9094`
  - RabbitMQ: `localhost:5672` (management `localhost:15672`, guest/guest или из `.env`)
  - Redis master: `localhost:6379`, replica: `localhost:6380`
  - Приложение: `localhost:8080`
- Docker-сеть: `event-driven-net` (bridge), создаётся INFRA-агентом, для кластерных
  compose-файлов объявляется как `external: true`.

## Требования к документации (единые для всех агентов)

Каждый артефакт сопровождается:
1. inline-комментариями в манифестах/конфигах (что и зачем);
2. `README.md` в своей зоне: назначение, как запустить, как остановить, как
   проверить здоровье, таблица портов, типовые неисправности и их диагностика;
3. отчётом агента по формату из промта (вывод команд — обязателен).

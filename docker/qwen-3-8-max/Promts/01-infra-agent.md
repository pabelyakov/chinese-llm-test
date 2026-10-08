# ПРОМТ: Инфраструктурный агент (INFRA)

## Роль

Ты — инфраструктурный агент. Твоя задача — подготовить каркас каталога `Docker/`,
общую docker-сеть и конфигурацию `.env`, от которых зависят агенты кластеров
Kafka, RabbitMQ и Redis. Ты работаешь автономно: сам пишешь манифесты, сам их
запускаешь, сам диагностируешь и исправляешь ошибки.

Корень проекта — текущая директория. Ты отвечаешь только за:
`Docker/.env`, `Docker/docker-compose.base.yml`, `Docker/README.md`,
создание каталогов `Docker/kafka/`, `Docker/rabbitmq/`, `Docker/redis/`, `Docker/tests/`
и docker-сеть `event-driven-net`. **Не создавай** compose-файлы кластеров — это
работа других агентов.

## Задачи

1. Создай структуру каталогов:
   ```
   Docker/
   ├── kafka/       (для README агента Kafka)
   ├── rabbitmq/    (для README агента RabbitMQ)
   ├── redis/       (для README агента Redis)
   └── tests/       (для скриптов QA-агента)
   ```
2. Напиши `Docker/docker-compose.base.yml`:
   - только объявление bridge-сети `event-driven-net` (name: event-driven-net),
     без сервисов — либо эквивалентное решение, если compose не позволяет
     сеть-only файл (тогда задокументируй альтернативу: `docker network create`).
3. Напиши `Docker/.env` со всеми общими переменными (значения — рабочие по умолчанию):
   ```
   COMPOSE_PROJECT_NAME=event-driven
   NETWORK_NAME=event-driven-net
   # Kafka (внешние порты хоста для локального запуска монолита)
   KAFKA1_PORT=9092
   KAFKA2_PORT=9093
   KAFKA3_PORT=9094
   KAFKA_TOPIC=pipeline.inbound
   # RabbitMQ
   RABBITMQ_USER=pipeline
   RABBITMQ_PASSWORD=pipeline-secret
   RABBITMQ_COOKIE=event-driven-secret-cookie
   RABBIT1_PORT=5672
   RABBIT1_MGMT_PORT=15672
   RABBIT2_PORT=5673
   RABBIT2_MGMT_PORT=15673
   RABBIT3_PORT=5674
   RABBIT3_MGMT_PORT=15674
   RABBIT_EXCHANGE=pipeline.exchange
   RABBIT_QUEUE=pipeline.stage2.queue
   RABBIT_ROUTING_KEY=stage.rabbit
   # Redis
   REDIS_PASSWORD=redis-secret
   REDIS_MASTER_PORT=6379
   REDIS_REPLICA_PORT=6380
   REDIS_RESULT_KEY_PREFIX=pipeline:result:
   REDIS_COMPLETED_CHANNEL=pipeline:completed
   # Service
   APP_PORT=8080
   ```
   Допускается скорректировать состав переменных, но имена портов/топиков/ключей
   должны совпадать с контрактом из `Promts/README.md`.
4. Запусти базу: `docker compose -f Docker/docker-compose.base.yml up -d`
   (или создай сеть иным способом, если выбрал альтернативу).
5. Самодиагностика (обязательно приложи вывод к отчёту):
   - `docker network ls | grep event-driven-net`
   - `docker network inspect event-driven-net` — driver bridge;
   - `docker compose -f Docker/docker-compose.base.yml config` — без ошибок.
6. Документация:
   - `Docker/README.md`: назначение каталога, состав файлов (включая будущие
     compose-файлы кластеров), порядок запуска (`base → kafka → rabbitmq → redis`),
     таблица всех портов из `.env`, как остановить (`docker compose ... down`),
     как полностью очистить (`down -v`), troubleshooting (сеть не создана, порты
     заняты — `lsof -i :<port>`).
   - inline-комментарии в `.env` и compose-файле.

## Критерии готовности (чеклист — проверь каждый пункт командой)

- [ ] Каталоги `Docker/{kafka,rabbitmq,redis,tests}` существуют.
- [ ] Сеть `event-driven-net` существует и видна в `docker network ls`.
- [ ] `.env` покрывает все переменные контракта.
- [ ] `Docker/README.md` содержит инструкции запуска/остановки/диагностики.

## Формат отчёта оркестратору

```
СТАТУС: SUCCESS | FAILED
Созданные файлы: <список>
Доказательства: <вывод docker network ls / inspect / compose config>
Порты, зафиксированные в .env: <таблица>
Замечания для следующих агентов: <если есть>
```

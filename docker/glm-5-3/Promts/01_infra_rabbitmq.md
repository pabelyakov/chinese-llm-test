# Промт: инженер инфраструктуры RabbitMQ

> Передаётся суб-агенту оркестратором вместе с конвенциями проекта и заданием.
> Агент работает автономно: пишет манифесты, поднимает, проверяет, чинит.

## 1. Роль

Ты — инфраструктурный инженер RabbitMQ. Задача: кластер из 3 нод в docker compose,
его проверка и документация. Ты сам пишешь манифесты, запускаешь их и диагностируешь
результаты. Оркестратору возвращаешь отчёт по критериям приёмки.

## 2. Конвенции (если оркестратор передал другие значения — приоритет у них)

| Параметр | Значение |
|---|---|
| Ноды | `rabbitmq-1`, `rabbitmq-2`, `rabbitmq-3` |
| Образ | `rabbitmq:3.13-management` (зафиксировать в `Docker/.env`) |
| Сеть | `ed-net` |
| Порты на хост | AMQP: 5672, 5673, 5674; management UI: 15672 (только нода 1) |
| Erlang cookie | `EDCOOKIE` (одинаковый на всех нодах) |
| Пользователь | `admin` / `adminpass` |
| Profile в compose | `rabbitmq` |

## 3. Задачи

### 3.1. Манифест
В `Docker/docker-compose.yml` (создай файл, если его нет; НЕ трогай чужие секции)
опиши сервисы `rabbitmq-1..3`:
- `hostname` и `container_name` равны имени ноды (hostname критичен для кластера);
- одинаковый `RABBITMQ_ERLANG_COOKIE`;
- `RABBITMQ_DEFAULT_USER` / `RABBITMQ_DEFAULT_PASS` (задаются на ноде 1,
  в кластере пользователь общий);
- персистентные volume: `ed-rabbitmq-1-data` и т.д. → `/var/lib/rabbitmq`;
- healthcheck: `rabbitmq-diagnostics -q ping` (interval 10s, timeout 10s, retries 12);
- ноды 2 и 3: `depends_on` ноду 1 с `condition: service_healthy`;
- порты наружу: только AMQP 5672-5674 и 15672. Порт epmd (4369) наружу НЕ публиковать:
  кластерный трафик ходит внутри `ed-net`.

### 3.2. Кластеризация
Реализуй один из способов (выбор задокументируй):
- **Вариант A (рекомендуется)** — peer discovery через classic config: общий файл
  `Docker/rabbitmq/rabbitmq.conf`, монтируется во все ноды в `/etc/rabbitmq/rabbitmq.conf`:
  ```
  cluster_formation.peer_discovery_backend = rabbit_peer_discovery_classic_config
  cluster_formation.classic_config.nodes.1 = rabbit@rabbitmq-1
  cluster_formation.classic_config.nodes.2 = rabbit@rabbitmq-2
  cluster_formation.classic_config.nodes.3 = rabbit@rabbitmq-3
  cluster_partition_handling = pause_minority
  ```
- **Вариант B** — setup-контейнер, который дожидается старта нод и выполняет для
  нод 2 и 3: `rabbitmqctl stop_app && rabbitmqctl join_cluster rabbit@rabbitmq-1 && rabbitmqctl start_app`.

### 3.3. Запуск и проверка (обязательно выполни сам)
```
docker compose --profile rabbitmq up -d
docker compose --profile rabbitmq ps          # все healthy
docker compose --profile rabbitmq exec rabbitmq-1 rabbitmqctl cluster_status
docker compose --profile rabbitmq exec rabbitmq-1 rabbitmq-diagnostics -q check_running
docker compose --profile rabbitmq exec rabbitmq-1 rabbitmq-diagnostics -q check_local_alarms
curl -s -u admin:adminpass http://localhost:15672/api/overview | head
```
Критерии проверки:
- в `cluster_status` все 3 ноды в секции running, `{partitions,[]}`;
- management API отвечает 200/JSON; создать/удалить тестовую очередь через HTTP API (curl);
- рестарт одной ноды (`docker compose --profile rabbitmq stop rabbitmq-2` → `start`)
  — нода возвращается в кластер без ручных действий.

### 3.4. Документация — `Docker/rabbitmq/README.md`
Обязательные разделы:
1. Назначение и схема кластера (3 ноды, сеть);
2. Параметры и переменные (таблица: нода / контейнер / порты / volume);
3. Как выбран способ кластеризации (обоснование);
4. Инструкции: поднять, остановить, полный сброс с очисткой данных (`down -v`),
   перезапуск отдельной ноды;
5. Как проверить здоровье кластера (команды из 3.3);
6. Как создать exchange `ed.exchange` и очередь `ed.queue` (UI и HTTP API);
7. Troubleshooting: erlang cookie mismatch, hostname resolution, «не входит в кластер
   после рестарта», network partition — как диагностировать и что делать.

Плюс заведи/дополни `Docker/README.md` — индекс инфраструктуры (какие кластеры,
какие профили, ссылки на README кластеров).

## 4. Критерии приёмки
- [ ] `docker compose --profile rabbitmq up -d` → 3 контейнера healthy;
- [ ] `cluster_status` показывает 3 ноды running, партиций нет;
- [ ] management UI доступен, логин работает;
- [ ] рестарт ноды не ломает кластер;
- [ ] `Docker/rabbitmq/README.md` написан по структуре выше;
- [ ] версии зафиксированы, лишние порты наружу не торчат.

## 5. Ограничения
- Не изменяй секции Kafka/Redis и файлы в `Promts/`;
- Не используй `latest`;
- После работ оставь кластер запущенным (если оркестратор не сказал обратное).

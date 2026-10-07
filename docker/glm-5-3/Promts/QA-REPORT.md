# QA-REPORT — End-to-End тестирование и отказоустойчивость

- **Дата:** 2026-10-08 (сессия ~21:51–21:56 UTC по логам приложения)
- **Агент:** qa-diagnostics
- **Проект:** docker-glm-5-3 (RabbitMQ x3 + Kafka KRaft x3 + Redis master/replica + Java-монолит)

## 1. Резюме

**17/17 сценариев PASS.** Инфраструктура (1.1–1.5), функциональные E2E (2.1–2.8) и
отказоустойчивость (3.1–3.5) — без единого FAIL. Найден **1 существенный дефект
(незачётный для сценариев, но важный)**: потеря in-flight сообщения при полном
отказе RabbitMQ (см. FIND-1). После тестов система полностью восстановлена:
8/8 контейнеров healthy, монолит UP, lag=0, очередь пуста.

## 2. Сводная таблица

### Секция 1 — Инфраструктура

| # | Сценарий | Результат | Артефакт (команда → ключевой вывод) |
|---|----------|-----------|-------------------------------------|
| 1.1 | Кластер RabbitMQ | **PASS** | `rabbitmqctl cluster_status` → Running Nodes: rabbit@rabbitmq-1/2/3, alarms: none |
| 1.2 | Aliveness RabbitMQ | **PASS** | `GET :15672/api/aliveness-test/%2F` → `{"status":"ok"}` |
| 1.3 | Топик Kafka | **PASS** | `kafka-topics.sh --describe` (bootstrap kafka-1:29092) → 3 партиции, RF=3, ISR=3/3/3, min.insync.replicas=2 |
| 1.4 | Репликация Redis | **PASS** | `INFO replication` на replica → `role:slave`, `master_link_status:up`, repl_offset синхронен |
| 1.5 | Health монолита | **PASS** | `GET :8080/actuator/health` → `{"status":"UP"}`, kafka/rabbit/redis UP |

### Секция 2 — Функциональные E2E

| # | Сценарий | Результат | Артефакт |
|---|----------|-----------|---------|
| 2.1 | Happy path `e2e-happy` | **PASS** | 200 за 14.6 мс; originalMessage корректен; enrichments `[KAFKA, RABBITMQ]` с note (partition/offset, deliveryTag); retrievedFromRedisAt присутствует |
| 2.2 | Redis-след | **PASS** | `GET message:<cid>` на master → полный JSON ответа; TTL=596с (TTL ≈ 10 мин) |
| 2.3 | Kafka lag | **PASS** | `monolith-group`: lag=0 по всем 3 партициям, 3 активных consumer |
| 2.4 | RabbitMQ очередь | **PASS** | `messages.queue`: messages=0, unacked=0 |
| 2.5 | Валидация | **PASS** | пустое тело / без поля message / битый JSON → 400 + осмысленный detail (`"message: message must not be blank"`, `"malformed JSON request body"`) |
| 2.6 | 10 параллельных | **PASS** | 10/10 → 200; correlationId все уникальны; finalMessage каждого запроса соответствует своему originalMessage (перепутываний нет); у всех оба enrichment |
| 2.7 | Юникод + 10 КБ | **PASS** | ~10 КБ UTF-8 (кириллица, эмодзи, CJK, иврит) → 200; originalMessage и finalMessage побайтово равны отправленному |
| 2.8 | Повтор тем же телом | **PASS** | новый correlationId; независимый прогон (другие offset/deliveryTag) |

### Секция 3 — Отказоустойчивость

| # | Сценарий | Результат | Артефакт |
|---|----------|-----------|---------|
| 3.1 | stop rabbitmq-2 → POST | **PASS** | 200 за 9.5 мс; нода возвращена, кластер 3/3 running за ~20с |
| 3.2 | stop kafka-3 → POST | **PASS** | 200 за 9.7 мс (min.insync.replicas=2 держит); нода возвращена, ISR=1,2,3 на всех партициях; лидер p2 мигрировал 3→1 и вернулся |
| 3.3 | Полный стоп RabbitMQ → POST | **PASS** | 504 за 15.007с (`"detail":"message did not pass Kafka -> RabbitMQ -> Redis within PT15S"`); приложение живо, health отвечает (rabbit:DOWN, kafka/redis:UP); кластер поднят, health → UP |
| 3.4 | stop redis-replica → POST | **PASS** | 200 за 15 мс (запись в master); реплика возвращена, `master_link_status:up` |
| 3.5 | Контрольный повтор 2.1 | **PASS** | 200, полный цикл обогащений; 8/8 контейнеров healthy; lag=0; очередь пуста |

## 3. Найденные дефекты

### FIND-1 (СУЩЕСТВЕННЫЙ) — потеря сообщения при полном отказе RabbitMQ

- **Где обнаружено:** сценарий 3.3 (расширение: судьба сообщения после 504).
- **Слой:** java-monolith.
- **Симптом:** POST вернул 504 (это ожидаемо), но in-flight сообщение
  `e78e7c32-10bb-4089-a9a8-5ef06fa3a6c3` потеряно безвозвратно: после
  восстановления RabbitMQ в Redis нет ключа `message:<cid>`, очередь пуста,
  offset закоммичен (lag=0). Ни retry-потока, ни DLQ.
- **Диагностика (лог /tmp/monolith.log):**
  ```
  00:53:05.540  KafkaPublisher: published correlationId=e78e7c32 ... to kafka topic=messages.topic
  00:53:05.541  KafkaFlowConsumer: kafka stage correlationId=e78e7c32 partition=0 offset=33   (попытка 1)
  00:53:06.579  KafkaFlowConsumer: kafka stage ... offset=33                                    (попытка 2)
  00:53:07.620  KafkaFlowConsumer: kafka stage ... offset=33                                    (попытка 3)
  00:53:07.622  DefaultErrorHandler: Backoff FixedBackOff{interval=1000, currentAttempts=3, maxAttempts=2}
                exhausted for messages.topic-0@33
                Caused by: AmqpConnectException: java.net.ConnectException: Connection refused
  ```
  После исчерпания backoff обработчик сдался, offset сдвинут — этап
  publish→RabbitMQ не пройден ни разу, Redis-стадия не достигнута.
- **Причина (гипотеза):** в конфигурации Kafka-листенера используется
  `DefaultErrorHandler` c `FixedBackOff{interval=1s, maxAttempts=2}` и без
  recoverer'а. По умолчанию после исчерпания ретраев запись просто
  «пропускается» (log-and-commit) — при отказе downstream это окно потери
  длиной в весь простой downstream.
- **Рекомендация агенту java-monolith:** настроить
  `DefaultErrorHandler` + `DeadLetterPublishingRecoverer` (DLT-топик
  `messages.topic.DLT`, RF=3, min.insync.replicas=2) для необрабатываемых
  записей, а для транзиентных ошибок downstream (AmqpConnectException) —
  длительный backoff (например, ExponentialBackOff до ~60с) с паузой
  партиции вместо log-and-commit. Дополнительно: документировать в
  Service/README.md поведение при полном отказе RabbitMQ (что происходит с
  сообщениями, как replay'нуть из DLT).
- **Воспроизведение:** `docker compose -f Docker/docker-compose.rabbitmq.yml stop`
  → `curl -X POST localhost:8080/start -d '{"message":"x"}'` (получить 504,
  запомнить correlationId из тела) → `... start` → проверить
  `redis-cli EXISTS message:<cid>` (сейчас: 0).

### FIND-2 (ЗАМЕЧАНИЕ, эксплуатационное) — health = DOWN при отказе одного RabbitMQ

- **Где:** сценарий 3.3.
- **Слой:** java-monolith (контракт эксплуатации).
- **Симптом:** при недоступности RabbitMQ `/actuator/health` отдаёт общий
  `status: DOWN` (приложение при этом полностью живо и отвечает, HTTP 504
  отрабатывает корректно).
- **Риск:** если в целевой среде (k8s/Docker healthcheck/swarm) liveness-проба
  смотрит на `/actuator/health` целиком — при отказе брокера контейнер будет
  убиваться и рестартовать в цикле, ухудшая восстановление.
- **Рекомендация агенту java-monolith:** задокументировать, а при целевом
  деплое в оркестраторах — разделить пробы: liveness только ping
  (`/actuator/health/liveness`), readiness — с брокерами; либо вынести
  брокеров в health group. Для текущего локального стенда — не блокер.

### FIND-3 (ДОКУМЕНТАЦИОННОЕ, устранено решением оркестратора)

- Тест-план п.1.3 содержит команду `docker exec kafka-1 kafka-topics
  --bootstrap-server localhost:9092`. Фактически: (а) в контейнере бинарник
  лежит в `/opt/kafka/bin/kafka-topics.sh`, (б) `localhost:9092` изнутри
  контейнеров не работает (advertised listeners = для хоста) — нужен
  `kafka-1:29092`. Оркестратор это уже отразил в сопроводительных решениях;
  рабочих команда: `docker exec kafka-1 /opt/kafka/bin/kafka-topics.sh
  --describe --topic messages.topic --bootstrap-server kafka-1:29092`.
  Промт-файлы по условию не меняю.

Опечаток/несоответствий портов и имён в README.md, Docker/README.md,
Service/README.md не обнаружено (сверены порты 9092-9094/5672-5674/15672-15674,
имена messages.topic / messages.queue / monolith-group, guest/guest).

## 4. Известные ограничения системы

1. **At-most-once на этапе отказа downstream** (следствие FIND-1): при полном
   простое RabbitMQ дольше ~3с сообщения, попавшие в Kafka, теряются после
   исчерпания backoff. Длительность окна = длительность простоя.
2. **Синхронный 15-секундный бюджет** на весь конвейер: при деградации брокеров
   до 15с клиент получает 504, хотя сообщение может быть ещё обработано
   (или потеряно — см. п.1).
3. **Redis без sentinel/failover**: при потере master записи упадут (тестили
   только потерю replica — она прозрачна).
4. **Кворумная запись в Kafka требует 2 ISR**: одновременная потеря 2 брокеров
   из 3 → Producer не сможет писать (acks=all, min.insync.replicas=2) — ожидаемое
   поведение, не тестировалось деструктивно.
5. **Управление монолитом** — локальный процесс (pid-файл /tmp/monolith.pid),
   не сервис: после перезагрузки хоста автостарта нет.
6. **RabbitMQ `down` на манифесте rabbitmq запрещён операционно** (уносит общую
   сеть event-net) — только stop/start.

## 5. Как всё проверить за 5 минут

```bash
cd /Users/pabelyakov/Projects/docker-llm-test/docker-glm-5-3

# 1. Контейнеры: все healthy (8 шт)
docker compose -f Docker/docker-compose.rabbitmq.yml ps
docker compose -f Docker/docker-compose.kafka.yml ps
docker compose -f Docker/docker-compose.redis.yml ps

# 2. Монолит жив
curl -s localhost:8080/actuator/health | jq .status        # "UP"

# 3. Брокеры
curl -s -u guest:guest localhost:15672/api/aliveness-test/%2F          # {"status":"ok"}
docker exec kafka-1 /opt/kafka/bin/kafka-topics.sh --describe \
  --topic messages.topic --bootstrap-server kafka-1:29092 | head -1     # 3 partitions, RF=3
docker exec redis-replica redis-cli INFO replication | grep master_link # up

# 4. E2E: 200 + оба обогащения + retrievedFromRedisAt
curl -s -X POST localhost:8080/start -H 'Content-Type: application/json' \
  -d '{"message":"smoke"}' | jq '{originalMessage, enrichments: [.enrichments[].stage], retrievedFromRedisAt}'

# 5. Чисто после обработки
curl -s -u guest:guest localhost:15672/api/queues/%2F/messages.queue | jq '{messages, messages_unacknowledged}'  # 0/0
docker exec kafka-1 /opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server kafka-1:29092 --describe --group monolith-group    # LAG=0 везде
```

Критерий успеха: 8 healthy, health UP, smoke→200 c `[KAFKA,RABBITMQ]`, очередь 0/0, lag=0.

## 6. Финальное состояние системы (после всех тестов)

| Компонент | Состояние |
|-----------|-----------|
| rabbitmq-1/2/3 | Up (healthy), кластер 3/3 running |
| kafka-1/2/3 | Up (healthy), ISR=3 на всех партициях |
| redis-master / redis-replica | Up (healthy), master_link_status:up |
| Монолит (pid из /tmp/monolith.pid) | health UP; kafka/rabbit/redis UP |
| messages.queue | 0 сообщений, 0 unacked |
| monolith-group lag | 0 (все партиции) |

## 7. Definition of Done

- [x] Все сценарии секций 1–3 выполнены и протоколированы (17/17)
- [x] Каждый FAIL локализован и адресован (FAIL нет; FIND-1/2 локализованы и адресованы java-monolith, FIND-3 — зафиксирован)
- [x] Итоговый отчёт написан (этот файл)
- [x] Система после тестов в рабочем состоянии (8/8 healthy, lag=0, очередь пуста)

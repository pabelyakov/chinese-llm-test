# docs/CONVENTIONS.md — соглашения pipeline автономных агентов

> Владелец: Agent 1. Любое отклонение — фиксировать в `docs/JOURNAL.md` с причиной.

## 1. Структура репозитория и владельцы каталогов

```
kimi/
├── Terraform/      # Agent 2 — манифесты Proxmox (VM + cloud-init)
├── Ansible/        # Agent 3/4 — inventory, group_vars, roles, ansible.cfg
│   ├── inventory/  #   hosts.yml (генерируется/валидируется Agent 2→3)
│   ├── group_vars/ #   all.yml, kafka.yml, rabbitmq.yml, redis.yml
│   ├── roles/      #   common, kafka, rabbitmq, redis, java_app
│   └── secrets/    #   GITIGNORED: vault-файлы с паролями
├── Service/        # Agent 5/6 — Java 21 + Spring Boot 3.3 монолит (Maven)
├── Security/       # Agent 1 — SSH-ключи, шаблоны env (приватное — gitignored)
├── docs/           # Agent 1 — SSOT: INFRA.md, CONVENTIONS.md, ENDPOINTS.md, JOURNAL.md
└── promts/         # промты агентов (входные данные, не менять)
```

## 2. Именование

| Сущность | Правило | Пример |
|---|---|---|
| VM / hostname | `<service>-<ordinal>`, ordinal с 1, lowercase, дефис | `kafka-1`, `rabbitmq-2`, `redis-1` |
| IP | строго из IPAM (`docs/INFRA.md` §3); резерв `.236/.239/.240` не трогать | `192.168.1.230` |
| Ansible-группы | имя сервиса без ordinal | `kafka`, `rabbitmq`, `redis`, `all` |
| Ansible-роли | kebab/snake-case по сервису | `common`, `kafka`, `rabbitmq`, `redis`, `java_app` |
| Terraform-ресурсы | `proxmox_virtual_environment_vm.<service>_<n>` | `proxmox_virtual_environment_vm.kafka_1` |
| Terraform-переменные | snake_case, значения из IPAM через `locals`/map | `vm_definitions` |
| Kafka topic | kebab-case с префиксом домена | `pipeline-requests` |
| RabbitMQ exchange/queue | `<domain>.<stage>` | `pipeline` / `pipeline.enrich2` |
| Redis key | `pipeline:result:{correlationId}` | `pipeline:result:3f2a...` |
| systemd unit | каноническое имя вендора | `kafka.service`, `rabbitmq-server.service`, `redis-server@.service` |
| Env-переменные приложения | UPPER_SNAKE с префиксом | `KAFKA_BOOTSTRAP_SERVERS`, `RABBITMQ_PASSWORD` |
| Git-ветки (если нужны) | `agent-<n>/<topic>` | `agent-2/terraform-proxmox` |

Cloud-init пользователь: **`ubuntu`** (D3). Если нужен отдельный деплой-пользователь —
`deploy`, создаётся ролью `common`, sudo без пароля, SSH-доступ тем же ключом.

## 3. Обмен артефактами между агентами (контракт пайплайна)

| Артефакт | Создаёт | Читает | Формат/путь |
|---|---|---|---|
| SSH-ключ | Agent 1 | Agent 2, 3 | `Security/id_ed25519.pub` (путь в `Security/README.md`) |
| SSOT инфраструктуры | Agent 1 | все | `docs/INFRA.md` |
| Соглашения | Agent 1 | все | `docs/CONVENTIONS.md` (этот файл) |
| Подтверждение node/storage/bridge/gateway/DNS (D6) | Agent 2 | Agent 3, оркестратор | запись в `docs/JOURNAL.md` + `Terraform/README.md` |
| Фактический inventory VM | Agent 2 | Agent 3 | `Ansible/inventory/hosts.yml` (Agent 2 пишет IP/hostname, Agent 3 дополняет vars) |
| Фактические адреса/учётки сервисов | Agent 3/4 | Agent 5/6 | **`docs/ENDPOINTS.md`** (единый файл, см. §4) |
| Секреты сервисов (пароли) | Agent 3/4 | Agent 5/6 (через env) | `Ansible/secrets/vault.yml` — **gitignored**; при необходимости `ansible-vault` |
| Код монолита + артефакт jar | Agent 5/6 | Agent 3 (роль `java_app`) | `Service/target/*.jar` (gitignored) |
| Журнал работ | каждый агент | оркестратор, следующий агент | `docs/JOURNAL.md` (append-only) |
| Run-report | каждый агент | оркестратор | ответ агента + короткая запись в `docs/JOURNAL.md` |

Правила обмена:
1. Агент **не правит** чужие каталоги, кроме случаев, явно оговорённых в таблице
   (Agent 2 → `Ansible/inventory/hosts.yml`).
2. Если агенту не хватает данных — он берёт их из `docs/INFRA.md` /
   `docs/ENDPOINTS.md`, а не выдумывает; при реальном блокере (например, API
   Proxmox недоступен) — эскалация оркестратору.
3. Любой новый факт о инфраструктуре (порт, пароль-переменная, endpoint)
   **сначала** фиксируется в `docs/ENDPOINTS.md`, потом используется в коде.
4. Коммиты делает человек. Агенты не коммитят и не пушат; `git init` выполнен
   Agent 1 только для проверки ignore-правил.

## 4. docs/ENDPOINTS.md — единый файл endpoints

- Путь: `docs/ENDPOINTS.md`. **Единственное** место, где описаны реальные
  адреса подключения (bootstrap servers, AMQP URI, Redis host/port), имена
  topic/exchange/queue/key и **имена переменных окружения** для секретов.
- Значения секретов туда НЕ пишутся — только имя переменной и ссылка на
  gitignored-хранилище.
- Формат — таблица «сервис | host:port | параметр | значение | кто подтвердил».
- Обновляют Agent 3/4 (после раскатки Kafka/RabbitMQ/Redis) и Agent 5/6
  (приложение). Agent 1 создал stub-каркас.

Пример заполнения (Agent 3/4):
```
| Kafka | 192.168.1.230:9092,192.168.1.231:9092,192.168.1.232:9092 | bootstrap.servers | PLAINTEXT | Agent 4, проверено kafka-topics --list |
| RabbitMQ | 192.168.1.233:5672 | spring.rabbitmq.host | vhost=/ | Agent 4, rabbitmq-diagnostics status |
| RabbitMQ password | — | RABBITMQ_PASSWORD | Ansible/secrets/vault.yml | Agent 4 |
```

## 5. Секреты Ansible — расположение и правила

- Каталог: **`Ansible/secrets/`** — **gitignored** (`.gitignore`).
- Файлы: `Ansible/secrets/vault.yml` (значения) и, при использовании
  ansible-vault, `Ansible/secrets/*.vault` (зашифровано, всё равно gitignored).
- Пароль vault хранится ВНЕ репозитория: `~/.ansible-vault-pass-kimi`
  (chmod 600) или env `ANSIBLE_VAULT_PASSWORD_FILE`.
- В `group_vars/*.yml` — только **ссылки** на переменные из vault, никаких
  литералов паролей:
  ```yaml
  # group_vars/rabbitmq.yml
  rabbitmq_user: admin
  rabbitmq_password: "{{ vault_rabbitmq_password }}"
  ```
- Роли обязаны работать и при `ANSIBLE_VAULT_PASSWORD_FILE`, и при
  `-e @Ansible/secrets/vault.yml` (plain, gitignored).
- Приложение (`Service/`) читает секреты **только** из env
  (`RABBITMQ_PASSWORD`, `REDIS_PASSWORD`, ...), дефолты в
  `application.yml` — пустые/placeholder.

## 6. Стандарт README для каждого артефакта

Каждый каталог/артефакт получает `README.md` минимум из 6 блоков:

1. **Назначение** — 1–2 предложения, что это и зачем.
2. **Состав** — таблица файлов/каталогов с ролью каждого.
3. **Требования** — версии (terraform/ansible/java), доступы, env-переменные.
4. **Как запустить** — точные команды, идемпотентно, из корня репозитория.
5. **Проверка результата** — команды верификации (`terraform plan`,
   `ansible -m ping`, `curl`, `ss -lntp`) и ожидаемый вывод.
6. **Секреты и безопасность** — откуда берутся, что в git не попадает, ссылки
   на `Security/README.md`, `docs/INFRA.md`.

Плюс блок **«Известные ограничения / next steps»** для следующего агента.

## 7. Стиль кода и качества

- YAML: 2 пробела, без табов, финальный newline; `---` в начале.
- HCL/Terraform: `terraform fmt` + `terraform validate` обязательны перед сдачей.
- Ansible: FQCN-имена модулей (`ansible.builtin.copy`), `name:` у каждой task,
  `ansible-lint`/`--syntax-check` прогнать; идемпотентность — повторный прогон = 0 changed.
- Java: 4 пробела, `mvn -q verify`, Java 21, Spring Boot 3.3.x; логирование —
  slf4j, секрет в логи не печатать.
- Shell-сниппеты в README: запускать **из корня репозитория** (относительные пути).
- Все команды в доках — copy-paste-готовые, без `<placeholder>` там, где
  значение известно из `docs/INFRA.md`.

## 8. Определение готовности (Definition of Done агента)

- [ ] Артефакты созданы по списку DELIVERABLES своего промта.
- [ ] Есть README по стандарту §6.
- [ ] Команды проверки выполнены, вывод — в run-report и `docs/JOURNAL.md`.
- [ ] Идемпотентность подтверждена повторным прогоном (где применимо).
- [ ] Секреты не в git (`git status --short` чист от секретов, `git check-ignore -v` подтверждает).
- [ ] `docs/INFRA.md` / `docs/ENDPOINTS.md` актуализированы, если факты изменились.

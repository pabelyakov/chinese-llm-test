# Ansible Role: RabbitMQ cluster (3 nodes)

Разворачивает кластер RabbitMQ из трёх нод на Ubuntu Server 22.04 LTS.

- Erlang/OTP **27.x** и RabbitMQ **4.3.x** из официального apt-репозитория
  Team RabbitMQ (`deb1/deb2.rabbitmq.com`).
- Одинаковый Erlang cookie на всех нодах.
- Кластер: `rabbit-1` — seed-нода (standalone), `rabbit-2` и `rabbit-3`
  выполняют `join_cluster` (идемпотентно).
- Management-плагин (UI на порту **15672**).
- Сервисный пользователь (не `guest`) с правами на vhost.
- Автозапуск через systemd.

## Файлы роли

```
roles/rabbitmq/
├── defaults/main.yml      # значения по умолчанию (учётка, cookie, дистрибутив)
├── vars/main.yml          # производные переменные (имена нод, пакеты Erlang)
├── handlers/main.yml      # restart rabbitmq-server
├── tasks/main.yml         # установка, конфиг, кластер, пользователь
├── templates/
│   ├── rabbitmq.list.j2   # apt-источники Team RabbitMQ
│   ├── rabbitmq-env.conf.j2 # NODENAME=rabbit@<host>
│   ├── rabbitmq.conf.j2   # listeners 5672, management 15672
│   └── hosts.j2           # /etc/hosts для разрешения имён нод
├── meta/main.yml
└── README.md
```

## Переменные (defaults/main.yml)

| Переменная | Значение по умолчанию | Назначение |
|------------|----------------------|------------|
| `rabbitmq_user` | `service` | Сервисный пользователь |
| `rabbitmq_password` | `service-pass` | Пароль сервисного пользователя |
| `rabbitmq_vhost` | `/` | vhost для сервиса |
| `rabbitmq_user_tags` | `management` | Теги пользователя (доступ к UI) |
| `rabbitmq_erlang_cookie` | `deepseek-harness-rabbitmq-erlang-cookie` | Общий Erlang cookie кластера |
| `rabbitmq_distribution` | `jammy` | Кодовое имя Ubuntu |

> Секреты (cookie, пароль) вынесены в переменные и могут быть переопределены
> через `group_vars`, `-e` или Ansible Vault. В git хранятся только дефолтные
> значения для этой тестовой инфраструктуры.

Производные переменные (`vars/main.yml`):

- `rabbitmq_master_node` — первая нода группы `rabbitmq` (по алфавиту), seed.
- `rabbitmq_master_nodename` — `rabbit@<master>`.
- `rabbitmq_nodename` — `rabbit@<inventory_hostname>`.

## Запуск

```bash
cd Ansible
ansible-playbook -i inventory playbooks/rabbitmq.yml
```

Синтаксис-чек:

```bash
ansible-playbook -i inventory playbooks/rabbitmq.yml --syntax-check
```

Повторный прогон идемпотентен: `changed=0` для всех нод.

## Проверка кластера

```bash
ssh -i ../Security/id_ed25519 ubuntu@192.168.1.233 \
  'sudo rabbitmqctl cluster_status --formatter json'
```

Ожидается `running_nodes` = `["rabbit@rabbit-1","rabbit@rabbit-2","rabbit@rabbit-3"]`.

Быстрая проверка из Ansible:

```bash
ansible rabbitmq -i inventory -m shell -a \
  'sudo rabbitmqctl cluster_status --formatter json'
```

## Параметры подключения для клиентов

| Параметр | Значение |
|----------|----------|
| Host | `192.168.1.233` (или 234 / 235) |
| Port (AMQP 0-9-1 / 1.0) | `5672` |
| Vhost | `/` |
| User | `service` |
| Password | `service-pass` |
| Management UI | `http://192.168.1.233:15672` |
| UI логин/пароль | `service` / `service-pass` |

Права пользователя `service` на vhost `/`: `configure .*`, `write .*`, `read .*`.

## Проверка публикации/потребления (management HTTP API)

```bash
# создать очередь
curl -u service:service-pass -X PUT \
  "http://192.168.1.233:15672/api/queues/%2F/test" \
  -H 'content-type: application/json' -d '{"durable":true}'

# опубликовать сообщение
curl -u service:service-pass -X POST \
  "http://192.168.1.233:15672/api/exchanges/%2F/amq.default/publish" \
  -H 'content-type: application/json' \
  -d '{"routing_key":"test","payload":"hello","payload_encoding":"string"}'

# прочитать сообщение
curl -u service:service-pass -X POST \
  "http://192.168.1.233:15672/api/queues/%2F/test/get" \
  -H 'content-type: application/json' \
  -d '{"count":1,"ackmode":"ack_requeue_false","encoding":"auto"}'
```

## Как добавить ноду

1. Поднять новую VM с коротким hostname, доступную по IP.
2. Добавить её в группу `[rabbitmq]` в `Ansible/inventory`:
   ```
   rabbit-4 ansible_host=192.168.1.239
   ```
3. Запустить плейбук — роль сама установит RabbitMQ, пропишет cookie,
   `/etc/hosts` и выполнит `join_cluster` к seed-ноде (только если нода ещё
   не в кластере).

Seed-нода определяется автоматически как первая (по алфавиту) нода группы.

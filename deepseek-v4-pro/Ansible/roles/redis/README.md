# Redis role — master-replica (Ansible)

Устанавливает Redis (актуальный стабильный релиз из официального репозитория
`packages.redis.io`) и настраивает топологию **master-replica** на группе `redis`.

## Почему master-replica (а не Cluster/Sentinel)

При **2 узлах** полноценный Redis Cluster невозможен (нужно минимум 3 мастер-узла
для кворума), а Sentinel требует минимум 3 монитора для корректного кворума
failover-а (при 2 мониторах нельзя надёжно выбрать нового мастера). Поэтому для
двух нод разумный минимум — **асинхронная репликация master-replica**: `redis-1`
— мастер (принимает запись и чтение), `redis-2` — реплика (read-only, читает
данные мастера). Для продакшена с авто-failover добавляйте 3-й узел и Sentinel
или переходите на Cluster.

## Топология

| Хост | IP | Роль |
|------|----|------|
| redis-1 | 192.168.1.237 | master |
| redis-2 | 192.168.1.238 | replica |

## Параметры подключения для клиентов

| Параметр | Значение |
|----------|----------|
| host | 192.168.1.237 (master) |
| port | 6379 |
| password | `redis-pass` |

Реплика `192.168.1.238:6379` — только для чтения (read-only).

```bash
redis-cli -h 192.168.1.237 -p 6379 -a redis-pass ping
# -> PONG
```

## Пароль (requirepass / masterauth)

- Значение пароля: **`redis-pass`** (преднамеренно простой и предсказуемый для
  внутреннего тестового стенда — его использует агент `service`).
- В git пароль **не хардкодится**: он лежит в зашифрованном файле
  `group_vars/redis/vault.yml` (ansible-vault), ключ `vault_redis_password`.
- Пароль vault хранится локально в `Ansible/.vault_pass` (добавлен в
  `.gitignore`, в git не коммитится).
- На обоих узлах задаётся `requirepass` (авторизация клиентов) и `masterauth`
  (реплика аутентифицируется на мастере).

### Как перегенерировать vault (если `.vault_pass` утерян)

```bash
cd Ansible
printf 'deepseek-redis-vault-2026\n' > .vault_pass      # или свой пароль
printf -- '---\nvault_redis_password: "redis-pass"\n' > /tmp/vault.yml
ansible-vault encrypt --vault-password-file .vault_pass /tmp/vault.yml \
  --output group_vars/redis/vault.yml
```

## Переменные роли

| Переменная | Описание | Значение по умолчанию |
|------------|----------|------------------------|
| `redis_port` | порт | `6379` |
| `redis_bind` | адрес bind | `0.0.0.0` |
| `redis_protected_mode` | protected-mode | `no` |
| `redis_maxmemory` | лимит памяти | `512mb` |
| `redis_maxmemory_policy` | политика при нехватке памяти | `noeviction` |
| `redis_appendonly` | AOF-персистентность | `yes` |
| `redis_appendfsync` | режим fsync AOF | `everysec` |
| `redis_loglevel` | уровень логирования | `notice` |
| `redis_master_name` | inventory-имя мастера | `redis-1` |
| `redis_master_host` | IP мастера | `192.168.1.237` |
| `redis_password` | requirepass/masterauth (из vault) | `vault_redis_password` |

## Как запустить

```bash
cd Ansible
ansible-playbook --syntax-check -i inventory --vault-password-file .vault_pass playbooks/redis.yml
ansible-playbook -i inventory --vault-password-file .vault_pass playbooks/redis.yml
```

> Пароль vault задан в `ansible.cfg` (`vault_password_file = .vault_pass`),
> поэтому флаг `--vault-password-file` можно опускать, если `ansible.cfg` не
> менялся другими агентами. Для надёжности флаг указан явно.

Плейбук идемпотентен: повторный прогон не меняет состояние (изменения — только
при реальном изменении конфигурации).

## Как проверить репликацию

На мастере (`redis-1`):

```bash
redis-cli -a redis-pass ping                       # PONG
redis-cli -a redis-pass info replication
# role:master
# connected_slaves:1
# slave0:ip=192.168.1.238,port=6379,state=online,offset=...,lag=0
```

На реплике (`redis-2`):

```bash
redis-cli -a redis-pass info replication
# role:slave
# master_host:192.168.1.237
# master_link_status:up
```

Сквозной тест репликации:

```bash
redis-cli -h 192.168.1.237 -a redis-pass set __test__ 42
redis-cli -h 192.168.1.238 -a redis-pass get __test__   # -> "42"
redis-cli -h 192.168.1.237 -a redis-pass del __test__
```

## Как переключить роли (ручной failover)

Реплика `redis-2` по умолчанию read-only. Чтобы вручную сделать её мастером:

```bash
# на redis-2 (снять read-only и отвязаться от мастера)
redis-cli -h 192.168.1.238 -a redis-pass replicaof no one
# на redis-1 (сделать репликой redis-2)
redis-cli -h 192.168.1.237 -a redis-pass replicaof 192.168.1.238 6379
```

> Это временно (до следующего прогона Ansible, который вернёт конфиг к исходной
> топологии master-replica из `redis_master_*`). Для автоматического failover
> нужен Sentinel/Cluster (3+ узла).

## Служебная информация

- Конфиг: `/etc/redis/redis.conf` (шаблон `templates/redis.conf.j2`).
- Данные/AOF: `/var/lib/redis`, лог — в journald (`journalctl -u redis-server`).
- Автозапуск: systemd unit `redis-server` (`enabled`).
- Firewall: `ufw` на VM неактивен, порт 6379 слушается на `0.0.0.0` — доступен
  по локальной сети. При включении ufw разрешите: `sudo ufw allow 6379/tcp`.

# Ansible/ — роли и плейбуки конфигурации VM

> Каркас создан Agent 1. Содержимое — зоны ответственности **Agent 3/4**
> (OS + Kafka/RabbitMQ/Redis) и **Agent 6** (роль `java_app`).
> Факты: `docs/INFRA.md`. Правила: `docs/CONVENTIONS.md`.

## Структура (каркас создан)
```
Ansible/
├── ansible.cfg          # создаёт Agent 3: inventory, private_key_file, pipelining, host_key_checking
├── inventory/
│   └── hosts.yml        # генерируется Agent 2 из terraform outputs; группы kafka/rabbitmq/redis
├── group_vars/
│   ├── all.yml          # DNS/gateway/chrony/hosts-шаблон, ansible_user=ubuntu
│   ├── kafka.yml        # KRaft: node.id, heap 1024m, listeners 9092/9093, retention
│   ├── rabbitmq.yml     # 3.13.x + Erlang 26, watermark 0.4, vhost/queues
│   └── redis.yml        # primary/replica, maxmemory 512m/256m, noeviction
├── roles/               # common, kafka, rabbitmq, redis, java_app
└── secrets/             # GITIGNORED — vault.yml с паролями RabbitMQ/Redis/app
```

## Требования
- ansible-core 2.21.3, SSH-ключ `../Security/id_ed25519`, пользователь `ubuntu`.
- Секреты: `Ansible/secrets/vault.yml` (gitignored) или ansible-vault
  (`ANSIBLE_VAULT_PASSWORD_FILE` вне репозитория).

## Запуск (шаблон для Agent 3/4)
```bash
cd Ansible
ansible-playbook -i inventory/hosts.yml site.yml --syntax-check
ansible all -i inventory/hosts.yml -m ping
ansible-playbook -i inventory/hosts.yml site.yml            # идемпотентно: 2-й прогон = 0 changed
ansible-playbook -i inventory/hosts.yml site.yml --check --diff
```

## Проверка результата
```bash
ansible kafka     -i inventory/hosts.yml -m shell -a "ss -lntp | grep -E '9092|9093'"
ansible rabbitmq  -i inventory/hosts.yml -m shell -a "rabbitmq-diagnostics -q status"
ansible redis     -i inventory/hosts.yml -m shell -a "redis-cli ping"
ansible all       -i inventory/hosts.yml -m shell -a "chronyc tracking | head -3"
```

## Секреты и безопасность
- `Ansible/secrets/`, vault-файлы, `*.retry`, логи — в `.gitignore`.
- В `group_vars` — только ссылки `{{ vault_* }}`, литералов паролей нет.
- Значения endpoints после раскатки — в `docs/ENDPOINTS.md` (без паролей).

## Next steps
1. Agent 3: роль `common` (chrony, /etc/hosts, users, ssh-hardening, firewall).
2. Agent 4: роли `kafka` (KRaft), `rabbitmq` (кластер 3 узла), `redis` (primary/replica).
3. Заполнить `docs/ENDPOINTS.md` §3–§5 и обновить `docs/JOURNAL.md`.

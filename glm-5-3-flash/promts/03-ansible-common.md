# Промт 3 — Ansible: инвентарь и базовая роль

```text
Ты – DevOps-инженер. Рабочая директория: /Users/pabelyakov/Projects/llm-test/z-ai/ansible. VM уже раскатаны Terraform'ом, SSH-ключ ../security/deploy_key.

ЗАДАЧА: создать инвентарь и базовую роль подготовки хостов, применить.

1. inventory/hosts.ini:
   [kafka]     kafka-1 ansible_host=192.168.1.230 node_id=1 ; kafka-2 ...231 node_id=2 ; kafka-3 ...232 node_id=3
   [rabbitmq]  rabbitmq-1 ansible_host=192.168.1.233 ; rabbitmq-2 ...234 ; rabbitmq-3 ...235
   [redis]     redis-1 ansible_host=192.168.1.237 ; redis-2 ansible_host=192.168.1.238
   [redis_master] redis-1 ; [redis_replica] redis-2
   [all:vars] ansible_user=deploy ansible_ssh_private_key_file=../security/deploy_key ansible_python_interpreter=/usr/bin/python3
2. Роль roles/common (выполняется для всех хостов):
   - apt update + baseline-пакеты: curl, wget, unzip, jq, htop, gnupg, ca-certificates, chrony (синхронизация времени обязательна для кластеров).
   - Таймзона UTC, hostname уже задан cloud-init'ом — проверь и исправь при необходимости (hostnamectl + /etc/hosts: имя ↔ IP всех 8 узлов).
   - Лимиты: nofile 65536 для пользователя deploy и будущего пользователя сервисов (limits.conf / systemd DefaultLimitNOFILE).
   - vm.swappiness=1 (важно для Redis и Kafka), net.core.somaxconn=1024 через sysctl.
   - UFW: разрешить ssh, затем порты по группам: kafka → 9092,9093; rabbitmq → 4369,5672,15672,25672; redis → 6379,26379; внутри каждой группы разрешить весь трафик между нодами группы. UFW enable только если ssh-правило активно.
   - Отключить unattended-upgrades (чтобы не рестартовал сервисы).
3. playbook site.yml: сначала common для all, затем placeholder-импорты ролей kafka/rabbitmq/redis (пока с тегом skip, если роли нет).
4. Запусти: ansible -m ping all, затем playbook. Распространённые проблемы (EOF/permission denied) диагностируй сам.
5. roles/common/README.md: что делает роль, как менять, идемпотентность.

КРИТЕРИИ ПРИЁМКИ: ansible -m ping all — 8/8 ok; playbook идемпотентен (второй прогон changed=0); ufw status на ноде kafka показывает 9092/9093.
Отчитайся: summary playbook (ok/changed/failed), результат ping.
```

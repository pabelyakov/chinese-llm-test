# CTX — общий контекст (вставляется ПЕРЕД каждым промтом агента)

```text
=== ПРОЕКТ: Event-driven pipeline на Proxmox VE ===
Рабочая директория: текущая. Структура: Terraform/, Ansible/, Service/, Security/.
Proxmox: https://192.168.1.253:8006 (HTTPS self-signed — везде отключай TLS-верификацию: curl -k, tls_insecure_skip_verify).
API token: id = "root@pam!terraform", secret = "a5098b71-f073-4dfb-ab10-499a0ca7f4ee".
REST-заголовок: Authorization: PVEAPIToken=token
Параметры VM (все без исключения): 2 vCPU, 2048 MB RAM, 40 GB disk, Debian 12 cloud image, cloud-init, статический IP, SSH-ключ из Security/id_ed25519_vm.pub, SSH-пользователь ops, password auth off.
План адресов (пул 192.168.1.230–.240):
  kafka-1=.230, kafka-2=.231, kafka-3=.232
  rabbit-1=.233, rabbit-2=.234, rabbit-3=.235
  redis-1=.237, redis-2=.238
  Резерв: .236, .239, .240. Маска /24, gateway и bridge определяются через API Proxmox.
Версии: Kafka 3.8.x (KRaft), RabbitMQ 3.13.x (quorum queues), Redis 7.x (cluster mode), Java 21, Spring Boot 3.3.x, Maven, terraform provider bpg/proxmox.

Архитектурные решения (зафиксированы, не отклоняться):
1. RabbitMQ = 3 VM (.233–.235), адрес .236 в резерве пула.
2. Redis Cluster на 2 VM: 6 инстансов (3 master + 3 replica), replica всегда на другой VM относительно master — это настоящий cluster-mode.
3. ОС: Debian 12 genericcloud (cloud image).

ОБЩИЕ ПРАВИЛА:
- Ничего не удаляй из уже существующего в Proxmox; работай строго в пуле адресов.
- Все операции идемпотентны (повторный запуск не должен ломать окружение).
- Секреты и ключи — только в Security/ и в env-переменных; никогда не хардкодь токен Proxmox в файлы, которые коммитятся; создавай .gitignore.
- При любой ошибке — диагностируй сам (journalctl, логи сервиса, Proxmox API, ping/ssh/tcp), чини, повторяй, и только потом отчитывайся.
- Обязательный артефакт каждого этапа — README.md в директории этапа: назначение, prerequisites, команды запуска, команды верификации, troubleshooting.
- Статус DONE выставляй только после прохождения своего раздела Acceptance с реальным выводом команд.

Формат отчёта агента (последнее сообщение):
STATUS: DONE | BLOCKED
EVIDENCE: фактические выводы команд из раздела Acceptance
CHANGES: список созданных/изменённых файлов и развёрнутых объектов
NOTES: отклонения, риски, что должен знать следующий агент
```

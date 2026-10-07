# Промт 8 — Агент «Приёмка»

(запускать после блока CTX.md)

```text
РОЛЬ: интеграционный QA-инженер.
ЗАДАЧА: финальная сквозная приёмка всей системы и документации. НИЧЕГО нового не разрабатывать —
только проверять, конфигурационно чинить и дописывать отсутствующие README по стандарту
(назначение / prerequisites / запуск / верификация / troubleshooting).

ПРОВЕРКИ:
1. terraform -chdir=Terraform plan -detailed-exitcode → exit 0 (нет дрейфа конфигурации).
2. ansible-playbook site.yml (из Ansible/) → всё зелёным, 0 критичных changes.
3. Статусы брокеров:
   - Kafka: kafka-broker-api-versions --bootstrap-server .230:9092 → 3 broker'а; describe cluster → контроллер назначен;
   - RabbitMQ: rabbitmqctl cluster_status → 3 узла в running; queue info pipeline.enriched → x-queue-type=quorum, реплики на всех узлах;
   - Redis: redis-cli -c -a <pass> CLUSTER INFO → cluster_state:ok; --cluster check .237:6371 → 16384/16384, 3M+3R.
4. E2E: 5 последовательных curl POST /start к запущенному Service → все 200, envelope с обоими stages
   и finalMarker; задержки end-to-end зафиксировать в отчёте.
5. Инвентарь пула: в Proxmox VM только на .230-.235 и .237-.238; .236, .239, .240 свободны;
   параметры 2 CPU / 2048 MB / 40 GB у всех 8 VM (через API).
6. Секьюрити-аудит: приватный ключ не дублируется нигде вне Security/; токен Proxmox не хардкодится
   ни в одном файле репозитория (grep -r 'a5098b71' — совпадения только в CTX.md и заглушках README про env);
   .gitignore на месте в Terraform/, Ansible/, Security/, Service/.
7. Документация: в каждой из 4 директорий (Terraform/, Ansible/, Service/, Security/) README.md проходит
   проверку «новичок воспроизводит этап только по тексту» — включая roles/*/README для ролей common/kafka/rabbitmq/redis.
8. Создай корневой README.md: обзор архитектуры (ASCII-диаграмма цепочки), порядок применения
   Security → Terraform → Ansible → Service, карта адресов, команды быстрой проверки, как пересоздать всё с нуля.

ACCEPTANCE (отчёт по каждому пункту с фактическим выводом команд):
- 8/8 пунктов PASS. Любой FAIL — почини на уровне соответствующего артефакта (роль/конфиг/код — минимальные правки)
  и перепроверь; повторить E2E после любых починкок.
```

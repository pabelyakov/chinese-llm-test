# promts/ — промты агентов для развёртывания event-driven системы

Набор промтов для автономных агентов. Точка входа — **`00-orchestrator.md`**:
запускаете оркестратора, а он уже запускает суб-агентов в нужном порядке, передаёт им
контекст и проверяет результат на каждом шаге.

## Как запускать

Запустите агенту (в вашей среде: opencode Task / Claude Code / иная) промт-оркестратор:

```
Прочитай promts/00-orchestrator.md и promts/shared-context.md и выполни роль оркестратора:
последовательно запускай суб-агентов 01..07, передавая каждому shared-context + его промт +
артефакты предыдущих шагов, и проверяй acceptance-критерии перед переходом дальше.
```

Оркестратор сам:
1. прочитает все промты и зависимости;
2. будет запускать суб-агентов по порядку (1 → 2 → 3 → 4 → 5 → 6 → 7);
3. передаст каждому `shared-context.md` + текст его промта + выходы предыдущих агентов;
4. не перейдёт к следующему шагу, пока acceptance-критерии не зелёные;
5. в конце выдаст сводный отчёт.

## Файлы

| Файл | Агент | Назначение | Зависит от |
|------|-------|-----------|-----------|
| `shared-context.md` | — | Общий контекст для ВСЕХ агентов (Proxmox, IPAM, ресурсы, версии, решения D1–D8) | — |
| `00-orchestrator.md` | Agent 0 | Оркестратор: запускает суб-агентов по порядку | все |
| `01-security-scaffolding.md` | Agent 1 | Каркас директорий, SSH-ключи, .gitignore, INFRA/CONVENTIONS | — |
| `02-terraform-proxmox.md` | Agent 2 | 8 VM в Proxmox + outputs + inventory для Ansible | Agent 1 |
| `03-ansible-kafka.md` | Agent 3 | common-роль + Kafka KRaft (230-232) + топик | Agent 2 |
| `04-ansible-rabbitmq.md` | Agent 4 | RabbitMQ-кластер (233-235) + vhost/user | Agent 2, common от Agent 3 |
| `05-ansible-redis.md` | Agent 5 | Redis primary(237)/replica(238) | Agent 2, common от Agent 3 |
| `06-java-service.md` | Agent 6 | Spring Boot монолит + POST /start + pipeline | Agent 3/4/5 (endpoints) |
| `07-integration-validation.md` | Agent 7 | E2E-прогон + корневой README/RUNBOOK/VALIDATION | Agent 6 |

## Порядок и параллелизм

```
1 → 2 → 3 → 4 → 5 → 6 → 7
```

Agent 3/4/5 можно параллелить ПОСЛЕ Agent 2, но безопаснее последовательно: Agent 3 создаёт и
применяет общую `common`-роль (chrony, /etc/hosts, firewall, sysctl), от которой зависят 4 и 5.

## Ключевые соглашения

- Все артефакты складываются в `Terraform/`, `Ansible/`, `Service/`, `Security/`.
- Endpoints и пути к credentials всех кластеров — в едином `docs/ENDPOINTS.md`
  (секреты отдельно, gitignored/vault). Agent 6 берёт конфиг только оттуда.
- Решения D1–D8 (в `shared-context.md`) приняты заранее и не переигрываются без команды человека.

## Перед запуском (сделать человеку)

1. Подтвердить/поправить решения D1–D8 в `shared-context.md` (особенно D1 RabbitMQ .236,
   D2 Redis 2 узла, D3 ОС, D6 gateway/DNS).
2. Экспортировать Proxmox-токен в env (НЕ в файлы) — см. `Security/proxmox.env.example`,
   который создаст Agent 1.

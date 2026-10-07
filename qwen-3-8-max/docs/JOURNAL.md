# docs/JOURNAL.md — журнал работ pipeline (append-only)

> Формат записи: `## [дата/время] Agent N — <тема>` → что сделано, проверки
> (с выводом), отклонения, блокеры, next steps. Новые записи **добавлять
> сверху не надо** — дописываем вниз, хронологически.

---

## [2026-10-07] Agent 1 — инфраструктурный каркас и безопасность

**Сделано**
- Создана структура: `Terraform/`, `Ansible/{inventory,group_vars,roles,secrets}`,
  `Service/`, `Security/`, `docs/` + README в каждом каталоге (стандарт — `docs/CONVENTIONS.md` §6).
- `git init -b main` (только для проверки ignore-правил; коммиты делает человек).
- Сгенерирован SSH-ключ ed25519 **без passphrase**: `Security/id_ed25519` (0600),
  `Security/id_ed25519.pub` (0644). Решение задокументировано в `Security/README.md`.
- `.gitignore`: приватные ключи, `*.pem`, `*.tfvars`, `*.tfstate*`, `*.tfplan`,
  `.terraform/`, `Ansible/secrets/`, vault-файлы, `Service/target/`, `.env*`,
  логи и мусор ОС/IDE. Публичные ключи и `*.example` разрешены.
- `Security/proxmox.env.example`: шаблон `PM_API_URL` / `PM_API_TOKEN_ID` /
  `PM_API_TOKEN_SECRET` / `PM_TLS_INSECURE` + placeholder'ы node/storage/bridge.
  **Реальный токен в файл не записан.**
- `docs/INFRA.md` (SSOT): IPAM 8 VM, ресурсы 2vCPU/2GB/40GB, ОС Ubuntu 24.04,
  версии стека, gateway/DNS, схема сети, порты (Kafka 9092/9093; RabbitMQ
  5672/15672/4369/25672; Redis 6379; app 8080; SSH 22), тюнинг под 2 GB RAM,
  решения D1–D8, эталонный `/etc/hosts`.
- `docs/CONVENTIONS.md`: именования, контракт обмена артефактами между агентами,
  единый `docs/ENDPOINTS.md`, расположение секретов Ansible (`Ansible/secrets/`,
  gitignored), стандарт README, DoD.
- `docs/ENDPOINTS.md`: stub-каркас с разделами 0–7 для заполнения Agent 2/3/4/5/6.

**Проверки (вывод)**
- `ssh-keygen -l -f Security/id_ed25519.pub` →
  `256 SHA256:WcxoiErzEvfMPSS81S/lNmoJZVTv6fuW+0y28TejacE kimi-lab-deploy@MacBook-Pro-Petr.local (ED25519)`
- `git check-ignore -v` для секретов → все правила срабатывают (см. run-report Agent 1).
- `git status --short` → приватный ключ/секреты не отображаются как untracked.

**Отклонения / решения**
- Ключ без passphrase — по требованию оркестратора, обоснование и компенсации в `Security/README.md`.
- `Ansible/secrets/` игнорируется целиком (включая собственный README) — осознанно.
- IP для Java-монолита не назначен (нет в IPAM; резерв `.236/.239/.240` не используется) —
  решение о хостинге приложения оставлено оркестратору/Agent 6.
- **НАЙДЕНА УТЕЧКА ВО ВХОДНЫХ ДАННЫХ**: `promts/shared-context.md` содержит
  реальный Proxmox-токен (`root@pam!terraform = a5098b71-...`). Файл добавлен в
  `.gitignore` (правило `promts/shared-context.md`), чтобы секрет не попал в
  историю git. Действие для оркестратора/человека: вычистить токен из промта
  и/или **ротировать токен** в Proxmox, после чего правило можно снять.
  В артефактах Agent 1 токен не записан (проверено `grep -r`).

**Next steps**
- Agent 2: подтвердить через Proxmox API node/storage/bridge/cloud-image и
  gateway `192.168.1.1` / DNS (D6), записать результат в `docs/ENDPOINTS.md` §2
  и в этот журнал; сгенерировать `Ansible/inventory/hosts.yml` из outputs.
- Agent 3/4: роли `common`, `kafka` (KRaft), `rabbitmq` (3 узла), `redis`
  (primary/replica); секреты в `Ansible/secrets/`; заполнить `docs/ENDPOINTS.md` §3–§5.

# Security/ — SSH-ключи и шаблоны секретов

Каталог **gitignored** для приватных материалов. В репозиторий попадают только
`*.pub`, `*.example` и этот README (см. `.gitignore` в корне).

## Состав

| Файл | Секрет? | В git? | Назначение |
|---|---|---|---|
| `id_ed25519` | ДА | НЕТ (ignored) | Приватный SSH-ключ для доступа ко всем VM лабы, права `0600` |
| `id_ed25519.pub` | нет | да | Публичный ключ — его забирает **Agent 2 (Terraform)** для cloud-init `ssh_authorized_keys` и **Agent 3 (Ansible)** для подключения |
| `proxmox.env.example` | нет | да | Шаблон env-переменных Proxmox API с placeholder'ами |
| `proxmox.env` | ДА | НЕТ (ignored) | Реальный токен; создаётся вручную копированием шаблона |

## Принятое решение: ключ БЕЗ пароля (passphrase-less)

Сгенерирован `ed25519` ключ **без passphrase**. Обоснование:

1. Полностью автономный pipeline: Terraform → Ansible → deploy Java-сервиса
   выполняются агентом без интерактива; passphrase потребовал бы ручного ввода
   или ssh-agent на каждой итерации, что ломает автоматизацию.
2. Ключ **лабораторный**, живёт только в закрытой сети `192.168.1.0/24`,
   выдаётся единственному пользователю `ubuntu` на 8 VM без root-доступа
   (privilege escalation через `sudo` ограничен пользователем deploy).
3. Приватный ключ не покидает машину оператора и исключён из git.

Компромисс и меры компенсации:
- ключ **одноразовый для лабы**: при компрометации — перегенерировать и
  перераскатать VM (`terraform taint` / повторный cloud-init);
- права `0600` на приватный ключ, `0644` на публичный;
- запрещён `PermitRootLogin` и password-auth на VM (настраивает Agent 3 в роли `common`);
- для продакшена такая схема НЕДОПУСТИМА — там обязателен passphrase + ssh-agent
  или hardware-токен (YubiKey/PIV), либо короткие SSH-сертификаты (Vault CA).

## Команды

```bash
# генерация (идемпотентно: НЕ перезаписывать существующий ключ без нужды!)
ssh-keygen -t ed25519 -C "kimi-lab-deploy" -f Security/id_ed25519 -N ""
chmod 600 Security/id_ed25519 && chmod 644 Security/id_ed25519.pub

# проверка отпечатка
ssh-keygen -l -f Security/id_ed25519.pub

# экспорт env Proxmox API в текущий shell (после создания proxmox.env)
cp -n Security/proxmox.env.example Security/proxmox.env   # затем вписать токен
set -a; source Security/proxmox.env; set +a
env | grep -E '^PM_' | sed 's/=\(.\{6\}\).*/=\1****/'      # без раскрытия секрета

# проверка доступа к VM (после раскатки Terraform'ом)
ssh -i Security/id_ed25519 -o StrictHostKeyChecking=accept-new ubuntu@192.168.1.230 hostname
```

## Политики (обязательны для всех агентов pipeline)

- **P1** Токен Proxmox и пароли сервисов передаются **только** через env /
  gitignored-файлы / `ansible-vault`. Хардкод в манифестах, ролях, README — запрещён.
- **P2** Секреты приложений (RabbitMQ/Redis/DB) Java-сервис читает из
  переменных окружения; в `Service/src/**` и `application.yml` — только ссылки
  на env с placeholder-значениями.
- **P3** Перед каждым коммитом: `git status --short` не должен показывать
  `*.tfvars`, `*.tfstate*`, `Security/id_ed25519`, `Ansible/secrets/`, `.env`.
- **P4** Kafka в лабе работает в режиме **PLAINTEXT без аутентификации** —
  это осознанное упрощение, зафиксировано в `docs/INFRA.md`. Не выносить наружу.
- **P5** Никогда не печатать значение секрета в логи/отчёты (маскировать).
- **P6** Реальный токен Proxmox присутствует во входном файле
  `promts/shared-context.md` → файл добавлен в `.gitignore`. Рекомендация:
  ротировать токен в Proxmox и вычистить его из промтов (см. `docs/JOURNAL.md`).

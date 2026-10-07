# Security

SSH-ключи доступа к виртуальным машинам лабораторного стенда Event-Driven.

## Назначение

Пара ключей Ed25519 используется:

- Terraform — для provision'инга VM в Proxmox (публичный ключ передаётся в cloud-init);
- Ansible — для подключения к VM (`private_key_file = ../Security/ed25519` в `ansible.cfg`);
- оператором — для ручного входа: `ssh -i Security/ed25519 deploy@<IP>`.

## Файлы

| Файл            | Права | Назначение        | В git      |
|-----------------|-------|-------------------|------------|
| `ed25519`       | 600   | приватный ключ    | **НЕТ** (см. `.gitignore`) |
| `ed25519.pub`   | 644   | публичный ключ    | да         |

## ВАЖНО: приватный ключ не коммитится

Приватный ключ `Security/ed25519` исключён в `.gitignore`. Никогда не добавляйте
его в git принудительно (`git add -f`), не копируйте в другие директории и не
передавайте в открытом виде. Если ключ попал в коммит — считайте его
скомпрометированным и регенерируйте (см. ниже).

## Процедура регенерации ключей

```bash
# 1. Сгенерировать новую пару (из корня репозитория)
ssh-keygen -t ed25519 -N "" -C "event-driven-lab" -f Security/ed25519

# 2. Выставить права
chmod 600 Security/ed25519
chmod 644 Security/ed25519.pub

# 3. Проверить права
ls -l Security/ed25519 Security/ed25519.pub
```

## Что перенастроить после регенерации

1. **Terraform** — Terraform прокатывает публичный ключ в VM через cloud-init
   (переменная `ssh_public_key` в `terraform.tfvars`). Обновите её значением из
   `Security/ed25519.pub` и выполните `terraform apply` для существующих VM
   (либо внесите ключ через vm Setings → Cloud-Init и пересоздайте VM).
2. **Ansible** — ничего менять не нужно: `ansible.cfg` ссылается на путь
   `../Security/ed25519`, имя файла сохраняется.
3. **Ручной доступ** — используйте новый ключ: `ssh -i Security/ed25519 deploy@<IP>`
   (старый ключ перестанет работать после обновления authorized_keys на VM).
4. **CI/секреты** — если ключ копировался в CI-переменные или vault, обновите и их.

## Проверка подключения

```bash
ssh -i Security/ed25519 -o StrictHostKeyChecking=no deploy@192.168.1.230
```

## Типовые проблемы

- `Permission denied (publickey)` — ключ не совпадает с `authorized_keys` на VM:
  повторите provisioning через Terraform или проверьте cloud-init (`cloud-init status`).
- `WARNING: UNPROTECTED PRIVATE KEY FILE` — сбросились права: `chmod 600 Security/ed25519`.
- Подключение зависает — VM недоступна/не имеет IP: проверьте IP-план и пинг до хоста.

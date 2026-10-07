# Security — SSH-ключи для инфраструктуры

Этот каталог содержит общую SSH-пару Ed25519 для доступа к VM (Ubuntu Server 22.04)
в ProxMox. Ключи созданы заново, без passphrase, исключительно для этой инфраструктуры.

## Файлы

| Файл | Назначение | Права |
|------|-----------|-------|
| `id_ed25519` | приватный ключ (Ansible) | `0600` |
| `id_ed25519.pub` | публичный ключ (cloud-init / Terraform) | `0644` |
| `README.md` | эта инструкция | — |
| `known_hosts.md` | инструкция по фиксации fingerprint хостов | — |

## Имя пользователя для VM

> **ВНИМАНИЕ (важно для агентов terraform / cloud-init):**
> Имя пользователя на VM зафиксировано как **`ubuntu`**.
>
> Это дефолтный пользователь cloud-image Ubuntu (Ubuntu Server 22.04). Публичный ключ
> внедряется именно в аккаунт `ubuntu` через cloud-init (`users: [ name: ubuntu, ... ]`).

## Где используется приватный ключ

- **Ansible** — подключение к хостам:
  - `ansible.cfg` → `private_key_file = Security/id_ed25519`
  - inventory / `host_vars` → `ansible_ssh_private_key_file=Security/id_ed25519`
  - `ansible_user=ubuntu`
- Локально можно проверить: `ssh -i Security/id_ed25519 ubuntu@<host>`

## Где используется публичный ключ

- **Terraform / cloud-init** — внедряется в VM при первой загрузке:
  ```yaml
  # cloud-init
  users:
    - name: ubuntu
      ssh_authorized_keys:
        - ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIBif3IuiO9OGaX8DRTbG+8ARE6RymhelgOiI6l/zSBxC deepseek-harness-agent
  ```
  Либо через Terraform: `sshkeys = file("Security/id_ed25519.pub")` (Proxmox `vmid`/cloud-init).

## Как перегенерировать ключи

```bash
cd Security
# резервная копия старых ключей (если нужна)
# rm -f id_ed25519 id_ed25519.pub

ssh-keygen -t ed25519 -f id_ed25519 -N "" -C "deepseek-harness-agent"
chmod 600 id_ed25519
chmod 644 id_ed25519.pub
```

Fallback (если Ed25519 недоступен):

```bash
ssh-keygen -t rsa -b 4096 -f id_rsa -N "" -C "deepseek-harness-agent"
chmod 600 id_rsa
chmod 644 id_rsa.pub
```

> После перегенерации обязательно обнови публичный ключ в cloud-init/Terraform и
> перекати VM (иначе новые ключи не попадут на уже созданные машины).

## Безопасность

- **НИКОГДА НЕ КОММИТЬ `id_ed25519` (приватный ключ) в git.** Он добавлен в
  `.gitignore` (корневой `/.gitignore` и `Security/.gitignore`).
- Ключ без passphrase — хранить только на машине разработчика.
- При утечке приватного ключа — перегенерировать и отозвать публичный ключ с VM.

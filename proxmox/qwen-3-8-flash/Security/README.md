# Security — SSH-материалки для управления VM

## Назначение

Ed25519-пара `id_ed25519_vm` — единственный метод аутентификации SSH-пользователя
`ops` на всех будущих VM пайплайна (kafka-1..3, rabbit-1..3, redis-1..2).

- Приватный ключ: `Security/id_ed25519_vm` (права `600`, в git **не попадает**).
- Публичный ключ: `Security/id_ed25519_vm.pub` (права `644`, коммитится).
- Парольная аутентификация на VM отключена (`PasswordAuthentication no`);
  ключ — единственный фактор доступа.

## Где используется

| Потребитель | Как |
|---|---|
| **Terraform/** (Proxmox cloud-init) | `Security/id_ed25519_vm.pub` подкладывается в resource `proxmox_virtual_environment_file` (cloud-init user snippet) / параметр `ssh_keys` — ключ попадает в `~ops/.ssh/authorized_keys` при первом загрузе VM. |
| **Ansible/** | `ansible.cfg` → `private_key_file = ../Security/id_ed25519_vm`; inventory: `ansible_user = ops`. Все playbooks подключаются к VM по статическим IP пула 192.168.1.230–.240. |
| **Service/** (CI/deploy) | При необходимости тот же приватный ключ передается через env-переменную (например `VM_SSH_PRIVATE_KEY`), не хардкодится в файлах. |

## Команды

Генерация (идемпотентно — перезапуск перезапишет пару только с явного удаления старых файлов):

```bash
ssh-keygen -t ed25519 -a 100 -N "" -f Security/id_ed25519_vm -C "ops@pipeline"
chmod 600 Security/id_ed25519_vm
chmod 644 Security/id_ed25519_vm.pub
```

Верификация:

```bash
ls -l Security/
# сверка: публичный ключ, выведенный из приватного, идентичен .pub
ssh-keygen -y -f Security/id_ed25519_vm | diff - Security/id_ed25519_vm.pub && echo OK
# проверка, что git игнорирует приватную часть
git check-ignore -v Security/id_ed25519_vm && echo PRIVATE-IGNORED
git check-ignore Security/id_ed25519_vm.pub || echo PUB-NOT-IGNORED
# SSH-доступ к VM (после развёртывания)
ssh -i Security/id_ed25519_vm -o IdentitiesOnly=yes -o StrictHostKeyChecking=accept-new ops@<IP-VM>
```

## Политика ротации

1. Ротация — не реже 12 месяцев, немедленно — при компрометации или увольнении
  владельца приватного ключа.
2. Порядок ротации: сгенерировать новую пару во временном пути → добавить новый
   публичный ключ в cloud-init/Terraform и перезапустить конфигурацию authorized_keys
   на всех VM (Ansible-задача) → проверить вход новым ключом → удалить старый ключ
   с VM → заменить `Security/id_ed25519_vm*` → закоммитить новый `.pub`.
3. Приватная часть хранится только в `Security/` на машинах операторов и в
   env-переменных CI (sealed secret); копирование в другие репозитории/артефакты
   запрещено.

## Предупреждение

**НЕ КОММИТИТЬ приватную часть `Security/id_ed25519_vm`.** `.gitignore` в этой
директории блокирует её, но перед каждым коммитом проверяйте
`git status`/`git diff --cached` — приватный ключ не должен попадать в индекс.
Утечка ключа = полный SSH-доступ ко всем VM пайплайна (root-уровня риск).

## Troubleshooting

- `Permission denied (publickey)` — проверить `-i Security/id_ed25519_vm`,
  `-o IdentitiesOnly=yes`, что `.pub` реально в `authorized_keys` VM и что права на
  приватный ключ `600`.
- `UNPROTECTED PRIVATE KEY FILE` — `chmod 600 Security/id_ed25519_vm`.
- `ssh-keygen -y` не совпадает с `.pub` — пара рассинхронизирована: перегенерировать
  и повторно применить cloud-init/authorized_keys через Ansible.

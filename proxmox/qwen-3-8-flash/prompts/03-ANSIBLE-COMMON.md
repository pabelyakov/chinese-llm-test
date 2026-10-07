# Промт 3 — Агент «Ansible Common»

(запускать после блока CTX.md)

```text
РОЛЬ: Ansible-инженер.
ЗАДАЧА: создать каркас Ansible/ и роль common, подготовить 8 VM к установке брокеров.

ШАГИ:
1. Ansible/ansible.cfg: private_key_file = ../Security/id_ed25519_vm, remote_user = ops,
   host_key_checking = False, inventory = inventory.ini.
2. Ansible/inventory.ini: группы [kafka] .230-.232, [rabbitmq] .233-.235, [redis] .237-.238,
   [infra:children] kafka,rabbitmq,redis; per-host ansible_host + ansible_ssh_common_args с key из Security/.
3. Создай структуру ролей вручную (tasks/handlers/vars/defaults/meta — без galaxy init).
4. roles/common (meta/README и комментарии в задачах — обязательны):
   - hostname по inventory_hostname + /etc/hosts со всеми 8 именами (групповые vars);
   - chrony, timezone UTC;
   - пакеты: curl, unzip, gnupg, acl, ca-certificates;
   - sysctl persist /etc/sysctl.d/99-pipeline.conf: vm.swappiness=0, vm.overcommit_memory=1, net.core.somaxconn=4096;
   - limits nofile 65536 для ops и сервисных пользователей;
   - swap: убедиться, что swap выключен и закомментирован в /etc/fstab (критично для Kafka/Redis).
5. site.yml: hosts: infra, roles: [common].
6. Выполни ansible-playbook site.yml; при ошибках (known_hosts, sudo, timeout) — диагностируй и чини до зелёного прогона.
7. Ansible/README.md: структура каталога, как добавить хост/роль/группу, как запускать, как отлаживать (--vv, ad-hoc ping, --check --diff).

ACCEPTANCE:
- ansible infra -m ping — 8/8 SUCCESS;
- ansible infra -m shell -a "hostname; sysctl -n vm.overcommit_memory vm.swappiness; swapon --show" —
  ожидаемые значения на всех узлах (swapon пусто);
- повторный запуск site.yml: 0 critical changes (идемпотентность);
- README.md на месте.
```

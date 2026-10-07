# AGENT 2 — Terraform / Proxmox: раскатка VM

=== SHARED CONTEXT (см. promts/shared-context.md) ===
ВХОД ОТ Agent1: Security/id_ed25519.pub, docs/INFRA.md, .gitignore, env-доступ к Proxmox.

РОЛЬ: Инженер Terraform/Proxmox.

ЦЕЛЬ: кодом Terraform поднять в Proxmox 8 VM (по IPAM) из Ubuntu 24.04 cloud image,
с ресурсами 2 vCPU / 2 GB / 40 GB, пробросом SSH-pubkey через cloud-init, и отдать Ansible
готовый inventory.

## ЗАДАЧИ
1. Авто-обнаружить через Proxmox API (или переменные с проверкой): имя node, storage с
   достаточным местом, сетевой bridge (vmbr0), gateway (D6 — подтвердить). Если не определяется — эскалировать.
2. Подготовить базовый шаблон: скачать Ubuntu 24.04 cloud image (qcow2), создать template VM
   в Proxmox (cloud-init). Либо использовать возможность провайдера создавать VM из образа.
   Задокументировать выбранный подход.
3. Написать манифесты в Terraform/: versions.tf, providers.tf (bpg/proxmox, PM_TLS_INSECURE),
   variables.tf, main.tf, outputs.tf, terraform.tfvars.example. Реальные значения — через
   tfvars (gitignored) и env. 8 VM: имена и IP строго по IPAM, статические адреса cloud-init
   (ipconfig0), gw, DNS, ssh_authorized_keys = pubkey из Security/, hostname, timezone, chrony.
4. Ресурсы каждой VM: cores=2, memory=2048, scsi0=40GB. qemu-guest-agent включён (agent=1).
5. Outputs: id, имя, IP каждой VM. Сгенерировать Ansible-inventory: через local_file или
   отдельный скрипт записать Ansible/inventory/hosts.yml (группы kafka/rabbitmq/redis,
   ansible_host=IP, ansible_user, ansible_ssh_private_key_file -> Security/id_ed25519).
   Также записать Ansible/group_vars/all.yml с IP и портами.
6. `terraform init/plan/apply`. Дождаться готовности VM и qemu-guest-agent.
7. ДИАГНОСТИКА: проверить SSH-доступность всех 8 IP с этой машины
   (ssh -i Security/id_ed25519 ubuntu@IP 'echo ok'), наличие IP внутри гостя, chrony, /etc/hosts.
   При ошибках — читать terraform output, cloud-init.log в госте, консоль Proxmox; исправлять и
   повторять apply.

## DELIVERABLES
Terraform/*.{tf,tfvars.example}, Terraform/README.md (полная инструкция: prerequisites, env,
init/plan/apply/destroy, как менять параметры, траблшутинг); сгенерированные
Ansible/inventory/hosts.yml и Ansible/group_vars/all.yml; outputs со списком VM/IP.

## ACCEPTANCE
- `terraform apply` проходит без ошибок, 8 VM в Proxmox в статусе running.
- Каждая VM: 2 vCPU / 2 GB / 40 GB, корректный статический IP из IPAM.
- SSH до всех 8 IP работает с ключом из Security/ (показать вывод проверки).
- hosts.yml и group_vars/all.yml валидны (`ansible-inventory --graph`).

## ПРАВИЛА
Автономно, идемпотентно (повторный apply не пересоздаёт VM). tfstate и tfvars — в .gitignore.
Токен только через env. Верни run-report: список VM+IP, путь к inventory, как подключаться.

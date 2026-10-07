# Промт 2 — Агент «Terraform»

(запускать после блока CTX.md)

```text
РОЛЬ: Terraform-инженер, работает с Proxmox VE.
ЗАДАЧА: раскатать 8 VM в Proxmox через Terraform (модуль в Terraform/).

ПРЕДВАРИТЕЛЬНАЯ РАЗВЕДКА (сделай до написания кода, curl -k к API):
1. GET /api2/json/nodes — узнай имя ноды Proxmox.
2. GET /api2/json/nodes/<node>/network?type=bridge — определи bridge (ожидаем vmbr0), его gateway и /24-сеть; эти значения подставь в cloud-init VM.
3. GET /api2/json/nodes/<node>/storage — выбери активное shared/local storage для дисков и для образа.

ШАГИ:
1. Напиши root-модуль в Terraform/: provider bpg/proxmox
   (pm_api_endpoint = env PM_API_ENDPOINT, pm_api_token_id = env PM_API_TOKEN_ID,
    pm_api_token_secret = env PM_API_TOKEN_SECRET — значения из CTX передавай только через env при запуске,
    НЕ в .tf-файлах).
2. resource proxmox_virtual_environment_download_file — Debian 12 genericcloud qcow2:
   https://cloud.debian.org/images/cloud/bookworm/latest/debian-12-genericcloud-amd64.qcow2
   в выбранное storage.
3. locals-карта 8 VM (имена/IPv4 строго из плана CTX), для каждой — proxmox_virtual_environment_vm:
   node, cores=2, sockets=1, memory=2048, disk 40G (virtio-scsi), agent enabled,
   initialization.cloud-init: user "ops", ssh_keys = содержимое Security/id_ed25519_vm.pub,
   static IPv4 + gateway + nameserver (из разведки), network device = bridge из разведки.
   vm_id зафиксируй детерминированно = последний октет (230…238), description каждой VM.
4. outputs: имена, IP, vm_id, ssh-команды. terraform outputs -json > Terraform/outputs.json.
5. Выполни: terraform init, fmt, validate, plan, apply -auto-approve.
6. Дождись готовности: для каждой VM — ping и
   ssh -o StrictHostKeyChecking=no -i ../Security/id_ed25519_vm ops@<IP> 'hostname'
   возвращает ожидаемое имя. Если cloud-init не применил IP/ключ — диагностируй через QEMU guest agent / Proxmox API,
   чини конфиг, пересоздай конкретную VM (destroy + apply только её).
7. Напиши Terraform/README.md: как задать токен через env, последовательность init/plan/apply, таблица VM,
   как добавить VM, troubleshooting (cloud-init не применился, неверный bridge, storage, каталог загрузки образа).
8. Создай Terraform/.gitignore (.terraform/, terraform.tfstate*) и terraform.tfvars.example.

ACCEPTANCE:
- terraform state list содержит образ + 8 VM (9+ ресурсов);
- for ip in 230 231 232 233 234 235 237 238; ssh ops@192.168.1.$ip hostname — 8 успешных hostname'ов;
- вывод API/GUI по каждой VM подтверждает 2 vCPU / 2048 MB / 40 GB и правильный IP;
- outputs.json на месте, README.md на месте.
```

# Terraform/ — раскатка VM в Proxmox VE

> Каталог подготовлен Agent 1 (каркас). Содержимое манифестов — зона
> ответственности **Agent 2**. Правила и факты — `docs/INFRA.md`,
> `docs/CONVENTIONS.md`.

## Требования
- Terraform v1.16.0, provider `bpg/proxmox` (последняя стабильная 2.x).
- Доступ до `https://192.168.1.253:8006/` (insecure TLS).
- Публичный SSH-ключ: `Security/id_ed25519.pub` (в cloud-init `ssh_authorized_keys`).

## Что должно появиться в этом каталоге (чек-лист для Agent 2)
```
Terraform/
├── versions.tf        # required_providers: bpg/proxmox
├── provider.tf        # endpoint + insecure, ТОЛЬКО из env PM_*
├── variables.tf       # node/storage/bridge/vm-спеки (дефолты из docs/INFRA.md)
├── locals.tf          # IPAM-карта 8 VM: hostname → ip → роль → node.id
├── main.tf            # proxmox_virtual_environment_vm ×8 + cloud-init
├── ubuntu-cloudimg.tf # загрузка/импорт образа Ubuntu 24.04 amd64
├── outputs.tf         # ip/hostname/vmid → пишется в Ansible/inventory/hosts.yml
├── terraform.tfvars   # GITIGNORED (*.tfvars в .gitignore) — локальные значения
└── README.md          # этот файл + результаты подтверждения node/storage/bridge (D6)
```

## Запуск
```bash
set -a; source Security/proxmox.env; set +a     # экспорт PM_* (файл gitignored)
cd Terraform
terraform init
terraform validate && terraform fmt -check
terraform plan -out tfplan                       # *.tfplan gitignored
terraform apply tfplan
```

## Проверка результата
```bash
terraform output -json | jq '.[] | {hostname, ip}'   # ожидания — 8 VM из IPAM
for ip in 230 231 232 233 234 235 237 238; do
  ssh -i ../Security/id_ed25519 -o StrictHostKeyChecking=accept-new ubuntu@192.168.1.$ip hostname
done
```

## Секреты и безопасность
- Токен Proxmox — **только** env (`PM_API_TOKEN_SECRET`), шаблон:
  `Security/proxmox.env.example`. Значение в файлы не писать.
- `*.tfvars`, `*.tfstate*`, `*.tfplan`, `.terraform/` — в `.gitignore`.
- Лимиты VM жёсткие (D8): 2 vCPU / 2048 MB / 40 GB.

## Next steps (для Agent 2)
1. Подтвердить через API: node, storage, bridge, cloud-image datastore, gateway/DNS (D6).
2. Результат зафиксировать в `docs/ENDPOINTS.md` §2 и `docs/JOURNAL.md`.
3. Сгенерировать `Ansible/inventory/hosts.yml` из outputs.

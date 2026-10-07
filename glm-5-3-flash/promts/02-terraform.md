# Промт 2 — Terraform: раскатка 8 VM в ProxMox

```text
Ты – DevOps-инженер. Рабочая директория: /Users/pabelyakov/Projects/llm-test/z-ai/terraform. Ключи для VM уже лежат в ../security/deploy_key(.pub).

ЗАДАЧА: написать манифесты Terraform и раскатать 8 VM в ProxMox.

КОНТЕКСТ:
- Адрес ProxMox API и токен передаются через переменные окружения:
  TF_VAR_pm_api_url, TF_VAR_pm_api_token (формат user@realm!tokenid=secret). Токен должен иметь права Administrator.
- Провайдер: bpg/proxmox (последняя стабильная мажорная версия).
- ОС: Ubuntu 22.04 LTS через клон cloud-init шаблона. Если шаблона нет в ProxMox — СНАЧАЛА сам скачай Ubuntu 22.04 cloud image (jammy-server-cloudimg-amd64.img), конвертируй и импортируй как шаблон через qm (SSH на гипервизор не нужен, если есть API; если через API нельзя — задокументируй ручные qm-команды в README и запроси их выполнение у пользователя, В МАНИФЕСТЕ предусмотри переменную target_template_id).
- Спецификация каждой VM: 2 vCPU, 2 GB RAM, 40 GB диск (virtio/scsi), network VirtIO, bridge vmbr0, VLAN – по умолчанию нет.

ЦЕЛЕВЫЕ VM (группы, статические IP, hostname):
- kafka-1 .. kafka-3      → 192.168.1.230, 231, 232
- rabbitmq-1 .. rabbitmq-3 → 192.168.1.233, 234, 235
- redis-1, redis-2        → 192.168.1.237, 238

ТРЕБОВАНИЯ К МАНИФЕСТАМ:
- variables.tf: pm_api_url, pm_api_token (sensitive), pm_node, vm_template, ssh_public_key_path (default ../security/deploy_key.pub), ip-адреса групп.
- main.tf: одна параметризованная for_each-конструкция для всех 8 VM (map с hostname, ip, ролью). user = cloud-init: пользователь deploy, ssh-ключ из security/, пакеты: qemu-guest-agent. Статический IP + шлюз 192.168.1.1 (вынеси в переменную) + DNS.
- outputs.tf: карта имя → IP (пригодится для Ansible-инвентаря).
- versions.tf: pinned версии провайдера.
- README.md: prerequisites (создание токена в ProxMox, права, создание шаблона — пошагово), команды init/plan/apply/destroy, как проверять VM в GUI/API, troubleshooting.

ПОРЯДОК РАБОТЫ:
1. terraform init, fmt, validate.
2. terraform plan → покажи сводку. apply выполняй сам.
3. Дождись старта VM и проверь доступность: ping + ssh -i ../security/deploy_key deploy@<ip> на каждую (до 5 минут ожидания с retry).
4. Если apply падает — диагностируй (terraform logs PROXY=1, состояние на стороне ProxMox через API curl) и чини сам.

КРИТЕРИИ ПРИЁМКИ: terraform apply успешен, все 8 VM доступны по SSH с ключом, IP соответствуют плану, outputs выдают карту адресов.
Отчитайся: вывод terraform output, результат SSH-проверок.
```

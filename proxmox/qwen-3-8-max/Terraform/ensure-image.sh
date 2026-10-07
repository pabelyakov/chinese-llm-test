#!/usr/bin/env bash
# ensure-image.sh — идемпотентно гарантирует наличие qcow2-образа Ubuntu 24.04
# в Proxmox storage (content: import) для импорта в диск template-VM.
#
# ЗАЧЕМ СКРИПТ, А НЕ TERRAFORM-РЕСУРС:
#   PVE import-from по умолчанию удаляет исходный файл после импорта
#   (delete-imported-volumes=1), поэтому proxmox_download_file-ресурс
#   «исчезал» бы из хранилища, и каждый terraform plan предлагал бы скачать
#   его заново (нарушение идемпотентности). Файл в import/ — одноразовая
#   «заготовка» для создания шаблона, её подготовка вынесена из state.
#
# ЗАВИСИМОСТИ: curl, python3. СЕКРЕТЫ — только из env (D7):
#   set -a; source Security/proxmox.env; set +a
#   (нужны PM_API_URL, PM_API_TOKEN_ID, PM_API_TOKEN_SECRET; опц. PM_NODE/PM_STORAGE)
#
# ЗАПУСК (из корня репозитория, перед terraform apply):
#   Terraform/ensure-image.sh
#
# ПОВЕДЕНИЕ (идемпотентно):
#   1. template VM уже существует          -> выход 0 (образ не нужен)
#   2. файл уже лежит в <storage>:import/  -> выход 0
#   3. иначе: PVE-узел сам скачивает образ по URL (download-url API),
#      SHA256 проверяется на стороне PVE; скрипт ждёт завершения задачи.
set -euo pipefail

API="${PM_API_URL:-https://192.168.1.253:8006/}"
API="${API%/}/api2/json"
: "${PM_API_TOKEN_ID:?env PM_API_TOKEN_ID не задан (source Security/proxmox.env)}"
: "${PM_API_TOKEN_SECRET:?env PM_API_TOKEN_SECRET не задан (source Security/proxmox.env)}"
AUTH="Authorization: PVEAPIToken=${PM_API_TOKEN_ID}=${PM_API_TOKEN_SECRET}"

NODE="${PM_NODE:-prox-01}"
STORAGE="${PM_STORAGE:-storage}"
TEMPLATE_VMID="${TEMPLATE_VMID:-9000}"
FILE_NAME="${IMAGE_FILE_NAME:-noble-server-cloudimg-amd64.qcow2}"
IMAGE_URL="${IMAGE_URL:-https://cloud-images.ubuntu.com/noble/current/noble-server-cloudimg-amd64.img}"
IMAGE_SHA256="${IMAGE_SHA256:-6a81c37564db9b1ee84e141922625e1d7c5b389b99bb3c572e0243607d5bb4d2}"

api() { curl -sk -H "$AUTH" "$@"; }

# 1. Шаблон уже существует — образ не нужен (его всё равно съел бы импорт).
if api "$API/nodes/$NODE/qemu/$TEMPLATE_VMID/status" | grep -q '"status"'; then
  echo "OK: template VM $TEMPLATE_VMID уже существует — образ не требуется."
  exit 0
fi

# 2. Файл уже в import-каталоге хранилища.
if api "$API/nodes/$NODE/storage/$STORAGE/content" | grep -q "import/$FILE_NAME\""; then
  echo "OK: $STORAGE:import/$FILE_NAME уже в хранилище."
  exit 0
fi

# 3. Скачивание силами PVE-узла (быстрее, чем через рабочую станцию;
#    контрольная сумма проверяется на стороне PVE).
echo "Скачивание $IMAGE_URL -> $STORAGE:import/$FILE_NAME (проверка sha256 на PVE)..."
RESP=$(api -X POST "$API/nodes/$NODE/storage/$STORAGE/download-url" \
  --data-urlencode "content=import" \
  --data-urlencode "filename=$FILE_NAME" \
  --data-urlencode "url=$IMAGE_URL" \
  --data-urlencode "checksum=$IMAGE_SHA256" \
  --data-urlencode "checksum-algorithm=sha256")
UPID=$(echo "$RESP" | python3 -c 'import json,sys; print(json.load(sys.stdin)["data"])') || {
  echo "ERROR: download-url не принял задачу: $RESP" >&2; exit 1; }
echo "Задача PVE: $UPID"

ENC=$(python3 -c 'import urllib.parse,sys; print(urllib.parse.quote(sys.argv[1], safe=""))' "$UPID")
while true; do
  ST=$(api "$API/nodes/$NODE/tasks/$ENC/status")
  STATUS=$(echo "$ST" | python3 -c 'import json,sys; print(json.load(sys.stdin)["data"]["status"])')
  if [ "$STATUS" = "stopped" ]; then
    EXIT=$(echo "$ST" | python3 -c 'import json,sys; print(json.load(sys.stdin)["data"].get("exitstatus","?"))')
    if [ "$EXIT" = "OK" ]; then
      echo "OK: образ скачан и проверен (sha256): $STORAGE:import/$FILE_NAME"
      exit 0
    fi
    echo "ERROR: задача скачивания завершилась с: $EXIT" >&2
    echo "Лог: $API/nodes/$NODE/tasks/$ENC/log" >&2
    exit 1
  fi
  sleep 5
done

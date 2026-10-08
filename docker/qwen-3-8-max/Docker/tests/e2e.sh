#!/usr/bin/env bash
# ============================================================================
# e2e.sh — QA блок B: сквозной пайплайн HTTP -> Kafka -> RabbitMQ -> Redis.
#
# Проверяет:
#   B5. Happy path x N (по умолчанию 10): POST /api/v1/start -> 200, envelope
#       содержит correlationId (уникальные), originalMessage, stages
#       kafka-enrichment И rabbit-enrichment.
#   B6. Redis-след: ключ pipeline:result:{correlationId} существует,
#       0 < TTL <= 60, JSON в Redis совпадает с HTTP-ответом.
#   B7. Валидация: пустое тело / без поля message / blank message -> 400 (не 5xx).
#   B8. Наблюдаемость: correlationId прослеживается в Service/service.log на
#       всех этапах; активный consumer на очереди RabbitMQ.
#
# Usage:
#   ./e2e.sh            # все проверки блока B
#   ./e2e.sh -n 20      # 20 итераций happy path вместо 10
#
# Exit code: 0 — все PASS, 1 — есть FAIL.
# Зависимости: docker, curl, python3 (или jq). Запускать из любого каталога.
# ============================================================================
set -u

REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
APP_URL="http://localhost:8080/api/v1/start"
HEALTH_URL="http://localhost:8080/actuator/health"
SERVICE_LOG="$REPO_ROOT/Service/service.log"
REDIS_PASS="redis-secret"
N=10
while getopts "n:u:" opt; do
  case $opt in
    n) N="$OPTARG" ;;
    u) APP_URL="$OPTARG" ;;
    *) echo "usage: $0 [-n count] [-u url]"; exit 2 ;;
  esac
done
FAILS=0
ok()   { echo "PASS: $1"; }
fail() { echo "FAIL: $1"; FAILS=$((FAILS+1)); }

redis_cli() { docker exec redis-master redis-cli -a "$REDIS_PASS" --no-auth-warning "$@"; }

# JSON-поле из файла ответа (python3, без jq-зависимости)
jget() { python3 -c "
import json,sys
try:
  d=json.load(open(sys.argv[1]))
except Exception:
  print(''); sys.exit()
v=d
for k in sys.argv[2].split('.'):
  if isinstance(v,list): v=v[int(k)]
  elif isinstance(v,dict): v=v.get(k)
  else: v=None; break
print(v if v is not None else '')" "$1" "$2"; }

echo "============================================================"
echo " B0. Health приложения"
echo "============================================================"
HEALTH=$(curl -sS "$HEALTH_URL")
STATUS=$(echo "$HEALTH" | python3 -c 'import json,sys;print(json.load(sys.stdin)["status"])' 2>/dev/null)
[ "$STATUS" = "UP" ] && ok "GET /actuator/health -> status UP" || fail "health: '$STATUS' ($HEALTH)"

echo
echo "============================================================"
echo " B5. Happy path x$N (POST $APP_URL)"
echo "============================================================"
TAG="qa-e2e-$(date +%s)"
ALL_CIDS=""
LAST_BODY=""
for i in $(seq 1 "$N"); do
  MSG="$TAG-$i"
  BODY_FILE=$(mktemp /tmp/qa-e2e-body.XXXXXX)
  CODE=$(curl -sS -o "$BODY_FILE" -w '%{http_code}' -X POST "$APP_URL" \
    -H 'Content-Type: application/json' -d "{\"message\":\"$MSG\"}")
  CID=$(jget "$BODY_FILE" correlationId)
  ORIG=$(jget "$BODY_FILE" originalMessage)
  STAGES=$(python3 -c "
import json,sys
try: print(','.join(s['stage'] for s in json.load(open(sys.argv[1]))['stages']))
except Exception: print('')" "$BODY_FILE")
  PROBLEM=""
  [ "$CODE" = "200" ] || PROBLEM="$PROBLEM http=$CODE"
  [ -n "$CID" ] || PROBLEM="$PROBLEM no-correlationId"
  [ "$ORIG" = "$MSG" ] || PROBLEM="$PROBLEM originalMessage='$ORIG'"
  [ "$STAGES" = "kafka-enrichment,rabbit-enrichment" ] || PROBLEM="$PROBLEM stages='$STAGES'"
  case " $ALL_CIDS " in *" $CID "*) PROBLEM="$PROBLEM duplicate-cid";; esac
  ALL_CIDS="$ALL_CIDS $CID"
  if [ -z "$PROBLEM" ]; then
    echo "  [$i/$N] 200 cid=$CID stages=$STAGES"
  else
    fail "запрос $i ($MSG):$PROBLEM"
    cat "$BODY_FILE"; echo
  fi
  LAST_BODY=$(cat "$BODY_FILE")
  LAST_CID="$CID"
  rm -f "$BODY_FILE"
done
UNIQ=$(echo $ALL_CIDS | tr ' ' '\n' | sort -u | grep -c .)
[ "$UNIQ" = "$N" ] && ok "$N/$N запросов: 200, envelope полный, correlationId уникальны ($UNIQ)" \
  || fail "уникальных correlationId $UNIQ из $N"

echo
echo "============================================================"
echo " B6. Redis-след: pipeline:result:$LAST_CID"
echo "============================================================"
echo "-- HTTP-ответ (последний запрос B5):"
echo "$LAST_BODY" | python3 -m json.tool
RVAL=$(redis_cli get "pipeline:result:$LAST_CID")
RTTL=$(redis_cli ttl "pipeline:result:$LAST_CID")
if [ -n "$RVAL" ] && [ "$RVAL" != "(nil)" ]; then
  ok "ключ существует"
else
  fail "ключ pipeline:result:$LAST_CID не найден в Redis master"
fi
if [ "$RTTL" -gt 0 ] 2>/dev/null && [ "$RTTL" -le 60 ] 2>/dev/null; then
  ok "TTL=$RTTL (0 < TTL <= 60)"
else
  fail "TTL=$RTTL (ожидалось 0 < TTL <= 60)"
fi
MATCH=$(python3 - "$LAST_BODY" <<'PYEOF'
import json,sys,subprocess
http=json.loads(sys.argv[1])
raw=subprocess.run(["docker","exec","redis-master","redis-cli","-a","redis-secret",
  "--no-auth-warning","get",f"pipeline:result:{http['correlationId']}"],
  capture_output=True,text=True).stdout.strip()
try:
  print("EQUAL" if json.loads(raw)==http else "DIFFER")
except Exception:
  print("PARSE_ERROR")
PYEOF
)
[ "$MATCH" = "EQUAL" ] && ok "JSON в Redis совпадает с HTTP-ответом (deep-equal)" \
  || fail "JSON в Redis НЕ совпадает с HTTP-ответом ($MATCH)"

echo
echo "============================================================"
echo " B7. Валидация: некорректные тела -> 400"
echo "============================================================"
validate_case() { # validate_case <описание> <данные>
  local desc="$1" data="$2" code body
  body=$(curl -sS -o /dev/null -w '%{http_code}' -X POST "$APP_URL" \
    -H 'Content-Type: application/json' ${data:+-d "$data"})
  code="$body"
  if [ "$code" = "400" ]; then ok "$desc -> HTTP $code"
  elif [ "$code" -ge 500 ] 2>/dev/null; then fail "$desc -> HTTP $code (5xx вместо 400!)"
  else fail "$desc -> HTTP $code (ожидалось 400)"; fi
}
validate_case "пустое тело (нет payload)"          ""
validate_case "пустой JSON-объект {}"              '{}'
validate_case "без поля message {\"foo\":\"bar\"}" '{"foo":"bar"}'
validate_case "blank message {\"message\":\"\"}"   '{"message":""}'

echo
echo "============================================================"
echo " B8. Наблюдаемость: цепочка correlationId в логах"
echo "============================================================"
echo "-- grep '$LAST_CID' Service/service.log:"
LOG_LINES=$(grep -F "$LAST_CID" "$SERVICE_LOG" 2>/dev/null)
if [ -n "$LOG_LINES" ]; then
  echo "$LOG_LINES" | head -20
  LINES=$(echo "$LOG_LINES" | grep -c .)
  for PHASE in "producing to Kafka" "Kafka stage" "Rabbit" "Redis" "responding 200"; do
    echo "$LOG_LINES" | grep -q "$PHASE" \
      && ok "фаза '$PHASE' присутствует ($LINES строк всего)" \
      || fail "фаза '$PHASE' НЕ найдена в логе для $LAST_CID"
  done
else
  fail "correlationId $LAST_CID не найден в $SERVICE_LOG"
fi
echo "-- активный consumer на очереди (RabbitMQ):"
docker exec rabbit1 rabbitmqctl list_consumers 2>/dev/null | grep -q pipeline.stage2.queue \
  && ok "consumer на pipeline.stage2.queue зарегистрирован" \
  || fail "нет consumer'а на pipeline.stage2.queue"
echo "-- лог rabbit1 (аутентификация приложения user=pipeline):"
docker logs rabbit1 2>&1 | grep "user 'pipeline' authenticated" | tail -2

echo
echo "============================================================"
if [ "$FAILS" = "0" ]; then echo " ИТОГ: все проверки блока B — PASS"; exit 0
else echo " ИТОГ: FAIL-проверок: $FAILS"; exit 1; fi

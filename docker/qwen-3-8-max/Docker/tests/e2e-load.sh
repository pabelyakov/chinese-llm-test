#!/usr/bin/env bash
# ============================================================================
# e2e-load.sh — QA блок C: нагрузочный мини-тест сквозного пайплайна.
#
#   C9.  N запросов (по умолчанию 50) последовательно (-p 1) или параллельно
#        (-p 20 — через xargs -P). Критерий успеха каждого запроса:
#        HTTP 200 + correlationId + stages kafka-enrichment,rabbit-enrichment.
#        Метрики: successes (обязано быть 100%), p50/p95/max латентность,
#        ошибки. Сырые результаты — в файле (путь печатается).
#   C10. После нагрузки: heap-метрика приложения
#        (/actuator/metrics/jvm.memory.used, area=heap) и (флаг --wait-expiry)
#        проверка истечения TTL: все ключи pipeline:result:{cid} этой нагрузки
#        удаляются из Redis не позже чем через TTL(60c)+запас.
#
# Usage:
#   ./e2e-load.sh                          # 50 последовательных запросов
#   ./e2e-load.sh -n 20 -p 20              # 20 параллельных
#   ./e2e-load.sh -n 50 --wait-expiry      # + контроль истечения TTL (~70с)
#
# Exit code: 0 — успех 100% и TTL-проверки PASS, 1 — иначе.
# Зависимости: docker, curl, xargs, awk, python3. Запускать из любого каталога.
# ============================================================================
set -u

APP_URL="http://localhost:8080/api/v1/start"
REDIS_PASS="redis-secret"
N=50
PAR=1
WAIT_EXPIRY=0
while [ $# -gt 0 ]; do
  case "$1" in
    -n) N="$2"; shift 2 ;;
    -p) PAR="$2"; shift 2 ;;
    -u) APP_URL="$2"; shift 2 ;;
    --wait-expiry) WAIT_EXPIRY=1; shift ;;
    *) echo "usage: $0 [-n count] [-p parallel] [-u url] [--wait-expiry]"; exit 2 ;;
  esac
done

RUN_TAG="qa-load-$(date +%s)"
TMP=$(mktemp -d /tmp/qa-load.XXXXXX)
trap 'echo "сырые результаты: $TMP/results.txt"' EXIT
FAILS=0
ok()   { echo "PASS: $1"; }
fail() { echo "FAIL: $1"; FAILS=$((FAILS+1)); }
# BSD date (macOS) не поддерживает %N — миллисекунды через python3
now_ms() { python3 -c 'import time;print(int(time.time()*1000))'; }

# Один запрос: пишет строку "seq http_code time_ms correlationId stages"
run_one() {
  local i="$1"
  local body="{\"message\":\"$RUN_TAG-$i\"}"
  local meta code t cid stages
  meta=$(curl -sS -o "$TMP/body.$i" -w '%{http_code} %{time_total}' \
    -X POST "$APP_URL" -H 'Content-Type: application/json' -d "$body" 2>"$TMP/err.$i")
  code="${meta%% *}"; t="${meta##* }"
  local tms; tms=$(awk -v s="$t" 'BEGIN{printf "%.1f", s*1000}')
  cid=$(python3 -c "
import json,sys
try: print(json.load(open(sys.argv[1]))['correlationId'])
except Exception: print('')" "$TMP/body.$i" 2>/dev/null)
  stages=$(python3 -c "
import json,sys
try: print(','.join(s['stage'] for s in json.load(open(sys.argv[1]))['stages']))
except Exception: print('')" "$TMP/body.$i" 2>/dev/null)
  echo "$i $code $tms $cid $stages" > "$TMP/res.$i"
}
export -f run_one
export APP_URL TMP RUN_TAG

echo "============================================================"
echo " C9. Нагрузка: N=$N, parallel=$PAR, url=$APP_URL, tag=$RUN_TAG"
echo "============================================================"
T0=$(now_ms)
seq 1 "$N" | xargs -P "$PAR" -I{} bash -c 'run_one "$@"' _ {}
cat "$TMP"/res.* | sort -n > "$TMP/results.txt"
T1=$(now_ms)

awk -v n="$N" -v wall="$((T1-T0))" '
function pct(p,   idx) { idx=int((p/100)*cnt); if(idx<1)idx=1; if(idx>cnt)idx=cnt; return lat[idx] }
{
  total++
  if ($2==200 && $4!="" && $5=="kafka-enrichment,rabbit-enrichment") succ++
  else { failn++; if (failn<=5) print "  ошибка: req="$1" http="$2" cid="$4" stages="$5 }
  latms[total]=$3
}
END {
  cnt=0; for(i=1;i<=total;i++){ cnt++; lat[cnt]=latms[i]+0 }
  # сортировка вставками (n небольшой)
  for(i=2;i<=cnt;i++){ v=lat[i]; j=i-1; while(j>=1 && lat[j]>v){lat[j+1]=lat[j];j--} lat[j+1]=v }
  printf "  всего=%d успехов=%d ошибок=%d\n", total, succ, failn
  printf "  wall-time=%d мс\n", wall
  if (cnt>0) printf "  латентность: p50=%.1f мс  p95=%.1f мс  max=%.1f мс  min=%.1f мс\n",
    pct(50), pct(95), lat[cnt], lat[1]
  printf "  успех: %.1f%%\n", (total>0)? 100*succ/total : 0
  exit (succ==total && total==n)?0:1
}' "$TMP/results.txt"
AWK_RC=$?
[ "$AWK_RC" = "0" ] && ok "нагрузка $N/$N: 100% успех (латентность выше)" \
  || fail "нагрузка: есть ошибки или не все запросы выполнены (rc=$AWK_RC)"

echo
echo "============================================================"
echo " C10. Состояние приложения после нагрузки (утечки)"
echo "============================================================"
REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
SERVICE_LOG="$REPO_ROOT/Service/service.log"
HEAP=$(curl -sS 'http://localhost:8080/actuator/metrics/jvm.memory.used?tag=area:heap')
if echo "$HEAP" | grep -q '"name"'; then
  echo "$HEAP" | python3 -m json.tool
  ok "метрика jvm.memory.used (heap) доступна — значение выше"
else
  echo "-- /actuator/metrics не exposed (404): $(echo "$HEAP" | head -c 200)"
  echo "   heap-метрика недоступна, проверяем утечки косвенно:"
  # косвенные признаки отсутствия утечек:
  # 1) health UP после нагрузки
  ST=$(curl -sS http://localhost:8080/actuator/health \
    | python3 -c 'import json,sys;print(json.load(sys.stdin)["status"])' 2>/dev/null)
  [ "$ST" = "UP" ] && ok "health после нагрузки: UP" || fail "health после нагрузки: '$ST'"
  # 2) реестр корреляций пуст (pending=0 в последней строке CorrelationRegistry)
  PEND=$(grep 'CorrelationRegistry' "$SERVICE_LOG" 2>/dev/null | tail -1 \
    | grep -o 'pending=[0-9]*' | tail -1)
  echo "   последняя запись реестра: ${PEND:-нет данных}"
  [ "$PEND" = "pending=0" ] && ok "CorrelationRegistry: pending=0 (future'и завершены, записи удалены)" \
    || fail "CorrelationRegistry: '$PEND' (ожидалось pending=0 — возможна утечка записей)"
fi

if [ "$WAIT_EXPIRY" = "1" ]; then
  echo
  echo "============================================================"
  echo " C10b. Истечение TTL ключей pipeline:result:* (ожидание ~70с)"
  echo "============================================================"
  CIDS=$(awk '$4!=""{print $4}' "$TMP/results.txt")
  ALIVE_BEFORE=$(echo "$CIDS" | while read -r c; do
    [ -n "$c" ] && docker exec redis-master redis-cli -a "$REDIS_PASS" --no-auth-warning \
      exists "pipeline:result:$c"; done | grep -c '^1$' || true)
  echo "-- сразу после нагрузки ключей живо: $ALIVE_BEFORE из $N"
  echo "-- sleep 70 (TTL 60с + запас)..."
  sleep 70
  ALIVE_AFTER=$(echo "$CIDS" | while read -r c; do
    [ -n "$c" ] && docker exec redis-master redis-cli -a "$REDIS_PASS" --no-auth-warning \
      exists "pipeline:result:$c"; done | grep -c '^1$' || true)
  TOTAL_KEYS=$(docker exec redis-master redis-cli -a "$REDIS_PASS" --no-auth-warning \
    --scan --pattern 'pipeline:result:*' | wc -l | tr -d ' ')
  echo "-- после ожидания: живо ключей нагрузки: $ALIVE_AFTER; всего pipeline:result:* в Redis: $TOTAL_KEYS"
  [ "$ALIVE_AFTER" = "0" ] && ok "все ключи нагрузки истекли по TTL" \
    || fail "$ALIVE_AFTER ключей нагрузки пережили TTL (утечка?)"
fi

echo
echo "============================================================"
if [ "$FAILS" = "0" ]; then echo " ИТОГ: блок C — PASS"; exit 0
else echo " ИТОГ: FAIL-проверок: $FAILS"; exit 1; fi

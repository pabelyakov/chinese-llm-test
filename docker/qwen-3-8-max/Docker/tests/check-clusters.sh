#!/usr/bin/env bash
# ============================================================================
# check-clusters.sh — QA блок A: проверка кластерности брокеров и Redis.
#
# Проверяет:
#   A1. Kafka: 3 ноды healthy; топик pipeline.inbound — 3 партиции, RF=3,
#       ISR >= 2 на каждой партиции.
#   A2. (опция --failover) Kafka отказоустойчивость: stop kafka2 ->
#       produce/consume с acks=all продолжаются, сквозной POST даёт 200 ->
#       start kafka2 -> healthy, ISR восстанавливается до 3.
#   A3. RabbitMQ: cluster_status — 3 running nodes; очередь
#       pipeline.stage2.queue типа quorum (durable); binding stage.rabbit.
#   A4. Redis: info replication — master + 1 online replica;
#       SET на master реплицируется (GET на replica).
#
# Usage:
#   ./check-clusters.sh                # только read-only проверки (A1,A3,A4)
#   ./check-clusters.sh --failover     # + A2 (docker stop/start kafka2)
#
# Exit code: 0 — все проверки PASS, 1 — есть FAIL.
# Зависимости: docker, curl, awk. Запускать из любого каталога.
# ============================================================================
set -u

REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
KAFKA_TOPIC="pipeline.inbound"
REDIS_PASS="redis-secret"
RABBIT_QUEUE="pipeline.stage2.queue"
RABBIT_RK="stage.rabbit"
APP_URL="http://localhost:8080/api/v1/start"
FAILS=0
DO_FAILOVER=0
[ "${1:-}" = "--failover" ] && DO_FAILOVER=1

ok()   { echo "PASS: $1"; }
fail() { echo "FAIL: $1"; FAILS=$((FAILS+1)); }

redis_cli() { # redis_cli <container> <args...>
  local c="$1"; shift
  docker exec "$c" redis-cli -a "$REDIS_PASS" --no-auth-warning "$@"
}

echo "============================================================"
echo " A1. Kafka: ноды healthy + топик $KAFKA_TOPIC (3p/RF3/ISR>=2)"
echo "============================================================"
KAFKA_HEALTHY=$(docker ps --filter name='^kafka[123]$' --filter health=healthy --format '{{.Names}}' | sort | tr '\n' ' ')
if [ "$KAFKA_HEALTHY" = "kafka1 kafka2 kafka3 " ]; then
  ok "3 kafka-ноды healthy: $KAFKA_HEALTHY"
else
  fail "ожидались healthy kafka1 kafka2 kafka3, фактически: '${KAFKA_HEALTHY% }'"
fi

DESCRIBE=$(docker exec kafka1 /opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server kafka1:29092 --describe --topic "$KAFKA_TOPIC" 2>/dev/null)
echo "$DESCRIBE"
TOPIC_LINE=$(echo "$DESCRIBE" | head -1)
PC=$(echo "$TOPIC_LINE"  | awk -F'PartitionCount: ' '{print $2}' | awk '{print $1}')
RF=$(echo "$TOPIC_LINE"  | awk -F'ReplicationFactor: ' '{print $2}' | awk '{print $1}')
[ "$PC" = "3" ] && ok "PartitionCount=3" || fail "PartitionCount=$PC (ожидалось 3)"
[ "$RF" = "3" ] && ok "ReplicationFactor=3" || fail "ReplicationFactor=$RF (ожидалось 3)"
# ISR >= 2 на каждой партиции
BAD_ISR=$(echo "$DESCRIBE" | awk -F'Isr: ' '/Partition:/ {n=split($2,a,/[, \t]/); cnt=0;
  for(i=1;i<=n;i++) if(a[i] ~ /^[0-9]+$/) cnt++; if(cnt<2) print "partition ISR="cnt}')
if [ -z "$BAD_ISR" ]; then
  ok "ISR >= 2 на всех партициях: $(echo "$DESCRIBE" | awk -F'Isr: ' '/Partition:/{split($2,a,/\t/); printf "%s ", a[1]}')"
else
  fail "$BAD_ISR"
fi

echo
echo "============================================================"
echo " A3. RabbitMQ: кластер 3 ноды, quorum-очередь, binding"
echo "============================================================"
CLUSTER_STATUS=$(docker exec rabbit1 rabbitmqctl cluster_status 2>/dev/null)
RUNNING=$(echo "$CLUSTER_STATUS" \
  | awk '/^Running Nodes/{f=1;next} /^Versions/{f=0} f&&/^rabbit@/{c++} END{print c+0}')
if [ "$RUNNING" = "3" ]; then
  ok "cluster_status: 3 running_nodes"
  echo "$CLUSTER_STATUS" | awk '/^Running Nodes/{f=1} /^Versions/{f=0} f' | sed '/^$/d;s/^/   /'
else
  fail "cluster_status running_nodes=$RUNNING (ожидалось 3)"
fi

QTYPE=$(docker exec rabbit1 rabbitmqctl list_queues name type durable 2>/dev/null \
  | awk -v q="$RABBIT_QUEUE" '$1==q {print $2" "$3}')
if [ "$QTYPE" = "quorum true" ]; then
  ok "очередь $RABBIT_QUEUE: type=quorum durable=true"
else
  fail "очередь $RABBIT_QUEUE: '${QTYPE:-не найдена}' (ожидалось 'quorum true')"
fi

BINDING=$(docker exec rabbit1 rabbitmqctl list_bindings 2>/dev/null \
  | awk -v rk="$RABBIT_RK" '$1=="pipeline.exchange" && $3=="'"$RABBIT_QUEUE"'" && $5==rk')
if [ -n "$BINDING" ]; then
  ok "binding существует: $BINDING"
else
  fail "binding pipeline.exchange --[$RABBIT_RK]--> $RABBIT_QUEUE не найден"
fi

echo
echo "============================================================"
echo " A4. Redis: master + online replica, репликация SET->GET"
echo "============================================================"
REPL=$(redis_cli redis-master info replication)
echo "$REPL" | grep -E 'role|connected_slaves|slave[0-9]'
ROLE=$(echo "$REPL" | awk -F: '/^role:/{gsub(/\r/,"",$2);print $2}')
SLAVES=$(echo "$REPL" | awk -F: '/^connected_slaves:/{gsub(/\r/,"",$2);print $2}')
ONLINE=$(echo "$REPL" | grep -c 'state=online')
if [ "$ROLE" = "master" ] && [ "$SLAVES" = "1" ] && [ "$ONLINE" -ge 1 ]; then
  ok "master: role=master connected_slaves=1 state=online"
else
  fail "master: role=$ROLE connected_slaves=$SLAVES online=$ONLINE"
fi
LINK=$(redis_cli redis-replica info replication | awk -F: '/^master_link_status:/{gsub(/\r/,"",$2);print $2}')
[ "$LINK" = "up" ] && ok "replica: master_link_status=up" || fail "replica: master_link_status=$LINK"

KEY="qa:repl-check:$$"
redis_cli redis-master set "$KEY" "repl-ok" >/dev/null
sleep 0.5
GOT=$(redis_cli redis-replica get "$KEY")
redis_cli redis-master del "$KEY" >/dev/null
[ "$GOT" = "repl-ok" ] && ok "SET на master -> GET на replica: '$GOT'" \
  || fail "репликация: GET на replica вернул '$GOT' (ожидалось 'repl-ok')"

if [ "$DO_FAILOVER" = "1" ]; then
  echo
  echo "============================================================"
  echo " A2. Kafka failover: stop kafka2 -> produce/consume -> start"
  echo "============================================================"
  FT="qa.failover.test"
  KTOPICS="docker exec kafka1 /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka1:29092"
  $KTOPICS --create --topic "$FT" --partitions 3 --replication-factor 3 >/dev/null 2>&1 || {
    $KTOPICS --delete --topic "$FT" >/dev/null 2>&1; sleep 2
    $KTOPICS --create --topic "$FT" --partitions 3 --replication-factor 3 >/dev/null 2>&1
  }
  docker stop kafka2 >/dev/null && echo "-- kafka2 остановлен"
  MSG="qa-failover-$(date +%s)"
  echo "$MSG" | docker run --rm -i --network host apache/kafka:3.9.2 \
    /opt/kafka/bin/kafka-console-producer.sh \
    --bootstrap-server localhost:9092,localhost:9094 --topic "$FT" \
    --producer-property acks=all >/dev/null 2>&1 \
    && ok "produce acks=all при kafka2 down" || fail "produce при kafka2 down"
  CONSUMED=$(docker run --rm --network host apache/kafka:3.9.2 \
    /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 \
    --topic "$FT" --from-beginning --timeout-ms 20000 2>/dev/null | tail -1)
  [ "$CONSUMED" = "$MSG" ] && ok "consume при kafka2 down: '$CONSUMED'" \
    || fail "consume: '$CONSUMED' != '$MSG'"
  CODE=$(curl -sS -o /dev/null -w '%{http_code}' -X POST "$APP_URL" \
    -H 'Content-Type: application/json' -d '{"message":"qa-failover-e2e"}')
  [ "$CODE" = "200" ] && ok "сквозной POST при kafka2 down: HTTP $CODE" \
    || fail "сквозной POST при kafka2 down: HTTP $CODE"
  docker start kafka2 >/dev/null && echo "-- kafka2 запущен, ждём healthy..."
  for i in $(seq 1 40); do
    [ "$(docker inspect --format '{{.State.Health.Status}}' kafka2 2>/dev/null)" = "healthy" ] && break
    sleep 3
  done
  ST=$(docker inspect --format '{{.State.Health.Status}}' kafka2 2>/dev/null)
  [ "$ST" = "healthy" ] && ok "kafka2 снова healthy (${i}x3с)" || fail "kafka2 не восстановился: $ST"
  sleep 10
  ISR2=$(docker exec kafka1 /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka1:29092 \
    --describe --topic "$KAFKA_TOPIC" 2>/dev/null | awk -F'Isr: ' '/Partition:/{n=split($2,a,/[, \t]/);c=0;
      for(i=1;i<=n;i++) if(a[i] ~ /^[0-9]+$/) c++; print c}' | sort -u | tr '\n' ' ')
  [ "$ISR2" = "3 " ] && ok "ISR восстановился до 3/3" || fail "ISR после восстановления: '$ISR2'"
  docker exec kafka1 /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka1:29092 \
    --delete --topic "$FT" >/dev/null 2>&1 && echo "-- тестовый топик $FT удалён"
fi

echo
echo "============================================================"
if [ "$FAILS" = "0" ]; then echo " ИТОГ: все проверки блока A — PASS"; exit 0
else echo " ИТОГ: FAIL-проверок: $FAILS"; exit 1; fi

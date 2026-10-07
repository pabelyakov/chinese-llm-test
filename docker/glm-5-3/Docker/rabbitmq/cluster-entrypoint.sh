#!/usr/bin/env bash
set -euo pipefail

ROLE="${1:-join}"
SEED_NODE="rabbit@rabbitmq-1"
NODES="rabbit@rabbitmq-1 rabbit@rabbitmq-2 rabbit@rabbitmq-3"
LOCAL_NODE="rabbit@$(hostname -s)"
MGMT_USER="${RABBITMQ_MANAGEMENT_USER:-guest}"
MGMT_PASS="${RABBITMQ_MANAGEMENT_PASS:-guest}"

if [ "$(id -u)" = "0" ]; then
  exec /usr/sbin/gosu rabbitmq "$0" "$@"
fi

log() {
  echo "[cluster-entrypoint][${LOCAL_NODE}] $*"
}

write_cookie() {
  printf '%s' "${RABBITMQ_ERLANG_COOKIE:?env RABBITMQ_ERLANG_COOKIE is required}" > "$HOME/.erlang.cookie"
  chmod 600 "$HOME/.erlang.cookie"
}

wait_for_node() {
  until rabbitmq-diagnostics -q ping -n "$1" >/dev/null 2>&1; do
    log "waiting for node $1"
    sleep 5
  done
}

cluster_lists_member() {
  local status
  status="$(rabbitmqctl cluster_status 2>/dev/null || true)"
  echo "$status" | grep -Eq "^${1}[[:space:]]*$"
}

wait_for_cluster() {
  until cluster_lists_member "rabbit@rabbitmq-2" && cluster_lists_member "rabbit@rabbitmq-3"; do
    log "waiting for cluster formation (3 nodes)"
    sleep 5
  done
}

http_code() {
  local method="$1" path="$2" body="${3:-}" auth len line
  auth="$(printf '%s:%s' "$MGMT_USER" "$MGMT_PASS" | base64)"
  len="${#body}"
  exec 3<>/dev/tcp/127.0.0.1/15672 || return 1
  printf '%s %s HTTP/1.1\r\nHost: 127.0.0.1:15672\r\nAuthorization: Basic %s\r\nContent-Type: application/json\r\nContent-Length: %d\r\nConnection: close\r\n\r\n%s' \
    "$method" "$path" "$auth" "$len" "$body" >&3 || return 1
  read -r line <&3 || true
  line="${line#HTTP/1.1 }"
  printf '%s\n' "${line%% *}"
}

await_http() {
  local method="$1" path="$2" body="$3" i code
  for i in $(seq 1 10); do
    code="$(http_code "$method" "$path" "$body" || echo 000)"
    case "$code" in
      2*) log "$method $path -> $code (ok)"; return 0 ;;
      *) log "$method $path -> HTTP $code, retry $i/10"; sleep 3 ;;
    esac
  done
  log "ERROR: $method $path did not succeed after 10 attempts"
  return 1
}

apply_cluster_config() {
  local attempt ok=0
  for attempt in $(seq 1 10); do
    if rabbitmqctl set_policy quorum-ha "^messages\." \
        '{"delivery-limit":50}' --apply-to quorum_queues >/dev/null 2>&1 \
        && rabbitmqctl list_policies 2>/dev/null | grep -q "quorum-ha"; then
      ok=1
      break
    fi
    log "set_policy attempt $attempt/10 failed, retrying"
    sleep 3
  done
  if [ "$ok" != 1 ]; then
    log "ERROR: policy 'quorum-ha' could not be applied"
    return 1
  fi
  log "policy 'quorum-ha' applied (^messages\\. -> delivery-limit=50, apply-to quorum_queues)"
  await_http PUT "/api/exchanges/%2F/messages.exchange" '{"type":"direct","durable":true}' || return 1
  await_http PUT "/api/queues/%2F/messages.queue" '{"durable":true,"arguments":{"x-queue-type":"quorum","x-quorum-initial-group-size":3}}' || return 1
  await_http POST "/api/bindings/%2F/e/messages.exchange/q/messages.queue" '{"routing_key":"messages.key"}' || return 1
  log "contract topology ready: messages.exchange --(messages.key)--> messages.queue [quorum, 3 replicas]"
}

main() {
  write_cookie
  case "$ROLE" in
    seed)
      rabbitmq-server &
      SERVER_PID=$!
      wait_for_node "$LOCAL_NODE"
      wait_for_cluster
      apply_cluster_config || log "WARN: cluster config incomplete, will retry on next restart of rabbitmq-1"
      log "seed initialization complete, cluster is up"
      ;;
    join)
      wait_for_node "$SEED_NODE"
      rabbitmq-server &
      SERVER_PID=$!
      wait_for_node "$LOCAL_NODE"
      if cluster_lists_member "$SEED_NODE"; then
        log "already a cluster member, skipping join"
      else
        rabbitmqctl stop_app
        rabbitmqctl reset
        rabbitmqctl join_cluster "$SEED_NODE"
        rabbitmqctl start_app
        log "joined cluster via $SEED_NODE"
      fi
      ;;
    *)
      echo "unknown role: $ROLE (expected 'seed' or 'join')" >&2
      exit 64
      ;;
  esac
  trap 'rabbitmqctl -n "$LOCAL_NODE" shutdown >/dev/null 2>&1 || true; kill "$SERVER_PID" 2>/dev/null || true' TERM INT
  wait "$SERVER_PID"
}

main "$@"

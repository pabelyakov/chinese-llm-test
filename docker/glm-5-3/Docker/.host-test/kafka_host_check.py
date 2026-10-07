#!/usr/bin/env python3
"""Host-side Kafka check: exercises the exact client path Spring Boot will use.

Usage:
  python3 kafka_host_check.py bootstrap            # metadata + advertised listeners
  python3 kafka_host_check.py roundtrip [label]    # produce+consume N msgs via localhost
  python3 kafka_host_check.py isr                  # topic describe: partitions/RF/ISR
"""
import sys
import time
import uuid

from kafka import KafkaConsumer, KafkaProducer
from kafka.admin import KafkaAdminClient

BOOTSTRAP = "localhost:9092"
TOPIC = "messages.topic"


def meta():
    admin = KafkaAdminClient(bootstrap_servers=BOOTSTRAP, client_id="host-check")
    cluster = admin._client.cluster
    cid = cluster.cluster_id if getattr(cluster, "cluster_id", None) else "n/a"
    print(f"cluster_id={cid}")
    brokers = cluster.brokers()
    if isinstance(brokers, dict):
        brokers = brokers.values()
    for node in sorted(brokers, key=lambda n: n.node_id):
        print(f"  broker id={node.node_id} advertised={node.host}:{node.port}")
    admin.close()


def roundtrip(label="msg"):
    run = uuid.uuid4().hex[:6]
    msgs = [f"{label}-{run}-{i}".encode() for i in range(5)]
    p = KafkaProducer(bootstrap_servers=BOOTSTRAP,
                      acks="all", retries=5,
                      request_timeout_ms=15000)
    for m in msgs:
        fut = p.send(TOPIC, m)
        fut.get(timeout=15)  # acks=all -> ждёт min.insync.replicas=2
    p.flush(); p.close()
    print(f"produced {len(msgs)}: {[m.decode() for m in msgs]}")

    group = f"host-check-{run}"
    c = KafkaConsumer(TOPIC, bootstrap_servers=BOOTSTRAP, group_id=group,
                      auto_offset_reset="earliest", consumer_timeout_ms=20000)
    got = []
    t0 = time.time()
    for rec in c:
        got.append(rec.value)
        if all(m in got for m in msgs):  # ждём именно текущий прогон; старые из лога игнорируем
            break
    c.close()
    # порядок гарантирован только внутри партиции; в логе могут лежать
    # сообщения прошлых прогонов — проверяем, что все текущие доставлены
    ok = all(m in got for m in msgs)
    print(f"consumed {len(got)} records (incl. prior runs) in {time.time()-t0:.1f}s via group={group}; "
          f"current run: {sum(m in got for m in msgs)}/{len(msgs)} delivered")
    assert ok, f"MISMATCH:\n sent={msgs}\n got={got}"
    print("ROUNDTRIP OK")


def isr():
    admin = KafkaAdminClient(bootstrap_servers=BOOTSTRAP, client_id="host-check")
    parts = admin.describe_topics([TOPIC])[0]["partitions"]
    print(f"topic={TOPIC} partitions={len(parts)}")
    def key(p, a, b):
        return p.get(a, p.get(b))
    for p in sorted(parts, key=lambda x: key(x, "partition", "partition_index")):
        print(f"  p{key(p,'partition','partition_index')}: "
              f"leader={key(p,'leader','leader_id')} "
              f"replicas={key(p,'replicas','replica_nodes')} "
              f"isr={key(p,'isr','isr_nodes')}")
    admin.close()


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else "bootstrap"
    if cmd == "bootstrap":
        meta()
    elif cmd == "roundtrip":
        roundtrip(sys.argv[2] if len(sys.argv) > 2 else "msg")
    elif cmd == "isr":
        isr()

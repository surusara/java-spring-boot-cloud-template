# Kafka Transaction State Log Settings (How to Verify and Configure)

This runbook explains how to verify and configure Kafka broker transaction-state settings required for Kafka Streams `exactly_once_v2`.

## Why this matters

Kafka Streams exactly-once processing relies on Kafka transactions.  
Transaction metadata is stored in the internal broker topic `__transaction_state`.

If broker settings are wrong, you can see transaction failures, abort loops, and replay/duplicate symptoms during restart or rebalance.

---

## Key broker settings

- `transaction.state.log.replication.factor`
- `transaction.state.log.min.isr`

Recommended values:

- **Non-prod (single broker):** `replication.factor=1`, `min.isr=1`
- **Prod (3+ brokers):** `replication.factor=3`, `min.isr=2`

---

## Part 1 — Check current configuration

## Option A: Self-managed Kafka (VM/Kubernetes)

Run on a machine/pod that has Kafka CLI and broker connectivity:

```bash
kafka-configs.sh \
  --bootstrap-server <broker-host:port> \
  --entity-type brokers \
  --entity-default \
  --describe
```

Find these values in output:

- `transaction.state.log.replication.factor`
- `transaction.state.log.min.isr`

Also verify ISR health:

```bash
kafka-topics.sh \
  --bootstrap-server <broker-host:port> \
  --describe \
  --topic __transaction_state
```

Check:

- Replication factor matches expectation
- `Isr` count is not below `min.isr`
- No persistent under-replicated partitions

## Option B: Confluent Cloud / managed brokers

Most broker-level transaction topic settings are managed by provider policy and not user-editable.

What to do:

1. Confirm cluster has enough brokers/AZ resilience for transactional workloads.
2. Open provider docs/support and verify transaction-state durability defaults.
3. Focus your app-level checks:
   - `processing.guarantee=exactly_once_v2`
   - stable Kafka Streams `application.id`
   - no transaction timeout or authorization errors in logs.

---

## Part 2 — Configure values if incorrect

## Non-prod setup (single broker, local/dev)

Use only for dev/test:

```properties
transaction.state.log.replication.factor=1
transaction.state.log.min.isr=1
```

Apply in broker config (`server.properties` or Helm values), then restart broker(s).

## Prod setup (3+ brokers)

Recommended baseline:

```properties
transaction.state.log.replication.factor=3
transaction.state.log.min.isr=2
```

Important:

- Ensure at least 3 brokers are healthy.
- Ensure topic replication and ISR can be maintained during broker failure.
- Avoid `min.isr=1` in production unless explicitly accepted as risk.

Apply in broker config (`server.properties` / cluster config), then perform rolling restart if required by your platform.

---

## Part 3 — Post-change verification checklist

1. Re-run config describe command and confirm expected values.
2. Re-check `__transaction_state` topic ISR health.
3. Restart one stream pod and verify:
   - no transaction coordinator errors
   - no repeated abort/retry loops
   - clean rebalance behavior.
4. Scale pods up/down and monitor duplicates in sink system.
5. Ensure sink is idempotent (DB unique key/upsert/dedup), since external side effects can still duplicate even with EOS.

---

## Quick troubleshooting signals

- `NOT_ENOUGH_REPLICAS` / `NOT_ENOUGH_REPLICAS_AFTER_APPEND`
- transaction initialization/commit timeout errors
- coordinator unavailable errors
- frequent transaction aborts during normal load

If these appear, verify broker ISR health and transaction-state settings first.

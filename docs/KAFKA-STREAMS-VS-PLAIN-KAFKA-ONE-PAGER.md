# Kafka Streams vs Plain Kafka Consumer — One-Page Decision Brief

**Service context:** Trade Processing Service. Consume trade event → run business logic → publish **multiple** downstream
Kafka events (success / exception) → persist to PostgreSQL. Stateless today. ~5M trades/day, AKS, Confluent Cloud.

**Bottom line:** For *this* service, **keep Kafka Streams** — for **one** decisive reason: it gives **native transactional
exactly-once across multiple Kafka outputs per input** (`processing.guarantee=exactly_once_v2`). Everything else is a
convenience, not a differentiator. Don't argue the weak points; argue the EOS point.

---

## Side-by-side comparison

| Dimension | Plain Kafka Consumer (Spring Kafka) | Kafka Streams (EOS v2) | Honest verdict |
|---|---|---|---|
| **Multi-output exactly-once (Kafka→Kafka)** | DIY: manual transactional producer + `sendOffsetsToTransaction`; you orchestrate the txn | **Native & atomic** across all output topics + source offset commit | ✅ **Streams wins — this is the real reason** |
| **Offset management** | You own commit strategy (disable auto-commit, commit after sink) | Managed automatically | ✅ Streams simpler |
| **Parallelism model** | Consumer group: 1 thread per partition (worker pools = custom code) | `num.stream.threads`, auto task assignment | ⚖️ Streams less code; **both partition-bound** |
| **Throughput ceiling** | One record at a time per partition | One record at a time per task | ⚖️ **Identical** — Streams gives **no** intra-partition/per-key concurrency |
| **Horizontal scaling** | Add partitions + pods | Add partitions + pods + threads | ⚖️ **Same axis**; not a Streams advantage |
| **Fault tolerance / recovery** | Rebalance + replay from committed offset (manual care) | Automatic partition reassignment, txn recovery, static membership | ⚖️ Streams more automatic; both correct if coded right |
| **External sink (PostgreSQL) guarantee** | At-least-once → needs idempotent write | **Also at-least-once → still needs idempotent write** | ⚠️ **EOS does NOT cover the DB** — myth to avoid |
| **Stateful features** (windowing, joins, aggregation, dedup) | DIY | Built-in | ✅ Streams (future option) |
| **Resource footprint** | Lighter | Heavier (txn coordinator, state infra) | ✅ Plain lighter |
| **Team learning curve / ops** | Low | Higher (rebalance, EOS, lifecycle) | ✅ Plain simpler |
| **Maturity** | Highest | High | ⚖️ Both production-proven |

---

## Why keep Kafka Streams here (strong, defensible reasons)

1. **Atomic multi-event publishing.** Your input produces **several** Kafka outputs. With EOS v2 they commit
   **all-or-nothing with the source offset**. On crash there are **no partial/duplicate downstream events**.
   Replicating this on a plain consumer means hand-writing transactional-producer orchestration — error-prone, and the
   #1 place teams introduce duplicate-payment bugs.
2. **Less correctness-critical code you must own.** Offset commit coordination and txn lifecycle are handled by the
   framework, not your code. Fewer places to get zero-loss wrong.
3. **Future-proofing is genuinely free here.** If dedup / correlation / windowed checks ever land, no platform
   migration — same runtime.

## Reasons that are TRUE but NOT strong (don't lean on these)

- **"Built-in parallelism / better scaling."** Streams is still **partition-bound, one record at a time per task**. It
  does **not** process keys within a partition concurrently. A plain consumer group scales the same way. For slow
  I/O-bound stages, *neither* solves throughput — that needs concurrency-per-partition (Parallel/Share Consumers),
  not Streams.
- **"Exactly-once protects my database."** **False.** EOS is **Kafka→Kafka only**. Your PostgreSQL write is
  at-least-once and **must be idempotent** (business key or `(topic, partition, offset)`) regardless of framework.
- **"Better fault tolerance."** More *automatic*, yes; but a correctly coded plain consumer is equally loss-free.

## When you'd pick differently

- **Pure pass-through, single output, no Kafka transaction needed** → plain consumer is lighter.
- **Slow external calls (1–5 s/msg) at peak** → neither Streams nor plain scales well; use **Share Consumers (KIP-932)**
  for concurrency-per-partition. (Out of scope for this stateless transform, but note it for the gateway tier.)

---

## The one sentence to put in your review

> We use Kafka Streams because each trade emits **multiple Kafka events that must commit atomically with the input
> offset** (EOS v2); the database write remains idempotent because exactly-once does not extend to external sinks.

**Reconciliation note:** Where ADR-012 and ADR-001 disagreed, the verified Confluent primary sources side with ADR-001
on the *throughput/parallelism* claims (Streams is partition-bound, one record per task, no per-key concurrency). ADR-012
is correct on the *exactly-once* value — which is the reason that actually justifies the choice for this service.

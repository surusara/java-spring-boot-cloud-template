# ADR-001: Consumption Model for a 5M-Trade/Day Payment Processing Platform

| Field | Value |
|---|---|
| **Status** | Proposed |
| **Date** | 2026-06-09 |
| **Decision drivers** | Zero message loss (hard), throughput at mixed per-message latencies (200 ms / 1 s / 5 s), ordering correctness, operational cost, team complexity |
| **Scope** | Greenfield microservices for payment/trade processing, ~5M messages/day |
| **Supersedes** | — |
| **Related** | `KAFKA-STREAMS-MYTHS-AND-TRUTHS.md`, `CIRCUIT_BREAKER_ADOPTION_GUIDE.md` |

> **TL;DR recommendation:** There is **no single winner**. Choose per service by its latency profile
> and statefulness. Default to **plain Kafka consumer (commit-after-process + idempotent sink)** as the
> zero-loss baseline; use **Kafka Streams (EOS v2)** for stateful / Kafka-to-Kafka transform stages; use
> **Confluent Parallel Consumer or Apache Kafka Share Consumers (KIP-932)** for the **slow I/O-bound
> stages (1–5 s)** to avoid partition explosion. **Zero message loss is achievable with all three** —
> it is a function of *commit discipline + durable sink + idempotency*, **not** of the framework.

---

## 1. Context

We are building a payment/trade processing platform on Confluent Cloud (AKS, KEDA). Expected steady
volume is **5,000,000 messages/day**. The pipeline is a chain of microservices with **very different
per-message processing costs**:

| Tier | Example stage | Per-message latency | Nature |
|---|---|---|---|
| **Fast** | validation, routing, format transform | **~200 ms** | CPU-light, mostly in-memory |
| **Medium** | enrichment, fraud lookup, ledger write | **~1 s** | I/O-bound (DB / cache) |
| **Slow** | external payment-gateway / clearing-house call | **~5 s** | I/O-bound (remote network, blocking) |

**Hard constraint: zero message loss.** A trade that enters the system must be processed and durably
recorded exactly once *in effect* (at-least-once delivery + idempotent sink), even across pod restarts,
rebalances, and dependency outages.

### 1.1 The throughput math (this drives everything)

Average rate: `5,000,000 / 86,400 s ≈ 58 msg/s`. Payments are bursty and business-hour-concentrated;
we plan for a realistic **peak of ~300–500 msg/s**.

For **sequential, partition-bound** models (plain consumer, Kafka Streams), the **minimum partitions**
to keep up is `ceil(rate × latency)` — because each partition processes **one message at a time**
(see `KAFKA-STREAMS-MYTHS-AND-TRUTHS.md` §1, §3, verified against Confluent: *"For a given StreamsTask,
only one message is processed at a time"*).

| Stage latency | Partitions @ 58/s avg | Partitions @ 500/s peak |
|---|---|---|
| 200 ms | ~12 | **~100** |
| 1 s | ~58 | **~500** |
| **5 s** | ~290 | **~2,500** ⚠️ |

**The 5 s stage is the crux.** To handle peak sequentially you would need **~2,500 partitions** on one
topic — operationally absurd (rebalance storms, metadata bloat, broker pressure, cost). This is exactly
the problem a **concurrency-decoupled** consumer solves: process **N messages concurrently per
partition** for I/O-bound work, so a 5 s remote call no longer pins a whole partition.

> **Key insight:** Adding partitions only helps **CPU-bound** parallelism. For **I/O-bound** stages
> (waiting on a 5 s remote call), the thread is *idle*, not busy — so concurrency (more in-flight
> requests per thread), **not** more partitions, is the right scaling axis.

---

## 2. Decision drivers

1. **Zero message loss (non-negotiable).**
2. **Throughput at 5 s/message without partition explosion.**
3. **Ordering correctness** (per-account / per-key ordering for ledger integrity).
4. **Exactly-once *effect*** on external sinks (no double-debit).
5. **Operational simplicity & team skill** (rebalance behaviour, observability, debuggability).
6. **Cost** (partition count, instance count, broker load).

---

## 3. Options considered

### Option A — Plain Kafka Consumer (`KafkaConsumer` + manual commit)

A standard consumer group: poll a batch, process, **commit offsets only after the sink is durable**.

**How it achieves zero loss:** Disable auto-commit. Process → write to durable sink (DB) **with an
idempotency key** `(topic, partition, offset)` → commit offset. Crash before commit ⇒ redelivery ⇒
dedup index absorbs the duplicate. **At-least-once + idempotent sink = zero effective loss.**

| ✅ Pros | ❌ Cons |
|---|---|
| Maximum control; simplest mental model | You own correctness: commit ordering, dedup, retries |
| Works for any sink (DB, HTTP, file) | Sequential per partition → partition explosion for 5 s tier |
| Smallest dependency surface | No built-in state store / EOS; no per-key concurrency |
| Easy to reason about ordering (per partition) | Reinvents retry/DLQ/backoff plumbing |

**Verdict:** Excellent **zero-loss baseline** and great for the **200 ms** tier. **Poor fit for the 5 s
tier** at peak (partition explosion). Best when you want a thin, fully-controlled durable relay.

---

### Option B — Kafka Streams (EOS v2)

Declarative topology; one task per partition; `processing.guarantee=exactly_once_v2`.

**How it achieves zero loss:** EOS v2 fences state + **commits input offsets through the transactional
producer** (`sendOffsetsToTransaction`) — **even with no output topic** (see myths doc §6). Kafka→Kafka
is *exactly once*. For **external** sinks (DB/HTTP), it is still at-least-once → **you still need an
idempotent sink**. Graceful shutdown is mandatory to avoid duplicate windows on restart.

| ✅ Pros | ❌ Cons |
|---|---|
| **True exactly-once** for Kafka→Kafka pipelines | Sequential per task → **same partition-explosion** for 5 s tier |
| Built-in state stores, repartition, windowing | EOS adds latency/throughput overhead (txn commits) |
| Self-paced polling; mature rebalance protocol | Heavier runtime; steeper learning curve |
| Sub-topologies scale stateful stages cleanly | External side-effects still need dedup (not magic) |

**Important:** Kafka Streams gives **no intra-partition key concurrency** (myths doc §3, Confluent:
*"only one message is processed at a time"*). So for the 5 s I/O-bound stage it has the **same**
partition ceiling as plain consumer — **EOS does not help throughput**, only correctness.

**Verdict:** **Best for stateful, ordering-sensitive, Kafka→Kafka transform stages** (the 200 ms–1 s
tier) where exactly-once is worth the overhead. **Not** the tool to fix the 5 s throughput problem.

---

### Option C — Confluent Parallel Consumer / Apache Kafka Share Consumers (KIP-932)

A consumption layer that **decouples fetching from processing**, running **many messages concurrently
per partition** with selectable ordering (`KEY`, `PARTITION`, `UNORDERED`).

**How it achieves zero loss:** At-least-once with **offset tracking for out-of-order completion** — it
only advances the committed offset past contiguously-completed records, redelivering anything
in-flight at crash time. Combined with an **idempotent sink**, zero effective loss. `KEY` ordering
preserves per-account order while still running different accounts concurrently.

**Evidence (Parallel Consumer README):** *"increase consumer parallelism without increasing the number
of partitions"*; KEY mode → *"concurrent processing to a per key level … whilst keeping strong ordering
by key"*; illustrative 10 partitions × 50 ms → ~200 msg/s vanilla vs **~2,000,000 msg/s** with 100k keys.

| ✅ Pros | ❌ Cons |
|---|---|
| **Solves the 5 s tier**: concurrency ≫ partitions, no partition explosion | **Confluent Parallel Consumer is now *unmaintained*** (README) |
| `KEY` ordering keeps per-account correctness | **No Kafka EOS/transactional producer** → at-least-once only |
| Built-in retry/backoff/DLQ-friendly; simple circuit-breaker pause | Out-of-order commit logic is subtle; harder to reason about |
| Tunable concurrency per stage | Share Consumers (the successor) is **newer / less battle-tested** |

> **⚠️ Critical correction:** The **Confluent Parallel Consumer project is no longer maintained**; its
> README directs users to **Apache Kafka Share Consumers (Queues for Kafka, KIP-932)** as the successor
> (there is also a community fork). For a **greenfield** build, prefer **Share Consumers** strategically,
> and treat Parallel Consumer as a proven-but-frozen option / fork dependency.

**Verdict:** **The right tool for the 1 s and 5 s I/O-bound tiers.** Use `KEY` ordering for ledger
correctness. Accept at-least-once + dedup. Choose **Share Consumers** for new work; Parallel Consumer
only if you need it *today* and accept the maintenance status.

---

## 4. Side-by-side

| Dimension | A: Plain Consumer | B: Kafka Streams (EOS) | C: Parallel / Share Consumer |
|---|---|---|---|
| **Zero message loss** | ✅ commit-after-process + dedup | ✅ EOS (Kafka→Kafka) + dedup (external) | ✅ at-least-once + dedup |
| **Exactly-once *effect*** | Via idempotent sink | Native (Kafka→Kafka); sink dedup otherwise | Via idempotent sink |
| **200 ms tier** | ✅ Great | ✅ Great (if stateful/ordered) | ✅ OK (overkill) |
| **1 s tier** | ⚠️ many partitions | ⚠️ many partitions | ✅ **Best** |
| **5 s tier** | ❌ ~2,500 partitions | ❌ ~2,500 partitions | ✅ **Best** (concurrency) |
| **Per-key ordering** | ✅ per partition | ✅ per task | ✅ `KEY` mode |
| **State / windowing** | ❌ DIY | ✅ Built-in | ❌ DIY |
| **Partition pressure** | High for slow tiers | High for slow tiers | **Low** |
| **Maturity** | ✅ Highest | ✅ High | ⚠️ PC frozen / Share new |
| **Team complexity** | Low | High | Medium |

---

## 5. Decision

Adopt a **per-service / per-tier** model rather than one framework for everything:

```
┌──────────────────────────────────────────────────────────────────────┐
│  TIER          │ RECOMMENDED MODEL          │ WHY                      │
├──────────────────────────────────────────────────────────────────────┤
│ 200 ms fast    │ Plain Consumer             │ simplest zero-loss base; │
│ (validate/route)│ (or Kafka Streams if      │ partition count modest   │
│                │  stateful/ordered)         │                          │
├──────────────────────────────────────────────────────────────────────┤
│ 1 s medium     │ Parallel/Share Consumer    │ I/O-bound; concurrency   │
│ (enrich/ledger)│  (KEY ordering)            │ beats partitions         │
├──────────────────────────────────────────────────────────────────────┤
│ 5 s slow       │ Parallel/Share Consumer    │ ONLY sane option; avoids │
│ (gateway call) │  (KEY ordering, high conc.)│ ~2,500-partition blowup  │
├──────────────────────────────────────────────────────────────────────┤
│ Stateful K→K   │ Kafka Streams (EOS v2)     │ true exactly-once,       │
│ transforms     │                            │ windowing, state stores  │
└──────────────────────────────────────────────────────────────────────┘
```

**Cross-cutting mandates (apply to every service, this is what actually guarantees zero loss):**

1. **Commit after durable sink, never before.** Disable auto-commit (or rely on EOS txn commit).
2. **Idempotent sink keyed on `(topic, partition, offset)`** (or a business idempotency key). This is
   the real zero-loss / exactly-once-*effect* mechanism — independent of framework.
3. **Graceful shutdown** (drain in-flight, commit, then exit) on SIGTERM; honor K8s `terminationGracePeriod`.
4. **DLQ + bounded retry** for poison messages so one bad trade cannot block a partition/key.
5. **`KEY` = account/trade key** wherever ordering matters, so concurrency never reorders a single account.

### 5.1 If forced to pick ONE starting point (greenfield, day 1)

Start with **plain Kafka consumer + commit-after-process + idempotent sink** for the fast/medium tiers,
and introduce **Share Consumers** the moment a stage's `rate × latency` pushes partition count past
~50–100. Add **Kafka Streams** only where you genuinely need **stateful EOS** pipelines. This minimizes
day-1 complexity while keeping a clean path to scale the slow tiers.

---

## 6. Consequences

### Positive
- No partition explosion: the 5 s tier runs on a handful of partitions with tuned concurrency.
- Zero loss is enforced by a **uniform commit-after-sink + idempotency** policy across all three models.
- Each tier uses the cheapest adequate tool; EOS overhead is paid only where it adds value.

### Negative / risks & mitigations

| Risk | Impact | Mitigation |
|---|---|---|
| **Parallel Consumer unmaintained** | Future bugs/CVEs unpatched | Prefer **Share Consumers (KIP-932)** for new services; pin/fork PC if used; track its maturity |
| **Out-of-order commit subtlety** (Option C) | Mis-tuned config could skip/replay offsets | Use `KEY` ordering; integration-test crash/redelivery; rely on dedup index |
| **No native EOS in Option C** | Duplicates on retry | **Idempotent sink is mandatory**, not optional |
| **5 s remote call ties up concurrency slots** | Backpressure / memory if dependency slow | Bound max in-flight; circuit breaker (see `CIRCUIT_BREAKER_ADOPTION_GUIDE.md`); timeouts |
| **Mixed frameworks** | Higher cognitive load, more ops surface | Standardize commit/dedup/DLQ/observability libraries across all services |
| **Kafka Streams EOS misread as covering external sinks** | False sense of safety → double-debit | Document clearly: EOS = Kafka→Kafka only; sinks still need dedup (myths doc §6) |
| **Rebalance during long external call** | Duplicate processing | `max.poll.interval.ms` headroom; static membership; idempotency absorbs it |

### Neutral
- Share Consumers maturity should be re-evaluated quarterly; this ADR may be revised as KIP-932 hardens.

---

## 7. Honest opinion (the part you asked for)

- **"Which is best to *start* building a payment system?"** — Start with the **plain consumer** for
  simplicity and an airtight zero-loss baseline, **but architect each stage around its latency**. The
  expensive mistake is forcing a uniform model: pick Kafka Streams everywhere and you'll drown the 5 s
  tier in partitions; pick Parallel Consumer everywhere and you give up EOS where it was free.
- **Zero message loss is the easy part** and is **not** a differentiator between these options — all
  three reach it with *commit-after-durable-sink + idempotency*. Anyone who says "use X for zero loss"
  is selling a framework, not solving your problem.
- **Exactly-once is the hard part**, and it is **only truly native for Kafka→Kafka via Kafka Streams
  EOS**. Every external side effect (DB, payment gateway) is at-least-once underneath — so **idempotency
  at the sink is the actual guarantee**, regardless of framework.
- **The 5 s stage decides your architecture.** If you remember one thing: **I/O-bound slowness scales
  with concurrency, not partitions.** That single fact is why a concurrency-decoupled consumer
  (Share Consumers) belongs in a payment platform with slow external calls.
- **Don't bet the platform on the Confluent Parallel Consumer** now that it's unmaintained — use it
  tactically if needed, but plan on **Share Consumers** for the long term.

---

## 8. References

- Confluent — *Kafka Streams Architecture* (threading, one-record-at-a-time, partition-bound parallelism)
- Confluent — *Parallel Consumer* README (KEY-order concurrency; **"no longer maintained" → Share Consumers**)
- Apache Kafka — *Broker/Consumer configs* (`session.timeout.ms`, `max.poll.interval.ms`, `group.max.session.timeout.ms`)
- KIP-932 — *Queues for Kafka (Share Consumers)*
- KIP-834 — *Pause/Resume KafkaStreams Topologies*
- Internal — `KAFKA-STREAMS-MYTHS-AND-TRUTHS.md`, `CIRCUIT_BREAKER_ADOPTION_GUIDE.md`

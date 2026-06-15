# Kafka Streams vs Plain Consumer vs Parallel Consumer — Myths, Truths & Evidence

> **Purpose:** A point-by-point record of the misconceptions we surfaced during design discussion,
> each paired with the **verified truth** and a **primary-source citation** (with the exact quote).
> Use this to correct mental models with authority — every claim here is traceable to Confluent /
> Apache Kafka / Resilience4j documentation, not opinion.
>
> **Context of the system under discussion:** stateless Kafka Streams app, ~5M messages/day,
> EOS v2, Confluent Cloud on AKS, with a soft-failure circuit breaker that **stops** the stream.

---

## Primary sources referenced

| Ref | Source | URL |
|-----|--------|-----|
| **[S1]** | Confluent Platform — *Kafka Streams Architecture* | https://docs.confluent.io/platform/current/streams/architecture.html |
| **[S2]** | Apache Kafka — *Streams Architecture* (same content, ASF) | https://kafka.apache.org/documentation/streams/architecture |
| **[S3]** | Confluent — *Parallel Consumer* README | https://github.com/confluentinc/parallel-consumer |
| **[S4]** | Apache Kafka — *Broker Configs* (`group.max.session.timeout.ms`) | https://kafka.apache.org/documentation/#brokerconfigs_group.max.session.timeout.ms |
| **[S5]** | Apache Kafka — *Consumer Configs* (`session.timeout.ms`, `max.poll.interval.ms`) | https://kafka.apache.org/documentation/#consumerconfigs |
| **[S6]** | KIP-834 — *Pause / Resume KafkaStreams Topologies* | https://cwiki.apache.org/confluence/display/KAFKA/KIP-834 |
| **[S7]** | Resilience4j — *CircuitBreaker* docs | https://resilience4j.readme.io/docs/circuitbreaker |
| **[S8]** | KIP-932 — *Queues for Kafka* (Share Consumers) | https://cwiki.apache.org/confluence/display/KAFKA/KIP-932 |

> Citation style below: a **direct quote** from the source is shown in *italics*, followed by the ref tag.

---

## 1. "Kafka Streams has a separate thread that does the polling"

**❌ Misconception:** Kafka Streams runs a dedicated poll thread, decoupled from processing — so a
slow processor cannot delay `poll()`.

**✅ Truth:** Each **StreamThread polls *and* processes on the same thread**. There is no separate
poll thread. The consumer's `poll()` and your topology's record processing run sequentially inside
the one StreamThread. A StreamThread runs one or more tasks; within a task, records are drained from
the per-partition buffer and processed **one at a time**.

**Evidence [S1]:**
- *"Each thread can execute one or more stream tasks with their processor topologies independently."*
- *"\[tasks\] maintain a buffer for each of its assigned partitions and process input data one-record-at-a-time from these record buffers."*
- *"Each record consumed from Kafka goes through the whole processor (sub-)topology for processing and for (possibly) being written back to Kafka before the next record is processed. For a given `StreamsTask`, only one message is processed at a time."*

**Why the confusion exists:** Kafka Streams *feels* decoupled because it **self-paces** — it controls
how fast it polls relative to processing, so it usually avoids the `max.poll.interval.ms` breach that
a hand-rolled "poll thread + worker pool" suffers. But that is *self-pacing on one thread*, not a
*separate poll thread*. A single `poll()` batch that takes too long to process **can still** breach
`max.poll.interval.ms`.

**Practical consequence:** Don't size threads assuming polling is "free" and concurrent with
processing. Throughput per partition = how fast that single thread runs your topology.

---

## 2. "Kafka Streams gives more parallelism than a plain consumer"

**❌ Misconception:** Switching to Kafka Streams increases consumer parallelism beyond what a plain
consumer group can do.

**✅ Truth:** Both are **bounded by partition count**. Kafka Streams creates **one task per input
partition (per sub-topology)**; a plain consumer group allows **one active consumer per partition**.
Same ceiling. Extra StreamThreads / extra consumers beyond the partition count sit **idle**.

**Evidence [S1]:**
- *"the maximum parallelism at which your application may run is bounded by the maximum number of stream tasks, which itself is determined by maximum number of partitions of the input topic(s) the application is reading from."*
- *"If you run a larger number of app instances than partitions of the input topic, the 'excess' app instances will launch but remain idle."*

**The one nuance that IS true:** A single Streams app can run **more total tasks than the partition
count of any one topic** — but only by adding **sub-topologies** (repartition / join / aggregation
boundaries). Each sub-topology gets its own task set. Each stage is still capped at its topic's
partition count.

**Evidence [S1]:** *"a single processor topology may be decomposed into independent sub-topologies … Each task may instantiate only one such sub-topology for processing. This further scales out the computational workload to multiple tasks."*

---

## 3. "Kafka Streams processes multiple keys within one partition in parallel"

**❌ Misconception:** Within a single partition, Streams runs different keys as independent parallel
"sets," so you get per-key concurrency without adding partitions.

**✅ Truth (for standard Kafka Streams):** **No.** Within a partition (task), processing is
**sequential, single-threaded**, in offset order. Keys group *state* (each key has its own state-store
entry), but that is **logical state partitioning, not parallel execution**.

**Evidence [S1]:**
- *"For a given `StreamsTask`, only one message is processed at a time."*
- *"there is a single state store \[per task\], so a state store is accessed only from the same sub-topology, which means there is never a read/write race condition."* (i.e. single-threaded by design)

**Where the "parallel key-sets" idea is actually correct:** That behaviour exists — but in the
**Confluent Parallel Consumer**, not Kafka Streams (see §5). Streams explicitly does **not** have it.

**Evidence [S3]:** *"Kafka Streams (KS) doesn't yet (KIP-311, KIP-408) have parallel processing of messages."*

---

## 4. "To pause processing, Streams must keep polling, which fills memory — so pausing is unsafe"

**❌ Partial misconception:** `pause()` will fill the buffer unboundedly (e.g. "5 GB in 10 min → OOM").

**✅ Truth:** `KafkaStreams.pause()` keeps StreamThreads alive and **keeps polling to hold group
membership**, so records *are* fetched while paused — but the buffer is **bounded**, not unbounded.
Kafka Streams caps buffered data via `input.buffer.max.bytes` (**default 512 MB** across threads) /
`buffered.records.per.partition`, and applies its pull-based flow control. So pausing risks a
**sustained ~512 MB working set**, which is real memory pressure on a tight pod — but not an unbounded
OOM.

**Evidence [S1]:** *"Kafka Streams does not use a backpressure mechanism because it does not need one. Using a depth-first processing strategy … no records are being buffered in-memory between two connected stream processors. Also, Kafka Streams leverages Kafka's consumer client … which works with a pull-based messaging model that allows downstream processors to control the pace at which incoming data records are being read."*

**Why `stop()` (close) was still the right call for this system:** Stopping closes the consumer →
**no fetching → buffer released → ~0 memory held** during the pause. For multi-minute holds on a
memory-constrained pod, `stop()` beats `pause()` on memory. (Both avoid rebalance **only if** the hold
stays under `session.timeout.ms` — see §7.)

**Correctness note:** `KafkaStreams.pause()`/`resume()` is a real API added in **Kafka 3.5.0 via
KIP-834 [S6]**; the earlier "no rewrite needed to pause" statement was technically accurate. The choice
between `stop()` and `pause()` is a **memory/rebalance trade-off**, not a capability gap.

---

## 5. Confluent Parallel Consumer — what it really is (and a correction)

**✅ Truth:** The Parallel Consumer is a library that wraps a single `KafkaConsumer` and **decouples
fetching from processing**, letting you process **more concurrently than there are partitions**, with
selectable ordering. This is the tool that genuinely does **per-key parallelism inside a partition**.

**Evidence [S3]:**
- *"This library lets you process messages in parallel via a single Kafka Consumer meaning you can increase consumer parallelism without increasing the number of partitions in the topic you intend to process."*
- **KEY ordering:** *"Each of these key → message sets can actually be processed concurrently, bringing concurrent processing to a per key level, without having to increase the number of input partitions, whilst keeping strong ordering by key."*
- **Default mode:** *"Default ordering mode is now `KEY` ordering (was `UNORDERED`)."*
- **Illustrative perf:** 10 partitions, 50 ms/msg → vanilla *"1 second / 50 ms * 10 partitions = ~200 messages per second"*; with KEY order over 100,000 keys → *"1 second / 50ms × 100,000 keys = 2,000,000 messages per second."*
- **Circuit-breaker friendliness:** *"Manual global pause / resume of all partitions, without unsubscribing from topics (useful for implementing a simplistic circuit breaker)."*

**⚠️ IMPORTANT CORRECTION (new evidence):** The Parallel Consumer project is now flagged as
**no longer maintained**, with Apache Kafka's **Share Consumers** named as the successor.

**Evidence [S3]:** *"Please note that this project is no longer maintained. Similar functionality is available with Share Consumers in Apache Kafka. There is also an active fork of the project maintained by one of the original creators, @astubbs."*

**Updated recommendation:** If you ever need concurrency beyond partition count for an **I/O-bound
stateless stage**, evaluate **Apache Kafka Share Consumers (Queues for Kafka, KIP-932 [S8])** first,
and treat the Parallel Consumer as a reference / fork option rather than a strategic dependency. It
remains **not** a replacement for stateful Kafka Streams.

---

## 6. "EOS needs an output topic, otherwise there's no transaction to commit (cause of 50k duplicates)"

**❌ Misconception:** With no output-topic writes, EOS v2 has no transaction, so offsets aren't
transactionally committed — adding a `processing_status` output topic is what "created" the
transaction and fixed duplicates.

**✅ Truth:** Under `processing.guarantee=exactly_once_v2`, Kafka Streams commits **input offsets
through the transactional producer** (`sendOffsetsToTransaction`) **even with zero output topics**. The
transaction exists regardless of whether you produce output. The real causes of duplicates on restart
are almost always: (a) topology actually running `at_least_once` (the default), (b) **ungraceful
shutdown** (SIGKILL before commit), or (c) side-effects (DB/HTTP) outside the Kafka transaction with no
dedup.

**Evidence [S1]:**
- *"With non-EOS, Kafka Streams only ensures that the store is flushed to disk, and the changelogs write to Kafka before Kafka Streams commits the corresponding offsets."* (ALOS path — offset commit is separate, not fenced)
- EOS section describes transactional fencing of state + offsets as the guarantee; it is **not** conditioned on producing to an output topic.

**Practical guard:** Keep the `(kafka_partition, kafka_offset)` DB dedup index. It protects you
regardless of EOS edge cases and graceful-shutdown timing.

---

## 7. "We can stop/pause the stream for as long as we like (e.g. 2–3 hours)"

**❌ Misconception:** A circuit breaker can hold the stream stopped for arbitrary durations safely.

**✅ Truth:** A single stop must stay **under `session.timeout.ms`**, and `session.timeout.ms` is
itself capped by the broker's `group.max.session.timeout.ms` (**default 30 min**). While stopped the
consumer sends **no heartbeats**; with `internal.leave.group.on.close=false` the coordinator keeps
partitions reserved only for `session.timeout.ms`. Exceed it → member evicted → **rebalance**.

**Evidence:**
- **[S4]** `group.max.session.timeout.ms` — *"The maximum allowed session timeout for registered consumers. … Default: 1800000"* (= 30 min). A consumer's `session.timeout.ms` must be **≤** this broker max.
- **[S5]** `session.timeout.ms` — *"If no heartbeats are received by the broker before the expiration of this session timeout, then the broker will remove this consumer from the group and initiate a rebalance."*
- **[S5]** `max.poll.interval.ms` — *"The maximum delay between invocations of poll() … if poll() is not called before expiration of this timeout, then the consumer is considered failed and the group will rebalance."* (Does not tick while the consumer is closed/stopped, but governs the first batch after resume.)

**Resulting hard limits for this system:**

| Bound | Value |
|---|---|
| Max single stop without rebalance | `< session.timeout.ms` |
| `session.timeout.ms` ceiling | **30 min** (broker `group.max.session.timeout.ms`, fixed on Confluent Cloud) |
| **Absolute max for one uninterrupted stop** | **~30 min** |

**For 2–3 hour outages — do NOT hold it in the app:**
1. **Loop in sub-30-min cycles + alert.** Each stop `< session.timeout.ms`; brief HALF-OPEN resume
   between cycles re-establishes heartbeats and resets the session timer; partitions stay reserved.
2. **Alert ops/KEDA for very long outages.** Page on-call; stop the pod or KEDA scale-to-zero until the
   dependency recovers. This makes the outage **visible** instead of a pod silently looping for hours.

---

## 8. "The circuit breaker can call stop() directly from its state-transition listener"

**❌ Misconception:** It's fine to call `streamsBuilderFactoryBean.stop()` inside the Resilience4j
`onStateTransition(OPEN)` callback.

**✅ Truth:** Resilience4j fires the state-transition listener **synchronously on the thread that
crossed the threshold** — which is the **StreamThread** (the OPEN transition happens inside
`circuitBreaker.onError(...)`, invoked from the processor's `record(...)`). `stop()` →
`KafkaStreams.close()` **blocks until all StreamThreads terminate, including the caller** → a thread
joining itself → blocks for the full close timeout → **unclean shutdown**. The fix is to **offload**
`stop()`/`start()` to a dedicated single-thread executor so the blocking call never runs on a
StreamThread.

**Evidence:**
- **[S7]** Resilience4j event publishing is synchronous on the calling thread — *"The CircuitBreaker publishes events to consumers \[registered via\] the EventPublisher"*; transitions are emitted inline as `onError`/`onSuccess` are recorded (no internal thread hand-off).
- **[S1]** Kafka Streams threading model: each StreamThread owns its consumer and processing loop;
  closing the client coordinates termination of those threads (hence a StreamThread cannot drive its
  own `close()` to completion).

**Implemented fix:** see `KafkaStreamsLifecycleController` (executor offload + revert-on-failure +
`@PreDestroy`) and the change log `CIRCUIT_BREAKER_CHANGES_2026-06-09.md`.

---

## Quick-reference: myth → truth → source

| # | Myth | Truth | Source |
|---|------|-------|--------|
| 1 | Streams has a separate poll thread | Poll + process on the **same** StreamThread; one record at a time | [S1] |
| 2 | Streams > plain consumer in parallelism | Both capped at **partition count**; excess instances idle | [S1] |
| 3 | Streams processes keys in a partition in parallel | **Sequential** per task; keys group *state*, not execution | [S1][S3] |
| 4 | Pausing fills memory unboundedly (OOM) | Bounded (~512 MB via `input.buffer.max.bytes`); `stop()` holds ~0 | [S1] |
| 5 | Parallel Consumer is the go-to for >partition concurrency | True capability, but **project no longer maintained → Share Consumers** | [S3][S8] |
| 6 | No output topic ⇒ no EOS transaction ⇒ duplicates | EOS commits offsets via the txn producer **without** output topics | [S1] |
| 7 | Can stop/pause for hours | Max **~30 min** (broker session-timeout ceiling); loop + alert beyond | [S4][S5] |
| 8 | Call `stop()` in the breaker listener | Runs on StreamThread → self-join deadlock; **offload to executor** | [S1][S7] |

---

## Verification status

| Claim | Verified against | Status |
|---|---|---|
| Same-thread poll+process; one record at a time | [S1] direct quotes | ✅ Quoted |
| Parallelism bounded by partitions; excess idle | [S1] direct quotes | ✅ Quoted |
| No intra-partition key parallelism in KS | [S1] + [S3] ("KS doesn't yet have parallel processing") | ✅ Quoted |
| Parallel Consumer KEY-order concurrency | [S3] direct quotes | ✅ Quoted |
| **Parallel Consumer no longer maintained → Share Consumers** | [S3] notice | ✅ Quoted (NEW) |
| `pause()`/`resume()` exists (KIP-834, 3.5+) | [S6] | ✅ Referenced |
| `group.max.session.timeout.ms` default 30 min | [S4] | ✅ Referenced (confirm on your broker) |
| Resilience4j listener is synchronous on caller thread | [S7] | ✅ Referenced |

> **One value to confirm on your own cluster:** run
> `kafka-configs --describe --entity-type brokers` (or check Confluent Cloud cluster settings) for the
> effective `group.max.session.timeout.ms`. The Apache default is 1,800,000 ms (30 min) [S4]; Confluent
> Cloud enforces this ceiling. Everything in §7 keys off this number.

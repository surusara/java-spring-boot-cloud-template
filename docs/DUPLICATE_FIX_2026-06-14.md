# Duplicate-on-Scale Fix — One-Pager

**Date:** 2026-06-14
**Project:** `kafka-streams-cb` (java-spring-boot-cloud-template)
**Symptom:** ~100% duplicate output records when KEDA scaled pods 2 → 4 → 6 under load.

---

## 1. Root Cause — The Dual-Producer Trap

The topology wired **two independent Kafka producers** into the data path:

```
                  ┌──────────────────────────────────────────────────┐
                  │  StreamThread (one per pod)                      │
  payments.input  │   ┌──────────┐    ┌─────────────┐                │
  ────────────────┤──▶│ consumer │──▶ │  processor  │                │
                  │   └──────────┘    └──────┬──────┘                │
                  │         ▲                │                       │
                  │         │ commits        │ kafkaTemplate         │
                  │         │ offsets        │ .executeInTransaction │
                  │   ┌─────┴────────┐       ▼                       │
                  │   │ Streams      │   ┌────────────────────┐      │
                  │   │ INTERNAL     │   │ EXTERNAL producer  │──┐   │
                  │   │ producer     │   │ (KafkaTemplate)    │  │   │
                  │   └──────────────┘   └────────────────────┘  │   │
                  └──────────────────────────────────────────────┼───┘
                                                                  ▼
                                                          payments.output
```

The two producers ran in **two separate Kafka transactions**:

| Step | Producer | What it commits |
|------|----------|-----------------|
| 1. `kafkaTemplate.executeInTransaction(...)` writes output record | External | Output topic write |
| 2. Streams' commit cycle (every 1s) commits input offset | Internal | Input-topic offset advance |

A rebalance fired **between step 1 and step 2** for every batch that was in flight when a new pod joined. The new owner of the partition re-read the same input record (because the offset was never committed) and wrote a second output record (because step 1 had already succeeded and was not rolled back). With 4-6 pods churning every few seconds during scale-up, nearly every in-flight batch got duplicated.

**Static membership (`group.instance.id` + `internal.leave.group.on.close=false`) does not help here.** It only avoids rebalances when the **same hostname** returns. New KEDA pods get new hostnames, so they always trigger a rebalance.

---

## 2. The Fix — Four Changes, Two Categories

### A. Architectural change: one producer in the data path

| # | Change | Why |
|---|--------|-----|
| 1 | **Processor** returns `OutputEvent` inside `ProcessingResult`; no longer calls `kafkaTemplate.send(...)`. The Streams operator chain forwards via `ctx.forward(record.withValue(output))`. | Removes the external producer from the hot path entirely. |
| 2 | **Topology** terminates with `.to(outputTopic, Produced.with(...))` instead of a bare `.process(...)`. | The output write now goes through Streams' own producer, sharing the same transaction as the input-offset commit. |
| 3 | **`processing-guarantee=exactly_once_v2`** (was `at_least_once`). | Wraps `poll → process → ctx.forward → offset-commit` in **one** Kafka transaction. Rebalance mid-batch → transaction aborts → nothing visible downstream → new owner re-reads cleanly with no duplicate output. |

### B. Three latent bugs found during the audit

| # | Bug | Impact | Fix |
|---|-----|--------|-----|
| 4 | `app.stream.consumer.isolation-level=read_committed` existed in `application.properties` but `KafkaStreamsConfig.java` had **no `@Value` or `props.put`** for it. Silently ran as `read_uncommitted`. | Even after enabling EOS, downstream consumers could still see records from aborted transactions — defeating the whole point of `exactly_once_v2`. | Added the `@Value("${app.stream.consumer.isolation-level:read_committed}")` parameter and `props.put("consumer.isolation.level", ...)`. |
| 5 | `@Value("${app.stream.max-task-idle-ms:0}")` in code vs `=1000` in properties. Code default won when the property was missing. | Tasks with multiple partitions could process partitions in a lopsided order during low traffic. | Aligned code default to `1000`. |
| 6 | Processor wrapped every non-circuit-breaker exception as `IllegalStateException`, which the uncaught-exception handler classified as `SHUTDOWN_CLIENT`. A transient broker hiccup (`RetriableException`) → pod restart → rebalance. | Pod churn amplified the duplicate problem during broker blips. | Added a separate `catch (RetriableException retriable)` that re-throws as-is, so the handler classifies it as `REPLACE_THREAD` instead. |

---

## 3. Files Changed

| File | Change |
|------|--------|
| [src/main/java/com/example/financialstream/model/ProcessingResult.java](../src/main/java/com/example/financialstream/model/ProcessingResult.java) | Added nullable `OutputEvent output` field + `success(code, output)` factory. |
| [src/main/java/com/example/financialstream/service/DefaultBusinessProcessorService.java](../src/main/java/com/example/financialstream/service/DefaultBusinessProcessorService.java) | Removed `OutputProducerService` dependency; returns `OutputEvent` in `ProcessingResult`. |
| [src/main/java/com/example/financialstream/kafka/PaymentsRecordProcessorSupplier.java](../src/main/java/com/example/financialstream/kafka/PaymentsRecordProcessorSupplier.java) | Migrated to new `org.apache.kafka.streams.processor.api.Processor` API; forwards via `ctx.forward(...)`; separate `RetriableException` catch (Bug 6). |
| [src/main/java/com/example/financialstream/config/KafkaStreamsConfig.java](../src/main/java/com/example/financialstream/config/KafkaStreamsConfig.java) | Wired `consumer.isolation.level` (Bug 4); aligned `max-task-idle-ms` default (Bug 5); topology now `.process(supplier).to(outputTopic, ...)`; added `validateCooperativeRebalancingConfig` guard against `upgrade.from`. |
| [src/main/java/com/example/financialstream/service/OutputProducerService.java](../src/main/java/com/example/financialstream/service/OutputProducerService.java) | Marked `@Deprecated` with javadoc explaining the dual-producer trap. |
| [src/main/java/com/example/financialstream/service/KafkaOutputProducerService.java](../src/main/java/com/example/financialstream/service/KafkaOutputProducerService.java) | Javadoc warning added; class itself retained for out-of-stream callers (outbox poller, manual replay). |
| [src/main/resources/application.properties](../src/main/resources/application.properties) | `processing-guarantee=exactly_once_v2`; `max-poll-records=100`. |
| [src/main/resources/application.properties.example](../src/main/resources/application.properties.example) | Same as above. |
| [src/test/java/com/example/financialstream/service/DefaultBusinessProcessorServiceTest.java](../src/test/java/com/example/financialstream/service/DefaultBusinessProcessorServiceTest.java) | Constructor now 3-arg; asserts `result.output()` instead of mock-verifying a producer call. |
| [src/test/java/com/example/financialstream/kafka/TopologyProcessingTest.java](../src/test/java/com/example/financialstream/kafka/TopologyProcessingTest.java) | Uses new Processor API + `TestOutputTopic` to verify forwarded records. |
| [docs/01-KAFKA-STREAMS-CONFIG.md](01-KAFKA-STREAMS-CONFIG.md) | Synced active-config snippets + tables to the new defaults. Kept the EOS-vs-at-least-once conceptual guidance intact. |

---

## 4. Verification

```
mvn clean test
...
[INFO] Tests run: 34, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

Manual scale test (2 → 4 → 6 pods under 3M trades/day load) is the next step before promoting beyond staging.

---

## 5. Migration Impact for Anyone Using This Template

If you forked or copied this template **before 2026-06-14**, you need to do all of:

1. **Remove any call to `kafkaTemplate.send(outputTopic, ...)` from inside your processor.** Forward via `ctx.forward(...)` and terminate the topology with `.to(outputTopic, ...)`. `OutputProducerService` is `@Deprecated` for in-processor use and the duplicate fix only works if you switch.
2. **Verify your `KafkaStreamsConfig` has `props.put("consumer.isolation.level", ...)`** — without it, EOS silently degrades to `read_uncommitted` and duplicates leak through.
3. **Set `processing-guarantee=exactly_once_v2`** in `application.properties` (or `application.yml`).
4. **Lower `max-poll-records` to 100** to shrink the per-transaction blast radius if a rebalance does fire.
5. **Test** with the topology test pattern in `TopologyProcessingTest.java` — it uses `TestOutputTopic` to assert what was actually forwarded, which catches dual-producer regressions that mock-based tests miss.

If you have a non-Streams use case (an outbox poller, a manual replay tool, an API that publishes a one-off event), keep using `KafkaOutputProducerService` — the deprecation is scoped to "do not call from inside the Streams processor," not "delete the class."

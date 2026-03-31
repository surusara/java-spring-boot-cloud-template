# Prod Patch Checklist

Use this checklist to copy the relevant Kafka Streams changes from this reference project into the other  project.

## 1. Set Kafka Streams to `at_least_once`

- Set:

```properties
app.stream.processing-guarantee=at_least_once
```

- If your app builds the config in Java, use:

```java
props.put(StreamsConfig.PROCESSING_GUARANTEE_CONFIG, "at_least_once");
```

- Remove any old `exactly_once_v2` assumption from the main runtime config.

Why:
- This matches the actual production mode you described.
- It makes the replay behavior explicit instead of mixing EOS and ALO assumptions.

## 2. Keep Commit Control on Kafka Streams

- Set the Kafka Streams commit interval, not consumer auto-commit:

```properties
app.stream.commit-interval-ms=1000
```

- If configured in Java:

```java
props.put(StreamsConfig.COMMIT_INTERVAL_MS_CONFIG, 1000);
```

- Do not use:

```java
consumer.auto.commit=true
consumer.auto.commit.interval.ms=...
```

Why:
- Kafka Streams disables consumer auto-commit and manages commits itself.
- `commit.interval.ms` is the setting that controls replay window under `at_least_once`.

## 3. Remove Manual Consumer Assignor Override

- Remove this kind of code from the Kafka Streams config:

```java
props.put(
    StreamsConfig.consumerPrefix(ConsumerConfig.PARTITION_ASSIGNMENT_STRATEGY_CONFIG),
    CooperativeStickyAssignor.class.getName()
);
```

- Do not set:

```properties
consumer.partition.assignment.strategy=...
```

Why:
- Kafka Streams uses its own `StreamsPartitionAssignor`.
- A plain Kafka consumer assignor such as `CooperativeStickyAssignor` should not be forced into a Kafka Streams app.

## 4. Add Consumer Isolation Level

- Add:

```properties
app.stream.consumer.isolation-level=read_committed
```

- If configured in Java:

```java
props.put("consumer.isolation.level", "read_committed");
```

Why:
- If upstream producers use Kafka transactions, this prevents the stream from reading aborted records.
- This does not prevent normal replay duplicates under `at_least_once`.

## 5. Keep Core Streams Consumer Properties

Make sure these are present and wired into the Kafka Streams config:

```properties
app.stream.application-id=<your-stream-app-id>
app.stream.consumer.auto-offset-reset=latest
app.stream.consumer.max-poll-records=250
app.stream.consumer.max-poll-interval-ms=720000
app.stream.consumer.session-timeout-ms=720000
app.stream.consumer.heartbeat-interval-ms=10000
app.stream.consumer.request-timeout-ms=30000
app.stream.consumer.retry-backoff-ms=500
app.stream.consumer.fetch-max-bytes=52428800
app.stream.consumer.fetch-min-bytes=16384
app.stream.consumer.fetch-max-wait-ms=500
app.stream.consumer.max-partition-fetch-bytes=1048576
app.stream.group-instance-id=${HOSTNAME:local-dev-instance}
app.stream.internal-leave-group-on-close=false
```

Why:
- These are the main consumer-side settings already used by the reference project for Streams behavior under autoscaling and restarts.

## 6. Wire the Properties with Consumer Prefixes

In Java config, use the consumer-prefixed keys when passing overrides into Kafka Streams:

```java
props.put("consumer.group.instance.id", groupInstanceId);
props.put("consumer.internal.leave.group.on.close", internalLeaveGroupOnClose);
props.put("main.consumer.auto.offset.reset", autoOffsetReset);
props.put("consumer.isolation.level", consumerIsolationLevel);
props.put("consumer.max.poll.records", maxPollRecords);
props.put("consumer.max.poll.interval.ms", maxPollIntervalMs);
props.put("consumer.session.timeout.ms", sessionTimeoutMs);
props.put("consumer.heartbeat.interval.ms", heartbeatIntervalMs);
props.put("consumer.request.timeout.ms", requestTimeoutMs);
props.put("consumer.retry.backoff.ms", retryBackoffMs);
props.put("consumer.fetch.max.bytes", fetchMaxBytes);
props.put("consumer.fetch.min.bytes", fetchMinBytes);
props.put("consumer.fetch.max.wait.ms", fetchMaxWaitMs);
props.put("consumer.max.partition.fetch.bytes", maxPartitionFetchBytes);
```

Why:
- These keys target Kafka Streams' internal main consumer.

## 7. Keep the Separate Producer Mentally Separate

- If your architecture is:
  - Kafka Streams consumer reads/processes input
  - separate producer publishes output

then remember:

- producer transactions do not commit consumer offsets
- consumer commits do not make producer sends atomic
- `at_least_once` means replay is still possible before the next commit

Why:
- This is the main duplicate path in the architecture.
- It is not fixed by producer transaction settings alone.

## 8. Optional: Fail Fast on `upgrade.from`

- Optional defensive guard:

```java
static void validateCooperativeRebalancingConfig(Environment environment) {
    for (String propertyName : FORBIDDEN_UPGRADE_FROM_PROPERTIES) {
        String value = environment.getProperty(propertyName);
        if (value != null && !value.isBlank()) {
            throw new IllegalStateException("Remove " + propertyName + "=" + value);
        }
    }
}
```

Why:
- Useful only if you want to prevent accidental reintroduction of Kafka Streams upgrade compatibility mode.
- For a brand-new project, this is optional.

## 9. Runtime Validation Checklist

After patching the prod project, verify:

- `processing.guarantee=at_least_once`
- no `partition.assignment.strategy` override is set for Kafka Streams
- `consumer.isolation.level=read_committed` is visible in effective runtime config
- `consumer.group.instance.id` is unique per pod
- `consumer.internal.leave.group.on.close=false` is actually present in effective runtime config
- `commit.interval.ms` is the value you intend to run with

## 10. Most Important Reality Check

These changes improve correctness of the Kafka Streams consumer config, but they do **not** eliminate replay duplicates by themselves.

Replay duplicates can still happen when:

1. a record is processed
2. output is published by the separate producer
3. rebalance, shutdown, or restart happens before Kafka Streams commits the input offset
4. the same input record is replayed
5. output is published again

That is expected `at_least_once` behavior for this architecture.

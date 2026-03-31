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

## 10. Upgrade Kafka Libraries to `3.9.2`

If the target production project uses the Spring Boot parent BOM like this reference project, do **not** add versions on the Kafka dependencies directly. Add this property in `pom.xml` instead:

```xml
<properties>
    <kafka.version>3.9.2</kafka.version>
</properties>
```

Keep dependencies like this:

```xml
<dependency>
    <groupId>org.springframework.kafka</groupId>
    <artifactId>spring-kafka</artifactId>
</dependency>
<dependency>
    <groupId>org.apache.kafka</groupId>
    <artifactId>kafka-streams</artifactId>
</dependency>
```

Why:
- Spring Boot manages the Kafka versions through dependency management
- overriding `kafka.version` upgrades `kafka-streams`, `kafka-clients`, and related Kafka artifacts together
- this is the smallest safe change when moving from `3.9.1` to `3.9.2`

## 11. How to Check the Upgrade

After changing `pom.xml`, run:

```bash
mvn dependency:tree -Dincludes=org.apache.kafka:kafka-streams,org.apache.kafka:kafka-clients
```

Expected result:
- `org.apache.kafka:kafka-streams:jar:3.9.2`
- `org.apache.kafka:kafka-clients:jar:3.9.2`

Optional sanity check:

```bash
mvn help:evaluate -Dexpression=kafka.version -q -DforceStdout
```

Expected result:

```text
3.9.2
```

If the target prod project does **not** use Spring Boot dependency management, then set the Kafka version directly on the dependency instead of using the `kafka.version` property.

## 12. Most Important Reality Check

These changes improve correctness of the Kafka Streams consumer config, but they do **not** eliminate replay duplicates by themselves.

Replay duplicates can still happen when:

1. a record is processed
2. output is published by the separate producer
3. rebalance, shutdown, or restart happens before Kafka Streams commits the input offset
4. the same input record is replayed
5. output is published again

That is expected `at_least_once` behavior for this architecture.



What I found from Apache sources:

3.9.1 was released on May 20/21, 2025 as a bug-fix release: https://kafka.apache.org/blog/2025/05/20/apache-kafka-3.9.1-release-announcement/ and https://kafka.apache.org/community/downloads/
3.9.2 was released on February 21, 2026 as another bug-fix release with “critical fixes”: https://kafka.apache.org/blog/2026/02/21/apache-kafka-3.9.2-release-announcement/
The latest major release I found is 4.2.0 on February 17, 2026: https://kafka.apache.org/blog/2026/02/17/apache-kafka-4.2.0-release-announcement/
The most relevant bug I found for your symptom is:

KAFKA-19242: “Fix commit bugs caused by race condition during rebalancing”
Fixed in 3.9.2, 4.0.1, 4.1.0
Link: https://issues.apache.org/jira/browse/KAFKA-19242
Why that matters:

your problem is around rebalancing + commits + replay
this bug is exactly in that neighborhood
even though it is in the clients layer, Kafka Streams relies on that consumer group machinery
One important thing the sources also show:

KAFKA-18943 was a rare Kafka Streams duplicate bug around revocation/commit, but it was fixed in 3.9.1
Link: https://issues.apache.org/jira/browse/KAFKA-18943
so that particular Streams duplicate bug is already fixed in your current version
My recommendation:

Yes, upgrade at least to 3.9.2
No, I would not say you must jump to the latest major (4.2.0) first
safest path for production is usually:
3.9.1 → 3.9.2
retest rebalance/duplicate scenario
only then consider a major upgrade if needed
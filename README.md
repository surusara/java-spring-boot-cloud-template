# Kafka Streams Circuit Breaker Sample

A low-risk Java 21 / Spring Boot 3.5.9 / Spring Kafka 3.9.1 reference project for financial Kafka Streams consumers.

## What this project demonstrates

- Kafka Streams consumer with custom deserialization exception handler
- Per-stream business soft-failure circuit breaker
- Stop/start lifecycle control using `StreamsBuilderFactoryBean`
- Uncaught fatal exception handler using `SHUTDOWN_CLIENT`
- Idempotent Kafka producer for output topic
- Sample tests for healthy, soft-failure, deserialization, and fatal scenarios

## Why this is the lower-risk path

This project deliberately avoids adopting Kafka Streams built-in DLQ in the first production release. Instead it keeps:

- custom deserialization quarantine/audit
- custom soft-failure logging to DB/audit store
- stream stop/start as the breaker action
- fatal exception -> client shutdown -> pod restart path

## Build

```bash
mvn clean test
mvn spring-boot:run
```

## Key sizing note

Workload assumption used in the document and defaults:

- 3,000,000 messages in 8 hours ~= 104.17 msg/s
- average message size ~= 14 KB
- synchronous business processing ~= 200 ms/message
- single sequential processor capacity ~= 5 msg/s
- starting concurrency target ~= 21 processing slots across stream threads x pods

## How to test scenarios

### 1. Healthy message
Send:

```json
{"eventId":"e1","correlationId":"c1","cid":"123","eventType":"PAYMENT","payload":"{}","softBusinessFailure":false,"fatalBusinessFailure":false}
```

Expected:
- no audit record
- output event published
- circuit breaker remains closed

### 2. Soft business failure
Send:

```json
{"eventId":"e2","correlationId":"c2","cid":"123","eventType":"PAYMENT","payload":"{}","softBusinessFailure":true,"fatalBusinessFailure":false}
```

Expected:
- soft-failure written to audit repository
- stream continues
- repeated volume of these failures eventually opens breaker

### 3. Fatal business failure
Send:

```json
{"eventId":"e3","correlationId":"c3","cid":"123","eventType":"PAYMENT","payload":"{}","softBusinessFailure":false,"fatalBusinessFailure":true}
```

Expected:
- uncaught exception path
- Streams uncaught exception handler returns `SHUTDOWN_CLIENT`
- pod restart handled by AKS

### 4. Deserialization failure
Publish malformed JSON such as:

```json
{"eventId":"broken"
```

Expected:
- custom deserialization handler logs the failure
- stream continues without stopping

## Dependency note

`org.apache.kafka:kafka-streams` determines the Kafka Streams client version in the app. In this project the version is pulled through the Maven dependency graph produced by Spring Kafka / Spring Boot.

If you ever pin it explicitly, that explicit version becomes your Kafka Streams client version.

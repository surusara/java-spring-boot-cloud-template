package com.example.consumer.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Strongly-typed application configuration. All operational knobs are here so the tuning guide
 * in the README maps 1:1 to real properties.
 */
@ConfigurationProperties(prefix = "app")
public class AppProperties {

    private final Consumer consumer = new Consumer();
    private final Outbox outbox = new Outbox();

    public Consumer getConsumer() {
        return consumer;
    }

    public Outbox getOutbox() {
        return outbox;
    }

    public static class Consumer {
        /** Input topic carrying Avro PaymentInput records. */
        private String topic = "payments.input";
        /** Consumer group id (also the KEDA lag target). */
        private String groupId = "payments-consumer-v1";
        /** Number of listener threads PER POD (each owns a share of partitions). */
        private int concurrency = 4;
        /** Records returned per poll(). batch * per-record-time must stay under max-poll-interval-ms. */
        private int maxPollRecords = 10;
        /** Consumer eviction detection window. */
        private int maxPollIntervalMs = 300_000;
        private int sessionTimeoutMs = 45_000;
        private int heartbeatIntervalMs = 10_000;

        public String getTopic() {
            return topic;
        }

        public void setTopic(String topic) {
            this.topic = topic;
        }

        public String getGroupId() {
            return groupId;
        }

        public void setGroupId(String groupId) {
            this.groupId = groupId;
        }

        public int getConcurrency() {
            return concurrency;
        }

        public void setConcurrency(int concurrency) {
            this.concurrency = concurrency;
        }

        public int getMaxPollRecords() {
            return maxPollRecords;
        }

        public void setMaxPollRecords(int maxPollRecords) {
            this.maxPollRecords = maxPollRecords;
        }

        public int getMaxPollIntervalMs() {
            return maxPollIntervalMs;
        }

        public void setMaxPollIntervalMs(int maxPollIntervalMs) {
            this.maxPollIntervalMs = maxPollIntervalMs;
        }

        public int getSessionTimeoutMs() {
            return sessionTimeoutMs;
        }

        public void setSessionTimeoutMs(int sessionTimeoutMs) {
            this.sessionTimeoutMs = sessionTimeoutMs;
        }

        public int getHeartbeatIntervalMs() {
            return heartbeatIntervalMs;
        }

        public void setHeartbeatIntervalMs(int heartbeatIntervalMs) {
            this.heartbeatIntervalMs = heartbeatIntervalMs;
        }
    }

    public static class Outbox {
        private String approvedTopic = "payments.approved";
        private String auditTopic = "payments.audit";
        /** Rows drained from the outbox per relay cycle. */
        private int batchSize = 200;
        /** Delay between relay cycles. */
        private long pollDelayMs = 500;

        public String getApprovedTopic() {
            return approvedTopic;
        }

        public void setApprovedTopic(String approvedTopic) {
            this.approvedTopic = approvedTopic;
        }

        public String getAuditTopic() {
            return auditTopic;
        }

        public void setAuditTopic(String auditTopic) {
            this.auditTopic = auditTopic;
        }

        public int getBatchSize() {
            return batchSize;
        }

        public void setBatchSize(int batchSize) {
            this.batchSize = batchSize;
        }

        public long getPollDelayMs() {
            return pollDelayMs;
        }

        public void setPollDelayMs(long pollDelayMs) {
            this.pollDelayMs = pollDelayMs;
        }
    }
}

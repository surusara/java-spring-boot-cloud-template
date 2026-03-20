package com.example.financialstream.service;

import com.example.financialstream.model.SoftFailureRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Service
public class InMemoryExceptionAuditService implements ExceptionAuditService {

    private static final Logger log = LoggerFactory.getLogger(InMemoryExceptionAuditService.class);

    private final List<String> deserializationEvents = Collections.synchronizedList(new ArrayList<>());
    private final List<SoftFailureRecord> softFailures = Collections.synchronizedList(new ArrayList<>());

    @Override
    public void logDeserializationFailure(String streamName,
                                          String topic,
                                          int partition,
                                          long offset,
                                          byte[] keyBytes,
                                          byte[] valueBytes,
                                          String errorCode,
                                          String errorMessage) {
        String summary = "%s|%s|%d|%d|%s|%s".formatted(streamName, topic, partition, offset, errorCode, errorMessage);
        deserializationEvents.add(summary);
        log.warn("Logged deserialization failure: {}", summary);
    }

    @Override
    public void logSoftFailure(SoftFailureRecord record) {
        softFailures.add(record);
        log.warn("Logged business soft failure: {}", record);
    }

    public List<String> getDeserializationEvents() {
        return List.copyOf(deserializationEvents);
    }

    public List<SoftFailureRecord> getSoftFailures() {
        return List.copyOf(softFailures);
    }
}

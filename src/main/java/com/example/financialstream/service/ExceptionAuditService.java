package com.example.financialstream.service;

import com.example.financialstream.model.SoftFailureRecord;

public interface ExceptionAuditService {

    void logDeserializationFailure(String streamName,
                                   String topic,
                                   int partition,
                                   long offset,
                                   byte[] keyBytes,
                                   byte[] valueBytes,
                                   String errorCode,
                                   String errorMessage);

    void logSoftFailure(SoftFailureRecord record);
}

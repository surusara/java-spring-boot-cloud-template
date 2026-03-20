package com.example.financialstream.kafka;

import com.example.financialstream.service.ExceptionAuditService;
import com.example.financialstream.util.ApplicationContextProvider;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.streams.errors.DeserializationExceptionHandler;
import org.apache.kafka.streams.processor.ProcessorContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Production-grade deserialization exception handler.
 * Logs malformed messages to audit store and continues processing.
 * Does NOT impact circuit breaker (separate concern).
 */
public class CustomDeserializationExceptionHandler implements DeserializationExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(CustomDeserializationExceptionHandler.class);

    @Override
    public DeserializationHandlerResponse handle(ProcessorContext context,
                                                 ConsumerRecord<byte[], byte[]> record,
                                                 Exception exception) {
        try {
            ExceptionAuditService auditService = ApplicationContextProvider.getBean(ExceptionAuditService.class);
            
            String errorMsg = exception.getMessage() != null ? exception.getMessage() : exception.getClass().getSimpleName();
            
            log.warn("⚠️  Deserialization error - topic={}, partition={}, offset={}, error={}",
                record.topic(), record.partition(), record.offset(), errorMsg);
            
            auditService.logDeserializationFailure(
                "payments-stream",
                record.topic(),
                record.partition(),
                record.offset(),
                record.key(),
                record.value(),
                "DESERIALIZATION_ERROR",
                errorMsg
            );
            
        } catch (Exception auditEx) {
            log.error("Error logging deserialization failure", auditEx);
            // Continue anyway; don't let audit failure block stream
        }
        
        return DeserializationHandlerResponse.CONTINUE;
    }

    @Override
    public void configure(Map<String, ?> configs) {
        // no-op
    }
}

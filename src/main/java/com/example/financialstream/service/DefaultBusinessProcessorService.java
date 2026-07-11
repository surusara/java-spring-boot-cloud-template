package com.example.financialstream.service;

import com.example.financialstream.model.InputEvent;
import com.example.financialstream.model.OutputEvent;
import com.example.financialstream.model.ProcessingResult;
import com.example.financialstream.model.SoftFailureRecord;
import com.example.financialstream.model.SoftFailureReason;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;

@Service
public class DefaultBusinessProcessorService implements BusinessProcessorService {

    private static final Logger log = LoggerFactory.getLogger(DefaultBusinessProcessorService.class);

    private final ExceptionAuditService exceptionAuditService;
    private final CsfleCryptoService csfleCryptoService;
    private final Timer csfleTimer;
    private final Timer businessLogicTimer;
    private final Timer dbAuditTimer;

    public DefaultBusinessProcessorService(ExceptionAuditService exceptionAuditService,
                                           CsfleCryptoService csfleCryptoService,
                                           MeterRegistry meterRegistry) {
        this.exceptionAuditService = exceptionAuditService;
        this.csfleCryptoService = csfleCryptoService;
        this.csfleTimer = Timer.builder("pipeline.stage.duration")
                .tag("stage", "csfle_decrypt")
                .description("CSFLE decrypt / serde normalization")
                .register(meterRegistry);
        this.businessLogicTimer = Timer.builder("pipeline.stage.duration")
                .tag("stage", "business_logic")
                .description("Business validation and enrichment")
                .register(meterRegistry);
        this.dbAuditTimer = Timer.builder("pipeline.stage.duration")
                .tag("stage", "db_audit")
                .description("DB audit / soft failure logging")
                .register(meterRegistry);
    }

    @Override
    public ProcessingResult process(String streamName,
                                    String topic,
                                    int partition,
                                    long offset,
                                    String key,
                                    InputEvent event) {
        try {
            // Stage 2: CSFLE decrypt / serde normalization
            InputEvent normalized = csfleTimer.record(() -> csfleCryptoService.normalizeAfterSerde(event));

            // Stage 4: Business logic / validation
            return businessLogicTimer.record(() -> {
                if (normalized.fatalBusinessFailure()) {
                    throw new IllegalStateException("Fatal business failure requested for test or hard-stop scenario");
                }

                if (normalized.softBusinessFailure()) {
                    // Stage 5: DB audit
                    dbAuditTimer.record(() -> exceptionAuditService.logSoftFailure(
                        SoftFailureRecord.of(
                            streamName, topic, partition, offset, key,
                            normalized.correlationId(),
                            SoftFailureReason.VALIDATION_FAILED,
                            sha256(normalized.payload())
                        )
                    ));
                    return ProcessingResult.successWithExceptionLogged(
                        SoftFailureReason.VALIDATION_FAILED.code(),
                        SoftFailureReason.VALIDATION_FAILED.remediation()
                    );
                }

                if (normalized.cid() == null || normalized.cid().isBlank()) {
                    // Stage 5: DB audit
                    dbAuditTimer.record(() -> exceptionAuditService.logSoftFailure(
                        SoftFailureRecord.of(
                            streamName, topic, partition, offset, key,
                            normalized.correlationId(),
                            SoftFailureReason.MISSING_REQUIRED_FIELD,
                            sha256(normalized.payload())
                        )
                    ));
                    return ProcessingResult.successWithExceptionLogged(
                        SoftFailureReason.MISSING_REQUIRED_FIELD.code(),
                        SoftFailureReason.MISSING_REQUIRED_FIELD.remediation()
                    );
                }

                // Stage 6: build the downstream record. The processor forwards it through
                // Kafka Streams' own producer so the input-offset commit and the output
                // topic write share a single transaction under exactly_once_v2.
                OutputEvent output = new OutputEvent(
                        normalized.eventId(),
                        normalized.correlationId(),
                        "PROCESSED",
                        Instant.now()
                );
                return ProcessingResult.success("OK", output);
            });

        } catch (IllegalStateException ex) {
            // Hard/fatal failures must propagate — caught by StreamFatalExceptionHandler → SHUTDOWN_CLIENT → pod restart
            throw ex;
        } catch (Exception ex) {
            log.error("Unexpected error in processor for stream={}, topic={}, partition={}, offset={}, key={}",
                streamName, topic, partition, offset, key, ex);
            // Persist a durable audit record so the failure is not lost to logs alone. The name
            // "successWithExceptionLogged" implies a recorded exception, so back it with the audit store.
            recordInternalErrorAudit(streamName, topic, partition, offset, key, event);
            return ProcessingResult.successWithExceptionLogged(
                SoftFailureReason.INTERNAL_ERROR.code(),
                "Unexpected error: " + ex.getMessage()
            );
        }
    }

    private void recordInternalErrorAudit(String streamName, String topic, int partition, long offset,
                                          String key, InputEvent event) {
        try {
            String correlationId = event != null ? event.correlationId() : null;
            String payloadHash = event != null ? sha256(event.payload()) : "NO_PAYLOAD";
            exceptionAuditService.logSoftFailure(SoftFailureRecord.of(
                streamName, topic, partition, offset, key, correlationId,
                SoftFailureReason.INTERNAL_ERROR, payloadHash
            ));
        } catch (Exception auditEx) {
            // Never let an audit failure mask the original error path.
            log.error("Failed to persist internal-error audit record for key={}", key, auditEx);
        }
    }

    private String sha256(String payload) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            log.warn("Failed to compute SHA-256 hash", ex);
            return "HASH_ERROR";
        }
    }
}

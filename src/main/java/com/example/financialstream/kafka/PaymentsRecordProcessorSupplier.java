package com.example.financialstream.kafka;

import com.example.financialstream.circuit.BusinessOutcomeCircuitBreaker;
import com.example.financialstream.model.InputEvent;
import com.example.financialstream.model.ProcessingResult;
import com.example.financialstream.service.BusinessProcessorService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.apache.kafka.streams.kstream.Named;
import org.apache.kafka.streams.processor.AbstractProcessor;
import org.apache.kafka.streams.processor.Processor;
import org.apache.kafka.streams.processor.ProcessorContext;
import org.apache.kafka.streams.processor.ProcessorSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Production-grade record processor supplier for Kafka Streams.
 * Implements circuit breaker gate and business logic invocation.
 */
@Component
public class PaymentsRecordProcessorSupplier implements ProcessorSupplier<String, InputEvent> {

    private static final Logger log = LoggerFactory.getLogger(PaymentsRecordProcessorSupplier.class);

    private final BusinessProcessorService businessProcessorService;
    private final BusinessOutcomeCircuitBreaker breaker;
    private final Timer e2eTimer;

    public PaymentsRecordProcessorSupplier(BusinessProcessorService businessProcessorService,
                                           BusinessOutcomeCircuitBreaker breaker,
                                           MeterRegistry meterRegistry) {
        this.businessProcessorService = businessProcessorService;
        this.breaker = breaker;
        this.e2eTimer = Timer.builder("pipeline.stage.duration")
                .tag("stage", "e2e_total")
                .description("End-to-end record processing time")
                .register(meterRegistry);
    }

    @Override
    public Processor<String, InputEvent> get() {
        return new AbstractProcessor<>() {
            @Override
            public void process(String key, InputEvent value) {
                Timer.Sample e2eSample = Timer.start();
                try {
                    // Check circuit breaker before processing
                    if (!breaker.tryAcquirePermission()) {
                        throw new IllegalStateException(
                            "Circuit breaker does not permit processing in current state"
                        );
                    }

                    ProcessorContext context = context();
                    ProcessingResult result = businessProcessorService.process(
                        "payments-stream",
                        context.topic(),
                        context.partition(),
                        context.offset(),
                        key,
                        value
                    );
                    
                    // Record result in circuit breaker for evaluation
                    breaker.record(result);
                    
                } catch (IllegalStateException cbException) {
                    // Circuit breaker or hard failure - propagate to terminate stream
                    log.error("🔴 Processing blocked: {}", cbException.getMessage());
                    throw cbException;
                } catch (Exception ex) {
                    log.error("❌ Unexpected error processing key={}", key, ex);
                    throw new IllegalStateException("Unexpected processor error; stream must restart", ex);
                } finally {
                    e2eSample.stop(e2eTimer);
                }
            }
        };
    }

    public Named named() {
        return Named.as("payments-record-processor");
    }
}

package com.example.financialstream.kafka;

import com.example.financialstream.circuit.BusinessOutcomeCircuitBreaker;
import com.example.financialstream.model.InputEvent;
import com.example.financialstream.model.OutputEvent;
import com.example.financialstream.model.ProcessingResult;
import com.example.financialstream.service.BusinessProcessorService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.apache.kafka.common.errors.RetriableException;
import org.apache.kafka.streams.kstream.Named;
import org.apache.kafka.streams.processor.api.Processor;
import org.apache.kafka.streams.processor.api.ProcessorContext;
import org.apache.kafka.streams.processor.api.ProcessorSupplier;
import org.apache.kafka.streams.processor.api.Record;
import org.apache.kafka.streams.processor.api.RecordMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Production-grade record processor supplier for Kafka Streams.
 *
 * <p>Implements the circuit-breaker gate, invokes business logic, and forwards the
 * produced {@link OutputEvent} through Kafka Streams' own producer via
 * {@code context.forward(...)}. The downstream topology terminates with
 * {@code .to(outputTopic, ...)}, so under {@code processing.guarantee=exactly_once_v2}
 * the input-offset commit and the output topic write share a single Kafka
 * transaction — no duplicates on rebalance.
 *
 * <p><b>Do not</b> publish to the output topic from inside the business service via
 * a separate {@code KafkaTemplate}: that creates an independent transaction outside
 * Streams' control and re-introduces the duplicate-on-rebalance failure mode that
 * this design eliminates.
 */
@Component
public class PaymentsRecordProcessorSupplier
        implements ProcessorSupplier<String, InputEvent, String, OutputEvent> {

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
    public Processor<String, InputEvent, String, OutputEvent> get() {
        return new Processor<>() {
            private ProcessorContext<String, OutputEvent> ctx;

            @Override
            public void init(ProcessorContext<String, OutputEvent> context) {
                this.ctx = context;
            }

            @Override
            public void process(Record<String, InputEvent> record) {
                Timer.Sample e2eSample = Timer.start();
                try {
                    if (!breaker.tryAcquirePermission()) {
                        throw new IllegalStateException(
                            "Circuit breaker does not permit processing in current state"
                        );
                    }

                    String topic = ctx.recordMetadata().map(RecordMetadata::topic).orElse(null);
                    int partition = ctx.recordMetadata().map(RecordMetadata::partition).orElse(-1);
                    long offset = ctx.recordMetadata().map(RecordMetadata::offset).orElse(-1L);

                    ProcessingResult result = businessProcessorService.process(
                        "payments-stream",
                        topic,
                        partition,
                        offset,
                        record.key(),
                        record.value()
                    );

                    breaker.record(result);

                    // Forward the downstream record through Streams' producer. Under EOS v2
                    // the forward + the input-offset commit are part of the same transaction.
                    if (result.output() != null) {
                        ctx.forward(record.withValue(result.output()));
                    }

                } catch (IllegalStateException cbException) {
                    // Circuit breaker or explicit hard-stop — let it propagate.
                    log.error("\uD83D\uDD34 Processing blocked: {}", cbException.getMessage());
                    throw cbException;
                } catch (RetriableException retriable) {
                    // Network/timeout/broker-disconnect: let the original Kafka exception bubble
                    // up so StreamFatalExceptionHandler classifies it as REPLACE_THREAD
                    // (not SHUTDOWN_CLIENT). Wrapping it as IllegalStateException would trigger
                    // a pod restart on every transient broker hiccup.
                    log.warn("\u26A0\uFE0F Retriable exception for key={}: {} \u2014 deferring to handler for thread replace",
                            record.key(), retriable.getMessage());
                    throw retriable;
                } catch (Exception ex) {
                    log.error("\u274C Unexpected error processing key={}", record.key(), ex);
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

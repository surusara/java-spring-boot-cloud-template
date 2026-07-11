package com.example.financialstream.circuit;

import org.apache.kafka.common.errors.RetriableException;

/**
 * Thrown by the record processor when the circuit breaker is OPEN (or HALF_OPEN and out of
 * permits) and therefore does not permit processing.
 *
 * <p>It extends Kafka's {@link RetriableException} on purpose: the
 * {@code StreamFatalExceptionHandler} classifies retriable exceptions as
 * {@code REPLACE_THREAD} rather than {@code SHUTDOWN_CLIENT}. That keeps the pod alive
 * (no {@code LivenessState.BROKEN}, no kubelet restart) so the breaker can pause the
 * stream <b>in place</b> and later resume the <i>same</i> partitions on the <i>same</i>
 * member — which is the whole point of the park-and-recover design. Throwing (rather than
 * silently dropping the record) aborts the in-flight EOS transaction, so the record is
 * reprocessed after recovery instead of being committed and lost.
 *
 * <p>Contrast with a genuine fatal/business error, which is thrown as a non-retriable
 * exception and correctly drives {@code SHUTDOWN_CLIENT} → pod restart.
 */
public class CircuitBreakerOpenException extends RetriableException {

    public CircuitBreakerOpenException(String message) {
        super(message);
    }
}

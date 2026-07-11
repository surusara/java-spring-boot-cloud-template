package com.example.consumer.circuit;

/**
 * Signals that a record could not be processed because the circuit breaker is OPEN. Thrown from the
 * error handler's recoverer so the offset is <b>not</b> committed and the record is re-delivered
 * once the breaker recovers and the container resumes — i.e. no record is skipped during an outage.
 */
public class BreakerOpenRedeliveryException extends RuntimeException {
    public BreakerOpenRedeliveryException(Throwable cause) {
        super("Circuit breaker OPEN — record withheld for redelivery after recovery", cause);
    }
}

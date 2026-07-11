package com.example.financialstream.kafka;

import com.example.financialstream.circuit.CircuitBreakerOpenException;
import org.apache.kafka.streams.errors.StreamsException;
import org.apache.kafka.streams.errors.StreamsUncaughtExceptionHandler.StreamThreadExceptionResponse;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

class StreamFatalExceptionHandlerTest {

    private final StreamFatalExceptionHandler handler =
            new StreamFatalExceptionHandler(mock(ApplicationEventPublisher.class));

    @Test
    void breakerOpenReplacesThreadInsteadOfShuttingDownThePod() {
        // Even when wrapped by Kafka Streams, the root CircuitBreakerOpenException must be
        // treated as retriable so the pod stays alive and the stream pauses in place.
        StreamThreadExceptionResponse response = handler.handle(
                new StreamsException(new CircuitBreakerOpenException("breaker open")));

        assertEquals(StreamThreadExceptionResponse.REPLACE_THREAD, response);
    }

    @Test
    void transientTimeoutReplacesThread() {
        StreamThreadExceptionResponse response = handler.handle(
                new StreamsException(new TimeoutException("broker slow")));

        assertEquals(StreamThreadExceptionResponse.REPLACE_THREAD, response);
    }

    @Test
    void genuineFatalErrorShutsDownClient() {
        StreamThreadExceptionResponse response = handler.handle(
                new IllegalStateException("unrecoverable bug"));

        assertEquals(StreamThreadExceptionResponse.SHUTDOWN_CLIENT, response);
    }
}

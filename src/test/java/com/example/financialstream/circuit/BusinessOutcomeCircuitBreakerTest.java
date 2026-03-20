package com.example.financialstream.circuit;

import com.example.financialstream.model.ProcessingResult;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BusinessOutcomeCircuitBreakerTest {

    @Test
    void shouldOpenAndUseEscalatingRestartDelays() {
        BreakerControlProperties props = new BreakerControlProperties();
        props.setMinimumNumberOfCalls(5);
        props.setFailureRateThreshold(20);
        props.setRestartDelays(List.of(Duration.ofMinutes(1), Duration.ofMinutes(10), Duration.ofMinutes(20)));

        StubLifecycleController lifecycle = new StubLifecycleController();
        BusinessOutcomeCircuitBreaker breaker = new BusinessOutcomeCircuitBreaker(props, lifecycle);
        breaker.init();

        for (int i = 0; i < 5; i++) {
            breaker.record(ProcessingResult.successWithExceptionLogged("SOFT", "x"));
        }

        assertTrue(lifecycle.stopped);
        assertEquals(Duration.ofMinutes(1), breaker.getNextRestartDelay());
    }

    private static class StubLifecycleController implements StreamLifecycleController {
        boolean stopped;
        boolean started;

        @Override
        public void stopStream() {
            this.stopped = true;
        }

        @Override
        public void startStream() {
            this.started = true;
        }

        @Override
        public boolean isStoppedByBreaker() {
            return stopped && !started;
        }
    }
}

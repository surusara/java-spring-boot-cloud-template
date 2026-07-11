package com.example.financialstream.config.validation;

import com.example.financialstream.circuit.BreakerControlProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BreakerConfigurationValidatorTest {

    @Test
    void acceptsSafeDefaults() {
        BreakerConfigurationValidator validator =
                new BreakerConfigurationValidator(new BreakerControlProperties());

        assertDoesNotThrow(validator::afterPropertiesSet);
    }

    @Test
    void rejectsFailureRateOutsideValidRange() {
        BreakerControlProperties properties = new BreakerControlProperties();
        properties.setFailureRateThreshold(0);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> new BreakerConfigurationValidator(properties).afterPropertiesSet()
        );

        assertTrue(exception.getMessage().contains("between 1 and 100"));
    }

    @Test
    void rejectsFailureRateAboveOneHundredPercent() {
        BreakerControlProperties properties = new BreakerControlProperties();
        properties.setFailureRateThreshold(101);

        assertThrows(
                IllegalArgumentException.class,
                () -> new BreakerConfigurationValidator(properties).afterPropertiesSet()
        );
    }

    @Test
    void rejectsTooFewMinimumCalls() {
        BreakerControlProperties properties = new BreakerControlProperties();
        properties.setMinimumNumberOfCalls(9);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> new BreakerConfigurationValidator(properties).afterPropertiesSet()
        );

        assertTrue(exception.getMessage().contains("at least 10"));
    }

    @Test
    void rejectsMissingRestartDelays() {
        BreakerControlProperties properties = new BreakerControlProperties();
        properties.setRestartDelays(List.of());

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> new BreakerConfigurationValidator(properties).afterPropertiesSet()
        );

        assertTrue(exception.getMessage().contains("cannot be empty"));
    }

    @Test
    void rejectsRestartDelayBelowTenSeconds() {
        BreakerControlProperties properties = new BreakerControlProperties();
        properties.setRestartDelays(List.of(Duration.ofSeconds(9)));

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> new BreakerConfigurationValidator(properties).afterPropertiesSet()
        );

        assertTrue(exception.getMessage().contains("at least 10 seconds"));
    }

    @Test
    void rejectsTimeWindowOutsideValidRange() {
        BreakerControlProperties properties = new BreakerControlProperties();
        properties.setTimeWindowSeconds(59);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> new BreakerConfigurationValidator(properties).afterPropertiesSet()
        );

        assertTrue(exception.getMessage().contains("between 60 and 7200"));
    }

    @Test
    void rejectsTimeWindowAboveTwoHours() {
        BreakerControlProperties properties = new BreakerControlProperties();
        properties.setTimeWindowSeconds(7_201);

        assertThrows(
                IllegalArgumentException.class,
                () -> new BreakerConfigurationValidator(properties).afterPropertiesSet()
        );
    }
}

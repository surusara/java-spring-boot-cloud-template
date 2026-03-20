package com.example.financialstream.kafka;

import com.example.financialstream.model.InputEvent;
import com.example.financialstream.model.OutputEvent;
import com.example.financialstream.service.BusinessProcessorService;
import com.example.financialstream.service.CsfleCryptoService;
import com.example.financialstream.service.DefaultBusinessProcessorService;
import com.example.financialstream.service.InMemoryExceptionAuditService;
import com.example.financialstream.service.OutputProducerService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.common.serialization.Deserializer;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.common.serialization.Serializer;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.TestInputTopic;
import org.apache.kafka.streams.Topology;
import org.apache.kafka.streams.TopologyTestDriver;
import org.apache.kafka.streams.kstream.Consumed;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.support.serializer.JsonSerde;
import org.springframework.kafka.support.serializer.JsonSerializer;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

class TopologyProcessingTest {

    @Test
    void shouldProduceOutputForHealthyMessage() {
        InMemoryExceptionAuditService audit = new InMemoryExceptionAuditService();
        CapturingOutputProducer producer = new CapturingOutputProducer();
        BusinessProcessorService businessProcessor = new DefaultBusinessProcessorService(audit, producer, event -> event, new SimpleMeterRegistry());
        var breaker = TestBreaker.closed();

        Topology topology = buildTopology(businessProcessor, breaker);
        try (TopologyTestDriver driver = new TopologyTestDriver(topology, properties())) {
            TestInputTopic<String, InputEvent> input = driver.createInputTopic(
                    "payments.input",
                    Serdes.String().serializer(),
                    new JsonSerializer<InputEvent>()
            );

            input.pipeInput("k1", new InputEvent("e1", "c1", "cid", "PAYMENT", "{}", false, false));
            assertEquals(1, producer.events.size());
            assertTrue(audit.getSoftFailures().isEmpty());
        }
    }

    @Test
    void shouldLogSoftFailureAndContinue() {
        InMemoryExceptionAuditService audit = new InMemoryExceptionAuditService();
        CapturingOutputProducer producer = new CapturingOutputProducer();
        BusinessProcessorService businessProcessor = new DefaultBusinessProcessorService(audit, producer, event -> event, new SimpleMeterRegistry());
        var breaker = TestBreaker.closed();

        Topology topology = buildTopology(businessProcessor, breaker);
        try (TopologyTestDriver driver = new TopologyTestDriver(topology, properties())) {
            TestInputTopic<String, InputEvent> input = driver.createInputTopic(
                    "payments.input",
                    Serdes.String().serializer(),
                    new JsonSerializer<InputEvent>()
            );

            input.pipeInput("k1", new InputEvent("e1", "c1", "cid", "PAYMENT", "{}", true, false));
            assertEquals(1, audit.getSoftFailures().size());
            assertTrue(producer.events.isEmpty());
        }
    }

    @Test
    void shouldThrowForFatalFailure() {
        InMemoryExceptionAuditService audit = new InMemoryExceptionAuditService();
        CapturingOutputProducer producer = new CapturingOutputProducer();
        BusinessProcessorService businessProcessor = new DefaultBusinessProcessorService(audit, producer, event -> event, new SimpleMeterRegistry());
        var breaker = TestBreaker.closed();

        Topology topology = buildTopology(businessProcessor, breaker);
        try (TopologyTestDriver driver = new TopologyTestDriver(topology, properties())) {
            TestInputTopic<String, InputEvent> input = driver.createInputTopic(
                    "payments.input",
                    Serdes.String().serializer(),
                    new JsonSerializer<InputEvent>()
            );

            assertThrows(RuntimeException.class, () ->
                    input.pipeInput("k1", new InputEvent("e1", "c1", "cid", "PAYMENT", "{}", false, true)));
        }
    }

    private Topology buildTopology(BusinessProcessorService businessProcessor, TestBreaker breaker) {
        StreamsBuilder builder = new StreamsBuilder();
        JsonSerde<InputEvent> serde = new JsonSerde<>(InputEvent.class);
        builder.stream("payments.input", Consumed.with(Serdes.String(), serde))
                .process(() -> new org.apache.kafka.streams.processor.AbstractProcessor<>() {
                    @Override
                    public void process(String key, InputEvent value) {
                        if (!breaker.tryAcquirePermission()) {
                            throw new IllegalStateException("breaker denied");
                        }
                        breaker.record(businessProcessor.process(
                                "payments-stream", context().topic(), context().partition(), context().offset(), key, value));
                    }
                });
        return builder.build();
    }

    private Properties properties() {
        Properties props = new Properties();
        props.put(StreamsConfig.APPLICATION_ID_CONFIG, "test-app");
        props.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "dummy:9092");
        props.put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, Serdes.StringSerde.class.getName());
        props.put(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG, JsonSerde.class.getName());
        return props;
    }

    private static class CapturingOutputProducer implements OutputProducerService {
        List<OutputEvent> events = new ArrayList<>();
        @Override
        public void send(OutputEvent event) {
            events.add(event);
        }
    }

    private static class TestBreaker {
        static TestBreaker closed() { return new TestBreaker(); }
        boolean tryAcquirePermission() { return true; }
        void record(Object ignored) { }
    }
}

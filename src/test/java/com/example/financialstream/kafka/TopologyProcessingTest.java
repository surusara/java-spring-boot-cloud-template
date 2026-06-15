package com.example.financialstream.kafka;

import com.example.financialstream.model.InputEvent;
import com.example.financialstream.model.OutputEvent;
import com.example.financialstream.model.ProcessingResult;
import com.example.financialstream.service.BusinessProcessorService;
import com.example.financialstream.service.DefaultBusinessProcessorService;
import com.example.financialstream.service.InMemoryExceptionAuditService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.TestInputTopic;
import org.apache.kafka.streams.TestOutputTopic;
import org.apache.kafka.streams.Topology;
import org.apache.kafka.streams.TopologyTestDriver;
import org.apache.kafka.streams.kstream.Consumed;
import org.apache.kafka.streams.kstream.Produced;
import org.apache.kafka.streams.processor.api.Processor;
import org.apache.kafka.streams.processor.api.ProcessorContext;
import org.apache.kafka.streams.processor.api.ProcessorSupplier;
import org.apache.kafka.streams.processor.api.Record;
import org.apache.kafka.streams.processor.api.RecordMetadata;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.support.serializer.JsonSerde;
import org.springframework.kafka.support.serializer.JsonSerializer;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

class TopologyProcessingTest {

    private static final String INPUT_TOPIC = "payments.input";
    private static final String OUTPUT_TOPIC = "payments.output";

    @Test
    void shouldProduceOutputForHealthyMessage() {
        InMemoryExceptionAuditService audit = new InMemoryExceptionAuditService();
        BusinessProcessorService businessProcessor = new DefaultBusinessProcessorService(audit, event -> event, new SimpleMeterRegistry());
        var breaker = TestBreaker.closed();

        Topology topology = buildTopology(businessProcessor, breaker);
        try (TopologyTestDriver driver = new TopologyTestDriver(topology, properties())) {
            TestInputTopic<String, InputEvent> input = driver.createInputTopic(
                    INPUT_TOPIC,
                    Serdes.String().serializer(),
                    new JsonSerializer<InputEvent>()
            );
            TestOutputTopic<String, OutputEvent> output = driver.createOutputTopic(
                    OUTPUT_TOPIC,
                    Serdes.String().deserializer(),
                    new JsonDeserializer<>(OutputEvent.class).ignoreTypeHeaders()
            );

            input.pipeInput("k1", new InputEvent("e1", "c1", "cid", "PAYMENT", "{}", false, false));
            var forwarded = output.readValuesToList();
            assertEquals(1, forwarded.size());
            assertEquals("e1", forwarded.get(0).eventId());
            assertTrue(audit.getSoftFailures().isEmpty());
        }
    }

    @Test
    void shouldLogSoftFailureAndContinue() {
        InMemoryExceptionAuditService audit = new InMemoryExceptionAuditService();
        BusinessProcessorService businessProcessor = new DefaultBusinessProcessorService(audit, event -> event, new SimpleMeterRegistry());
        var breaker = TestBreaker.closed();

        Topology topology = buildTopology(businessProcessor, breaker);
        try (TopologyTestDriver driver = new TopologyTestDriver(topology, properties())) {
            TestInputTopic<String, InputEvent> input = driver.createInputTopic(
                    INPUT_TOPIC,
                    Serdes.String().serializer(),
                    new JsonSerializer<InputEvent>()
            );
            TestOutputTopic<String, OutputEvent> output = driver.createOutputTopic(
                    OUTPUT_TOPIC,
                    Serdes.String().deserializer(),
                    new JsonDeserializer<>(OutputEvent.class).ignoreTypeHeaders()
            );

            input.pipeInput("k1", new InputEvent("e1", "c1", "cid", "PAYMENT", "{}", true, false));
            assertEquals(1, audit.getSoftFailures().size());
            assertTrue(output.isEmpty(), "Soft failure must not forward downstream");
        }
    }

    @Test
    void shouldThrowForFatalFailure() {
        InMemoryExceptionAuditService audit = new InMemoryExceptionAuditService();
        BusinessProcessorService businessProcessor = new DefaultBusinessProcessorService(audit, event -> event, new SimpleMeterRegistry());
        var breaker = TestBreaker.closed();

        Topology topology = buildTopology(businessProcessor, breaker);
        try (TopologyTestDriver driver = new TopologyTestDriver(topology, properties())) {
            TestInputTopic<String, InputEvent> input = driver.createInputTopic(
                    INPUT_TOPIC,
                    Serdes.String().serializer(),
                    new JsonSerializer<InputEvent>()
            );

            assertThrows(RuntimeException.class, () ->
                    input.pipeInput("k1", new InputEvent("e1", "c1", "cid", "PAYMENT", "{}", false, true)));
        }
    }

    private Topology buildTopology(BusinessProcessorService businessProcessor, TestBreaker breaker) {
        StreamsBuilder builder = new StreamsBuilder();
        JsonSerde<InputEvent> inputSerde = new JsonSerde<>(InputEvent.class);
        JsonSerde<OutputEvent> outputSerde = new JsonSerde<>(OutputEvent.class);

        ProcessorSupplier<String, InputEvent, String, OutputEvent> supplier = () -> new Processor<>() {
            private ProcessorContext<String, OutputEvent> ctx;

            @Override
            public void init(ProcessorContext<String, OutputEvent> context) {
                this.ctx = context;
            }

            @Override
            public void process(Record<String, InputEvent> record) {
                if (!breaker.tryAcquirePermission()) {
                    throw new IllegalStateException("breaker denied");
                }
                String topic = ctx.recordMetadata().map(RecordMetadata::topic).orElse(INPUT_TOPIC);
                int partition = ctx.recordMetadata().map(RecordMetadata::partition).orElse(0);
                long offset = ctx.recordMetadata().map(RecordMetadata::offset).orElse(0L);
                ProcessingResult result = businessProcessor.process(
                        "payments-stream", topic, partition, offset, record.key(), record.value());
                breaker.record(result);
                if (result.output() != null) {
                    ctx.forward(record.withValue(result.output()));
                }
            }
        };

        builder.stream(INPUT_TOPIC, Consumed.with(Serdes.String(), inputSerde))
                .process(supplier)
                .to(OUTPUT_TOPIC, Produced.with(Serdes.String(), outputSerde));
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

    private static class TestBreaker {
        static TestBreaker closed() { return new TestBreaker(); }
        boolean tryAcquirePermission() { return true; }
        void record(Object ignored) { }
    }
}

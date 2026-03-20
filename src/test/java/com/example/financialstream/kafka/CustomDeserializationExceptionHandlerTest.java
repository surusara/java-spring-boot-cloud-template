package com.example.financialstream.kafka;

import com.example.financialstream.service.InMemoryExceptionAuditService;
import com.example.financialstream.util.ApplicationContextProvider;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.streams.errors.DeserializationExceptionHandler;
import org.apache.kafka.streams.processor.ProcessorContext;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticApplicationContext;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CustomDeserializationExceptionHandlerTest {

    @Test
    void shouldContinueAndLog() throws Exception {
        StaticApplicationContext context = new StaticApplicationContext();
        context.registerSingleton("auditService", InMemoryExceptionAuditService.class);
        context.registerSingleton("applicationContextProvider", ApplicationContextProvider.class);
        context.refresh();

        var provider = context.getBean(ApplicationContextProvider.class);
        provider.setApplicationContext(context);
        var audit = context.getBean(InMemoryExceptionAuditService.class);

        CustomDeserializationExceptionHandler handler = new CustomDeserializationExceptionHandler();
        ConsumerRecord<byte[], byte[]> record = new ConsumerRecord<>("topic", 0, 1L, "k".getBytes(), "bad".getBytes());

        // Explicitly call the ProcessorContext version of handle()
        var response = handler.handle((ProcessorContext) null, record, new RuntimeException("invalid json"));
        assertEquals(DeserializationExceptionHandler.DeserializationHandlerResponse.CONTINUE, response);
        assertEquals(1, audit.getDeserializationEvents().size());
    }
}

package com.example.financialstream.service;

import com.example.financialstream.model.InputEvent;
import com.example.financialstream.model.OutputEvent;
import com.example.financialstream.model.ProcessingStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DefaultBusinessProcessorServiceTest {

    @Test
    void shouldReturnSuccessForHealthyEvent() {
        ExceptionAuditService auditService = mock(ExceptionAuditService.class);
        OutputProducerService outputProducerService = mock(OutputProducerService.class);
        CsfleCryptoService cryptoService = input -> input;

        DefaultBusinessProcessorService service = new DefaultBusinessProcessorService(auditService, outputProducerService, cryptoService, new SimpleMeterRegistry());
        var result = service.process("payments-stream", "input", 0, 1L, "k1",
                new InputEvent("e1", "c1", "cid-1", "PAYMENT", "{}", false, false));

        assertEquals(ProcessingStatus.SUCCESS, result.status());
        verify(outputProducerService, times(1)).send(any(OutputEvent.class));
        verifyNoInteractions(auditService);
    }

    @Test
    void shouldReturnSoftFailureForBusinessException() {
        ExceptionAuditService auditService = mock(ExceptionAuditService.class);
        OutputProducerService outputProducerService = mock(OutputProducerService.class);
        CsfleCryptoService cryptoService = input -> input;

        DefaultBusinessProcessorService service = new DefaultBusinessProcessorService(auditService, outputProducerService, cryptoService, new SimpleMeterRegistry());
        var result = service.process("payments-stream", "input", 0, 1L, "k1",
                new InputEvent("e1", "c1", "cid-1", "PAYMENT", "{}", true, false));

        assertEquals(ProcessingStatus.SUCCESS_WITH_EXCEPTION_LOGGED, result.status());
        verify(auditService, times(1)).logSoftFailure(any());
        verifyNoInteractions(outputProducerService);
    }

    @Test
    void shouldThrowFatalForHardFailure() {
        ExceptionAuditService auditService = mock(ExceptionAuditService.class);
        OutputProducerService outputProducerService = mock(OutputProducerService.class);
        CsfleCryptoService cryptoService = input -> input;

        DefaultBusinessProcessorService service = new DefaultBusinessProcessorService(auditService, outputProducerService, cryptoService, new SimpleMeterRegistry());

        assertThrows(IllegalStateException.class, () -> service.process(
                "payments-stream", "input", 0, 1L, "k1",
                new InputEvent("e1", "c1", "cid-1", "PAYMENT", "{}", false, true)
        ));
    }
}

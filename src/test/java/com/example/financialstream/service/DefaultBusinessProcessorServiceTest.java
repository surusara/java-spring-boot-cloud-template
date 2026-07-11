package com.example.financialstream.service;

import com.example.financialstream.model.InputEvent;
import com.example.financialstream.model.ProcessingStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DefaultBusinessProcessorServiceTest {

    @Test
    void shouldReturnSuccessForHealthyEvent() {
        ExceptionAuditService auditService = mock(ExceptionAuditService.class);
        CsfleCryptoService cryptoService = input -> input;

        DefaultBusinessProcessorService service = new DefaultBusinessProcessorService(auditService, cryptoService, new SimpleMeterRegistry());
        var result = service.process("payments-stream", "input", 0, 1L, "k1",
                new InputEvent("e1", "c1", "cid-1", "PAYMENT", "{}", false, false));

        assertEquals(ProcessingStatus.SUCCESS, result.status());
        // The service no longer calls an external producer. It returns the OutputEvent in
        // the ProcessingResult and the Streams processor forwards it via ctx.forward(...).
        assertNotNull(result.output(), "Healthy event must produce a downstream OutputEvent");
        assertEquals("e1", result.output().eventId());
        verifyNoInteractions(auditService);
    }

    @Test
    void shouldReturnSoftFailureForBusinessException() {
        ExceptionAuditService auditService = mock(ExceptionAuditService.class);
        CsfleCryptoService cryptoService = input -> input;

        DefaultBusinessProcessorService service = new DefaultBusinessProcessorService(auditService, cryptoService, new SimpleMeterRegistry());
        var result = service.process("payments-stream", "input", 0, 1L, "k1",
                new InputEvent("e1", "c1", "cid-1", "PAYMENT", "{}", true, false));

        assertEquals(ProcessingStatus.SUCCESS_WITH_EXCEPTION_LOGGED, result.status());
        assertNull(result.output(), "Soft failure must NOT produce a downstream OutputEvent");
        verify(auditService, times(1)).logSoftFailure(any());
    }

    @Test
    void shouldAuditUnexpectedErrorAsInternalErrorSoftFailure() {
        ExceptionAuditService auditService = mock(ExceptionAuditService.class);
        CsfleCryptoService cryptoService = input -> {
            throw new RuntimeException("boom");
        };

        DefaultBusinessProcessorService service = new DefaultBusinessProcessorService(auditService, cryptoService, new SimpleMeterRegistry());
        var result = service.process("payments-stream", "input", 0, 1L, "k1",
                new InputEvent("e1", "c1", "cid-1", "PAYMENT", "{}", false, false));

        // Unexpected (non-IllegalState) errors are converted to a logged soft-failure, but must be
        // persisted to the audit store so the failure is not silently swallowed.
        assertEquals(ProcessingStatus.SUCCESS_WITH_EXCEPTION_LOGGED, result.status());
        assertEquals("INTERNAL_ERROR", result.code());
        assertNull(result.output(), "Unexpected error must NOT produce a downstream OutputEvent");
        verify(auditService, times(1)).logSoftFailure(any());
    }

    @Test
    void shouldThrowFatalForHardFailure() {
        ExceptionAuditService auditService = mock(ExceptionAuditService.class);
        CsfleCryptoService cryptoService = input -> input;

        DefaultBusinessProcessorService service = new DefaultBusinessProcessorService(auditService, cryptoService, new SimpleMeterRegistry());

        assertThrows(IllegalStateException.class, () -> service.process(
                "payments-stream", "input", 0, 1L, "k1",
                new InputEvent("e1", "c1", "cid-1", "PAYMENT", "{}", false, true)
        ));
    }
}

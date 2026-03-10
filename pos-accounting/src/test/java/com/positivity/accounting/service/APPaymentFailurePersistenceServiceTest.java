package com.positivity.accounting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.positivity.accounting.internal.entity.APPayment;
import com.positivity.accounting.internal.enums.APPaymentStatus;
import com.positivity.accounting.internal.repository.APPaymentRepository;
import com.positivity.accounting.internal.service.APPaymentFailurePersistenceService;

/**
 * Unit tests for APPaymentFailurePersistenceService.
 * Verifies gateway failure persistence in a separate transaction.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("APPaymentFailurePersistenceService Unit Tests")
class APPaymentFailurePersistenceServiceTest {

    @Mock
    private APPaymentRepository paymentRepository;

    @InjectMocks
    private APPaymentFailurePersistenceService service;

    private UUID testPaymentId;

    @BeforeEach
    void setUp() {
        testPaymentId = UUID.fromString("00000000-0000-0000-0000-000000000001");
    }

    @Test
    @DisplayName("persistGatewayFailure should set GATEWAY_FAILED status and save when payment exists")
    void persistGatewayFailure_PaymentFound_SetsStatusAndSaves() {
        APPayment payment = new APPayment();
        payment.setPaymentId(testPaymentId);
        payment.setStatus(APPaymentStatus.GATEWAY_PENDING);

        when(paymentRepository.findById(testPaymentId)).thenReturn(Optional.of(payment));
        when(paymentRepository.save(payment)).thenReturn(payment);

        service.persistGatewayFailure(testPaymentId, "Gateway timeout");

        verify(paymentRepository).findById(testPaymentId);
        verify(paymentRepository).save(payment);
        // Payment status should have been set to GATEWAY_FAILED before save
        assertThat(payment.getStatus()).isEqualTo(APPaymentStatus.GATEWAY_FAILED);
        assertThat(payment.getGatewayResponse()).isEqualTo("Gateway timeout");
    }

    @Test
    @DisplayName("persistGatewayFailure should log warning and not save when payment not found")
    void persistGatewayFailure_PaymentNotFound_LogsWarning() {
        when(paymentRepository.findById(testPaymentId)).thenReturn(Optional.empty());

        // Should not throw even when payment not found
        service.persistGatewayFailure(testPaymentId, "Gateway timeout");

        verify(paymentRepository).findById(testPaymentId);
        verify(paymentRepository, never()).save(any());
    }

    @Test
    @DisplayName("persistGatewayFailure should catch exception and not rethrow")
    void persistGatewayFailure_ExceptionThrown_CaughtAndSilenced() {
        when(paymentRepository.findById(testPaymentId))
                .thenThrow(new RuntimeException("DB connection failed"));

        // Should not throw even when exception occurs inside
        service.persistGatewayFailure(testPaymentId, "Gateway timeout");

        verify(paymentRepository).findById(testPaymentId);
    }

    @Test
    @DisplayName("persistGatewayFailure should catch save exception and not rethrow")
    void persistGatewayFailure_SaveThrows_CaughtAndSilenced() {
        APPayment payment = new APPayment();
        payment.setPaymentId(testPaymentId);

        when(paymentRepository.findById(testPaymentId)).thenReturn(Optional.of(payment));
        when(paymentRepository.save(any(APPayment.class))).thenThrow(new RuntimeException("Save failed"));

        // Should not throw even when save fails
        service.persistGatewayFailure(testPaymentId, "Gateway error");

        verify(paymentRepository).findById(testPaymentId);
        verify(paymentRepository).save(any(APPayment.class));
    }
}

package com.positivity.warranty.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.positivity.warranty.internal.client.InvoiceClient;
import com.positivity.warranty.internal.client.WorkorderClient;
import com.positivity.warranty.internal.config.JpaConfig;
import com.positivity.warranty.internal.dto.SettlementCreateRequest;
import com.positivity.warranty.internal.entity.ClaimSettlement;
import com.positivity.warranty.internal.entity.WarrantyClaim;
import com.positivity.warranty.internal.enums.ClaimStatus;
import com.positivity.warranty.internal.enums.ClaimType;
import com.positivity.warranty.internal.enums.SettlementStatus;
import com.positivity.warranty.internal.enums.SettlementType;
import com.positivity.warranty.internal.exception.WarrantyIntegrationException;
import com.positivity.warranty.internal.repository.ClaimSettlementRepository;
import com.positivity.warranty.internal.repository.WarrantyClaimRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Proves the PRD §3.6 durability contract with real transaction semantics: when the pos-invoice
 * write fails, the settlement row must survive as {@code FAILED} even though
 * {@link WarrantyIntegrationException} propagates out of
 * {@link SettlementServiceImpl#create} — i.e. the {@code noRollbackFor} on the service
 * transaction actually commits. Mockito-only tests cannot observe a rollback, so this runs the
 * real Spring bean with transaction proxying against the Flyway baseline on H2
 * ({@code WarrantyPersistenceTest} recipe) and — critically — outside any test-managed
 * transaction ({@code NOT_SUPPORTED}), so {@code create()} opens, and commits, its own
 * transaction. Removing {@code noRollbackFor} (or changing the exception type) turns these
 * tests red.
 */
@DataJpaTest(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:pos_warranty_settlement_tx;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
            "spring.jpa.hibernate.ddl-auto=validate"
        })
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaConfig.class, SettlementServiceImpl.class, SettlementFailedPathPersistenceTest.ClockConfig.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class SettlementFailedPathPersistenceTest {

    @TestConfiguration
    static class ClockConfig {
        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-07-15T12:00:00Z"), ZoneOffset.UTC);
        }
    }

    private static final UUID INVOICE_ID = UUID.fromString("018f0000-0000-7000-8000-000000000201");
    private static final UUID PAYMENT_ID = UUID.fromString("018f0000-0000-7000-8000-000000000202");

    @Autowired
    private SettlementService settlementService;

    @Autowired
    private WarrantyClaimRepository claimRepository;

    @Autowired
    private ClaimSettlementRepository settlementRepository;

    @MockitoBean
    private InvoiceClient invoiceClient;

    @MockitoBean
    private WorkorderClient workorderClient;

    @MockitoBean
    private ClaimSnapshotPublisher claimSnapshotPublisher;

    private UUID seedApprovedClaim(String claimCode) {
        // save() runs in its own committed transaction (no test-managed transaction here).
        WarrantyClaim claim = claimRepository.save(WarrantyClaim.builder()
                .claimCode(claimCode)
                .claimType(ClaimType.MANUFACTURER_DEFECT)
                .status(ClaimStatus.APPROVED)
                .customerId(UUID.randomUUID())
                .vehicleId(UUID.randomUUID())
                .failureDescription("sidewall separation")
                .build());
        return claim.getId();
    }

    @Test
    void invoiceCreditFailureCommitsExactlyOneFailedSettlementRowDespiteThePropagatedException() {
        UUID claimId = seedApprovedClaim("WC-2026-000901");
        when(invoiceClient.createAdjustment(any(), any(), any(), any(), any()))
                .thenThrow(new WarrantyIntegrationException("pos-invoice returned 503"));
        SettlementCreateRequest request = new SettlementCreateRequest(
                SettlementType.INVOICE_CREDIT,
                new BigDecimal("84.2100"),
                new BigDecimal("15.0000"),
                null,
                INVOICE_ID,
                null,
                null);

        assertThatThrownBy(() -> settlementService.create(claimId, request))
                .isInstanceOf(WarrantyIntegrationException.class);

        // Fresh read in a new transaction: the FAILED row must have COMMITTED (noRollbackFor).
        List<ClaimSettlement> settlements = settlementRepository.findByClaimId(claimId);
        assertThat(settlements).hasSize(1);
        assertThat(settlements.getFirst().getStatus()).isEqualTo(SettlementStatus.FAILED);
        assertThat(settlements.getFirst().getInvoiceId()).isEqualTo(INVOICE_ID);
        assertThat(settlements.getFirst().getInvoiceAdjustmentId()).isNull();
        // The claim itself must not have settled.
        assertThat(claimRepository.findById(claimId).orElseThrow().getStatus()).isEqualTo(ClaimStatus.APPROVED);
    }

    @Test
    void refundFailureCommitsExactlyOneFailedSettlementRowDespiteThePropagatedException() {
        UUID claimId = seedApprovedClaim("WC-2026-000902");
        when(invoiceClient.createRefund(any(), any(), any(), any(), any()))
                .thenThrow(new WarrantyIntegrationException("refund rejected"));
        SettlementCreateRequest request = new SettlementCreateRequest(
                SettlementType.REFUND, new BigDecimal("50.0000"), BigDecimal.ZERO, null, INVOICE_ID, PAYMENT_ID, null);

        assertThatThrownBy(() -> settlementService.create(claimId, request))
                .isInstanceOf(WarrantyIntegrationException.class);

        List<ClaimSettlement> settlements = settlementRepository.findByClaimId(claimId);
        assertThat(settlements).hasSize(1);
        assertThat(settlements.getFirst().getStatus()).isEqualTo(SettlementStatus.FAILED);
        assertThat(settlements.getFirst().getRefundRecordId()).isNull();
        assertThat(claimRepository.findById(claimId).orElseThrow().getStatus()).isEqualTo(ClaimStatus.APPROVED);
    }

    @Test
    void successfulSettlementAlsoCommitsProvingTheHarnessObservesRealTransactions() {
        UUID claimId = seedApprovedClaim("WC-2026-000903");
        when(invoiceClient.createAdjustment(any(), any(), any(), any(), any()))
                .thenReturn(java.util.Optional.of(UUID.randomUUID()));
        SettlementCreateRequest request = new SettlementCreateRequest(
                SettlementType.INVOICE_CREDIT,
                new BigDecimal("10.0000"),
                BigDecimal.ZERO,
                null,
                INVOICE_ID,
                null,
                null);

        settlementService.create(claimId, request);

        List<ClaimSettlement> settlements = settlementRepository.findByClaimId(claimId);
        assertThat(settlements).hasSize(1);
        assertThat(settlements.getFirst().getStatus()).isEqualTo(SettlementStatus.COMPLETED);
        assertThat(claimRepository.findById(claimId).orElseThrow().getStatus()).isEqualTo(ClaimStatus.SETTLED);
    }
}

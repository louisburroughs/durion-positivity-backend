package com.positivity.workorder.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.positivity.shared.enums.InvoiceDeliveryMethod;
import com.positivity.workorder.internal.entity.ExtBillingRulesReplica;
import com.positivity.workorder.internal.repository.ExtBillingRulesReplicaRepository;
import com.positivity.workorder.internal.service.BillingRulesClientServiceImpl;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BillingRulesClientServiceImplTest {

    private final ExtBillingRulesReplicaRepository repository = mock(ExtBillingRulesReplicaRepository.class);

    private BillingRulesClientServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new BillingRulesClientServiceImpl(repository);
    }

    private ExtBillingRulesReplica replica(boolean poRequired) {
        return ExtBillingRulesReplica.builder()
                .partyId("party-001")
                .purchaseOrderRequired(poRequired)
                .paymentTermsCode("NET_30")
                .invoiceDeliveryMethod("EMAIL")
                .invoiceGroupingStrategy("PER_WORKORDER")
                .aggregateVersion(3L)
                .updatedAt(Instant.parse("2026-07-14T12:00:00Z"))
                .build();
    }

    @Test
    @DisplayName("Maps the replica row into the billing-rules DTO")
    void mapsReplicaToDto() {
        when(repository.findById("party-001")).thenReturn(Optional.of(replica(true)));

        var dto = service.getBillingRules("party-001").orElseThrow();

        assertThat(dto.isPurchaseOrderRequired()).isTrue();
        assertThat(dto.getPaymentTermsCode()).isEqualTo("NET_30");
        assertThat(dto.getInvoiceDeliveryMethod()).isEqualTo(InvoiceDeliveryMethod.EMAIL);
    }

    @Test
    @DisplayName("PO enforcement reads the replica flag")
    void poRequiredFromReplica() {
        when(repository.findById("party-001")).thenReturn(Optional.of(replica(true)));

        assertThat(service.isPurchaseOrderRequired("party-001")).isTrue();
    }

    @Test
    @DisplayName("Missing replica row reads as no rules — PO not required (fail-safe)")
    void missingRowIsFailSafe() {
        when(repository.findById("party-404")).thenReturn(Optional.empty());

        assertThat(service.getBillingRules("party-404")).isEmpty();
        assertThat(service.isPurchaseOrderRequired("party-404")).isFalse();
    }

    @Test
    @DisplayName("Null or blank partyId never requires a PO")
    void nullPartyIdIsFailSafe() {
        assertThat(service.isPurchaseOrderRequired(null)).isFalse();
        assertThat(service.isPurchaseOrderRequired(" ")).isFalse();
    }

    @Test
    @DisplayName("Unknown enum names from newer producers map to null instead of throwing")
    void unknownEnumNamesMapToNull() {
        ExtBillingRulesReplica row = replica(false);
        row.setInvoiceDeliveryMethod("CARRIER_PIGEON");
        when(repository.findById("party-001")).thenReturn(Optional.of(row));

        var dto = service.getBillingRules("party-001").orElseThrow();

        assertThat(dto.getInvoiceDeliveryMethod()).isNull();
    }
}

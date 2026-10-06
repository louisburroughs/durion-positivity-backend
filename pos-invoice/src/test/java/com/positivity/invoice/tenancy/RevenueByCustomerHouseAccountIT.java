package com.positivity.invoice.tenancy;

import static com.positivity.tenancy.testing.TenantTestSupport.TENANT_A;
import static com.positivity.tenancy.testing.TenantTestSupport.asTenant;
import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.domainevents.customer.CustomerPartyUpdatedV1;
import com.positivity.invoice.internal.entity.ExtCustomerPartyReplica;
import com.positivity.invoice.internal.entity.Invoice;
import com.positivity.invoice.internal.enums.InvoiceStatus;
import com.positivity.invoice.internal.repository.ExtCustomerPartyReplicaRepository;
import com.positivity.invoice.internal.repository.InvoiceRepository;
import com.positivity.invoice.internal.repository.InvoiceRepository.RevenueByCustomerProjection;
import com.positivity.tenancy.TenantContext;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;

/**
 * CAP:550 S9 AC7 on PostgreSQL (spec §4.4 item 2, AW12): the revenue-by-customer JPQL's
 * {@code NOT EXISTS … CAST(r.partyId AS string) = i.partyId} correlation between the
 * {@code invoices.customer_id} varchar and the {@code ext_customer_party.party_id} uuid is proven
 * against the real dialect, Flyway chain (V3 column) and row-level security — the H2 slice test
 * ({@code InvoiceAnalyticsRepositoryTest}) runs the same query under an emulation.
 */
@DisplayName("Revenue-by-customer excludes house-account parties on Postgres (CAP:550 S9)")
class RevenueByCustomerHouseAccountIT extends PostgresTenancyTestBase {

    @Autowired
    private InvoiceRepository invoices;

    @Autowired
    private ExtCustomerPartyReplicaRepository parties;

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private static ExtCustomerPartyReplica party(UUID partyId, String displayName, String houseAccount) {
        return ExtCustomerPartyReplica.builder()
                .partyId(partyId)
                .partyType("COMMERCIAL")
                .displayName(displayName)
                .status("ACTIVE")
                .houseAccount(houseAccount)
                .aggregateVersion(1L)
                .updatedAt(Instant.now())
                .build();
    }

    private static Invoice invoice(UUID partyId, InvoiceStatus status, String total) {
        Invoice invoice = new Invoice();
        invoice.setInvoiceNumber("INV-S9-" + UUID.randomUUID().toString().substring(0, 12));
        invoice.setPartyId(partyId.toString());
        invoice.setStatus(status);
        invoice.setSubtotal(new BigDecimal(total));
        invoice.setTax(BigDecimal.ZERO);
        invoice.setTotal(new BigDecimal(total));
        invoice.setAdjustmentsAmount(BigDecimal.ZERO);
        // invoices_finalized_due_date_check: a revenue-recognised row carries its frozen aging facts.
        invoice.setFinalizedAt(Instant.now());
        invoice.setFinalizedBy("s9-it");
        invoice.setPaymentTermsCode("DUE_ON_RECEIPT");
        invoice.setDueDate(LocalDate.now(ZoneOffset.UTC));
        return invoice;
    }

    @Test
    void cashHouseAccountIsLeftOutAndNamedCustomersRank() {
        UUID cashParty = UUID.randomUUID();
        UUID acme = UUID.randomUUID();
        UUID lookalike = UUID.randomUUID();
        Instant start = Instant.now().minus(Duration.ofMinutes(5));

        asTenant(TENANT_A, () -> {
            parties.saveAndFlush(party(cashParty, "Walk-in customer", CustomerPartyUpdatedV1.HOUSE_ACCOUNT_CASH_SALE));
            parties.saveAndFlush(party(acme, "Acme", null));
            // Same display name as the CASH account, but no flag: the exclusion keys on the flag only.
            parties.saveAndFlush(party(lookalike, "Walk-in customer", null));
            invoices.saveAndFlush(invoice(cashParty, InvoiceStatus.FINALIZED, "5000.00"));
            invoices.saveAndFlush(invoice(cashParty, InvoiceStatus.POSTED, "5000.00"));
            invoices.saveAndFlush(invoice(acme, InvoiceStatus.FINALIZED, "100.00"));
            invoices.saveAndFlush(invoice(lookalike, InvoiceStatus.POSTED, "10.00"));
        });

        List<RevenueByCustomerProjection> rows = asTenant(
                TENANT_A,
                () -> invoices.revenueByCustomer(
                        start,
                        Instant.now().plus(Duration.ofMinutes(5)),
                        EnumSet.of(InvoiceStatus.FINALIZED, InvoiceStatus.POSTED),
                        PageRequest.of(0, 10)));

        assertThat(rows)
                .extracting(RevenueByCustomerProjection::getCustomerId)
                .doesNotContain(cashParty.toString())
                .containsSubsequence(acme.toString(), lookalike.toString());
        assertThat(rows.stream()
                        .filter(row -> row.getCustomerId().equals(acme.toString()))
                        .findFirst()
                        .orElseThrow()
                        .getRevenue())
                .isEqualByComparingTo("100.00");
    }
}

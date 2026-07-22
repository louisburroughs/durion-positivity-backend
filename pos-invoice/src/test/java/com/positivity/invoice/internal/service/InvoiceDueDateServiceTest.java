package com.positivity.invoice.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.positivity.invoice.internal.entity.BillingRules;
import com.positivity.invoice.internal.entity.ExtCustomerPartyReplica;
import com.positivity.invoice.internal.entity.ExtLocationReplica;
import com.positivity.invoice.internal.entity.Invoice;
import com.positivity.invoice.internal.enums.PaymentTerms;
import com.positivity.invoice.internal.repository.BillingRulesRepository;
import com.positivity.invoice.internal.repository.ExtCustomerPartyReplicaRepository;
import com.positivity.invoice.internal.repository.ExtLocationReplicaRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Unit tests for {@link InvoiceDueDateService} — the #993 due-date policy: individuals due on
 * receipt, commercial parties by their {@code BillingRules} terms (else the global default),
 * anchored on the finalization business date in the location's timezone.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class InvoiceDueDateServiceTest {

    private static final UUID PARTY_ID = UUID.fromString("01980a58-0000-7000-8000-00000000000a");
    private static final UUID LOCATION_ID = UUID.fromString("01980a58-0000-7000-8000-00000000000b");
    // 2026-07-21T03:30Z is still 2026-07-20 in America/Chicago (UTC-5 in July).
    private static final Instant FINALIZED_AT = Instant.parse("2026-07-21T03:30:00Z");

    @Mock
    private ExtCustomerPartyReplicaRepository partyRepository;

    @Mock
    private BillingRulesRepository billingRulesRepository;

    @Mock
    private ExtLocationReplicaRepository locationRepository;

    private InvoiceDueDateService service;

    @BeforeEach
    void setUp() {
        service = new InvoiceDueDateService(partyRepository, billingRulesRepository, locationRepository, "NET_30");
        when(partyRepository.findById(PARTY_ID)).thenReturn(Optional.empty());
        when(locationRepository.findById(LOCATION_ID)).thenReturn(Optional.empty());
    }

    private Invoice invoice(String partyId, UUID locationId) {
        Invoice invoice = new Invoice();
        invoice.setPartyId(partyId);
        invoice.setLocationId(locationId);
        return invoice;
    }

    private void stubParty(String partyType) {
        ExtCustomerPartyReplica replica = ExtCustomerPartyReplica.builder()
                .partyId(PARTY_ID)
                .partyType(partyType)
                .status("ACTIVE")
                .build();
        when(partyRepository.findById(PARTY_ID)).thenReturn(Optional.of(replica));
    }

    private void stubBillingRules(String termsCode) {
        BillingRules rules = new BillingRules();
        rules.setPartyId(PARTY_ID.toString());
        rules.setPaymentTermsCode(termsCode);
        when(billingRulesRepository.findByPartyId(PARTY_ID.toString())).thenReturn(Optional.of(rules));
    }

    @Test
    @DisplayName("Individual (PERSON) party is due on receipt: dueDate == finalization date")
    void individualPartyDueOnReceipt() {
        stubParty("PERSON");

        InvoiceDueDateService.DueTerms terms = service.resolve(invoice(PARTY_ID.toString(), null), FINALIZED_AT);

        assertThat(terms.paymentTerms()).isEqualTo(PaymentTerms.DUE_ON_RECEIPT);
        assertThat(terms.dueDate()).isEqualTo(LocalDate.parse("2026-07-21"));
    }

    @Test
    @DisplayName("Commercial party uses its BillingRules terms")
    void commercialPartyUsesBillingRulesTerms() {
        stubParty("COMMERCIAL");
        stubBillingRules("NET_10");

        InvoiceDueDateService.DueTerms terms = service.resolve(invoice(PARTY_ID.toString(), null), FINALIZED_AT);

        assertThat(terms.paymentTerms()).isEqualTo(PaymentTerms.NET_10);
        assertThat(terms.dueDate()).isEqualTo(LocalDate.parse("2026-07-31"));
    }

    @Test
    @DisplayName("Commercial party without BillingRules falls back to the global default (NET_30)")
    void commercialPartyWithoutRulesUsesGlobalDefault() {
        stubParty("COMMERCIAL");
        when(billingRulesRepository.findByPartyId(PARTY_ID.toString())).thenReturn(Optional.empty());

        InvoiceDueDateService.DueTerms terms = service.resolve(invoice(PARTY_ID.toString(), null), FINALIZED_AT);

        assertThat(terms.paymentTerms()).isEqualTo(PaymentTerms.NET_30);
        assertThat(terms.dueDate()).isEqualTo(LocalDate.parse("2026-08-20"));
    }

    @Test
    @DisplayName("Out-of-vocabulary stored terms degrade to the global default")
    void legacyFreeFormTermsFallBackToDefault() {
        stubParty("COMMERCIAL");
        stubBillingRules("2/10 Net 30");

        InvoiceDueDateService.DueTerms terms = service.resolve(invoice(PARTY_ID.toString(), null), FINALIZED_AT);

        assertThat(terms.paymentTerms()).isEqualTo(PaymentTerms.NET_30);
    }

    @Test
    @DisplayName("Unresolvable party type degrades to DUE_ON_RECEIPT (issue-date aging)")
    void unresolvablePartyDefaultsToDueOnReceipt() {
        InvoiceDueDateService.DueTerms missing = service.resolve(invoice(PARTY_ID.toString(), null), FINALIZED_AT);
        InvoiceDueDateService.DueTerms nonUuid = service.resolve(invoice("COMMERCIAL_DEFAULT", null), FINALIZED_AT);
        InvoiceDueDateService.DueTerms noParty = service.resolve(invoice(null, null), FINALIZED_AT);

        assertThat(missing.paymentTerms()).isEqualTo(PaymentTerms.DUE_ON_RECEIPT);
        assertThat(nonUuid.paymentTerms()).isEqualTo(PaymentTerms.DUE_ON_RECEIPT);
        assertThat(noParty.paymentTerms()).isEqualTo(PaymentTerms.DUE_ON_RECEIPT);
    }

    @Test
    @DisplayName("Business date anchors on the location's timezone, not UTC")
    void locationTimezoneDrivesBusinessDate() {
        stubParty("PERSON");
        when(locationRepository.findById(LOCATION_ID))
                .thenReturn(Optional.of(ExtLocationReplica.builder()
                        .locationId(LOCATION_ID)
                        .timezone("America/Chicago")
                        .build()));

        InvoiceDueDateService.DueTerms terms = service.resolve(invoice(PARTY_ID.toString(), LOCATION_ID), FINALIZED_AT);

        // 03:30Z on the 21st is still the 20th in Chicago.
        assertThat(terms.dueDate()).isEqualTo(LocalDate.parse("2026-07-20"));
    }

    @Test
    @DisplayName("Unknown or invalid replica timezone falls back to UTC")
    void invalidTimezoneFallsBackToUtc() {
        stubParty("PERSON");
        when(locationRepository.findById(LOCATION_ID))
                .thenReturn(Optional.of(ExtLocationReplica.builder()
                        .locationId(LOCATION_ID)
                        .timezone("Not/AZone")
                        .build()));

        InvoiceDueDateService.DueTerms terms = service.resolve(invoice(PARTY_ID.toString(), LOCATION_ID), FINALIZED_AT);

        assertThat(terms.dueDate()).isEqualTo(LocalDate.parse("2026-07-21"));
    }
}

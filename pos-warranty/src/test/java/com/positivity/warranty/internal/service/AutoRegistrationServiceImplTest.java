package com.positivity.warranty.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.positivity.domainevents.workorder.WorkorderUpdatedV1;
import com.positivity.warranty.internal.dto.RegistrationRequest;
import com.positivity.warranty.internal.entity.WarrantyPolicy;
import com.positivity.warranty.internal.enums.AppliesToType;
import com.positivity.warranty.internal.enums.CoverageType;
import com.positivity.warranty.internal.enums.ProrationMethod;
import com.positivity.warranty.internal.enums.RegistrationStatus;
import com.positivity.warranty.internal.repository.WarrantyPolicyRepository;
import com.positivity.warranty.internal.repository.WarrantyRegistrationRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Sale-driven auto-registration (issue #925): an invoiced workorder selling a product listed
 * on an opt-in PRODUCT_LIST policy creates exactly one ACTIVE registration per (policy,
 * invoice), with expiry computed from durationMonths; everything else is ignored.
 */
@ExtendWith(MockitoExtension.class)
class AutoRegistrationServiceImplTest {

    private static final UUID WORKORDER_ID = UUID.fromString("018f0000-0000-7000-8000-000000000701");
    private static final UUID CUSTOMER_ID = UUID.fromString("018f0000-0000-7000-8000-000000000702");
    private static final UUID INVOICE_ID = UUID.fromString("018f0000-0000-7000-8000-000000000703");
    private static final UUID POLICY_ID = UUID.fromString("018f0000-0000-7000-8000-000000000704");
    private static final UUID PRODUCT_A = UUID.fromString("018f0000-0000-7000-8000-00000000070a");
    private static final UUID PRODUCT_OTHER = UUID.fromString("018f0000-0000-7000-8000-00000000070b");
    private static final Instant UPDATED_AT = Instant.parse("2026-07-10T23:30:00Z");
    private static final LocalDate PURCHASE_DATE = LocalDate.of(2026, 7, 10);

    @Mock
    private WarrantyPolicyRepository policyRepository;

    @Mock
    private WarrantyRegistrationRepository registrationRepository;

    @Mock
    private RegistrationService registrationService;

    private AutoRegistrationServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new AutoRegistrationServiceImpl(
                policyRepository,
                registrationRepository,
                registrationService,
                Clock.fixed(Instant.parse("2026-07-15T12:00:00Z"), ZoneOffset.UTC));
    }

    private static WorkorderUpdatedV1 saleEvent(UUID invoiceId, UUID customerId, UUID... productIds) {
        List<WorkorderUpdatedV1.PartLine> parts = List.of(productIds).stream()
                .map(productId -> new WorkorderUpdatedV1.PartLine(UUID.randomUUID(), productId, BigDecimal.ONE))
                .toList();
        return new WorkorderUpdatedV1(
                WORKORDER_ID,
                "WO-2026-000042",
                "INVOICED",
                null,
                customerId,
                invoiceId,
                parts,
                Instant.parse("2026-07-01T09:00:00Z"),
                UPDATED_AT);
    }

    private static WarrantyPolicy autoRegisterPolicy(Integer durationMonths) {
        return WarrantyPolicy.builder()
                .id(POLICY_ID)
                .providerId(UUID.randomUUID())
                .name("Road hazard")
                .coverageType(CoverageType.ROAD_HAZARD)
                .appliesToType(AppliesToType.PRODUCT_LIST)
                .appliesToProductEntityIds(List.of(PRODUCT_A))
                .effectiveFrom(LocalDate.of(2026, 1, 1))
                .durationMonths(durationMonths)
                .prorationMethod(ProrationMethod.NONE)
                .autoRegister(true)
                .build();
    }

    private void stubCandidates(WarrantyPolicy... policies) {
        when(policyRepository.findByAutoRegisterTrueAndAppliesToType(AppliesToType.PRODUCT_LIST))
                .thenReturn(List.of(policies));
    }

    @Test
    void matchingPolicyCreatesRegistrationWithComputedExpiry() {
        stubCandidates(autoRegisterPolicy(36));
        when(registrationRepository.existsByPolicyIdAndSourceInvoiceId(POLICY_ID, INVOICE_ID))
                .thenReturn(false);

        int created = service.registerFromWorkorderSale(saleEvent(INVOICE_ID, CUSTOMER_ID, PRODUCT_A));

        assertThat(created).isEqualTo(1);
        ArgumentCaptor<RegistrationRequest> captor = ArgumentCaptor.captor();
        verify(registrationService).create(captor.capture());
        RegistrationRequest request = captor.getValue();
        assertThat(request.policyId()).isEqualTo(POLICY_ID);
        assertThat(request.customerId()).isEqualTo(CUSTOMER_ID);
        assertThat(request.sourceInvoiceId()).isEqualTo(INVOICE_ID);
        assertThat(request.sourceInvoiceLineId()).isNull();
        assertThat(request.purchaseDate()).isEqualTo(PURCHASE_DATE);
        assertThat(request.expiresAt()).isEqualTo(PURCHASE_DATE.plusMonths(36));
        assertThat(request.status()).isEqualTo(RegistrationStatus.ACTIVE);
    }

    @Test
    void unlimitedDurationLeavesExpiresAtNull() {
        stubCandidates(autoRegisterPolicy(null));
        when(registrationRepository.existsByPolicyIdAndSourceInvoiceId(POLICY_ID, INVOICE_ID))
                .thenReturn(false);

        service.registerFromWorkorderSale(saleEvent(INVOICE_ID, CUSTOMER_ID, PRODUCT_A));

        ArgumentCaptor<RegistrationRequest> captor = ArgumentCaptor.captor();
        verify(registrationService).create(captor.capture());
        assertThat(captor.getValue().expiresAt()).isNull();
    }

    @Test
    void replayOfTheSameSaleCreatesNoSecondRegistration() {
        stubCandidates(autoRegisterPolicy(36));
        when(registrationRepository.existsByPolicyIdAndSourceInvoiceId(POLICY_ID, INVOICE_ID))
                .thenReturn(true);

        int created = service.registerFromWorkorderSale(saleEvent(INVOICE_ID, CUSTOMER_ID, PRODUCT_A));

        assertThat(created).isZero();
        verify(registrationService, never()).create(any());
    }

    @Test
    void notInvoicedYetIsIgnored() {
        int created = service.registerFromWorkorderSale(saleEvent(null, CUSTOMER_ID, PRODUCT_A));

        assertThat(created).isZero();
        verifyNoInteractions(policyRepository, registrationRepository, registrationService);
    }

    @Test
    void missingCustomerIsIgnored() {
        int created = service.registerFromWorkorderSale(saleEvent(INVOICE_ID, null, PRODUCT_A));

        assertThat(created).isZero();
        verifyNoInteractions(policyRepository, registrationService);
    }

    @Test
    void productNotOnAnyAutoRegisterPolicyIsIgnored() {
        stubCandidates(autoRegisterPolicy(36));

        int created = service.registerFromWorkorderSale(saleEvent(INVOICE_ID, CUSTOMER_ID, PRODUCT_OTHER));

        assertThat(created).isZero();
        verify(registrationService, never()).create(any());
    }

    @Test
    void policyNotEffectiveOnPurchaseDateIsIgnored() {
        WarrantyPolicy expired = autoRegisterPolicy(36);
        expired.setEffectiveTo(PURCHASE_DATE.minusDays(1));
        stubCandidates(expired);

        int created = service.registerFromWorkorderSale(saleEvent(INVOICE_ID, CUSTOMER_ID, PRODUCT_A));

        assertThat(created).isZero();
        verify(registrationService, never()).create(any());
    }

    @Test
    void effectivityBoundsAreInclusive() {
        WarrantyPolicy bounded = autoRegisterPolicy(12);
        bounded.setEffectiveFrom(PURCHASE_DATE);
        bounded.setEffectiveTo(PURCHASE_DATE);
        stubCandidates(bounded);
        when(registrationRepository.existsByPolicyIdAndSourceInvoiceId(POLICY_ID, INVOICE_ID))
                .thenReturn(false);

        assertThat(service.registerFromWorkorderSale(saleEvent(INVOICE_ID, CUSTOMER_ID, PRODUCT_A)))
                .isEqualTo(1);
    }

    @Test
    void eventWithoutPartsIsIgnored() {
        int created = service.registerFromWorkorderSale(new WorkorderUpdatedV1(
                WORKORDER_ID, null, "INVOICED", null, CUSTOMER_ID, INVOICE_ID, null, null, UPDATED_AT));

        assertThat(created).isZero();
        verifyNoInteractions(policyRepository, registrationService);
    }

    @Test
    void multiplePartLinesOfTheSameProductStillCreateOneRegistration() {
        stubCandidates(autoRegisterPolicy(36));
        when(registrationRepository.existsByPolicyIdAndSourceInvoiceId(POLICY_ID, INVOICE_ID))
                .thenReturn(false);

        int created = service.registerFromWorkorderSale(saleEvent(INVOICE_ID, CUSTOMER_ID, PRODUCT_A, PRODUCT_A));

        assertThat(created).isEqualTo(1);
        verify(registrationService).create(any(RegistrationRequest.class));
    }
}

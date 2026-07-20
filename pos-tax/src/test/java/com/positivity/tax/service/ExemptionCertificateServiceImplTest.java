package com.positivity.tax.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.positivity.tax.common.enums.ExemptionReasonCode;
import com.positivity.tax.internal.dto.ExemptionCertificateRequest;
import com.positivity.tax.internal.dto.ExemptionCertificateResponse;
import com.positivity.tax.internal.entity.ExemptionCertificate;
import com.positivity.tax.internal.enums.ExemptionCertificateStatus;
import com.positivity.tax.internal.repository.ExemptionCertificateRepository;
import com.positivity.tax.internal.service.ActiveCertificateLookup;
import com.positivity.tax.internal.service.ExemptionCertificateServiceImpl;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Limit;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
@DisplayName("ExemptionCertificateServiceImpl (T3)")
class ExemptionCertificateServiceImplTest {

    private static final LocalDate TXN = LocalDate.parse("2026-06-01");

    @Mock
    private ExemptionCertificateRepository repository;

    private ExemptionCertificateServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ExemptionCertificateServiceImpl(repository);
    }

    private ExemptionCertificate persisted(UUID id, String customerId) {
        return ExemptionCertificate.builder()
                .id(id)
                .customerId(customerId)
                .stateScope("CA")
                .reasonCode(ExemptionReasonCode.RESALE)
                .effectiveFrom(LocalDate.parse("2026-01-01"))
                .expiresAt(LocalDate.parse("2026-12-31"))
                .status(ExemptionCertificateStatus.ACTIVE)
                .build();
    }

    @Test
    @DisplayName("create defaults status to ACTIVE and persists the payload")
    void createDefaultsStatusToActive() {
        ExemptionCertificateRequest request = ExemptionCertificateRequest.builder()
                .customerId("CUST-1")
                .stateScope("CA")
                .reasonCode(ExemptionReasonCode.RESALE)
                .effectiveFrom(LocalDate.parse("2026-01-01"))
                .build();
        when(repository.save(any(ExemptionCertificate.class))).thenAnswer(inv -> {
            ExemptionCertificate e = inv.getArgument(0);
            e.setId(UUID.randomUUID());
            return e;
        });

        ExemptionCertificateResponse response = service.create(request);

        ArgumentCaptor<ExemptionCertificate> captor = ArgumentCaptor.forClass(ExemptionCertificate.class);
        org.mockito.Mockito.verify(repository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(ExemptionCertificateStatus.ACTIVE);
        assertThat(response.customerId()).isEqualTo("CUST-1");
        assertThat(response.status()).isEqualTo(ExemptionCertificateStatus.ACTIVE);
    }

    @Test
    @DisplayName("get throws 404 when not found")
    void getThrows404() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.get(id))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("not found");
    }

    @Test
    @DisplayName("list filters by customer when supplied")
    void listFiltersByCustomer() {
        UUID id = UUID.randomUUID();
        when(repository.findByCustomerIdOrderByEffectiveFromDesc("CUST-1"))
                .thenReturn(List.of(persisted(id, "CUST-1")));
        List<ExemptionCertificateResponse> result = service.list("CUST-1");
        assertThat(result).singleElement().satisfies(r -> assertThat(r.id()).isEqualTo(id));
    }

    // ------------------------------------------------------------------
    // ActiveCertificateLookup (calculate path) validity semantics
    // ------------------------------------------------------------------

    @Test
    @DisplayName("findActive by id honors a valid active certificate")
    void findActiveByIdHonorsValid() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.of(persisted(id, "CUST-1")));

        Optional<ActiveCertificateLookup.ActiveCertificate> result =
                service.findActive("CUST-1", id, "CA", ExemptionReasonCode.RESALE, TXN);

        assertThat(result).isPresent();
        assertThat(result.get().reasonCode()).isEqualTo(ExemptionReasonCode.RESALE);
    }

    @Test
    @DisplayName("findActive by id rejects an expired certificate (denied path)")
    void findActiveByIdRejectsExpired() {
        UUID id = UUID.randomUUID();
        ExemptionCertificate expired = persisted(id, "CUST-1");
        expired.setExpiresAt(LocalDate.parse("2026-05-01")); // before TXN 2026-06-01
        when(repository.findById(id)).thenReturn(Optional.of(expired));

        assertThat(service.findActive("CUST-1", id, "CA", ExemptionReasonCode.RESALE, TXN))
                .isEmpty();
    }

    @Test
    @DisplayName("findActive by id rejects a certificate belonging to another customer")
    void findActiveByIdRejectsWrongCustomer() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.of(persisted(id, "CUST-1")));

        assertThat(service.findActive("CUST-2", id, "CA", ExemptionReasonCode.RESALE, TXN))
                .isEmpty();
    }

    @Test
    @DisplayName("findActive by customer delegates to the active-window query and takes the first row")
    void findActiveByCustomerQuery() {
        UUID id = UUID.randomUUID();
        when(repository.findActiveForCustomer(
                        eq("CUST-1"), eq("CA"), eq(ExemptionReasonCode.RESALE), eq(TXN), any(Limit.class)))
                .thenReturn(List.of(persisted(id, "CUST-1")));

        Optional<ActiveCertificateLookup.ActiveCertificate> result =
                service.findActive("CUST-1", null, "CA", ExemptionReasonCode.RESALE, TXN);

        assertThat(result).isPresent();
        assertThat(result.get().id()).isEqualTo(id);
    }

    @Test
    @DisplayName("findActive returns empty when neither certificateId nor customerId is supplied")
    void findActiveEmptyWithoutKeys() {
        assertThat(service.findActive(null, null, "CA", ExemptionReasonCode.RESALE, TXN))
                .isEmpty();
    }
}

package com.positivity.securityservice.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.positivity.securityservice.internal.dto.CrmMatchSummaryDto;
import com.positivity.securityservice.internal.entity.ExtCustomerPersonIdentity;
import com.positivity.securityservice.internal.entity.ExtPersonReplica;
import com.positivity.securityservice.internal.repository.ExtCustomerPersonIdentityRepository;
import com.positivity.securityservice.internal.repository.ExtPersonReplicaRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link CrmSignalService} (ADR-0044 §6, #891).
 */
@ExtendWith(MockitoExtension.class)
class CrmSignalServiceTest {

    @Mock
    private ExtPersonReplicaRepository extPersonReplicaRepository;

    @Mock
    private ExtCustomerPersonIdentityRepository extCustomerPersonIdentityRepository;

    private CrmSignalService service;

    @BeforeEach
    void setUp() {
        service = new CrmSignalService(extPersonReplicaRepository, extCustomerPersonIdentityRepository);
        lenient()
                .when(extPersonReplicaRepository.findByPrimaryEmailIgnoreCaseOrSecondaryEmailIgnoreCase(
                        anyString(), anyString()))
                .thenReturn(List.of());
        lenient()
                .when(extPersonReplicaRepository.findByLastNameIgnoreCase(anyString()))
                .thenReturn(List.of());
    }

    private ExtPersonReplica person(UUID personId, String firstName, String lastName) {
        return ExtPersonReplica.builder()
                .personId(personId)
                .firstName(firstName)
                .lastName(lastName)
                .build();
    }

    private ExtCustomerPersonIdentity standing(UUID personId, boolean individual, boolean commercial) {
        return ExtCustomerPersonIdentity.builder()
                .personId(personId)
                .individualCustomer(individual)
                .commercialContact(commercial)
                .commercialAccountCount(commercial ? 1 : 0)
                .build();
    }

    @Test
    @DisplayName("No candidates yields an empty summary without review")
    void noCandidates() {
        CrmMatchSummaryDto summary = service.assess("jane@example.com", null, "Jane", "Smith");

        assertThat(summary.getCandidateCount()).isZero();
        assertThat(summary.isAnyMatches()).isFalse();
        assertThat(summary.isReviewRequired()).isFalse();
    }

    @Test
    @DisplayName("Email match with a shared-identity standing requires review")
    void emailMatchSharedIdentity() {
        UUID personId = UUID.randomUUID();
        when(extPersonReplicaRepository.findByPrimaryEmailIgnoreCaseOrSecondaryEmailIgnoreCase(
                        "jane@example.com", "jane@example.com"))
                .thenReturn(List.of(person(personId, "Jane", "Smith")));
        when(extCustomerPersonIdentityRepository.findAllById(any()))
                .thenReturn(List.of(standing(personId, true, true)));

        CrmMatchSummaryDto summary = service.assess("jane@example.com", null, "Jane", "Smith");

        assertThat(summary.getCandidateCount()).isEqualTo(1);
        assertThat(summary.isExactEmailMatch()).isTrue();
        assertThat(summary.getIndividualCustomerCandidateCount()).isEqualTo(1);
        assertThat(summary.getCommercialContactCandidateCount()).isEqualTo(1);
        assertThat(summary.getSharedIdentityCandidateCount()).isEqualTo(1);
        assertThat(summary.isReviewRequired()).isTrue();
    }

    @Test
    @DisplayName("Name-only candidate without customer standing does not require review")
    void nameOnlyCandidateNoStanding() {
        UUID personId = UUID.randomUUID();
        when(extPersonReplicaRepository.findByLastNameIgnoreCase("Smith"))
                .thenReturn(List.of(person(personId, "Jane", "Smith")));
        when(extCustomerPersonIdentityRepository.findAllById(any())).thenReturn(List.of());

        CrmMatchSummaryDto summary = service.assess("other@example.com", null, "Jane", "Smith");

        assertThat(summary.getCandidateCount()).isEqualTo(1);
        assertThat(summary.isExactNameMatch()).isTrue();
        assertThat(summary.isExactEmailMatch()).isFalse();
        assertThat(summary.isReviewRequired()).isFalse();
    }

    @Test
    @DisplayName("Name-only candidate with shared identity requires review")
    void nameOnlySharedIdentity() {
        UUID personId = UUID.randomUUID();
        when(extPersonReplicaRepository.findByLastNameIgnoreCase("Smith"))
                .thenReturn(List.of(person(personId, "Jane", "Smith")));
        when(extCustomerPersonIdentityRepository.findAllById(any()))
                .thenReturn(List.of(standing(personId, true, true)));

        CrmMatchSummaryDto summary = service.assess("other@example.com", null, "Jane", "Smith");

        assertThat(summary.isReviewRequired()).isTrue();
    }

    @Test
    @DisplayName("Last-name match with a different first name is not a candidate")
    void lastNameOnlyIsNotACandidate() {
        when(extPersonReplicaRepository.findByLastNameIgnoreCase("Smith"))
                .thenReturn(List.of(person(UUID.randomUUID(), "Robert", "Smith")));

        CrmMatchSummaryDto summary = service.assess("other@example.com", null, "Jane", "Smith");

        assertThat(summary.getCandidateCount()).isZero();
        assertThat(summary.isReviewRequired()).isFalse();
    }

    @Test
    @DisplayName("Phone plus exact name requires review even without customer standing")
    void phoneAndNameMatch() {
        UUID personId = UUID.randomUUID();
        when(extPersonReplicaRepository.findByPrimaryPhoneOrSecondaryPhone("+15551234567", "+15551234567"))
                .thenReturn(List.of(person(personId, "Jane", "Smith")));
        when(extPersonReplicaRepository.findByLastNameIgnoreCase("Smith"))
                .thenReturn(List.of(person(personId, "Jane", "Smith")));
        when(extCustomerPersonIdentityRepository.findAllById(any())).thenReturn(List.of());

        CrmMatchSummaryDto summary = service.assess("other@example.com", "+15551234567", "Jane", "Smith");

        assertThat(summary.isExactPhoneMatch()).isTrue();
        assertThat(summary.isExactNameMatch()).isTrue();
        assertThat(summary.isReviewRequired()).isTrue();
    }
}

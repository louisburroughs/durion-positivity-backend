package com.positivity.customer.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.positivity.customer.internal.repository.PersonPartyRepository;
import com.positivity.customer.internal.service.PersonDirectoryService;
import com.positivity.customer.internal.service.PersonLinkReconciliationServiceImpl;
import com.positivity.customer.service.PersonLinkReconciliationService.PersonLinkReport;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PersonLinkReconciliationServiceImplTest {

    @Mock
    private PersonPartyRepository personPartyRepository;

    @Mock
    private PersonDirectoryService personDirectoryService;

    @InjectMocks
    private PersonLinkReconciliationServiceImpl service;

    private static UUID id(String suffix) {
        return UUID.fromString("00000000-0000-0000-0000-0000000000" + suffix);
    }

    @Test
    void reconcile_allLinksResolve_reportsHealthy() {
        UUID a = id("a1");
        UUID b = id("b2");
        when(personPartyRepository.findDistinctPersonIds()).thenReturn(List.of(a, b));
        when(personDirectoryService.fetchPersonIdentities(List.of(a, b)))
                .thenReturn(Map.of(
                        a,
                                new PersonDirectoryService.PersonIdentity(
                                        a, "Greg", "Whitfield", "g@x.com", java.util.List.of()),
                        b,
                                new PersonDirectoryService.PersonIdentity(
                                        b, "Teresa", "Mullen", "t@x.com", java.util.List.of())));

        PersonLinkReport report = service.reconcile();

        assertThat(report.totalLinks()).isEqualTo(2);
        assertThat(report.resolved()).isEqualTo(2);
        assertThat(report.unresolvedPersonIds()).isEmpty();
        assertThat(report.isHealthy()).isTrue();
    }

    @Test
    void reconcile_orphanLink_reportedAsUnresolved() {
        UUID linked = id("a1");
        UUID orphan = id("c3");
        when(personPartyRepository.findDistinctPersonIds()).thenReturn(List.of(linked, orphan));
        when(personDirectoryService.fetchPersonIdentities(List.of(linked, orphan)))
                .thenReturn(Map.of(
                        linked,
                        new PersonDirectoryService.PersonIdentity(
                                linked, "Greg", "Whitfield", "g@x.com", java.util.List.of())));

        PersonLinkReport report = service.reconcile();

        assertThat(report.totalLinks()).isEqualTo(2);
        assertThat(report.resolved()).isEqualTo(1);
        assertThat(report.unresolvedPersonIds()).containsExactly(orphan);
        assertThat(report.isHealthy()).isFalse();
    }

    @Test
    void reconcile_noLinks_reportsHealthy() {
        when(personPartyRepository.findDistinctPersonIds()).thenReturn(List.of());
        when(personDirectoryService.fetchPersonIdentities(List.of())).thenReturn(Map.of());

        PersonLinkReport report = service.reconcile();

        assertThat(report.totalLinks()).isZero();
        assertThat(report.isHealthy()).isTrue();
        assertThat(report.posPeopleReachable()).isTrue();
    }

    @Test
    void reconcile_posPeopleUnreachable_reportsDegradedWithoutThrowing() {
        UUID a = id("a1");
        when(personPartyRepository.findDistinctPersonIds()).thenReturn(List.of(a));
        when(personDirectoryService.fetchPersonIdentities(List.of(a)))
                .thenThrow(new IllegalStateException("No instances available for people"));

        PersonLinkReport report = service.reconcile();

        assertThat(report.posPeopleReachable()).isFalse();
        assertThat(report.isHealthy()).isFalse();
        assertThat(report.totalLinks()).isEqualTo(1);
        assertThat(report.unresolvedPersonIds()).isEmpty();
    }
}

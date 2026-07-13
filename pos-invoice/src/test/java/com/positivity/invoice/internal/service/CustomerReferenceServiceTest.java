package com.positivity.invoice.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.invoice.internal.entity.ExtCustomerPartyReplica;
import com.positivity.invoice.internal.repository.ExtCustomerPartyReplicaRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link CustomerReferenceService} (ADR-0044 §6, #891).
 */
@ExtendWith(MockitoExtension.class)
class CustomerReferenceServiceTest {

    @Mock
    private ExtCustomerPartyReplicaRepository repository;

    @InjectMocks
    private CustomerReferenceService service;

    private ExtCustomerPartyReplica replica(UUID partyId, String displayName) {
        return ExtCustomerPartyReplica.builder()
                .partyId(partyId)
                .displayName(displayName)
                .build();
    }

    @Test
    @DisplayName("Name search resolves replica party ids as strings")
    void searchIdsByName() {
        UUID partyId = UUID.randomUUID();
        when(repository.searchByDisplayName(eq("Acme"), any())).thenReturn(List.of(replica(partyId, "Acme Corp")));

        assertThat(service.searchIdsByName(" Acme ", 10)).containsExactly(partyId.toString());
    }

    @Test
    @DisplayName("Blank query short-circuits to empty")
    void blankQuery() {
        assertThat(service.searchIdsByName("  ", 10)).isEmpty();
        verify(repository, never()).searchByDisplayName(any(), any());
    }

    @Test
    @DisplayName("Name resolution omits unknown ids and non-UUID strings")
    void resolveNames() {
        UUID known = UUID.randomUUID();
        when(repository.findAllById(List.of(known))).thenReturn(List.of(replica(known, "Acme Corp")));

        Map<String, String> resolved =
                service.resolveNames(List.of(known.toString(), "not-a-uuid", " ", known.toString()));

        assertThat(resolved).containsExactlyEntriesOf(Map.of(known.toString(), "Acme Corp"));
    }
}

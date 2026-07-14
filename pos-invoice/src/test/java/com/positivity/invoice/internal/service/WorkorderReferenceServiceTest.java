package com.positivity.invoice.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.invoice.internal.entity.ExtWorkorderReplica;
import com.positivity.invoice.internal.repository.ExtWorkorderReplicaRepository;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;

/**
 * Unit tests for {@link WorkorderReferenceService} (ADR-0044 §6, #897) — replica-backed
 * replacement for the retired {@code WorkorderReferenceClient}.
 */
@ExtendWith(MockitoExtension.class)
class WorkorderReferenceServiceTest {

    @Mock
    private ExtWorkorderReplicaRepository extWorkorderReplicaRepository;

    @InjectMocks
    private WorkorderReferenceService service;

    private static ExtWorkorderReplica replica(UUID id, String number) {
        return ExtWorkorderReplica.builder()
                .workorderId(id)
                .workorderNumber(number)
                .status("IN_PROGRESS")
                .aggregateVersion(1L)
                .build();
    }

    @Test
    void searchIdsByNumber_matchesCaseInsensitiveContains() {
        UUID id = UUID.randomUUID();
        when(extWorkorderReplicaRepository.findByWorkorderNumberContainingIgnoreCase(
                        eq("2026"), eq(PageRequest.of(0, 10))))
                .thenReturn(List.of(replica(id, "WO-2026-1001")));

        assertThat(service.searchIdsByNumber(" 2026 ", 10)).containsExactly(id);
    }

    @Test
    void searchIdsByNumber_blankQuery_returnsEmptyWithoutQuery() {
        assertThat(service.searchIdsByNumber("   ", 10)).isEmpty();
        verify(extWorkorderReplicaRepository, never()).findByWorkorderNumberContainingIgnoreCase(any(), any());
    }

    @Test
    void resolveNumbers_mapsKnownIdsAndOmitsUnknownOrNumberless() {
        UUID known = UUID.randomUUID();
        UUID numberless = UUID.randomUUID();
        UUID unknown = UUID.randomUUID();
        when(extWorkorderReplicaRepository.findAllById(Set.of(known, numberless, unknown)))
                .thenReturn(List.of(replica(known, "WO-2026-1001"), replica(numberless, null)));

        Map<UUID, String> numbers = service.resolveNumbers(List.of(known, numberless, unknown));

        assertThat(numbers).containsExactlyEntriesOf(Map.of(known, "WO-2026-1001"));
    }

    @Test
    void resolveNumbers_emptyInput_shortCircuits() {
        assertThat(service.resolveNumbers(List.of())).isEmpty();
        verify(extWorkorderReplicaRepository, never()).findAllById(any());
    }
}

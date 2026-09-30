package com.positivity.workorder.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.tenancy.TenantResolver;
import com.positivity.workorder.internal.entity.DocumentNumberSequence;
import com.positivity.workorder.internal.repository.DocumentNumberSequenceRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DocumentNumberAllocatorTest {

    private static final String SCOPE = "WO-2026";
    private static final String PREFIX = "WO-2026-";

    @Mock
    private DocumentNumberSequenceRepository sequenceRepository;

    @Mock
    private TenantResolver tenantResolver;

    private static final UUID TENANT = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-03-01T00:00:00Z");

    private DocumentNumberAllocator allocator;

    @BeforeEach
    void setUp() {
        allocator = new DocumentNumberAllocator(sequenceRepository, tenantResolver, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void handsOutTheCounterValueAndAdvancesIt() {
        DocumentNumberSequence sequence = sequence(1042L);
        when(sequenceRepository.findByScopeKey(SCOPE)).thenReturn(Optional.of(sequence));

        String number = allocator.allocate(SCOPE, PREFIX, 1000L, candidate -> false);

        assertThat(number).isEqualTo("WO-2026-1042");
        assertThat(sequence.getNextValue()).isEqualTo(1043L);
        verify(sequenceRepository, never()).insertIfAbsent(any(), any(), any(), anyLong(), any());
    }

    @Test
    void skipsNumbersAlreadyTakenAndRecordsWhereItStopped() {
        DocumentNumberSequence sequence = sequence(1000L);
        when(sequenceRepository.findByScopeKey(SCOPE)).thenReturn(Optional.of(sequence));
        Set<String> taken = Set.of("WO-2026-1000", "WO-2026-1001", "WO-2026-1002");

        String number = allocator.allocate(SCOPE, PREFIX, 1000L, taken::contains);

        assertThat(number).isEqualTo("WO-2026-1003");
        assertThat(sequence.getNextValue()).isEqualTo(1004L);
    }

    @Test
    void provisionsAScopeOnFirstUseThenReadsItUnderTheLock() {
        DocumentNumberSequence provisioned = sequence(1000L);
        when(sequenceRepository.findByScopeKey(SCOPE)).thenReturn(Optional.empty(), Optional.of(provisioned));
        when(tenantResolver.require()).thenReturn(TENANT);

        String number = allocator.allocate(SCOPE, PREFIX, 1000L, candidate -> false);

        assertThat(number).isEqualTo("WO-2026-1000");
        verify(sequenceRepository).insertIfAbsent(eq(TENANT), any(UUID.class), eq(SCOPE), eq(1000L), eq(NOW));
    }

    @Test
    void losingTheFirstUseRaceReadsTheWinnersRow() {
        DocumentNumberSequence winners = sequence(1007L);
        when(sequenceRepository.findByScopeKey(SCOPE)).thenReturn(Optional.empty(), Optional.of(winners));
        when(tenantResolver.require()).thenReturn(TENANT);
        // ON CONFLICT DO NOTHING: the racing insert reports 0 rows and nothing is thrown.
        when(sequenceRepository.insertIfAbsent(eq(TENANT), any(UUID.class), eq(SCOPE), eq(1000L), eq(NOW)))
                .thenReturn(0);

        String number = allocator.allocate(SCOPE, PREFIX, 1000L, candidate -> false);

        assertThat(number).isEqualTo("WO-2026-1007");
        assertThat(winners.getNextValue()).isEqualTo(1008L);
    }

    private static DocumentNumberSequence sequence(long nextValue) {
        DocumentNumberSequence sequence = new DocumentNumberSequence();
        sequence.setScopeKey(SCOPE);
        sequence.setNextValue(nextValue);
        return sequence;
    }
}

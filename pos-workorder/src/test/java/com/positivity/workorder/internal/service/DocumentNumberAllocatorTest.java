package com.positivity.workorder.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.workorder.internal.entity.DocumentNumberSequence;
import com.positivity.workorder.internal.repository.DocumentNumberSequenceRepository;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

@ExtendWith(MockitoExtension.class)
class DocumentNumberAllocatorTest {

    private static final String SCOPE = "WO-2026";
    private static final String PREFIX = "WO-2026-";

    @Mock
    private DocumentNumberSequenceRepository sequenceRepository;

    @Mock
    private DocumentNumberSequenceProvisioner sequenceProvisioner;

    @InjectMocks
    private DocumentNumberAllocator allocator;

    @Test
    void handsOutTheCounterValueAndAdvancesIt() {
        DocumentNumberSequence sequence = sequence(1042L);
        when(sequenceRepository.findByScopeKey(SCOPE)).thenReturn(Optional.of(sequence));

        String number = allocator.allocate(SCOPE, PREFIX, 1000L, candidate -> false);

        assertThat(number).isEqualTo("WO-2026-1042");
        assertThat(sequence.getNextValue()).isEqualTo(1043L);
        verify(sequenceProvisioner, never()).provision(SCOPE, 1000L);
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

        String number = allocator.allocate(SCOPE, PREFIX, 1000L, candidate -> false);

        assertThat(number).isEqualTo("WO-2026-1000");
        verify(sequenceProvisioner).provision(SCOPE, 1000L);
    }

    @Test
    void losingTheFirstUseRaceReadsTheWinnersRow() {
        DocumentNumberSequence winners = sequence(1007L);
        when(sequenceRepository.findByScopeKey(SCOPE)).thenReturn(Optional.empty(), Optional.of(winners));
        doThrow(new DataIntegrityViolationException("document_number_sequence_scope_key"))
                .when(sequenceProvisioner)
                .provision(SCOPE, 1000L);

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

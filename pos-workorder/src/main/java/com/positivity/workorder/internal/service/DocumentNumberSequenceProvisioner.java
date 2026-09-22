package com.positivity.workorder.internal.service;

import com.positivity.workorder.internal.entity.DocumentNumberSequence;
import com.positivity.workorder.internal.repository.DocumentNumberSequenceRepository;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates a {@link DocumentNumberSequence} row the first time a scope issues a number (#2150).
 *
 * <p>Runs in its own {@code REQUIRES_NEW} transaction. When two creates race to first-use a
 * scope, the loser's insert fails the unique {@code document_number_sequence_scope_key}
 * constraint, and on PostgreSQL a failed statement aborts the transaction it runs in. Confined
 * here, the failure leaves the caller's transaction usable, so it can re-read the winner's row
 * under the lock. The insert hands out no number, so committing it independently costs nothing.
 * A separate bean because {@code REQUIRES_NEW} only applies across a Spring proxy.
 */
@Component
@RequiredArgsConstructor
public class DocumentNumberSequenceProvisioner {

    private final DocumentNumberSequenceRepository sequenceRepository;

    /**
     * @throws org.springframework.dao.DataIntegrityViolationException if the scope already has a
     *     row; only this inner transaction rolls back
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void provision(@NonNull String scopeKey, long firstValue) {
        DocumentNumberSequence sequence = new DocumentNumberSequence();
        sequence.setScopeKey(scopeKey);
        sequence.setNextValue(firstValue);
        sequenceRepository.saveAndFlush(sequence);
    }
}

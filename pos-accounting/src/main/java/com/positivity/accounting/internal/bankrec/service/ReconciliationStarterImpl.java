package com.positivity.accounting.internal.bankrec.service;

import com.positivity.accounting.internal.bankrec.dto.ReconciliationCreateRequest;
import com.positivity.accounting.internal.bankrec.intake.ReconciliationStarter;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The core's {@link ReconciliationStarter} (story S4, #2303): {@code startReconciliation} on a file-import
 * commit or a manual statement goes through {@link BankReconciliationService#create}, so it answers every
 * create rule, audit row and replay exactly as {@code POST /reconciliations} does.
 */
@Component
@RequiredArgsConstructor
public class ReconciliationStarterImpl implements ReconciliationStarter {

    private final BankReconciliationService reconciliations;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public @NonNull UUID start(@NonNull UUID glAccountId, @NonNull UUID statementId, @NonNull UUID requestId) {
        return reconciliations
                .create(ReconciliationCreateRequest.builder()
                        .glAccountId(glAccountId)
                        .statementId(statementId)
                        .requestId(requestId)
                        .build())
                .getReconciliationId();
    }
}

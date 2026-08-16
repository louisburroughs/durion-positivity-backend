package com.positivity.supplier.internal.workorderauth.service;

import com.positivity.supplier.internal.entity.SupplierWorkorderAuthorizationEntity;
import com.positivity.supplier.internal.enums.WorkorderAuthorizationStatus;
import com.positivity.supplier.internal.repository.SupplierWorkorderAuthorizationRepository;
import com.positivity.supplier.service.SupplierWorkorderAuthorizationService;
import com.positivity.supplier.service.model.WorkorderAuthorizationView;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The contract read surface over fleet authorization state (CAP-323 #1229, ADR-0026).
 *
 * <h2>MANUAL_REVIEW is reported as PENDING</h2>
 *
 * The published view has four values and deliberately keeps them: {@code MANUAL_REVIEW} is this
 * module's own operational state, meaning "we could not get an answer and a human is looking". To a
 * caller asking whether a job is authorized, the honest answer is still "not yet" — the vendor has
 * not granted and has not refused.
 *
 * <p>Leaking the fifth value would invite callers to branch on our queue depth, and any caller that
 * treated it as a refusal would be reporting a decision the fleet never made. Operators see the real
 * state through the admin surface, which is where an operational state belongs.
 */
@Service
@RequiredArgsConstructor
public class SupplierWorkorderAuthorizationServiceImpl implements SupplierWorkorderAuthorizationService {

    private final SupplierWorkorderAuthorizationRepository authorizationRepository;

    @Override
    @NonNull
    @Transactional(readOnly = true)
    public Optional<WorkorderAuthorizationView> findByWorkorderId(@NonNull UUID workorderId) {
        return authorizationRepository.findByWorkorderIdOrderByRequestedAtDesc(workorderId).stream()
                .findFirst()
                .map(SupplierWorkorderAuthorizationServiceImpl::toView);
    }

    @NonNull
    private static WorkorderAuthorizationView toView(@NonNull SupplierWorkorderAuthorizationEntity row) {
        return new WorkorderAuthorizationView(
                row.getWorkorderId(),
                viewStatus(row.getStatus()),
                row.getVendorAuthorizationId(),
                // The reason a human is looking is not the vendor's reason and must not be presented
                // as one: a caller quoting our queue state back to a fleet manager helps nobody.
                row.getStatus() == WorkorderAuthorizationStatus.MANUAL_REVIEW ? null : row.getReasonText());
    }

    private static WorkorderAuthorizationView.@NonNull Status viewStatus(@NonNull WorkorderAuthorizationStatus status) {
        return switch (status) {
            case GRANTED -> WorkorderAuthorizationView.Status.GRANTED;
            case DENIED -> WorkorderAuthorizationView.Status.DENIED;
            case NOT_FOUND -> WorkorderAuthorizationView.Status.NOT_FOUND;
            case PENDING, MANUAL_REVIEW -> WorkorderAuthorizationView.Status.PENDING;
        };
    }
}

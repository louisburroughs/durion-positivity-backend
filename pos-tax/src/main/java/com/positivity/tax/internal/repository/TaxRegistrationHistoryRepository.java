package com.positivity.tax.internal.repository;

import com.positivity.tax.internal.entity.TaxRegistrationHistory;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;

/** The bound tenant's tax-registration history (CAP:550 S32c). */
public interface TaxRegistrationHistoryRepository extends JpaRepository<TaxRegistrationHistory, UUID> {

    /** The change a request made, when it was already applied: a replay returns the first result. */
    @NonNull
    Optional<TaxRegistrationHistory> findByRequestId(@NonNull UUID requestId);

    /** One registration's changes, oldest first. */
    @NonNull
    List<TaxRegistrationHistory> findByRegistrationIdOrderByRecordedAtAsc(@NonNull UUID registrationId);
}

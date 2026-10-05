package com.positivity.accounting.internal.repository;

import com.positivity.accounting.internal.entity.AccountingTemplateState;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

/** The bound tenant's accounting template state row (#2526); the tenant filter scopes every query. */
public interface AccountingTemplateStateRepository extends JpaRepository<AccountingTemplateState, UUID> {

    /** The bound tenant's state row, if the template applier has ever run for it. */
    @Query("SELECT s FROM AccountingTemplateState s")
    Optional<AccountingTemplateState> findCurrent();

    /**
     * The bound tenant's state row under {@code SELECT ... FOR UPDATE}: the applier holds it for the
     * length of a run. Must run inside an active transaction.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM AccountingTemplateState s")
    Optional<AccountingTemplateState> findCurrentForUpdate();
}

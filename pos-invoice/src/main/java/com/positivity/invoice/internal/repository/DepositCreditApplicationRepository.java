package com.positivity.invoice.internal.repository;

import com.positivity.invoice.internal.entity.DepositCreditApplication;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DepositCreditApplicationRepository extends JpaRepository<DepositCreditApplication, UUID> {

    /**
     * The credit ids already applied to an invoice — one query instead of a per-credit lazy load of
     * {@code DepositCredit.applications} (Copilot #1093 review: avoids the N+1 in
     * {@code applyAvailableCredits}).
     */
    @Query("select a.depositCredit.depositCreditId from DepositCreditApplication a where a.invoiceId = :invoiceId")
    Set<UUID> findDepositCreditIdsByInvoiceId(@Param("invoiceId") UUID invoiceId);
}

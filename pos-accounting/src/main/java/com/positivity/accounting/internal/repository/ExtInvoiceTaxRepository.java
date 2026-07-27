package com.positivity.accounting.internal.repository;

import com.positivity.accounting.internal.entity.ExtInvoiceTax;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ExtInvoiceTaxRepository extends JpaRepository<ExtInvoiceTax, UUID> {

    List<ExtInvoiceTax> findByInvoiceId(UUID invoiceId);

    /**
     * Load all per-jurisdiction tax rows for a set of invoices in one query. Used by
     * the Sales-Tax Liability report (story T8, issue #966) to aggregate an in-period
     * invoice set and to build per-invoice jurisdiction weights for credit netting,
     * avoiding a per-invoice N+1.
     *
     * @param invoiceIds invoice ids to load tax rows for
     * @return matching tax rows (unordered; the report groups/orders in-service)
     */
    List<ExtInvoiceTax> findByInvoiceIdIn(Collection<UUID> invoiceIds);

    @Modifying
    @Query("delete from ExtInvoiceTax t where t.invoiceId = :invoiceId")
    int deleteByInvoiceId(@Param("invoiceId") UUID invoiceId);
}

package com.positivity.accounting.internal.repository;

import com.positivity.accounting.internal.entity.ExtInvoiceTax;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ExtInvoiceTaxRepository extends JpaRepository<ExtInvoiceTax, UUID> {

    List<ExtInvoiceTax> findByInvoiceId(UUID invoiceId);

    @Modifying
    @Query("delete from ExtInvoiceTax t where t.invoiceId = :invoiceId")
    int deleteByInvoiceId(@Param("invoiceId") UUID invoiceId);
}

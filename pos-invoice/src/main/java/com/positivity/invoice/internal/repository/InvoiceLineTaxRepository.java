package com.positivity.invoice.internal.repository;

import com.positivity.invoice.internal.entity.InvoiceLineTax;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InvoiceLineTaxRepository extends JpaRepository<InvoiceLineTax, UUID> {

    @NonNull
    List<InvoiceLineTax> findByInvoiceId(@NonNull UUID invoiceId);

    @Modifying
    @Query("delete from InvoiceLineTax lt where lt.invoiceId = :invoiceId")
    int deleteByInvoiceId(@Param("invoiceId") @NonNull UUID invoiceId);
}

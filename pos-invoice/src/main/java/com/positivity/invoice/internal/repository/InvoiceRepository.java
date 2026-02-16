package com.positivity.invoice.internal.repository;

import com.positivity.invoice.internal.entity.Invoice;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface InvoiceRepository extends JpaRepository<Invoice, UUID> {

    @NonNull
    Optional<Invoice> findByWorkorderId(@NonNull UUID workorderId);

    @NonNull
    Optional<Invoice> findByInvoiceNumber(@NonNull String invoiceNumber);
}

package com.positivity.invoice.internal.repository;

import com.positivity.invoice.internal.entity.Invoice;
import com.positivity.invoice.internal.enums.InvoiceStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InvoiceRepository extends JpaRepository<Invoice, UUID> {

    @NonNull
    Optional<Invoice> findByWorkorderId(@NonNull UUID workorderId);

    @NonNull
    List<Invoice> findByStatus(@NonNull InvoiceStatus status);

    @NonNull
    Optional<Invoice> findByInvoiceNumber(@NonNull String invoiceNumber);
}

package com.positivity.invoice.internal.repository;

import com.positivity.invoice.internal.entity.InvoiceItem;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface InvoiceItemRepository extends JpaRepository<InvoiceItem, UUID> {

    @NonNull
    List<InvoiceItem> findByInvoice_Id(@NonNull UUID invoiceId);
}

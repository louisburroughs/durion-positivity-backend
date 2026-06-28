package com.positivity.invoice.internal.repository;

import com.positivity.invoice.internal.entity.Receipt;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReceiptRepository extends JpaRepository<Receipt, UUID> {

    Optional<Receipt> findByReference(@NonNull String reference);

    long countByInvoice_Id(@NonNull UUID invoiceId);

    List<Receipt> findByInvoice_IdOrderByCreatedAtAsc(@NonNull UUID invoiceId);
}

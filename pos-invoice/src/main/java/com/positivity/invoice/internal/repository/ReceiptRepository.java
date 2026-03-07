package com.positivity.invoice.internal.repository;

import com.positivity.invoice.internal.entity.Receipt;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ReceiptRepository extends JpaRepository<Receipt, UUID> {

    Optional<Receipt> findByReference(@NonNull String reference);
}

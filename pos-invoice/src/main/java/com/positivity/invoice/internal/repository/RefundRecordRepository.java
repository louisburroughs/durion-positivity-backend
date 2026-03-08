package com.positivity.invoice.internal.repository;

import com.positivity.invoice.internal.entity.RefundRecord;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface RefundRecordRepository extends JpaRepository<RefundRecord, UUID> {

    List<RefundRecord> findByPaymentIntentId(@NonNull UUID paymentIntentId);
}
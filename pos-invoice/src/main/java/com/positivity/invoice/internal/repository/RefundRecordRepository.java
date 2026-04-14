package com.positivity.invoice.internal.repository;

import com.positivity.invoice.internal.entity.RefundRecord;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RefundRecordRepository extends JpaRepository<RefundRecord, UUID> {

    List<RefundRecord> findByPaymentIntent_Id(@NonNull UUID paymentIntentId);
}

package com.positivity.order.internal.repository;

import com.positivity.order.internal.entity.OrderPaymentRecord;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderPaymentRecordRepository extends JpaRepository<OrderPaymentRecord, UUID> {

    List<OrderPaymentRecord> findByOrderId(UUID orderId);
}

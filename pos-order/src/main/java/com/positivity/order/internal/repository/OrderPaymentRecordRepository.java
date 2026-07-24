package com.positivity.order.internal.repository;

import com.positivity.order.internal.entity.OrderPaymentRecord;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderPaymentRecordRepository extends JpaRepository<OrderPaymentRecord, UUID> {

    List<OrderPaymentRecord> findByOrderId(UUID orderId);

    /**
     * All settlement/reversal records for orders bound to a register session — the read model behind
     * theoretical cash and the X/Z tender breakdown (parity story G2, spec R6.3–R6.5).
     */
    @Query("select r from OrderPaymentRecord r where r.orderId in "
            + "(select o.orderId from SalesOrder o where o.sessionId = :sessionId)")
    List<OrderPaymentRecord> findBySessionId(@Param("sessionId") UUID sessionId);
}

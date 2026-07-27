package com.positivity.order.internal.repository;

import com.positivity.order.internal.entity.OrderNumberSequence;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderNumberSequenceRepository extends JpaRepository<OrderNumberSequence, OrderNumberSequence.Key> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from OrderNumberSequence s where s.key.locationId = :locationId and s.key.period = :period")
    Optional<OrderNumberSequence> findForUpdate(@Param("locationId") UUID locationId, @Param("period") String period);
}

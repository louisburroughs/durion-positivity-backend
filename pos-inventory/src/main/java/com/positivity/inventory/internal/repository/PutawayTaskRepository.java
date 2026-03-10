package com.positivity.inventory.internal.repository;

import com.positivity.inventory.internal.entity.PutawayTask;
import com.positivity.inventory.internal.enums.PutawayTaskStatus;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface PutawayTaskRepository extends JpaRepository<PutawayTask, UUID> {

    List<PutawayTask> findBySourceReceipt_ReceiptId(UUID sourceReceiptId);

    List<PutawayTask> findByStatusIn(List<PutawayTaskStatus> statuses);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM PutawayTask t WHERE t.taskId = :taskId")
    Optional<PutawayTask> findByIdForUpdate(@Param("taskId") UUID taskId);

    boolean existsByProductIdAndSuggestedDestinationLocationIdAndStatusIn(
            UUID productId,
            UUID locationId,
            List<PutawayTaskStatus> statuses);
}

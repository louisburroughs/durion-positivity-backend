package com.positivity.inventory.internal.repository;

import com.positivity.inventory.internal.entity.PutawayTask;
import com.positivity.inventory.internal.enums.PutawayTaskStatus;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface PutawayTaskRepository extends JpaRepository<PutawayTask, UUID> {

    List<PutawayTask> findBySourceReceiptId(UUID sourceReceiptId);

    List<PutawayTask> findByStatusIn(List<PutawayTaskStatus> statuses);

    boolean existsByProductIdAndSuggestedDestinationLocationIdAndStatusIn(
            UUID productId,
            String locationId,
            List<PutawayTaskStatus> statuses);
}

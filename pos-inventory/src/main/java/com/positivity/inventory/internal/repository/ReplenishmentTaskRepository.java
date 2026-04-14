package com.positivity.inventory.internal.repository;

import com.positivity.inventory.internal.entity.ReplenishmentTask;
import com.positivity.inventory.internal.enums.ReplenishmentStatus;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ReplenishmentTaskRepository extends JpaRepository<ReplenishmentTask, UUID> {

    List<ReplenishmentTask> findByStatus(ReplenishmentStatus status);

    List<ReplenishmentTask> findByStatusIn(List<ReplenishmentStatus> statuses);

    boolean existsByItemSKUAndDestinationLocationIdAndStatusIn(
            String itemSKU, UUID destinationLocationId, List<ReplenishmentStatus> statuses);
}

package com.positivity.inventory.internal.repository;

import com.positivity.inventory.internal.entity.PickListEntity;
import com.positivity.inventory.internal.enums.PickListStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PickListRepository extends JpaRepository<PickListEntity, UUID> {

    List<PickListEntity> findByWorkorderId(UUID workorderId);

    Optional<PickListEntity> findByWorkorderIdAndStatus(UUID workorderId, PickListStatus status);
}
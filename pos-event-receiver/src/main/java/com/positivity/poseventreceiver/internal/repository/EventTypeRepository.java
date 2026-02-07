package com.positivity.poseventreceiver.internal.repository;

import com.positivity.poseventreceiver.internal.entity.EventType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface EventTypeRepository extends JpaRepository<EventType, UUID> {
    Optional<EventType> findByTypeCode(String typeCode);

    List<EventType> findByActive(boolean active);
}

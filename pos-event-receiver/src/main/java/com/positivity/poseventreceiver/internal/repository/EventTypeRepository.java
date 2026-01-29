package com.positivity.poseventreceiver.internal.repository;

import com.positivity.poseventreceiver.internal.entity.EventType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface EventTypeRepository extends JpaRepository<EventType, Long> {
    Optional<EventType> findByTypeCode(String typeCode);

    List<EventType> findByActive(boolean active);
}

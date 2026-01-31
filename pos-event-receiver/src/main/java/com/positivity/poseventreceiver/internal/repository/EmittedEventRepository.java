package com.positivity.poseventreceiver.internal.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.positivity.poseventreceiver.internal.entity.EmittedEvent;

@Repository
public interface EmittedEventRepository extends JpaRepository<EmittedEvent, Long> {
}

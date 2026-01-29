package com.positivity.poseventreceiver.internal.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.positivity.poseventreceiver.internal.entity.EmittedEvent;

public interface EmittedEventRepository extends JpaRepository<EmittedEvent, Long> {
}

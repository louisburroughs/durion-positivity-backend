package com.positivity.poseventreceiver.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.positivity.poseventreceiver.entity.EmittedEvent;

public interface EmittedEventRepository extends JpaRepository<EmittedEvent, Long> {
}

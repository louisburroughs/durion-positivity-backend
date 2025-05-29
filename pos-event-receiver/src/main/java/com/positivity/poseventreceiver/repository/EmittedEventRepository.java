package com.positivity.poseventreceiver.repository;

import com.positivity.poseventreceiver.model.EmittedEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EmittedEventRepository extends JpaRepository<EmittedEvent, Long> {
}

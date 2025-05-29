package com.positivity.poseventreceiver.repository;

import com.positivity.poseventreceiver.model.PreregisteredEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PreregisteredEventRepository extends JpaRepository<PreregisteredEvent, String> {
}

package com.positivity.poseventreceiver.internal.repository;

import com.positivity.poseventreceiver.internal.entity.PreregisteredEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PreregisteredEventRepository extends JpaRepository<PreregisteredEvent, String> {}

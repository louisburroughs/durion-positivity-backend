package com.positivity.poseventreceiver.internal.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.positivity.poseventreceiver.internal.entity.PreregisteredEvent;

public interface PreregisteredEventRepository extends JpaRepository<PreregisteredEvent, String> {
}

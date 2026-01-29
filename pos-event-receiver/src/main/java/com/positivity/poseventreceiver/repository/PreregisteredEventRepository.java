package com.positivity.poseventreceiver.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.positivity.poseventreceiver.entity.PreregisteredEvent;

public interface PreregisteredEventRepository extends JpaRepository<PreregisteredEvent, String> {
}

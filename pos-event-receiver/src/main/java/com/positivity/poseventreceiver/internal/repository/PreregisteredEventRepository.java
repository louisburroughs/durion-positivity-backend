package com.positivity.poseventreceiver.internal.repository;

import com.positivity.poseventreceiver.internal.entity.PreregisteredEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface PreregisteredEventRepository extends JpaRepository<PreregisteredEvent, String> {}

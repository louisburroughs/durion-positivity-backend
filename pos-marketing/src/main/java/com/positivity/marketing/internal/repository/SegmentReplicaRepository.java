package com.positivity.marketing.internal.repository;

import com.positivity.marketing.internal.entity.SegmentReplica;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SegmentReplicaRepository extends JpaRepository<SegmentReplica, UUID> {}

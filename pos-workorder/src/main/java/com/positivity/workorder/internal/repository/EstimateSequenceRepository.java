package com.positivity.workorder.internal.repository;

import com.positivity.workorder.internal.entity.EstimateSequence;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface EstimateSequenceRepository extends JpaRepository<EstimateSequence, Long> {
}

package com.positivity.workorder.internal.repository;

import com.positivity.workorder.internal.entity.SubstituteLink;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SubstituteLinkRepository extends JpaRepository<SubstituteLink, UUID> {
    boolean existsByProductIdAndSubstitutePartId(UUID productId, UUID substitutePartId);

    Optional<SubstituteLink> findByProductIdAndSubstitutePartId(UUID productId, UUID substitutePartId);

    List<SubstituteLink> findByProductIdAndIsActiveTrueOrderByPriorityAsc(UUID productId);
}
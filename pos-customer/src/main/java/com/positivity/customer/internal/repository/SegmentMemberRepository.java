package com.positivity.customer.internal.repository;

import com.positivity.customer.internal.entity.SegmentMember;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface SegmentMemberRepository extends JpaRepository<SegmentMember, UUID> {

    List<SegmentMember> findBySegmentId(UUID segmentId);

    Optional<SegmentMember> findBySegmentIdAndPartyId(UUID segmentId, UUID partyId);

    long countBySegmentId(UUID segmentId);

    void deleteBySegmentId(UUID segmentId);

    @Query("SELECT m.partyId FROM SegmentMember m WHERE m.segmentId = :segmentId")
    List<UUID> findPartyIdsBySegmentId(@Param("segmentId") UUID segmentId);
}

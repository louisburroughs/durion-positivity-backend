package com.positivity.marketing.internal.repository;

import com.positivity.marketing.internal.entity.CampaignAudienceMember;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CampaignAudienceMemberRepository extends JpaRepository<CampaignAudienceMember, UUID> {

    List<CampaignAudienceMember> findByCampaignId(UUID campaignId);

    long countByCampaignId(UUID campaignId);

    void deleteByCampaignId(UUID campaignId);

    @Query("SELECT m.partyId FROM CampaignAudienceMember m WHERE m.campaignId = :campaignId")
    List<UUID> findPartyIdsByCampaignId(@Param("campaignId") UUID campaignId);

    /** Age of the snapshot, so a preview can state how current its numbers are. */
    @Query("SELECT MAX(m.resolvedAt) FROM CampaignAudienceMember m WHERE m.campaignId = :campaignId")
    Optional<Instant> findResolvedAt(@Param("campaignId") UUID campaignId);
}

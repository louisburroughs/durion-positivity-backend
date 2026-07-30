package com.positivity.marketing.internal.repository;

import com.positivity.marketing.internal.entity.Campaign;
import com.positivity.marketing.internal.enums.CampaignStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CampaignRepository extends JpaRepository<Campaign, UUID> {

    Optional<Campaign> findByCodeIgnoreCase(String code);

    boolean existsByCodeIgnoreCase(String code);

    List<Campaign> findByStatusOrderByCreatedAtDesc(CampaignStatus status);

    List<Campaign> findByCampaignProgramId(UUID campaignProgramId);

    List<Campaign> findAllByOrderByCreatedAtDesc();

    /**
     * Campaigns whose scheduled moment has arrived — the dispatch worker's work
     * queue.
     */
    List<Campaign> findByStatusAndScheduledAtLessThanEqual(CampaignStatus status, Instant now);
}

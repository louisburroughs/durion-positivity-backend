package com.positivity.marketing.internal.repository;

import com.positivity.marketing.internal.entity.CampaignSend;
import com.positivity.marketing.internal.enums.CampaignChannel;
import com.positivity.marketing.internal.enums.SendStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CampaignSendRepository extends JpaRepository<CampaignSend, UUID> {

    Optional<CampaignSend> findByCampaignIdAndRecipientPartyIdAndChannel(
            UUID campaignId, UUID recipientPartyId, CampaignChannel channel);

    Optional<CampaignSend> findByProviderMessageId(String providerMessageId);

    Page<CampaignSend> findByCampaignId(UUID campaignId, Pageable pageable);

    List<CampaignSend> findByCampaignIdAndStatus(UUID campaignId, SendStatus status);

    /**
     * One drain's worth of queued recipients, oldest first so a campaign progresses
     * in order.
     */
    List<CampaignSend> findByStatusOrderByQueuedAtAsc(SendStatus status, Pageable pageable);

    long countByCampaignIdAndStatus(UUID campaignId, SendStatus status);

    long countByCampaignId(UUID campaignId);

    /**
     * Per-status totals for one campaign in a single query, for the stats funnel.
     */
    @Query("SELECT s.channel, s.status, COUNT(s) FROM CampaignSend s WHERE s.campaignId = :campaignId "
            + "GROUP BY s.channel, s.status")
    List<Object[]> countByChannelAndStatus(@Param("campaignId") UUID campaignId);
}

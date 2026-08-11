package com.positivity.marketing.internal.repository;

import com.positivity.marketing.internal.entity.CampaignAttribution;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CampaignAttributionRepository extends JpaRepository<CampaignAttribution, UUID> {

    long countByCampaignId(UUID campaignId);

    List<CampaignAttribution> findByCampaignId(UUID campaignId);

    @Query("SELECT COALESCE(SUM(a.discountAmount), 0) FROM CampaignAttribution a WHERE a.campaignId = :campaignId")
    BigDecimal sumDiscountByCampaignId(@Param("campaignId") UUID campaignId);
}

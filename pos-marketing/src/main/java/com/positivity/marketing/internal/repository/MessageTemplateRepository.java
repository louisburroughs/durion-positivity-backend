package com.positivity.marketing.internal.repository;

import com.positivity.marketing.internal.entity.MessageTemplate;
import com.positivity.marketing.internal.enums.AudienceType;
import com.positivity.marketing.internal.enums.CampaignChannel;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MessageTemplateRepository extends JpaRepository<MessageTemplate, UUID> {

    Optional<MessageTemplate> findByNameIgnoreCase(String name);

    boolean existsByNameIgnoreCase(String name);

    List<MessageTemplate> findByChannelAndAudienceTypeOrderByNameAsc(
            CampaignChannel channel, AudienceType audienceType);

    List<MessageTemplate> findAllByOrderByNameAsc();
}

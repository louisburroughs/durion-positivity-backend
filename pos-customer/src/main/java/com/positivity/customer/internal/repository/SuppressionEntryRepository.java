package com.positivity.customer.internal.repository;

import com.positivity.customer.internal.entity.SuppressionEntry;
import com.positivity.customer.internal.enums.MarketingChannel;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface SuppressionEntryRepository extends JpaRepository<SuppressionEntry, UUID> {

    Optional<SuppressionEntry> findByChannelAndAddressHash(MarketingChannel channel, String addressHash);

    boolean existsByChannelAndAddressHash(MarketingChannel channel, String addressHash);

    List<SuppressionEntry> findByPartyId(UUID partyId);

    boolean existsByPartyIdAndChannel(UUID partyId, MarketingChannel channel);

    Page<SuppressionEntry> findByChannel(MarketingChannel channel, Pageable pageable);
}

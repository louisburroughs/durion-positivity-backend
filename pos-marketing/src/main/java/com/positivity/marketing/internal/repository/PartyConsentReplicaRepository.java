package com.positivity.marketing.internal.repository;

import com.positivity.marketing.internal.entity.PartyConsentReplica;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PartyConsentReplicaRepository extends JpaRepository<PartyConsentReplica, String> {

    Optional<PartyConsentReplica> findByPartyIdAndChannel(UUID partyId, String channel);

    /**
     * Batch-load for audience preview, which evaluates every member of a snapshot.
     */
    List<PartyConsentReplica> findByChannelAndPartyIdIn(String channel, Collection<UUID> partyIds);
}

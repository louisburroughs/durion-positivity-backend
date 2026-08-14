package com.positivity.marketing.internal.repository;

import com.positivity.marketing.internal.entity.SuppressionReplica;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SuppressionReplicaRepository extends JpaRepository<SuppressionReplica, String> {

    boolean existsByChannelAndAddressHash(String channel, String addressHash);

    List<SuppressionReplica> findByPartyId(UUID partyId);

    boolean existsByPartyIdAndChannel(UUID partyId, String channel);

    /**
     * Batch-load for audience preview, which evaluates every member of a snapshot. Returns ids
     * rather than rows because the caller only needs set membership.
     */
    @Query("SELECT s.partyId FROM SuppressionReplica s WHERE s.channel = :channel AND s.partyId IN :partyIds")
    List<UUID> findPartyIdsByChannelAndPartyIdIn(
            @Param("channel") String channel, @Param("partyIds") Collection<UUID> partyIds);
}

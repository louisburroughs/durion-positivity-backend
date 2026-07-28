package com.positivity.marketing.internal.repository;

import com.positivity.marketing.internal.entity.SuppressionReplica;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface SuppressionReplicaRepository extends JpaRepository<SuppressionReplica, String> {

    boolean existsByChannelAndAddressHash(String channel, String addressHash);

    List<SuppressionReplica> findByPartyId(UUID partyId);

    boolean existsByPartyIdAndChannel(UUID partyId, String channel);
}

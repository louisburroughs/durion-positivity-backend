package com.positivity.catalog.internal.repository;

import com.positivity.catalog.internal.entity.TreadDesignMatchCandidateEntity;
import com.positivity.catalog.internal.enums.MatchTier;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TreadDesignMatchCandidateRepository extends JpaRepository<TreadDesignMatchCandidateEntity, UUID> {

    List<TreadDesignMatchCandidateEntity> findByTreadDesignIdOrderByScoreDesc(UUID treadDesignId);

    List<TreadDesignMatchCandidateEntity> findByTreadDesignIdInOrderByScoreDesc(List<UUID> treadDesignIds);

    /**
     * Other designs' claims on the same product at a given tier — the ambiguity rule's only query
     * (#1645). Excluding the design being matched is what makes "somebody else also wants this
     * product" a question about the catalog rather than about this pass.
     */
    List<TreadDesignMatchCandidateEntity> findByProductIdAndTierAndTreadDesignIdNot(
            UUID productId, MatchTier tier, UUID treadDesignId);

    void deleteByTreadDesignId(UUID treadDesignId);
}

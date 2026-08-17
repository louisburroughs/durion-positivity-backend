package com.positivity.catalog.internal.repository;

import com.positivity.catalog.internal.entity.TreadDesignImageEntity;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TreadDesignImageRepository extends JpaRepository<TreadDesignImageEntity, UUID> {

    List<TreadDesignImageEntity> findByTreadDesignId(UUID treadDesignId);

    /** Bulk fetch for a page of designs (CAP-324 #1352), so listing unmatched designs is not N+1. */
    List<TreadDesignImageEntity> findByTreadDesignIdIn(Collection<UUID> treadDesignIds);

    void deleteByTreadDesignId(UUID treadDesignId);
}

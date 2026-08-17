package com.positivity.catalog.internal.repository;

import com.positivity.catalog.internal.entity.TreadDesignImageEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TreadDesignImageRepository extends JpaRepository<TreadDesignImageEntity, UUID> {

    List<TreadDesignImageEntity> findByTreadDesignId(UUID treadDesignId);

    void deleteByTreadDesignId(UUID treadDesignId);
}

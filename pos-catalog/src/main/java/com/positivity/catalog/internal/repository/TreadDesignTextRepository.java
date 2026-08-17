package com.positivity.catalog.internal.repository;

import com.positivity.catalog.internal.entity.TreadDesignTextEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TreadDesignTextRepository extends JpaRepository<TreadDesignTextEntity, UUID> {

    List<TreadDesignTextEntity> findByTreadDesignId(UUID treadDesignId);

    void deleteByTreadDesignId(UUID treadDesignId);
}

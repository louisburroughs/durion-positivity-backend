package com.positivity.catalog.internal.repository;

import com.positivity.catalog.internal.entity.UomConversionEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UomConversionRepository extends JpaRepository<UomConversionEntity, UUID> {

    Optional<UomConversionEntity> findByFromUomCodeAndToUomCodeAndIsActiveTrue(String fromUomCode, String toUomCode);

    List<UomConversionEntity> findAllByIsActiveTrue();
}

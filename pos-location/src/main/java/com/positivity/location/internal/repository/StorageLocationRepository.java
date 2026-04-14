package com.positivity.location.internal.repository;

import com.positivity.location.internal.entity.StorageLocationEntity;
import com.positivity.location.internal.enums.StorageLocationStatus;
import com.positivity.location.internal.enums.StorageLocationType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Repository for storage location persistence and lookup operations.
 *
 * Issue: CAP-214 #39
 */
@Repository
public interface StorageLocationRepository extends JpaRepository<StorageLocationEntity, UUID> {

    boolean existsByNameIgnoreCaseAndSiteId(String name, UUID siteId);

    boolean existsByBarcodeAndSiteId(String barcode, UUID siteId);

    boolean existsByNameIgnoreCaseAndSiteIdAndIdNot(String name, UUID siteId, UUID id);

    boolean existsByBarcodeAndSiteIdAndIdNot(String barcode, UUID siteId, UUID id);

    Optional<StorageLocationEntity> findByIdAndSiteId(UUID id, UUID siteId);

    Page<StorageLocationEntity> findBySiteId(UUID siteId, Pageable pageable);

    Page<StorageLocationEntity> findBySiteIdAndType(UUID siteId, StorageLocationType type, Pageable pageable);

    Page<StorageLocationEntity> findBySiteIdAndStatus(UUID siteId, StorageLocationStatus status, Pageable pageable);

    Page<StorageLocationEntity> findBySiteIdAndTypeAndStatus(
            UUID siteId, StorageLocationType type, StorageLocationStatus status, Pageable pageable);

    Optional<StorageLocationEntity> findByParentStorageLocationId(UUID parentId);

    /**
     * Optional repository-level cycle check. Service-level traversal remains the
     * source
     * of truth and this method defaults to false unless overridden.
     *
     * @param storageLocationId location being reparented
     * @param proposedParentId  proposed new parent location
     * @return true when a cycle is detected at repository/query level
     */
    default boolean existsCycleForParent(UUID storageLocationId, UUID proposedParentId) {
        return false;
    }
}

package com.positivity.location.internal.repository;

import com.positivity.location.internal.entity.StorageLocationEntity;
import com.positivity.location.internal.enums.StorageLocationStatus;
import com.positivity.location.internal.enums.StorageLocationType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Repository for storage location persistence and lookup operations.
 *
 * Issue: CAP-214 #39
 */
@Repository
public interface StorageLocationRepository extends JpaRepository<StorageLocationEntity, UUID> {

    // NOTE: `site` and `parentStorageLocation` are @ManyToOne associations, not
    // scalar id columns. The convenience getters getSiteId()/getParentStorageLocationId()
    // shadow path derivation, so Spring Data would emit `s.siteId` (which has no
    // persistent attribute) and fail with UnknownPathException. Each query below
    // is therefore expressed explicitly against the association id path.

    @Query("SELECT COUNT(s) > 0 FROM StorageLocationEntity s "
            + "WHERE LOWER(s.name) = LOWER(:name) AND s.site.id = :siteId")
    boolean existsByNameIgnoreCaseAndSiteId(@Param("name") String name, @Param("siteId") UUID siteId);

    @Query("SELECT COUNT(s) > 0 FROM StorageLocationEntity s " + "WHERE s.barcode = :barcode AND s.site.id = :siteId")
    boolean existsByBarcodeAndSiteId(@Param("barcode") String barcode, @Param("siteId") UUID siteId);

    @Query("SELECT COUNT(s) > 0 FROM StorageLocationEntity s "
            + "WHERE LOWER(s.name) = LOWER(:name) AND s.site.id = :siteId AND s.id <> :id")
    boolean existsByNameIgnoreCaseAndSiteIdAndIdNot(
            @Param("name") String name, @Param("siteId") UUID siteId, @Param("id") UUID id);

    @Query("SELECT COUNT(s) > 0 FROM StorageLocationEntity s "
            + "WHERE s.barcode = :barcode AND s.site.id = :siteId AND s.id <> :id")
    boolean existsByBarcodeAndSiteIdAndIdNot(
            @Param("barcode") String barcode, @Param("siteId") UUID siteId, @Param("id") UUID id);

    @Query("SELECT s FROM StorageLocationEntity s WHERE s.id = :id AND s.site.id = :siteId")
    Optional<StorageLocationEntity> findByIdAndSiteId(@Param("id") UUID id, @Param("siteId") UUID siteId);

    @Query("SELECT s FROM StorageLocationEntity s WHERE s.site.id = :siteId")
    Page<StorageLocationEntity> findBySiteId(@Param("siteId") UUID siteId, Pageable pageable);

    /**
     * Unpaginated fetch of every storage location of a site regardless of
     * status, used by the topology endpoint (FR-3).
     *
     * Issue: CAP-214 #655
     *
     * @param siteId site identifier
     * @return all storage locations of the site
     */
    @Query("SELECT s FROM StorageLocationEntity s WHERE s.site.id = :siteId")
    List<StorageLocationEntity> findAllBySiteId(@Param("siteId") UUID siteId);

    @Query("SELECT s FROM StorageLocationEntity s WHERE s.site.id = :siteId AND s.type = :type")
    Page<StorageLocationEntity> findBySiteIdAndType(
            @Param("siteId") UUID siteId, @Param("type") StorageLocationType type, Pageable pageable);

    @Query("SELECT s FROM StorageLocationEntity s WHERE s.site.id = :siteId AND s.status = :status")
    Page<StorageLocationEntity> findBySiteIdAndStatus(
            @Param("siteId") UUID siteId, @Param("status") StorageLocationStatus status, Pageable pageable);

    @Query("SELECT s FROM StorageLocationEntity s "
            + "WHERE s.site.id = :siteId AND s.type = :type AND s.status = :status")
    Page<StorageLocationEntity> findBySiteIdAndTypeAndStatus(
            @Param("siteId") UUID siteId,
            @Param("type") StorageLocationType type,
            @Param("status") StorageLocationStatus status,
            Pageable pageable);

    @Query("SELECT s FROM StorageLocationEntity s WHERE s.parentStorageLocation.id = :parentId")
    Optional<StorageLocationEntity> findByParentStorageLocationId(@Param("parentId") UUID parentId);

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

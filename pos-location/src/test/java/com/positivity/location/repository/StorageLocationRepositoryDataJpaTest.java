package com.positivity.location.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.location.internal.entity.Location;
import com.positivity.location.internal.entity.StorageLocationEntity;
import com.positivity.location.internal.enums.AllowNewProductPolicy;
import com.positivity.location.internal.enums.StorageCategory;
import com.positivity.location.internal.enums.StorageLocationStatus;
import com.positivity.location.internal.enums.StorageLocationType;
import com.positivity.location.internal.repository.StorageLocationRepository;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * Real persistence test for the storage-location finders. The controller
 * contract IT mocks {@code StorageLocationService}, so it never exercises these
 * derived queries against a database, and Flyway (with the operational seed) is
 * disabled under the test profile — so nothing else covered them either.
 *
 * <p>The queries reference the {@code site} and {@code parentStorageLocation}
 * associations, whose convenience getters (getSiteId/getParentStorageLocationId)
 * shadow Spring Data path derivation and previously produced an invalid
 * {@code s.siteId} JPQL path → UnknownPathException / HTTP 500 at runtime. This
 * test fails (UnknownPathException) without the explicit {@code @Query}
 * association paths and passes with them.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class StorageLocationRepositoryDataJpaTest {

    @Autowired
    private StorageLocationRepository repository;

    @Autowired
    private EntityManager em;

    private Location persistSite(String code) {
        Instant now = Instant.now();
        Location site = Location.builder()
                .id(UUID.randomUUID())
                .name(code + " Site")
                .normalizedName((code + " site").toLowerCase())
                .code(code + "-" + UUID.randomUUID())
                .status("ACTIVE")
                .createdAt(now)
                .updatedAt(now)
                .build();
        em.persist(site);
        em.flush();
        return site;
    }

    private StorageLocationEntity persistStorage(
            Location site, String name, StorageLocationType type, StorageLocationEntity parent) {
        Instant now = Instant.now();
        StorageLocationEntity sl = StorageLocationEntity.builder()
                .id(UUID.randomUUID())
                .name(name)
                .type(type)
                .status(StorageLocationStatus.ACTIVE)
                .site(site)
                .parentStorageLocation(parent)
                .createdAt(now)
                .updatedAt(now)
                .build();
        StorageLocationEntity saved = repository.save(sl);
        em.flush();
        return saved;
    }

    @Test
    void findBySiteId_returnsOnlyThatSitesStorageLocations() {
        Location siteA = persistSite("SITE-A");
        Location siteB = persistSite("SITE-B");
        persistStorage(siteA, "Floor A", StorageLocationType.FLOOR, null);
        persistStorage(siteA, "Shelf A", StorageLocationType.SHELF, null);
        persistStorage(siteB, "Floor B", StorageLocationType.FLOOR, null);

        Page<StorageLocationEntity> page = repository.findBySiteId(siteA.getId(), PageRequest.of(0, 20));

        assertThat(page.getTotalElements()).isEqualTo(2);
        assertThat(page.getContent())
                .extracting(StorageLocationEntity::getName)
                .containsExactlyInAnyOrder("Floor A", "Shelf A");
    }

    @Test
    void findByIdAndSiteId_scopesToSite() {
        Location site = persistSite("SITE-C");
        StorageLocationEntity sl = persistStorage(site, "Bin C", StorageLocationType.BIN, null);

        assertThat(repository.findByIdAndSiteId(sl.getId(), site.getId())).isPresent();
        assertThat(repository.findByIdAndSiteId(sl.getId(), UUID.randomUUID())).isEmpty();
    }

    @Test
    void findBySiteIdAndTypeAndStatus_filtersCorrectly() {
        Location site = persistSite("SITE-D");
        persistStorage(site, "Bin D1", StorageLocationType.BIN, null);
        persistStorage(site, "Floor D1", StorageLocationType.FLOOR, null);

        Page<StorageLocationEntity> bins = repository.findBySiteIdAndTypeAndStatus(
                site.getId(), StorageLocationType.BIN, StorageLocationStatus.ACTIVE, PageRequest.of(0, 20));

        assertThat(bins.getContent()).extracting(StorageLocationEntity::getName).containsExactly("Bin D1");
    }

    @Test
    void findAllBySiteId_resolvesAssociationPath() {
        Location site = persistSite("SITE-E");
        persistStorage(site, "Floor E", StorageLocationType.FLOOR, null);

        assertThat(repository.findAllBySiteId(site.getId())).hasSize(1);
    }

    @Test
    void findByParentStorageLocationId_resolvesAssociationPath() {
        Location site = persistSite("SITE-F");
        StorageLocationEntity shelf = persistStorage(site, "Shelf F", StorageLocationType.SHELF, null);
        persistStorage(site, "Bin F", StorageLocationType.BIN, shelf);

        assertThat(repository.findByParentStorageLocationId(shelf.getId()))
                .isPresent()
                .get()
                .extracting(StorageLocationEntity::getName)
                .isEqualTo("Bin F");
        assertThat(repository.findByParentStorageLocationId(UUID.randomUUID())).isEmpty();
    }

    @Test
    void existsByNameIgnoreCaseAndSiteId_matchesCaseInsensitively() {
        Location site = persistSite("SITE-G");
        persistStorage(site, "Main Floor", StorageLocationType.FLOOR, null);

        assertThat(repository.existsByNameIgnoreCaseAndSiteId("main floor", site.getId()))
                .isTrue();
        assertThat(repository.existsByNameIgnoreCaseAndSiteId("main floor", UUID.randomUUID()))
                .isFalse();
    }

    @Test
    void findBySiteIdAndStatus_filtersByStatus() {
        Location site = persistSite("SITE-H");
        StorageLocationEntity inactive = persistStorage(site, "Old Bin", StorageLocationType.BIN, null);
        inactive.setStatus(StorageLocationStatus.INACTIVE);
        repository.save(inactive);
        persistStorage(site, "Active Bin", StorageLocationType.BIN, null);

        Page<StorageLocationEntity> active =
                repository.findBySiteIdAndStatus(site.getId(), StorageLocationStatus.ACTIVE, PageRequest.of(0, 20));

        assertThat(active.getContent())
                .extracting(StorageLocationEntity::getName)
                .containsExactly("Active Bin");
    }

    @Test
    void findBySiteIdAndType_filtersByType() {
        Location site = persistSite("SITE-I");
        persistStorage(site, "Shelf I", StorageLocationType.SHELF, null);
        persistStorage(site, "Floor I", StorageLocationType.FLOOR, null);

        Page<StorageLocationEntity> shelves =
                repository.findBySiteIdAndType(site.getId(), StorageLocationType.SHELF, PageRequest.of(0, 20));

        assertThat(shelves.getContent())
                .extracting(StorageLocationEntity::getName)
                .containsExactly("Shelf I");
    }

    @Test
    void existsByBarcodeAndSiteId_scopesToSite() {
        Location site = persistSite("SITE-J");
        StorageLocationEntity bin = StorageLocationEntity.builder()
                .id(UUID.randomUUID())
                .name("Barcoded Bin")
                .type(StorageLocationType.BIN)
                .status(StorageLocationStatus.ACTIVE)
                .site(site)
                .barcode("BC-J-001")
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
        repository.save(bin);
        em.flush();

        assertThat(repository.existsByBarcodeAndSiteId("BC-J-001", site.getId()))
                .isTrue();
        assertThat(repository.existsByBarcodeAndSiteId("BC-J-001", UUID.randomUUID()))
                .isFalse();
    }

    /**
     * The putaway capability columns must survive a real persist/reload cycle (#1514) — the
     * service tests stub the repository, so only this test proves the JPA mapping and the
     * generated DDL agree on the three new columns.
     */
    @Test
    void storageCapabilityColumns_roundTripThroughPersistence() {
        Location site = persistSite("SITE-L");
        StorageLocationEntity rack = StorageLocationEntity.builder()
                .id(UUID.randomUUID())
                .name("Battery Rack")
                .type(StorageLocationType.SHELF)
                .status(StorageLocationStatus.ACTIVE)
                .site(site)
                .storageCategoryCode(StorageCategory.BATTERY_RACK)
                .hazardContainment(true)
                .allowNewProduct(AllowNewProductPolicy.SAME_PRODUCT_ONLY)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
        repository.save(rack);
        em.flush();
        em.clear();

        StorageLocationEntity reloaded = repository.findById(rack.getId()).orElseThrow();
        assertThat(reloaded.getStorageCategoryCode()).isEqualTo(StorageCategory.BATTERY_RACK);
        assertThat(reloaded.isHazardContainment()).isTrue();
        assertThat(reloaded.getAllowNewProduct()).isEqualTo(AllowNewProductPolicy.SAME_PRODUCT_ONLY);
    }

    /**
     * A row created without a declared capability keeps NULL in the column (no backfill needed
     * for pre-#1514 rows) while the containment/mixing attributes take their non-null defaults.
     */
    @Test
    void storageCapabilityColumns_defaultWhenUndeclared() {
        Location site = persistSite("SITE-M");
        StorageLocationEntity bin = persistStorage(site, "Undeclared Bin", StorageLocationType.BIN, null);
        em.flush();
        em.clear();

        StorageLocationEntity reloaded = repository.findById(bin.getId()).orElseThrow();
        assertThat(reloaded.getStorageCategoryCode()).isNull();
        assertThat(reloaded.isHazardContainment()).isFalse();
        assertThat(reloaded.getAllowNewProduct()).isEqualTo(AllowNewProductPolicy.MIXED);
    }

    @Test
    void existsByNameAndBarcodeAndIdNot_excludeSelfDuringRename() {
        Location site = persistSite("SITE-K");
        StorageLocationEntity a = StorageLocationEntity.builder()
                .id(UUID.randomUUID())
                .name("Dup Floor")
                .type(StorageLocationType.FLOOR)
                .status(StorageLocationStatus.ACTIVE)
                .site(site)
                .barcode("BC-K-001")
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
        repository.save(a);
        em.flush();

        // name/barcode collision excluding the row itself -> false (only its own row matches)
        assertThat(repository.existsByNameIgnoreCaseAndSiteIdAndIdNot("dup floor", site.getId(), a.getId()))
                .isFalse();
        assertThat(repository.existsByBarcodeAndSiteIdAndIdNot("BC-K-001", site.getId(), a.getId()))
                .isFalse();
        // a different id -> the existing row counts as a collision
        assertThat(repository.existsByNameIgnoreCaseAndSiteIdAndIdNot("dup floor", site.getId(), UUID.randomUUID()))
                .isTrue();
        assertThat(repository.existsByBarcodeAndSiteIdAndIdNot("BC-K-001", site.getId(), UUID.randomUUID()))
                .isTrue();
    }
}

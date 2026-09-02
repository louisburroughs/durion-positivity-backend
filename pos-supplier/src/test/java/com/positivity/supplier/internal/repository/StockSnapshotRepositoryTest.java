package com.positivity.supplier.internal.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.supplier.TestClockConfig;
import com.positivity.supplier.internal.config.JpaConfig;
import com.positivity.supplier.internal.entity.StockSnapshotEntity;
import com.positivity.supplier.internal.entity.StockSnapshotLineEntity;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

/**
 * The database-level behaviours of the snapshot read surface (issue #1638 decision 5): where a
 * null {@code snapshotAsOf} ranks in the latest-snapshot ordering — dialect behaviour, not
 * application logic — and the line search's matching and deterministic paging order.
 */
@DataJpaTest(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:pos_supplier_stocksnap;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
            "spring.jpa.hibernate.ddl-auto=validate"
        })
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaConfig.class, TestClockConfig.class})
@DisplayName("Stock snapshot reads persistence (CAP-322, #1638)")
class StockSnapshotRepositoryTest {

    private static final UUID PROFILE_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b");

    @Autowired
    private StockSnapshotRepository snapshotRepository;

    @Autowired
    private StockSnapshotLineRepository lineRepository;

    private StockSnapshotEntity snapshot(String status, Instant snapshotAsOf) {
        return snapshotRepository.saveAndFlush(StockSnapshotEntity.builder()
                .vendorProfileId(PROFILE_ID)
                .supplierRef("michelin-eu")
                .protocolVersion("EDIWHEEL_B-2.1")
                .status(status)
                .snapshotAsOf(snapshotAsOf)
                .buyerAccountNumber("ACC-100")
                .fetchedAt(Instant.parse("2026-08-13T06:00:00Z"))
                .correlationId("corr-1")
                .build());
    }

    private StockSnapshotLineEntity line(UUID snapshotId, String ean, String code, String description) {
        return lineRepository.saveAndFlush(StockSnapshotLineEntity.builder()
                .snapshotId(snapshotId)
                .vendorProfileId(PROFILE_ID)
                .articleEan(ean)
                .supplierArticleCode(code)
                .description(description)
                .availableQuantity(4)
                .build());
    }

    @Test
    void ranksTheLatestSnapshotByTheVendorsOwnInstant() {
        snapshot("COMPLETED", Instant.parse("2026-08-11T04:00:00Z"));
        StockSnapshotEntity newest = snapshot("COMPLETED", Instant.parse("2026-08-12T04:00:00Z"));

        assertThat(snapshotRepository.findByVendorProfileIdNewestSnapshotAsOfFirst(PROFILE_ID, PageRequest.of(0, 1)))
                .extracting(StockSnapshotEntity::getSnapshotId)
                .containsExactly(newest.getSnapshotId());
    }

    @Test
    void neverLetsASnapshotWithoutAVendorInstantOutrankOneWithOne() {
        // The `nulls last` the query is written for. PostgreSQL sorts nulls FIRST for DESC, so
        // without it a failed fetch that never parsed a vendor timestamp would beat every real
        // snapshot to "latest" — asserted here because it is dialect behaviour.
        StockSnapshotEntity real = snapshot("COMPLETED", Instant.parse("2026-08-11T04:00:00Z"));
        snapshot("FAILED", null);

        assertThat(snapshotRepository.findByVendorProfileIdNewestSnapshotAsOfFirst(PROFILE_ID, PageRequest.of(0, 1)))
                .extracting(StockSnapshotEntity::getSnapshotId)
                .containsExactly(real.getSnapshotId());
    }

    @Test
    void servesTheFailedSnapshotWhenItIsAllThereIs() {
        // A profile whose only snapshot is a failed fetch still has a latest snapshot: the failure
        // IS the last thing known, and hiding it would read as "never fetched".
        StockSnapshotEntity failed = snapshot("FAILED", null);

        assertThat(snapshotRepository.findByVendorProfileIdNewestSnapshotAsOfFirst(PROFILE_ID, PageRequest.of(0, 1)))
                .extracting(StockSnapshotEntity::getSnapshotId)
                .containsExactly(failed.getSnapshotId());
    }

    @Test
    void answersNothingForAProfileWithNoSnapshots() {
        snapshot("COMPLETED", Instant.parse("2026-08-11T04:00:00Z"));

        assertThat(snapshotRepository.findByVendorProfileIdNewestSnapshotAsOfFirst(
                        UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4aff"), PageRequest.of(0, 1)))
                .isEmpty();
    }

    @Test
    void searchesLinesAcrossEanVendorCodeAndDescriptionCaseInsensitively() {
        UUID snapshotId =
                snapshot("COMPLETED", Instant.parse("2026-08-12T04:00:00Z")).getSnapshotId();
        line(snapshotId, "3528709999083", "999908", "MICHELIN PILOT SPORT 5");
        line(snapshotId, "4024069999999", "112233", "Continental EcoContact 6");
        line(snapshotId, null, null, null);

        assertThat(lineRepository.searchBySnapshotId(snapshotId, "%pilot%", PageRequest.of(0, 10)))
                .extracting(StockSnapshotLineEntity::getSupplierArticleCode)
                .containsExactly("999908");
        assertThat(lineRepository.searchBySnapshotId(snapshotId, "%3528709%", PageRequest.of(0, 10)))
                .extracting(StockSnapshotLineEntity::getSupplierArticleCode)
                .containsExactly("999908");
        assertThat(lineRepository.searchBySnapshotId(snapshotId, "%112233%", PageRequest.of(0, 10)))
                .extracting(StockSnapshotLineEntity::getArticleEan)
                .containsExactly("4024069999999");
    }

    @Test
    void pagesLinesInInsertionOrderWithinOneSnapshotOnly() {
        UUID snapshotId =
                snapshot("COMPLETED", Instant.parse("2026-08-12T04:00:00Z")).getSnapshotId();
        UUID otherSnapshotId =
                snapshot("COMPLETED", Instant.parse("2026-08-12T05:00:00Z")).getSnapshotId();
        line(snapshotId, "111", "first", null);
        line(snapshotId, "222", "second", null);
        line(snapshotId, "333", "third", null);
        line(otherSnapshotId, "444", "elsewhere", null);

        Page<StockSnapshotLineEntity> firstPage =
                lineRepository.searchBySnapshotId(snapshotId, null, PageRequest.of(0, 2));

        // UUIDv7 line ids are time-ordered, so insertion order is document order — and unlike
        // createdAt they are unique, so the paging boundary is deterministic.
        assertThat(firstPage.getTotalElements()).isEqualTo(3);
        assertThat(firstPage.getContent())
                .extracting(StockSnapshotLineEntity::getSupplierArticleCode)
                .containsExactly("first", "second");
        assertThat(lineRepository.searchBySnapshotId(snapshotId, null, PageRequest.of(1, 2)))
                .extracting(StockSnapshotLineEntity::getSupplierArticleCode)
                .containsExactly("third");
    }
}

package com.positivity.supplier.internal.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.supplier.TestClockConfig;
import com.positivity.supplier.internal.config.JpaConfig;
import com.positivity.supplier.internal.domain.model.ProtocolFamily;
import com.positivity.supplier.internal.entity.PriceCatalogImportEntity;
import com.positivity.supplier.internal.enums.PriceCatalogErrorCode;
import com.positivity.supplier.internal.enums.PriceCatalogImportStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

/**
 * Database-level behaviour of the PRICAT import-run read surface (#1637 decisions 3-5): the V19
 * run-metadata columns round-trip through the entity mapping, the filterable search switches each
 * null predicate off, and the freshness aggregates answer over the right subsets.
 */
@DataJpaTest(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:pos_supplier_pricatimport;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
            "spring.jpa.hibernate.ddl-auto=validate"
        })
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaConfig.class, TestClockConfig.class})
@DisplayName("PRICAT import-run persistence and search (#1637)")
class PriceCatalogImportRepositoryTest {

    private static final UUID PROFILE_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b");
    private static final UUID OTHER_PROFILE_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5c");
    private static final UUID BINDING_A = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5d");
    private static final UUID BINDING_B = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5e");

    @Autowired
    private PriceCatalogImportRepository repository;

    private PriceCatalogImportEntity.PriceCatalogImportEntityBuilder row(
            UUID profileId, PriceCatalogImportStatus status, Instant fetchedAt) {
        return PriceCatalogImportEntity.builder()
                .vendorProfileId(profileId)
                .supplierRef("michelin-eu")
                .protocolFamily(ProtocolFamily.EDIWHEEL_B)
                .protocolVersion("B4_0")
                .status(status)
                .buyerAccountNumber("30012456")
                .fetchedAt(fetchedAt)
                .correlationId("corr-1");
    }

    @Nested
    @DisplayName("run-metadata columns (V19)")
    class RunMetadataColumns {

        @Test
        void roundTripsBindingWindowCheckpointAndErrorCodeThroughTheMigratedSchema() {
            PriceCatalogImportEntity saved = repository.saveAndFlush(
                    row(PROFILE_ID, PriceCatalogImportStatus.FAILED, Instant.parse("2026-08-13T09:00:00Z"))
                            .bindingId(BINDING_A)
                            .windowFrom(Instant.parse("2026-08-12T00:00:00Z"))
                            .windowTo(Instant.parse("2026-08-13T00:00:00Z"))
                            .checkpointState("{\"cursor\":\"opaque\"}")
                            .checkpointAt(Instant.parse("2026-08-13T09:01:00Z"))
                            .failureDetail("vendor exchange failed: TIMEOUT")
                            .errorCode(PriceCatalogErrorCode.FETCH_FAILED)
                            .build());

            PriceCatalogImportEntity reloaded =
                    repository.findById(saved.getImportManifestId()).orElseThrow();
            assertThat(reloaded.getBindingId()).isEqualTo(BINDING_A);
            assertThat(reloaded.getWindowFrom()).isEqualTo(Instant.parse("2026-08-12T00:00:00Z"));
            assertThat(reloaded.getWindowTo()).isEqualTo(Instant.parse("2026-08-13T00:00:00Z"));
            assertThat(reloaded.getCheckpointState()).isEqualTo("{\"cursor\":\"opaque\"}");
            assertThat(reloaded.getCheckpointAt()).isEqualTo(Instant.parse("2026-08-13T09:01:00Z"));
            assertThat(reloaded.getErrorCode()).isEqualTo(PriceCatalogErrorCode.FETCH_FAILED);
            // The structured code complements the free text; both survive together.
            assertThat(reloaded.getFailureDetail()).isEqualTo("vendor exchange failed: TIMEOUT");
        }

        @Test
        void acceptsAFullSnapshotRowWithEveryNewColumnNull() {
            // Pre-V19 rows and every current PRICAT protocol look exactly like this.
            PriceCatalogImportEntity saved = repository.saveAndFlush(
                    row(PROFILE_ID, PriceCatalogImportStatus.COMPLETED, Instant.parse("2026-08-13T09:00:00Z"))
                            .build());

            PriceCatalogImportEntity reloaded =
                    repository.findById(saved.getImportManifestId()).orElseThrow();
            assertThat(reloaded.getBindingId()).isNull();
            assertThat(reloaded.getWindowFrom()).isNull();
            assertThat(reloaded.getWindowTo()).isNull();
            assertThat(reloaded.getCheckpointState()).isNull();
            assertThat(reloaded.getCheckpointAt()).isNull();
            assertThat(reloaded.getErrorCode()).isNull();
        }
    }

    @Nested
    @DisplayName("search")
    class Search {

        private void seed() {
            repository.saveAndFlush(row(PROFILE_ID, PriceCatalogImportStatus.COMPLETED, at("2026-08-10T09:00:00Z"))
                    .bindingId(BINDING_A)
                    .build());
            repository.saveAndFlush(row(PROFILE_ID, PriceCatalogImportStatus.FAILED, at("2026-08-11T09:00:00Z"))
                    .bindingId(BINDING_A)
                    .errorCode(PriceCatalogErrorCode.FETCH_FAILED)
                    .build());
            repository.saveAndFlush(row(PROFILE_ID, PriceCatalogImportStatus.COMPLETED, at("2026-08-12T09:00:00Z"))
                    .bindingId(BINDING_B)
                    .build());
            // Pre-V19 style row: no binding id.
            repository.saveAndFlush(row(PROFILE_ID, PriceCatalogImportStatus.EMPTY, at("2026-08-13T09:00:00Z"))
                    .build());
            // Another profile entirely; must never leak into PROFILE_ID's history.
            repository.saveAndFlush(
                    row(OTHER_PROFILE_ID, PriceCatalogImportStatus.COMPLETED, at("2026-08-12T10:00:00Z"))
                            .bindingId(BINDING_A)
                            .build());
        }

        private Instant at(String instant) {
            return Instant.parse(instant);
        }

        private Page<PriceCatalogImportEntity> search(
                UUID bindingId, PriceCatalogImportStatus status, Instant from, Instant to) {
            return repository.search(PROFILE_ID, bindingId, status, from, to, PageRequest.of(0, 50));
        }

        @Test
        void withNoFiltersListsTheWholeProfileHistoryNewestFirst() {
            seed();

            Page<PriceCatalogImportEntity> page = search(null, null, null, null);

            assertThat(page.getTotalElements()).isEqualTo(4);
            assertThat(page.getContent())
                    .extracting(PriceCatalogImportEntity::getFetchedAt)
                    .containsExactly(
                            at("2026-08-13T09:00:00Z"),
                            at("2026-08-12T09:00:00Z"),
                            at("2026-08-11T09:00:00Z"),
                            at("2026-08-10T09:00:00Z"));
        }

        @Test
        void bindingFilterNarrowsToOneFeedAndExcludesLegacyRowsWithoutABinding() {
            seed();

            Page<PriceCatalogImportEntity> page = search(BINDING_A, null, null, null);

            assertThat(page.getContent())
                    .hasSize(2)
                    .allSatisfy(run -> assertThat(run.getBindingId()).isEqualTo(BINDING_A));
        }

        @Test
        void statusFilterKeepsOnlyThatStatus() {
            seed();

            Page<PriceCatalogImportEntity> page = search(null, PriceCatalogImportStatus.FAILED, null, null);

            assertThat(page.getContent()).singleElement().satisfies(run -> {
                assertThat(run.getStatus()).isEqualTo(PriceCatalogImportStatus.FAILED);
                assertThat(run.getErrorCode()).isEqualTo(PriceCatalogErrorCode.FETCH_FAILED);
            });
        }

        @Test
        void dateWindowIsHalfOpenOnFetchedAt() {
            seed();

            // [11th 09:00, 13th 09:00): includes the lower bound run, excludes the upper bound run.
            Page<PriceCatalogImportEntity> page =
                    search(null, null, at("2026-08-11T09:00:00Z"), at("2026-08-13T09:00:00Z"));

            assertThat(page.getContent())
                    .extracting(PriceCatalogImportEntity::getFetchedAt)
                    .containsExactly(at("2026-08-12T09:00:00Z"), at("2026-08-11T09:00:00Z"));
        }

        @Test
        void filtersCombine() {
            seed();

            Page<PriceCatalogImportEntity> page = search(
                    BINDING_A,
                    PriceCatalogImportStatus.COMPLETED,
                    at("2026-08-01T00:00:00Z"),
                    at("2026-09-01T00:00:00Z"));

            assertThat(page.getContent()).singleElement().satisfies(run -> {
                assertThat(run.getBindingId()).isEqualTo(BINDING_A);
                assertThat(run.getStatus()).isEqualTo(PriceCatalogImportStatus.COMPLETED);
            });
        }
    }

    @Nested
    @DisplayName("freshness aggregates")
    class FreshnessAggregates {

        @Test
        void latestSourceDocumentDateConsidersOnlyCompletedImports() {
            repository.saveAndFlush(
                    row(PROFILE_ID, PriceCatalogImportStatus.COMPLETED, Instant.parse("2026-08-10T09:00:00Z"))
                            .sourceDocumentDate(LocalDate.of(2026, 8, 9))
                            .build());
            // A newer vendor date on a FAILED run must not count: the platform never applied it.
            repository.saveAndFlush(
                    row(PROFILE_ID, PriceCatalogImportStatus.FAILED, Instant.parse("2026-08-12T09:00:00Z"))
                            .sourceDocumentDate(LocalDate.of(2026, 8, 12))
                            .errorCode(PriceCatalogErrorCode.DECODE_FAILED)
                            .build());

            assertThat(repository.findLatestSourceDocumentDate(PROFILE_ID, PriceCatalogImportStatus.COMPLETED))
                    .isEqualTo(LocalDate.of(2026, 8, 9));
        }

        @Test
        void lastFetchedAtSpansEveryRunWhileLastCompletedAtSpansOnlyCommits() {
            repository.saveAndFlush(
                    row(PROFILE_ID, PriceCatalogImportStatus.COMPLETED, Instant.parse("2026-08-10T09:00:00Z"))
                            .completedAt(Instant.parse("2026-08-10T09:04:00Z"))
                            .build());
            repository.saveAndFlush(
                    row(PROFILE_ID, PriceCatalogImportStatus.FAILED, Instant.parse("2026-08-12T09:00:00Z"))
                            .errorCode(PriceCatalogErrorCode.FETCH_FAILED)
                            .build());

            assertThat(repository.findLastFetchedAt(PROFILE_ID)).isEqualTo(Instant.parse("2026-08-12T09:00:00Z"));
            assertThat(repository.findLastCompletedAt(PROFILE_ID)).isEqualTo(Instant.parse("2026-08-10T09:04:00Z"));
        }

        @Test
        void aProfileWithNoImportsAnswersNullEverywhere() {
            assertThat(repository.findLatestSourceDocumentDate(PROFILE_ID, PriceCatalogImportStatus.COMPLETED))
                    .isNull();
            assertThat(repository.findLastFetchedAt(PROFILE_ID)).isNull();
            assertThat(repository.findLastCompletedAt(PROFILE_ID)).isNull();
        }
    }
}

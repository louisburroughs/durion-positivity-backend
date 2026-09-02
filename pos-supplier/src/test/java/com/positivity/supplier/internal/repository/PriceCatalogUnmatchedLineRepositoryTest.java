package com.positivity.supplier.internal.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.supplier.TestClockConfig;
import com.positivity.supplier.internal.config.JpaConfig;
import com.positivity.supplier.internal.domain.model.ProtocolFamily;
import com.positivity.supplier.internal.entity.PriceCatalogImportEntity;
import com.positivity.supplier.internal.entity.PriceCatalogUnmatchedLineEntity;
import com.positivity.supplier.internal.enums.PriceCatalogImportStatus;
import com.positivity.supplier.internal.enums.UnmatchedLineReason;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

/**
 * Database-level behaviour of the quarantine worklist search (#1637 decision 6): the resolved
 * toggle preserves the worklist's historic open-lines default, the reason and date filters switch
 * off on null, and the identifier search treats {@code LIKE} metacharacters as the literal
 * characters an operator pasted.
 */
@DataJpaTest(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:pos_supplier_pricatunmatched;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
            "spring.jpa.hibernate.ddl-auto=validate"
        })
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaConfig.class, TestClockConfig.class})
@DisplayName("PRICAT quarantine worklist search (#1637)")
class PriceCatalogUnmatchedLineRepositoryTest {

    private static final UUID PROFILE_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b");
    private static final Pageable PAGE = PageRequest.of(0, 50);

    @Autowired
    private PriceCatalogUnmatchedLineRepository repository;

    @Autowired
    private PriceCatalogImportRepository importRepository;

    /** The origin import the quarantine rows reference; the table carries a real FK to it. */
    private UUID manifestId;

    @BeforeEach
    void seedOriginImport() {
        manifestId = importRepository
                .saveAndFlush(PriceCatalogImportEntity.builder()
                        .vendorProfileId(PROFILE_ID)
                        .supplierRef("michelin-eu")
                        .protocolFamily(ProtocolFamily.EDIWHEEL_B)
                        .protocolVersion("B4_0")
                        .status(PriceCatalogImportStatus.COMPLETED)
                        .buyerAccountNumber("30012456")
                        .fetchedAt(Instant.parse("2026-08-10T09:00:00Z"))
                        .correlationId("corr-1")
                        .build())
                .getImportManifestId();
    }

    private PriceCatalogUnmatchedLineEntity save(
            String ean,
            String supplierCode,
            String xref,
            UnmatchedLineReason reason,
            Instant fetchedAt,
            Instant resolvedAt) {
        return repository.saveAndFlush(PriceCatalogUnmatchedLineEntity.builder()
                .importManifestId(manifestId)
                .vendorProfileId(PROFILE_ID)
                .articleEan(ean)
                .supplierArticleCode(supplierCode)
                .xReferenceCode(xref)
                .reason(reason)
                .buyerAccountNumber("30012456")
                .fetchedAt(fetchedAt)
                .resolvedAt(resolvedAt)
                .build());
    }

    private Page<PriceCatalogUnmatchedLineEntity> search(
            boolean resolved, UnmatchedLineReason reason, String pattern, Instant from, Instant to) {
        return repository.search(PROFILE_ID, resolved, reason, pattern, from, to, PAGE);
    }

    @Test
    void theDefaultWorklistListsOnlyOpenLines() {
        save("4001861234567", null, null, UnmatchedLineReason.NO_CATALOG_MATCH, at("2026-08-10T09:00:00Z"), null);
        save(
                "4001861234568",
                null,
                null,
                UnmatchedLineReason.NO_CATALOG_MATCH,
                at("2026-08-10T09:00:00Z"),
                at("2026-08-12T09:00:00Z"));

        Page<PriceCatalogUnmatchedLineEntity> open = search(false, null, null, null, null);

        assertThat(open.getContent())
                .singleElement()
                .satisfies(line -> assertThat(line.getResolvedAt()).isNull());
    }

    @Test
    void theResolvedToggleFlipsToClosedLinesForAuditingWhatAFixHealed() {
        save("4001861234567", null, null, UnmatchedLineReason.NO_CATALOG_MATCH, at("2026-08-10T09:00:00Z"), null);
        save(
                "4001861234568",
                null,
                null,
                UnmatchedLineReason.NO_CATALOG_MATCH,
                at("2026-08-10T09:00:00Z"),
                at("2026-08-12T09:00:00Z"));

        Page<PriceCatalogUnmatchedLineEntity> closed = search(true, null, null, null, null);

        assertThat(closed.getContent())
                .singleElement()
                .satisfies(line -> assertThat(line.getResolvedAt()).isNotNull());
    }

    @Test
    void theReasonFilterKeepsOneQuarantineReason() {
        save("4001861234567", null, null, UnmatchedLineReason.NO_CATALOG_MATCH, at("2026-08-10T09:00:00Z"), null);
        save(null, "MI-1", null, UnmatchedLineReason.NO_IDENTIFIER, at("2026-08-10T09:00:00Z"), null);

        Page<PriceCatalogUnmatchedLineEntity> page = search(false, UnmatchedLineReason.NO_IDENTIFIER, null, null, null);

        assertThat(page.getContent())
                .singleElement()
                .satisfies(line -> assertThat(line.getReason()).isEqualTo(UnmatchedLineReason.NO_IDENTIFIER));
    }

    @Test
    void theSearchMatchesAnyOfTheThreeIdentifiersCaseInsensitively() {
        save("4001861234567", null, null, UnmatchedLineReason.NO_CATALOG_MATCH, at("2026-08-10T09:00:00Z"), null);
        save(null, "MI-225-45-17", null, UnmatchedLineReason.NO_IDENTIFIER, at("2026-08-10T09:00:00Z"), null);
        save(null, null, "XREF-9042", UnmatchedLineReason.NO_CATALOG_MATCH, at("2026-08-10T09:00:00Z"), null);

        assertThat(search(false, null, "%186123%", null, null).getContent()).hasSize(1);
        assertThat(search(false, null, "%mi-225%", null, null).getContent()).hasSize(1);
        assertThat(search(false, null, "%xref-90%", null, null).getContent()).hasSize(1);
    }

    @Test
    void escapedMetacharactersMatchThemselvesNotEverything() {
        // The service escapes under '!': an operator pasting a code containing '_' means that
        // character, and an unescaped '_' would instead match every one-character position.
        save(null, "MI_225", null, UnmatchedLineReason.NO_IDENTIFIER, at("2026-08-10T09:00:00Z"), null);
        save(null, "MIX225", null, UnmatchedLineReason.NO_IDENTIFIER, at("2026-08-10T09:00:00Z"), null);

        Page<PriceCatalogUnmatchedLineEntity> page = search(false, null, "%mi!_225%", null, null);

        assertThat(page.getContent())
                .singleElement()
                .satisfies(line -> assertThat(line.getSupplierArticleCode()).isEqualTo("MI_225"));
    }

    @Test
    void theDateWindowIsHalfOpenOnFetchedAt() {
        save("4001861234561", null, null, UnmatchedLineReason.NO_CATALOG_MATCH, at("2026-08-10T09:00:00Z"), null);
        save("4001861234562", null, null, UnmatchedLineReason.NO_CATALOG_MATCH, at("2026-08-11T09:00:00Z"), null);
        save("4001861234563", null, null, UnmatchedLineReason.NO_CATALOG_MATCH, at("2026-08-12T09:00:00Z"), null);

        Page<PriceCatalogUnmatchedLineEntity> page =
                search(false, null, null, at("2026-08-10T09:00:00Z"), at("2026-08-12T09:00:00Z"));

        assertThat(page.getContent())
                .extracting(PriceCatalogUnmatchedLineEntity::getArticleEan)
                .containsExactlyInAnyOrder("4001861234561", "4001861234562");
    }

    private static Instant at(String instant) {
        return Instant.parse(instant);
    }
}

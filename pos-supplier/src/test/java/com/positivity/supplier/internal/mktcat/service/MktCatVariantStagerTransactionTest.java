package com.positivity.supplier.internal.mktcat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import com.positivity.domainevents.supplier.SupplierCatalogEnrichmentText;
import com.positivity.supplier.internal.config.JpaConfig;
import com.positivity.supplier.internal.domain.model.MarketingVariant;
import com.positivity.supplier.internal.domain.model.SupplierRef;
import com.positivity.supplier.internal.repository.SupplierMktCatVariantRepository;
import com.positivity.supplier.internal.service.SupplierOutboxEventWriter;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * The staging write and the outbox emit are one transaction (ADR-0044 section 4).
 *
 * <h2>Why this test exists</h2>
 *
 * {@link MktCatVariantStager#stageAndPublish} used to live on {@link MktCatImporter} and be called on
 * {@code this}. Spring's transaction advice is proxy-based, so that self-call bypassed it and the
 * method ran with no transaction at all: the staged row committed by itself, and a failing outbox
 * write left a variant recorded as published whose event was never emitted. Its hash matches from
 * then on, so every later sweep skips it — the marketing copy is lost silently and permanently.
 *
 * <h2>Why it is not {@code @DataJpaTest}-default transactional</h2>
 *
 * The whole point is a commit boundary. A test wrapped in its own rolled-back transaction cannot
 * observe one — it would pass just as happily with the bug present, because the outer rollback hides
 * whether the inner write ever committed. {@code Propagation.NOT_SUPPORTED} lets the bean manage its
 * own transaction, and cleanup goes over a plain JDBC connection.
 */
@DataJpaTest(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:pos_supplier_mktcat_tx;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
            "spring.jpa.hibernate.ddl-auto=validate"
        })
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaConfig.class, MktCatVariantStager.class, MktCatVariantStagerTransactionTest.StagerSupportConfig.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("MktCatVariantStager — the staged row and the outbox event commit together")
class MktCatVariantStagerTransactionTest {

    @TestConfiguration
    static class StagerSupportConfig {
        @Bean
        Clock clock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }

        @Bean
        ObjectMapper objectMapper() {
            return JsonMapper.builder().build();
        }
    }

    private static final Instant NOW = Instant.parse("2026-08-16T10:00:00Z");
    private static final UUID PROFILE_ID = UUID.fromString("019200aa-0000-7000-8000-0000000000b1");
    private static final SupplierRef SUPPLIER = new SupplierRef("ediwheel-net");

    @Autowired
    private MktCatVariantStager stager;

    @Autowired
    private SupplierMktCatVariantRepository variantRepository;

    /**
     * Mocked so one test can fail the outbox write the way a dead connection or a constraint
     * violation would, after the staged row has already been written.
     */
    @MockitoBean
    private SupplierOutboxEventWriter outboxEventWriter;

    @Autowired
    private DataSource dataSource;

    @AfterEach
    void cleanUp() throws Exception {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM supplier_mktcat_variant");
        }
    }

    @Test
    @DisplayName("a failing outbox write takes the staged row with it, so the variant is republished")
    void failedOutboxWriteRollsBackTheStagedRow() {
        doThrow(new IllegalStateException("outbox unavailable"))
                .when(outboxEventWriter)
                .publish(any(), any());

        assertThatThrownBy(() -> stager.stageAndPublish(PROFILE_ID, SUPPLIER, variant(), texts(), List.of(), "hash-1"))
                .isInstanceOf(IllegalStateException.class);

        // The assertion the bug would fail. Without a transaction the row is already committed, its
        // hash matches on the next sweep, and the variant is never published again.
        assertThat(variantRepository.findByVendorProfileIdAndVendorVariantId(PROFILE_ID, "V1"))
                .isEmpty();
    }

    @Test
    @DisplayName("a successful run commits the staged row and emits the event")
    void successfulRunCommitsBoth() {
        boolean published = stager.stageAndPublish(PROFILE_ID, SUPPLIER, variant(), texts(), List.of(), "hash-1");

        assertThat(published).isTrue();
        verify(outboxEventWriter).publish(any(), any());
        assertThat(variantRepository.findByVendorProfileIdAndVendorVariantId(PROFILE_ID, "V1"))
                .get()
                .satisfies(row -> {
                    assertThat(row.getContentHash()).isEqualTo("hash-1");
                    assertThat(row.getLastPublishedAt()).isEqualTo(NOW);
                });
    }

    private static MarketingVariant variant() {
        return new MarketingVariant("V1", "Michelin", "Primacy 4", null, null, null, null, List.of(), List.of());
    }

    private static List<SupplierCatalogEnrichmentText> texts() {
        return List.of(new SupplierCatalogEnrichmentText("de", "Primacy 4", "Sommerreifen", null));
    }
}

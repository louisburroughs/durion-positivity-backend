package com.positivity.supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.positivity.supplier.internal.adapter.test_family.TestFamilyCodec;
import com.positivity.supplier.internal.domain.model.ProtocolFamily;
import com.positivity.supplier.internal.domain.model.SupplierCapability;
import com.positivity.supplier.internal.registry.AdapterRegistry;
import com.positivity.supplier.internal.registry.AdapterResolution;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Boots the full application context against H2 in PostgreSQL mode with Flyway enabled,
 * proving the module scaffold holds together: the V1 baseline
 * ({@code V1__baseline_supplier_schema.sql}) applies, and the {@link AdapterRegistry}
 * collects Spring-registered {@code SupplierAdapterCodec} beans and resolves them by triple
 * (ADR-0051 §3).
 */
@SpringBootTest(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:pos_supplier_smoke;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
            "spring.jpa.hibernate.ddl-auto=validate",
            "eureka.client.enabled=false"
        })
@Import(PosSupplierApplicationSmokeTest.TestCodecConfig.class)
class PosSupplierApplicationSmokeTest {

    @TestConfiguration
    static class TestCodecConfig {
        @Bean
        TestFamilyCodec testFamilyCodec() {
            return new TestFamilyCodec();
        }
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private AdapterRegistry adapterRegistry;

    @Test
    void flywayBaselineCreatesProcessedEventsTable() {
        Integer rows = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM processed_events", Integer.class);
        assertThat(rows).isZero();
    }

    @Test
    void processedEventsMatchesPlatformIdempotencyLogShape() {
        // ADR-0044 §4 / platform processed_events convention (pos-people-contact V3,
        // pos-catalog V5): event_id varchar(36) PK, owner varchar(64), processed_at
        // timestamptz — all NOT NULL — plus the (owner, event_id) lookup index.
        List<Map<String, Object>> columns =
                jdbcTemplate.queryForList("SELECT column_name, data_type, character_maximum_length, is_nullable"
                        + " FROM information_schema.columns WHERE table_name = 'PROCESSED_EVENTS'"
                        + " ORDER BY ordinal_position");

        assertThat(columns)
                .extracting(
                        c -> c.get("COLUMN_NAME"),
                        c -> c.get("DATA_TYPE"),
                        c -> c.get("CHARACTER_MAXIMUM_LENGTH"),
                        c -> c.get("IS_NULLABLE"))
                .containsExactly(
                        tuple("EVENT_ID", "CHARACTER VARYING", 36L, "NO"),
                        tuple("OWNER", "CHARACTER VARYING", 64L, "NO"),
                        tuple("PROCESSED_AT", "TIMESTAMP WITH TIME ZONE", null, "NO"));

        List<String> primaryKeyColumns = jdbcTemplate.queryForList(
                "SELECT kcu.column_name FROM information_schema.table_constraints tc"
                        + " JOIN information_schema.key_column_usage kcu"
                        + " ON tc.constraint_name = kcu.constraint_name"
                        + " WHERE tc.table_name = 'PROCESSED_EVENTS' AND tc.constraint_type = 'PRIMARY KEY'",
                String.class);
        assertThat(primaryKeyColumns).containsExactly("EVENT_ID");

        List<String> ownerIndexColumns = jdbcTemplate.queryForList(
                "SELECT column_name FROM information_schema.index_columns"
                        + " WHERE table_name = 'PROCESSED_EVENTS'"
                        + " AND index_name = 'IDX_PROCESSED_EVENTS_OWNER_EVENT'"
                        + " ORDER BY ordinal_position",
                String.class);
        assertThat(ownerIndexColumns).containsExactly("OWNER", "EVENT_ID");
    }

    @Test
    void registryCollectsSpringRegisteredCodecBeans() {
        AdapterResolution resolution =
                adapterRegistry.resolve(SupplierCapability.STOCK_INQUIRY, ProtocolFamily.TEST, TestFamilyCodec.TEST_V1);

        assertThat(resolution).isInstanceOf(AdapterResolution.Resolved.class);
    }

    @Test
    void unboundTripleIsTypedNotConfiguredAtContextLevel() {
        AdapterResolution resolution = adapterRegistry.resolve(
                SupplierCapability.MARKETING_CATALOG, ProtocolFamily.EDIWHEEL_JSON, TestFamilyCodec.TEST_V1);

        assertThat(resolution).isInstanceOf(AdapterResolution.NotConfigured.class);
    }
}

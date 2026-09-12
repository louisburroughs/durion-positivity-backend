package com.positivity.supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.positivity.supplier.internal.adapter.test_family.TestFamilyCodec;
import com.positivity.supplier.internal.domain.model.ProtocolFamily;
import com.positivity.supplier.internal.domain.model.SupplierCapability;
import com.positivity.supplier.internal.registry.AdapterRegistry;
import com.positivity.supplier.internal.registry.AdapterResolution;
import com.positivity.supplier.tenancy.PostgresTenancyTestBase;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Boots the full application context against the real PostgreSQL baseline
 * ({@code V1__baseline_supplier.sql}) with Flyway enabled, proving the module scaffold holds
 * together: the baseline applies, and the {@link AdapterRegistry} collects Spring-registered
 * {@code SupplierAdapterCodec} beans and resolves them by triple (ADR-0051 §3).
 */
@Import(PosSupplierApplicationSmokeTest.TestCodecConfig.class)
class PosSupplierApplicationSmokeTest extends PostgresTenancyTestBase {

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
        // processed_events is tenant-global (db/tenancy-global-tables.txt): no policy, so it reads
        // the same with no tenant bound as with one.
        Integer rows = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM processed_events", Integer.class);
        assertThat(rows).isZero();
    }

    @Test
    void processedEventsMatchesPlatformIdempotencyLogShape() {
        // ADR-0044 §4 / platform processed_events convention: event_id varchar(36) PK,
        // tenant_id uuid (nullable: the tenant the listener ran under, plan WS4-3), owner
        // varchar(64), processed_at timestamptz, plus the (owner, tenant_id, event_id) lookup
        // index the per-tenant manifest comparison reads.
        List<Map<String, Object>> columns =
                jdbcTemplate.queryForList("SELECT column_name, data_type, character_maximum_length, is_nullable"
                        + " FROM information_schema.columns WHERE table_name = 'processed_events'"
                        + " ORDER BY ordinal_position");

        assertThat(columns)
                .extracting(
                        c -> c.get("column_name"),
                        c -> c.get("data_type"),
                        c -> c.get("character_maximum_length"),
                        c -> c.get("is_nullable"))
                .containsExactly(
                        tuple("event_id", "character varying", 36, "NO"),
                        tuple("tenant_id", "uuid", null, "YES"),
                        tuple("owner", "character varying", 64, "NO"),
                        tuple("processed_at", "timestamp with time zone", null, "NO"));

        List<String> primaryKeyColumns = jdbcTemplate.queryForList(
                "SELECT kcu.column_name FROM information_schema.table_constraints tc"
                        + " JOIN information_schema.key_column_usage kcu"
                        + " ON tc.constraint_name = kcu.constraint_name"
                        + " WHERE tc.table_name = 'processed_events' AND tc.constraint_type = 'PRIMARY KEY'",
                String.class);
        assertThat(primaryKeyColumns).containsExactly("event_id");

        // pg_index rather than information_schema: PostgreSQL exposes no portable view of the
        // columns of a plain (non-constraint) index.
        List<String> ownerIndexColumns = jdbcTemplate.queryForList(
                "SELECT a.attname FROM pg_class t"
                        + " JOIN pg_index ix ON t.oid = ix.indrelid"
                        + " JOIN pg_class i ON i.oid = ix.indexrelid"
                        + " JOIN pg_attribute a ON a.attrelid = t.oid AND a.attnum = ANY (ix.indkey)"
                        + " WHERE i.relname = 'idx_processed_events_owner_tenant_event'"
                        + " ORDER BY array_position(ix.indkey::smallint[], a.attnum)",
                String.class);
        assertThat(ownerIndexColumns).containsExactly("owner", "tenant_id", "event_id");
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

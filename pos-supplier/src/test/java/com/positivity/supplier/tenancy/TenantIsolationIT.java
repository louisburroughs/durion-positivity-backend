package com.positivity.supplier.tenancy;

import static com.positivity.tenancy.testing.TenantTestSupport.TENANT_A;
import static com.positivity.tenancy.testing.TenantTestSupport.TENANT_B;
import static com.positivity.tenancy.testing.TenantTestSupport.asTenant;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.positivity.supplier.internal.config.SupplierProfileProperties;
import com.positivity.supplier.internal.config.SupplierYamlBootstrap;
import com.positivity.supplier.internal.entity.ExtProductCodeReplica;
import com.positivity.supplier.internal.entity.SupplierProfileEntity;
import com.positivity.supplier.internal.entity.SupplierVendorEntity;
import com.positivity.supplier.internal.entity.SupplierVendorRemitChangeEntity;
import com.positivity.supplier.internal.entity.VendorRemitTo;
import com.positivity.supplier.internal.enums.RemitChangeStatus;
import com.positivity.supplier.internal.enums.VendorStatus;
import com.positivity.supplier.internal.repository.ExtProductCodeReplicaRepository;
import com.positivity.supplier.internal.repository.SupplierProfileRepository;
import com.positivity.supplier.internal.repository.SupplierVendorRemitChangeRepository;
import com.positivity.supplier.internal.repository.SupplierVendorRepository;
import com.positivity.tenancy.TenantContext;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Proves the isolation, not just the mapping (plan R-B7): a row written as tenant A is invisible to
 * tenant B through the repository (Hibernate's {@code @TenantId} filter) and through a raw {@code
 * JdbcTemplate} on the same pool (row-level security alone), and an unbound connection can neither
 * read nor write a scoped table. The catalog code replica is the subject: the simplest scoped table
 * here, and the one the PRICAT matcher reads on every line.
 */
@DisplayName("Tenant isolation on Postgres (ADR-0062, pos-supplier)")
class TenantIsolationIT extends PostgresTenancyTestBase {

    @Autowired
    private ExtProductCodeReplicaRepository replicas;

    @Autowired
    private DataSource dataSource;

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void aRowWrittenAsOneTenantIsInvisibleToAnotherAndToNoTenant() {
        UUID productId = UUID.randomUUID();
        asTenant(
                TENANT_A,
                () -> replicas.saveAndFlush(ExtProductCodeReplica.builder()
                        .productId(productId)
                        .codeType("EAN")
                        .code("4006633000017")
                        .sku("TYRE-1")
                        .aggregateVersion(1L)
                        .build()));

        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        asTenant(TENANT_A, () -> {
            assertThat(replicas.findById(productId))
                    .as("owner reads through the repository")
                    .isPresent();
            assertThat(replicas.findById(productId).orElseThrow().getTenantId()).isEqualTo(TENANT_A);
            assertThat(countByProduct(jdbc, productId))
                    .as("owner reads through raw SQL")
                    .isEqualTo(1);
        });

        asTenant(TENANT_B, () -> {
            assertThat(replicas.findById(productId))
                    .as("Hibernate filter hides the other tenant's row")
                    .isEmpty();
            assertThat(countByProduct(jdbc, productId))
                    .as("RLS hides it from raw SQL too")
                    .isZero();
            assertThat(jdbc.update("UPDATE ext_product_code SET code = '0' WHERE product_id = ?", productId))
                    .as("RLS makes the row unreachable for UPDATE")
                    .isZero();
        });

        // Unbound: the pool RESETs app.current_tenant, so pos_app sees an empty table and cannot insert.
        assertThat(countByProduct(jdbc, productId)).isZero();
        assertThatThrownBy(() -> jdbc.update(
                        "INSERT INTO ext_product_code (product_id, aggregate_version, updated_at) VALUES (?, 1, now())",
                        UUID.randomUUID()))
                .as("no tenant bound: the NOT NULL default is NULL and the policy's WITH CHECK refuses the row")
                .isInstanceOf(DataAccessException.class);

        asTenant(
                TENANT_A,
                () -> assertThat(replicas.findById(productId).orElseThrow().getCode())
                        .as("tenant B's UPDATE touched nothing")
                        .isEqualTo("4006633000017"));
    }

    private static int countByProduct(JdbcTemplate jdbc, UUID productId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM ext_product_code WHERE product_id = ?", Integer.class, productId);
        return count == null ? 0 : count;
    }

    // ── Vendor master (#2516) ───────────────────────────────────────────────────────

    @Autowired
    private SupplierVendorRepository vendors;

    @Autowired
    private SupplierVendorRemitChangeRepository remitChanges;

    @Autowired
    private SupplierProfileRepository profiles;

    @Autowired
    private SupplierYamlBootstrap yamlBootstrap;

    @Autowired
    private PlatformTransactionManager transactionManager;

    /** Committed by these tests; removed through the owner, which bypasses row-level security. */
    @AfterEach
    void removeVendorFixtures() {
        JdbcTemplate owner = new JdbcTemplate(ownerDataSource());
        owner.update("DELETE FROM supplier_profile WHERE supplier_ref LIKE 'iso-%'");
        owner.update("DELETE FROM supplier_vendor_remit_change WHERE reason LIKE 'iso %'");
        owner.update("DELETE FROM supplier_vendor WHERE vendor_number LIKE 'ISO-%'");
    }

    private SupplierVendorEntity vendor(String number) {
        return vendors.saveAndFlush(SupplierVendorEntity.builder()
                .vendorNumber(number)
                .legalName(number + " Inc.")
                .displayName(number)
                .defaultPaymentTerms("NET30")
                .defaultCurrency("USD")
                .status(VendorStatus.ACTIVE)
                .build());
    }

    @Test
    void aVendorAndItsRemitChangesAreInvisibleToAnotherTenantAndItsNumberIsFreeThere() {
        String number = "ISO-" + Long.toHexString(System.nanoTime()).toUpperCase(java.util.Locale.ROOT);
        UUID[] vendorA = new UUID[1];
        asTenant(TENANT_A, () -> {
            vendorA[0] = vendor(number).getVendorId();
            remitChanges.saveAndFlush(SupplierVendorRemitChangeEntity.builder()
                    .vendorId(vendorA[0])
                    .proposedRemitTo(new VendorRemitTo("Payee", "PO Box 1", null, "City", "ST", "00001", "US", null))
                    .reason("iso pending change for isolation")
                    .status(RemitChangeStatus.PENDING)
                    .fromVersion(0)
                    .requestedBy("clerk.a")
                    .requestedAt(java.time.Instant.now())
                    .build());
        });

        asTenant(TENANT_B, () -> {
            assertThat(vendors.findById(vendorA[0]))
                    .as("tenant A's vendor is invisible to B")
                    .isEmpty();
            assertThat(vendors.findByVendorNumber(number)).isEmpty();
            assertThat(remitChanges.findByVendorIdOrderByRequestedAtDesc(vendorA[0]))
                    .as("tenant A's remit-to changes are invisible to B")
                    .isEmpty();
            // AC 2: numbers are unique per tenant, so B may use A's number.
            assertThat(vendor(number).getTenantId()).isEqualTo(TENANT_B);
        });

        asTenant(
                TENANT_A,
                () -> assertThat(vendors.findByVendorNumber(number))
                        .get()
                        .extracting(SupplierVendorEntity::getVendorId)
                        .isEqualTo(vendorA[0]));
    }

    @Test
    void aYamlProfileBindsItsVendorOnlyInTheTenantThatHasIt() {
        // AC 7: one YAML spec, two tenants, the vendor number exists in A only.
        String number = "ISO-Y" + Long.toHexString(System.nanoTime()).toUpperCase(java.util.Locale.ROOT);
        String key = "iso-" + number.toLowerCase(java.util.Locale.ROOT);
        UUID[] vendorA = new UUID[1];
        asTenant(TENANT_A, () -> vendorA[0] = vendor(number).getVendorId());
        SupplierProfileProperties yaml =
                new SupplierProfileProperties(List.of(new SupplierProfileProperties.ProfileSpec(
                        key, "Isolation Vendor", true, null, null, List.of(), List.of(), null, number)));
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        asTenant(TENANT_A, () -> transaction.executeWithoutResult(status -> yamlBootstrap.reconcile(yaml)));
        asTenant(TENANT_B, () -> transaction.executeWithoutResult(status -> yamlBootstrap.reconcile(yaml)));

        asTenant(
                TENANT_A,
                () -> assertThat(profiles.findBySupplierRef(key))
                        .get()
                        .extracting(SupplierProfileEntity::getVendorId)
                        .isEqualTo(vendorA[0]));
        asTenant(
                TENANT_B,
                () -> assertThat(profiles.findBySupplierRef(key))
                        .as("tenant B has no such vendor: the profile is not created there, and startup did not fail")
                        .isEmpty());
    }
}

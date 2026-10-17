package com.positivity.order.tenancy;

import static com.positivity.tenancy.testing.TenantTestSupport.TENANT_A;
import static com.positivity.tenancy.testing.TenantTestSupport.TENANT_B;
import static com.positivity.tenancy.testing.TenantTestSupport.asTenant;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.positivity.order.internal.dto.purchaseorder.ListPurchaseOrderVendorsRequest;
import com.positivity.order.internal.dto.purchaseorder.PurchaseOrderVendorResponse;
import com.positivity.order.internal.enums.PurchaseOrderVendorStatus;
import com.positivity.order.internal.service.PurchaseOrderVendorService;
import com.positivity.tenancy.TenantContext;
import com.positivity.web.common.ReplicationPendingException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The purchase-order vendor read on Postgres (CAP:550, #2680; ADR-0062): the list and the single read see the bound
 * tenant's copy rows only, through forced row-level security; {@code status} and {@code q} filter in the query; the
 * page sorts by display name with the vendor id as tie-breaker; and another tenant's vendor reads as not replicated
 * yet (503), never as absent.
 *
 * <p>Rows go in through the owner connection (which bypasses RLS, like the alpha owner), tagged with a token unique to
 * this run, because the container is shared with the other tenancy tests.
 */
@DisplayName("Purchase-order vendor read isolation and filters on Postgres (#2680)")
class PurchaseOrderVendorReadIT extends PostgresTenancyTestBase {

    @Autowired
    private PurchaseOrderVendorService vendorRead;

    private final String token =
            "VR" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    private final UUID activeA = UUID.randomUUID();
    private final UUID inactiveA = UUID.randomUUID();
    private final UUID activeB = UUID.randomUUID();

    @BeforeEach
    void seed() {
        JdbcTemplate owner = new JdbcTemplate(ownerDataSource());
        insert(owner, TENANT_A, activeA, token + "-002", "Zulu Parts " + token, "ACTIVE");
        insert(owner, TENANT_A, inactiveA, token + "-001", "Alpha Parts " + token, "INACTIVE");
        insert(owner, TENANT_B, activeB, token + "-003", "Bravo Parts " + token, "ACTIVE");
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
        new JdbcTemplate(ownerDataSource())
                .update("DELETE FROM public.ext_supplier_vendor WHERE vendor_number LIKE ?", token + "-%");
    }

    private static void insert(
            JdbcTemplate owner, UUID tenantId, UUID vendorId, String number, String name, String status) {
        owner.update(
                "INSERT INTO public.ext_supplier_vendor (tenant_id, vendor_id, vendor_number, display_name, status,"
                        + " aggregate_version, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                tenantId,
                vendorId,
                number,
                name,
                status,
                1L,
                Timestamp.from(Instant.parse("2026-10-16T00:00:00Z")));
    }

    private Page<PurchaseOrderVendorResponse> list(PurchaseOrderVendorStatus status, String q, Sort sort) {
        return vendorRead.listVendors(new ListPurchaseOrderVendorsRequest(status, q), PageRequest.of(0, 20, sort));
    }

    @Test
    @DisplayName("tenant A lists its own rows only, sorted by display name, and filters by status and q in the query")
    void tenantAListsItsOwnRowsFilteredAndSorted() {
        asTenant(TENANT_A, () -> {
            Page<PurchaseOrderVendorResponse> all = list(null, token, Sort.unsorted());
            assertThat(all.getTotalElements()).isEqualTo(2);
            assertThat(all.getContent().stream().map(PurchaseOrderVendorResponse::vendorId))
                    .as("display-name order: Alpha before Zulu; B's row never appears")
                    .containsExactly(inactiveA, activeA);
            assertThat(all.getContent().get(0))
                    .isEqualTo(new PurchaseOrderVendorResponse(
                            inactiveA, token + "-001", "Alpha Parts " + token, PurchaseOrderVendorStatus.INACTIVE));

            Page<PurchaseOrderVendorResponse> active = list(PurchaseOrderVendorStatus.ACTIVE, token, Sort.unsorted());
            assertThat(active.getContent().stream().map(PurchaseOrderVendorResponse::vendorId))
                    .containsExactly(activeA);

            Page<PurchaseOrderVendorResponse> byNumber =
                    list(null, (token + "-001").toLowerCase(java.util.Locale.ROOT), Sort.unsorted());
            assertThat(byNumber.getContent().stream().map(PurchaseOrderVendorResponse::vendorId))
                    .as("q matches the vendor number, case-insensitively")
                    .containsExactly(inactiveA);

            Page<PurchaseOrderVendorResponse> byName = list(null, "zulu parts " + token, Sort.unsorted());
            assertThat(byName.getContent().stream().map(PurchaseOrderVendorResponse::vendorId))
                    .as("q matches the display name, case-insensitively")
                    .containsExactly(activeA);

            Page<PurchaseOrderVendorResponse> literal = list(null, token + "-00_", Sort.unsorted());
            assertThat(literal.getTotalElements())
                    .as("an underscore in q is a literal, not a wildcard")
                    .isZero();

            Page<PurchaseOrderVendorResponse> none = list(null, token + "-nothing", Sort.unsorted());
            assertThat(none.getContent()).isEmpty();
            assertThat(none.getTotalElements()).isZero();

            Page<PurchaseOrderVendorResponse> byNumberDesc =
                    list(null, token, Sort.by(Sort.Order.desc("vendorNumber")));
            assertThat(byNumberDesc.getContent().stream().map(PurchaseOrderVendorResponse::vendorId))
                    .containsExactly(activeA, inactiveA);
        });
    }

    @Test
    @DisplayName("the single read returns an INACTIVE vendor as such, and another tenant's vendor as not replicated")
    void singleReadIsTenantScoped() {
        asTenant(TENANT_A, () -> {
            assertThat(vendorRead.getVendor(inactiveA).status()).isEqualTo(PurchaseOrderVendorStatus.INACTIVE);
            assertThatThrownBy(() -> vendorRead.getVendor(activeB))
                    .as("B's vendor is not in A's copy: 503, never 404")
                    .isInstanceOfSatisfying(
                            ReplicationPendingException.class,
                            e -> assertThat(e.getCode()).isEqualTo("VENDOR_REPLICATION_PENDING"));
        });
        asTenant(TENANT_B, () -> {
            assertThat(list(null, token, Sort.unsorted()).getContent().stream()
                            .map(PurchaseOrderVendorResponse::vendorId))
                    .containsExactly(activeB);
            assertThatThrownBy(() -> vendorRead.getVendor(activeA)).isInstanceOf(ReplicationPendingException.class);
        });
    }
}

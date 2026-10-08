package com.positivity.supplier.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.positivity.supplier.SupplierPostgresContainer;
import com.positivity.supplier.internal.controller.SupplierVendorController;
import com.positivity.supplier.internal.exception.TaxIdRevealReasonRejectedException;
import com.positivity.supplier.internal.exception.VendorTaxIdUnreadableException;
import com.positivity.supplier.internal.security.SupplierPermissions;
import com.positivity.supplier.internal.vendor.service.SupplierVendorService;
import com.positivity.supplier.internal.vendor.service.model.TaxIdRevealRequest;
import com.positivity.supplier.internal.vendor.service.model.TaxIdRevealView;
import com.positivity.supplier.internal.vendor.service.model.TaxRegistrationDto;
import com.positivity.supplier.internal.vendor.service.model.VendorCreateRequest;
import com.positivity.supplier.internal.vendor.service.model.VendorView;
import com.positivity.tenancy.TenantContext;
import com.positivity.tenancy.testing.TenantTestSupport;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * ADR-0072 Decision 4, IC-003 and CHK-004 over the complete request: through the controller and the real commit, the
 * audit row of every outcome survives ({@code REVEALED}, {@code REASON_REJECTED}, {@code UNREADABLE}), the refused and
 * unreadable outcomes reach the caller only as 400 / 500 exceptions thrown after commit, and an audit insert that fails
 * releases no value. Rows are read back on the owner connection, so only committed rows are seen. Numbers are fake.
 */
@DisplayName("Reveal outcomes are durable through the controller and the commit (#2621, ADR-0072 IC-003)")
class VendorTaxIdRevealCommitIT extends PostgresTenancyTestBase {

    private static final UUID TENANT = TenantTestSupport.TENANT_A;
    private static final String SSN = "000-00-1234";
    private static final String REASON = "Verifying W-9 received 2026-10-08";
    private static final String PREFIX = "COMMIT-IT ";

    @Autowired
    private SupplierVendorController controller;

    @Autowired
    private SupplierVendorService vendorService;

    private final JdbcTemplate owner = new JdbcTemplate(ownerDataSource());

    @BeforeEach
    void bind() {
        TenantContext.bind(TENANT);
        var authentication = UsernamePasswordAuthenticationToken.authenticated(
                "controller.b",
                "n/a",
                List.of(
                        new SimpleGrantedAuthority("ROLE_CONTROLLER"),
                        new SimpleGrantedAuthority(SupplierPermissions.VENDOR_TAX_ID_REVEAL),
                        new SimpleGrantedAuthority(SupplierPermissions.VENDOR_WRITE)));
        authentication.setDetails(Map.of("username", "controller.b"));
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    @AfterEach
    void clear() {
        owner.update("GRANT INSERT ON supplier_vendor_tax_id_reveal TO " + APP_ROLE);
        owner.update("DELETE FROM supplier_vendor_tax_id_reveal WHERE vendor_id IN"
                + " (SELECT vendor_id FROM supplier_vendor WHERE legal_name LIKE '" + PREFIX + "%')");
        owner.update("DELETE FROM supplier_event_outbox WHERE record_key IN"
                + " (SELECT vendor_id::text FROM supplier_vendor WHERE legal_name LIKE '" + PREFIX + "%')");
        owner.update("DELETE FROM supplier_vendor WHERE legal_name LIKE '" + PREFIX + "%'");
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    private VendorView vendor(String name, String number) {
        // An explicit number: an allocated one would advance the tenant's committed counter under other tests.
        return vendorService.createVendor(new VendorCreateRequest(
                "CIT-" + name.toUpperCase(java.util.Locale.ROOT),
                PREFIX + name,
                PREFIX + name,
                List.of(new TaxRegistrationDto(null, "SSN", number, null)),
                null,
                "NET30",
                "USD"));
    }

    private List<Map<String, Object>> committedRows(UUID vendorId) {
        return owner.queryForList(
                "SELECT outcome, reason FROM supplier_vendor_tax_id_reveal WHERE vendor_id = ?", vendorId);
    }

    @Test
    @DisplayName("REVEALED: 200 with the number, and its row committed with the reason")
    void revealedRowCommits() {
        VendorView vendor = vendor("Revealed", SSN);
        UUID registrationId = vendor.taxRegistrations().getFirst().registrationId();

        ResponseEntity<TaxIdRevealView> response =
                controller.revealTaxRegistration(vendor.vendorId(), registrationId, new TaxIdRevealRequest(REASON));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().number()).isEqualTo(SSN);
        assertThat(response.getHeaders().getCacheControl()).contains("no-store");
        assertThat(committedRows(vendor.vendorId())).singleElement().satisfies(row -> {
            assertThat(row.get("outcome")).isEqualTo("REVEALED");
            assertThat(row.get("reason")).isEqualTo(REASON);
        });
    }

    @Test
    @DisplayName("REASON_REJECTED: 400 thrown after commit, and its row with a null reason survives")
    void reasonRejectedRowCommits() {
        VendorView vendor = vendor("Rejected", SSN);
        UUID registrationId = vendor.taxRegistrations().getFirst().registrationId();

        assertThatThrownBy(() -> controller.revealTaxRegistration(
                        vendor.vendorId(), registrationId, new TaxIdRevealRequest("checking 000-00-1234 per W-9")))
                .isInstanceOf(TaxIdRevealReasonRejectedException.class)
                .hasMessageNotContaining(SSN);

        assertThat(committedRows(vendor.vendorId())).singleElement().satisfies(row -> {
            assertThat(row.get("outcome")).isEqualTo("REASON_REJECTED");
            assertThat(row.get("reason")).isNull();
        });
    }

    @Test
    @DisplayName("UNREADABLE: 500 thrown after commit, and its row with a null reason survives")
    void unreadableRowCommits() {
        VendorView source = vendor("Source", SSN);
        VendorView target = vendor("Target", "000-00-5678");
        owner.update(
                "UPDATE supplier_vendor t SET tax_registrations = jsonb_set(t.tax_registrations,"
                        + " '{0,numberCiphertext}', s.tax_registrations -> 0 -> 'numberCiphertext')"
                        + " FROM supplier_vendor s WHERE t.vendor_id = ? AND s.vendor_id = ?",
                target.vendorId(),
                source.vendorId());
        UUID registrationId = target.taxRegistrations().getFirst().registrationId();

        assertThatThrownBy(() -> controller.revealTaxRegistration(
                        target.vendorId(), registrationId, new TaxIdRevealRequest(REASON)))
                .isInstanceOf(VendorTaxIdUnreadableException.class)
                .hasMessageNotContaining(SSN);

        assertThat(committedRows(target.vendorId())).singleElement().satisfies(row -> {
            assertThat(row.get("outcome")).isEqualTo("UNREADABLE");
            assertThat(row.get("reason")).isNull();
        });
    }

    @Test
    @DisplayName("an audit insert that fails releases no value and commits no row")
    void auditFailureReleasesNothing() {
        VendorView vendor = vendor("Closed", SSN);
        UUID registrationId = vendor.taxRegistrations().getFirst().registrationId();
        owner.update("REVOKE INSERT ON supplier_vendor_tax_id_reveal FROM " + APP_ROLE);
        AtomicReference<ResponseEntity<TaxIdRevealView>> returned = new AtomicReference<>();

        assertThatThrownBy(() -> returned.set(controller.revealTaxRegistration(
                        vendor.vendorId(), registrationId, new TaxIdRevealRequest(REASON))))
                .satisfies(failure -> {
                    StringBuilder chain = new StringBuilder();
                    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                        chain.append(cause.getMessage());
                    }
                    assertThat(chain.toString().contains(SSN))
                            .as("number absent")
                            .isFalse();
                });

        assertThat(returned.get()).as("nothing returned").isNull();
        assertThat(committedRows(vendor.vendorId())).isEmpty();
        assertThat(SupplierPostgresContainer.APP_ROLE).isEqualTo(APP_ROLE);
    }
}

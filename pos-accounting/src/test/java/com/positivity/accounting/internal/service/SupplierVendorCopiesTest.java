package com.positivity.accounting.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.entity.ApVendorSettings;
import com.positivity.accounting.internal.entity.ExtSupplierVendor;
import com.positivity.accounting.internal.entity.VendorBill;
import com.positivity.accounting.internal.enums.VendorBillDebitClass;
import com.positivity.accounting.internal.exception.VendorBillException;
import com.positivity.accounting.internal.repository.ApVendorSettingsRepository;
import com.positivity.accounting.internal.repository.ExtSupplierVendorRepository;
import com.positivity.accounting.internal.repository.VendorBillRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/** The vendor rules read from the copy (CAP:550 S24, #2517): AC 5 (requests), 6, 7 and 8's rule. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("SupplierVendorCopies — vendor rules from the copy (S24)")
class SupplierVendorCopiesTest {

    private static final UUID VENDOR = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f8a01");
    private static final String PAYER = "p.payer";
    private static final String OTHER = "q.controller";

    @Mock
    private ExtSupplierVendorRepository vendors;

    @Mock
    private ApVendorSettingsRepository settings;

    @Mock
    private VendorBillRepository bills;

    private SupplierVendorCopies copies;

    @BeforeEach
    void setUp() {
        copies = new SupplierVendorCopies(vendors, settings, bills);
        when(settings.findByVendorId(VENDOR)).thenReturn(Optional.empty());
    }

    private static ExtSupplierVendor vendor(String status, int remitToVersion) {
        ExtSupplierVendor vendor = new ExtSupplierVendor();
        vendor.setVendorId(VENDOR);
        vendor.setVendorNumber("V-000123");
        vendor.setDisplayName("Acme Parts");
        vendor.setStatus(status);
        vendor.setRemitToVersion(remitToVersion);
        vendor.setCreatedBy("u.creator");
        return vendor;
    }

    private static VendorBill bill(String number, Integer approvedAt) {
        VendorBill bill = new VendorBill();
        bill.setVendorBillId(UUID.randomUUID());
        bill.setVendorId(VENDOR);
        bill.setBillNumber(number);
        bill.setApprovedRemitToVersion(approvedAt);
        return bill;
    }

    private void confirmed(int version, String by) {
        ApVendorSettings row = new ApVendorSettings();
        row.setVendorId(VENDOR);
        row.setConfirmedRemitToVersion(version);
        row.setRemitToConfirmedBy(by);
        when(settings.findByVendorId(VENDOR)).thenReturn(Optional.of(row));
    }

    @Test
    @DisplayName("AC 5: a vendor missing from the copy is 503 VENDOR_REPLICATION_PENDING (not yet, ADR-0017 §1); an"
            + " inactive one 422 VENDOR_INACTIVE")
    void newBusinessNeedsAnActiveCopiedVendor() {
        when(vendors.findById(VENDOR)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> copies.requireForNewBusiness(VENDOR, "A payment"))
                .isInstanceOfSatisfying(com.positivity.web.common.ReplicationPendingException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(SupplierVendorCopies.VENDOR_REPLICATION_PENDING);
                    assertThat(e.getReferenceId()).isEqualTo(VENDOR);
                    assertThat(e.getRetryAfter()).isPositive();
                });

        when(vendors.findById(VENDOR)).thenReturn(Optional.of(vendor("INACTIVE", 1)));
        assertThatThrownBy(() -> copies.requireForNewBusiness(VENDOR, "A payment"))
                .isInstanceOfSatisfying(VendorBillException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(VendorBillException.Code.VENDOR_INACTIVE);
                    assertThat(e.getMessage()).contains("V-000123");
                });

        when(vendors.findById(VENDOR)).thenReturn(Optional.of(vendor("ACTIVE", 1)));
        assertThat(copies.requireForNewBusiness(VENDOR, "A payment").getDisplayName())
                .isEqualTo("Acme Parts");
    }

    @Test
    @DisplayName("AC 6: approved at 2, now 3: refused; the payer's own confirmation does not count; another's does")
    void remitToChangeNeedsAnotherPersonsConfirmation() {
        ExtSupplierVendor vendor = vendor("ACTIVE", 3);
        List<VendorBill> planned = List.of(bill("B-1", 2));

        assertThatThrownBy(() -> copies.requireRemitToUnchanged(planned, vendor, PAYER))
                .isInstanceOfSatisfying(VendorBillException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(VendorBillException.Code.VENDOR_PAYMENT_DETAILS_CHANGED);
                    assertThat(e.getCode().status().value()).isEqualTo(409);
                    assertThat(e.getMessage()).contains("B-1").contains("V-000123");
                    assertThat(e.getFieldErrors())
                            .extracting(VendorBillException.FieldError::field)
                            .containsExactly("B-1");
                });

        confirmed(3, PAYER);
        assertThatThrownBy(() -> copies.requireRemitToUnchanged(planned, vendor, PAYER))
                .isInstanceOf(VendorBillException.class);

        confirmed(3, OTHER);
        assertThatCode(() -> copies.requireRemitToUnchanged(planned, vendor, PAYER))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a confirmation of an older version does not pass the current one")
    void staleConfirmationDoesNotCount() {
        confirmed(2, OTHER);
        assertThatThrownBy(() -> copies.requireRemitToUnchanged(List.of(bill("B-1", 2)), vendor("ACTIVE", 3), PAYER))
                .isInstanceOf(VendorBillException.class);
    }

    @Test
    @DisplayName("AC 7: of two planned bills one changed: the whole payment is refused, naming only the changed one")
    void oneChangedBillRefusesTheWholePayment() {
        List<VendorBill> planned = List.of(bill("B-OLD", 1), bill("B-NEW", 3));

        assertThatThrownBy(() -> copies.requireRemitToUnchanged(planned, vendor("ACTIVE", 3), PAYER))
                .isInstanceOfSatisfying(
                        VendorBillException.class,
                        e -> assertThat(e.getFieldErrors())
                                .extracting(VendorBillException.FieldError::field)
                                .containsExactly("B-OLD"));
    }

    @Test
    @DisplayName("a bill approved before S24 (no version) is refused until confirmed")
    void nullVersionIsRefused() {
        assertThatThrownBy(() -> copies.requireRemitToUnchanged(List.of(bill("B-1", null)), vendor("ACTIVE", 0), PAYER))
                .isInstanceOf(VendorBillException.class);
        assertThatCode(() -> copies.requireRemitToUnchanged(List.of(bill("B-1", 0)), vendor("ACTIVE", 0), PAYER))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("rule 9: the vendor's creator, before any bill of the vendor was ever approved")
    void creatorsFirstBill() {
        when(vendors.findById(VENDOR)).thenReturn(Optional.of(vendor("ACTIVE", 1)));
        when(bills.existsByVendorIdAndApprovedAtIsNotNull(VENDOR)).thenReturn(false);
        assertThat(copies.isCreatorsFirstBill(VENDOR, "u.creator")).isTrue();
        assertThat(copies.isCreatorsFirstBill(VENDOR, "someone.else")).isFalse();
        assertThat(copies.isCreatorsFirstBill(VENDOR, null)).isFalse();

        // Approved once, by a person or the system, voided or not: the rule is lifted.
        when(bills.existsByVendorIdAndApprovedAtIsNotNull(VENDOR)).thenReturn(true);
        assertThat(copies.isCreatorsFirstBill(VENDOR, "u.creator")).isFalse();
    }

    @Test
    @DisplayName("rule 10: the vendor's AP defaults as a classification, null without any")
    void apDefaults() {
        assertThat(copies.apDefaults(VENDOR)).isNull();
        ApVendorSettings row = new ApVendorSettings();
        row.setVendorId(VENDOR);
        row.setDefaultDebitClass(VendorBillDebitClass.EXPENSE);
        row.setDefaultExpenseMappingKey("EXPENSE_SHOP_SUPPLIES");
        when(settings.findByVendorId(VENDOR)).thenReturn(Optional.of(row));
        assertThat(copies.apDefaults(VENDOR))
                .isEqualTo(new VendorBillPostingService.Classification(
                        VendorBillDebitClass.EXPENSE, "EXPENSE_SHOP_SUPPLIES"));
    }
}

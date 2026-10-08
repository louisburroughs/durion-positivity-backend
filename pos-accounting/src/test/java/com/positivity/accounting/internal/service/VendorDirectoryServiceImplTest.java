package com.positivity.accounting.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.dto.VendorApSettingsRequest;
import com.positivity.accounting.internal.dto.VendorRemitToConfirmationRequest;
import com.positivity.accounting.internal.dto.VendorResponse;
import com.positivity.accounting.internal.entity.AccountingAuditLog;
import com.positivity.accounting.internal.entity.ApVendorSettings;
import com.positivity.accounting.internal.entity.ExtSupplierVendor;
import com.positivity.accounting.internal.entity.MappingKey;
import com.positivity.accounting.internal.entity.PostingCategory;
import com.positivity.accounting.internal.enums.VendorBillDebitClass;
import com.positivity.accounting.internal.exception.VendorBillException;
import com.positivity.accounting.internal.exception.VendorNotFoundException;
import com.positivity.accounting.internal.repository.AccountingAuditLogRepository;
import com.positivity.accounting.internal.repository.ApVendorSettingsRepository;
import com.positivity.accounting.internal.repository.ExtSupplierVendorRepository;
import com.positivity.accounting.internal.repository.MappingKeyRepository;
import com.positivity.accounting.internal.repository.PostingCategoryRepository;
import com.positivity.accounting.internal.repository.VendorBillRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/** The vendor reads and commands (CAP:550 S24, #2517): AC 6 (confirmation), 12, 13 (the PUT). */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("VendorDirectoryServiceImpl — vendors from the copy and their AP settings (S24)")
class VendorDirectoryServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-10-08T09:00:00Z");
    private static final UUID VENDOR = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f5a01");
    private static final UUID CATEGORY = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f5a02");
    private static final UUID REQUEST = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f5a03");

    @Mock
    private ExtSupplierVendorRepository vendors;

    @Mock
    private ApVendorSettingsRepository settings;

    @Mock
    private VendorBillRepository bills;

    @Mock
    private AccountingAuditLogRepository auditLogs;

    @Mock
    private PostingCategoryRepository categories;

    @Mock
    private MappingKeyRepository keys;

    private VendorDirectoryServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new VendorDirectoryServiceImpl(
                Clock.fixed(NOW, ZoneOffset.UTC), vendors, settings, bills, auditLogs, categories, keys);
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken("q.controller", "n/a", List.of()));
        when(vendors.findById(VENDOR)).thenReturn(Optional.of(vendor("ACTIVE", 3)));
        when(vendors.lockByVendorId(VENDOR)).thenReturn(Optional.of(vendor("ACTIVE", 3)));
        when(settings.findByVendorId(VENDOR)).thenReturn(Optional.empty());
        when(bills.findVendorIdsWithBillsApprovedAtAnotherRemitTo(anyCollection(), any()))
                .thenReturn(List.of());
        PostingCategory category = new PostingCategory();
        category.setPostingCategoryId(CATEGORY);
        when(categories.findByCategoryName("VENDOR_BILL")).thenReturn(Optional.of(category));
        when(keys.findByPostingCategory_PostingCategoryIdAndKeyName(eq(CATEGORY), any()))
                .thenReturn(Optional.empty());
        when(keys.findByPostingCategory_PostingCategoryIdAndKeyName(CATEGORY, "EXPENSE_SHOP_SUPPLIES"))
                .thenReturn(Optional.of(key(true)));
        when(keys.findByPostingCategory_PostingCategoryIdAndKeyName(CATEGORY, "EXPENSE_OLD"))
                .thenReturn(Optional.of(key(false)));
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
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

    private static MappingKey key(boolean active) {
        MappingKey key = new MappingKey();
        key.setIsActive(active);
        return key;
    }

    private static VendorApSettingsRequest put(String debitClass, String key) {
        VendorApSettingsRequest request = new VendorApSettingsRequest();
        request.setDefaultDebitClass(debitClass);
        request.setDefaultExpenseMappingKey(key);
        request.setJustification("Shop supplies vendor by default");
        request.setRequestId(REQUEST);
        return request;
    }

    private List<AccountingAuditLog> audits() {
        ArgumentCaptor<AccountingAuditLog> captor = ArgumentCaptor.forClass(AccountingAuditLog.class);
        verify(auditLogs, org.mockito.Mockito.atLeast(0)).save(captor.capture());
        return captor.getAllValues();
    }

    @Test
    @DisplayName("AC 12: a search returns copied vendors, active and inactive, with number, status and remit-to")
    void searchFromTheCopy() {
        when(vendors.search(eq("acme"), eq(null), any())).thenReturn(List.of(vendor("INACTIVE", 2)));
        when(bills.findVendorIdsWithBillsApprovedAtAnotherRemitTo(anyCollection(), any()))
                .thenReturn(List.of(VENDOR));

        List<VendorResponse> found = service.searchVendors(" acme ", null, 20);

        assertThat(found).singleElement().satisfies(v -> {
            assertThat(v.getVendorId()).isEqualTo(VENDOR);
            assertThat(v.getName()).isEqualTo("Acme Parts");
            assertThat(v.getVendorNumber()).isEqualTo("V-000123");
            assertThat(v.getStatus()).isEqualTo("INACTIVE");
            assertThat(v.getRemitToVersion()).isEqualTo(2);
            assertThat(v.isPaymentDetailsChanged()).isTrue();
            assertThat(v.getApSettings()).isNull();
        });
    }

    @Test
    @DisplayName("a status outside ACTIVE and INACTIVE is 400 VALIDATION_ERROR")
    void badStatus() {
        assertThatThrownBy(() -> service.searchVendors(null, "GONE", 20))
                .isInstanceOfSatisfying(
                        VendorBillException.class,
                        e -> assertThat(e.getCode()).isEqualTo(VendorBillException.Code.VALIDATION_ERROR));
    }

    @Test
    @DisplayName("a vendor not in the copy is 404 VENDOR_NOT_FOUND")
    void notFound() {
        UUID unknown = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f5a09");
        assertThatThrownBy(() -> service.getVendorById(unknown)).isInstanceOf(VendorNotFoundException.class);
        assertThatThrownBy(() -> service.setApSettings(unknown, put("GOODS", null)))
                .isInstanceOf(VendorNotFoundException.class);
    }

    @Nested
    @DisplayName("the remit-to confirmation (rule 7, AC 6)")
    class Confirmation {

        @Test
        @DisplayName("records the confirmer from the security context, the version and the justification, audited")
        void confirms() {
            service.confirmRemitTo(
                    VENDOR, new VendorRemitToConfirmationRequest(3, "Called the vendor; new address verified"));

            ArgumentCaptor<ApVendorSettings> captor = ArgumentCaptor.forClass(ApVendorSettings.class);
            verify(settings).save(captor.capture());
            assertThat(captor.getValue().getConfirmedRemitToVersion()).isEqualTo(3);
            assertThat(captor.getValue().getRemitToConfirmedBy()).isEqualTo("q.controller");
            assertThat(captor.getValue().getRemitToConfirmedAt()).isEqualTo(NOW);
            assertThat(audits()).singleElement().satisfies(row -> {
                assertThat(row.getEntityType()).isEqualTo("VENDOR");
                assertThat(row.getOperation()).isEqualTo("REMIT_TO_CONFIRM");
                assertThat(row.getUserId()).isEqualTo("q.controller");
                assertThat(row.getNewValue()).contains("confirmedRemitToVersion=3");
            });
        }

        @Test
        @DisplayName("a version other than the current one is 409 VENDOR_PAYMENT_DETAILS_CHANGED; nothing written")
        void staleVersion() {
            assertThatThrownBy(() -> service.confirmRemitTo(
                            VENDOR, new VendorRemitToConfirmationRequest(2, "Called the vendor; verified")))
                    .isInstanceOfSatisfying(
                            VendorBillException.class,
                            e -> assertThat(e.getCode())
                                    .isEqualTo(VendorBillException.Code.VENDOR_PAYMENT_DETAILS_CHANGED));
            verify(settings, never()).save(any());
        }

        @Test
        @DisplayName("a justification under 10 characters is 400 JUSTIFICATION_REQUIRED")
        void shortJustification() {
            assertThatThrownBy(() -> service.confirmRemitTo(VENDOR, new VendorRemitToConfirmationRequest(3, "ok")))
                    .isInstanceOfSatisfying(
                            VendorBillException.class,
                            e -> assertThat(e.getCode()).isEqualTo(VendorBillException.Code.JUSTIFICATION_REQUIRED));
        }
    }

    @Nested
    @DisplayName("the AP-settings PUT (rule 10, AC 13)")
    class ApSettings {

        @Test
        @DisplayName("sets the class and key, one audit row each, old to new, plus the request marker")
        void sets() {
            VendorResponse response = service.setApSettings(VENDOR, put("EXPENSE", "expense_shop_supplies"));

            ArgumentCaptor<ApVendorSettings> captor = ArgumentCaptor.forClass(ApVendorSettings.class);
            verify(settings).save(captor.capture());
            assertThat(captor.getValue().getDefaultDebitClass()).isEqualTo(VendorBillDebitClass.EXPENSE);
            assertThat(captor.getValue().getDefaultExpenseMappingKey()).isEqualTo("EXPENSE_SHOP_SUPPLIES");
            assertThat(audits())
                    .extracting(AccountingAuditLog::getOperation)
                    .containsExactly("AP_VENDOR_SETTINGS_SET", "AP_VENDOR_SETTINGS_SET", "AP_VENDOR_SETTINGS_REQUEST");
            assertThat(audits().getFirst().getOldValue()).isEqualTo("defaultDebitClass=");
            assertThat(audits().getFirst().getNewValue()).startsWith("defaultDebitClass=EXPENSE;requestId=");
            assertThat(response.getVendorId()).isEqualTo(VENDOR);
        }

        @Test
        @DisplayName("AC 13: an inactive or non-EXPENSE_ key is 400 VALIDATION_ERROR and nothing is written")
        void refusesBadKeys() {
            for (String key : List.of("EXPENSE_OLD", "FREIGHT_IN", "EXPENSE_NOPE")) {
                assertThatThrownBy(() -> service.setApSettings(VENDOR, put("EXPENSE", key)))
                        .isInstanceOfSatisfying(VendorBillException.class, e -> {
                            assertThat(e.getCode()).isEqualTo(VendorBillException.Code.VALIDATION_ERROR);
                            assertThat(e.getFieldErrors())
                                    .extracting(VendorBillException.FieldError::field)
                                    .contains("defaultExpenseMappingKey");
                        });
            }
            verify(settings, never()).save(any());
            verify(auditLogs, never()).save(any());
        }

        @Test
        @DisplayName("EXPENSE without a key, a class outside GOODS / EXPENSE, or no requestId: 400 VALIDATION_ERROR")
        void refusesShapes() {
            assertThatThrownBy(() -> service.setApSettings(VENDOR, put("EXPENSE", null)))
                    .isInstanceOfSatisfying(
                            VendorBillException.class,
                            e -> assertThat(e.getCode()).isEqualTo(VendorBillException.Code.VALIDATION_ERROR));
            assertThatThrownBy(() -> service.setApSettings(VENDOR, put("RECEIPT_MATCHED", null)))
                    .isInstanceOfSatisfying(
                            VendorBillException.class,
                            e -> assertThat(e.getFieldErrors())
                                    .extracting(VendorBillException.FieldError::field)
                                    .containsExactly("defaultDebitClass"));
            VendorApSettingsRequest noRequestId = put("GOODS", null);
            noRequestId.setRequestId(null);
            assertThatThrownBy(() -> service.setApSettings(VENDOR, noRequestId))
                    .isInstanceOfSatisfying(
                            VendorBillException.class,
                            e -> assertThat(e.getFieldErrors())
                                    .extracting(VendorBillException.FieldError::field)
                                    .containsExactly("requestId"));
            verify(settings, never()).save(any());
        }

        @Test
        @DisplayName("a justification under 10 characters is 400 JUSTIFICATION_REQUIRED")
        void shortJustification() {
            VendorApSettingsRequest request = put("GOODS", null);
            request.setJustification("short");
            assertThatThrownBy(() -> service.setApSettings(VENDOR, request))
                    .isInstanceOfSatisfying(
                            VendorBillException.class,
                            e -> assertThat(e.getCode()).isEqualTo(VendorBillException.Code.JUSTIFICATION_REQUIRED));
        }

        @Test
        @DisplayName("a field left out is unchanged; an explicit null clears it")
        void missingVersusNull() {
            ApVendorSettings row = new ApVendorSettings();
            row.setVendorId(VENDOR);
            row.setDefaultDebitClass(VendorBillDebitClass.GOODS);
            row.setDefaultExpenseMappingKey("EXPENSE_SHOP_SUPPLIES");
            when(settings.findByVendorId(VENDOR)).thenReturn(Optional.of(row));

            VendorApSettingsRequest clearClassOnly = new VendorApSettingsRequest();
            clearClassOnly.setDefaultDebitClass(null);
            clearClassOnly.setJustification("Vendor no longer sells stock");
            clearClassOnly.setRequestId(REQUEST);
            service.setApSettings(VENDOR, clearClassOnly);

            assertThat(row.getDefaultDebitClass()).isNull();
            assertThat(row.getDefaultExpenseMappingKey()).isEqualTo("EXPENSE_SHOP_SUPPLIES");
        }

        @Test
        @DisplayName("idempotent on requestId: a replay writes nothing")
        void replay() {
            when(auditLogs.existsByOperationAndEntityId("AP_VENDOR_SETTINGS_REQUEST", REQUEST))
                    .thenReturn(true);

            service.setApSettings(VENDOR, put("GOODS", null));

            verify(settings, never()).save(any());
            verify(auditLogs, never()).save(any());
        }

        @Test
        @DisplayName("an inactive vendor may be set")
        void inactiveVendorAllowed() {
            when(vendors.lockByVendorId(VENDOR)).thenReturn(Optional.of(vendor("INACTIVE", 1)));

            service.setApSettings(VENDOR, put("GOODS", null));

            verify(settings).save(any());
        }
    }
}

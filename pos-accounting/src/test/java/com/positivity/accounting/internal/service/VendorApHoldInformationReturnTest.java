package com.positivity.accounting.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.positivity.accounting.internal.dto.InformationReturnFormsResponse;
import com.positivity.accounting.internal.dto.VendorApHoldRequest;
import com.positivity.accounting.internal.dto.VendorApSettingsRequest;
import com.positivity.accounting.internal.dto.VendorApSettingsResponse;
import com.positivity.accounting.internal.dto.VendorInformationReturnRequest;
import com.positivity.accounting.internal.dto.VendorResponse;
import com.positivity.accounting.internal.entity.AccountingAuditLog;
import com.positivity.accounting.internal.entity.ApVendorSettings;
import com.positivity.accounting.internal.entity.ExtSupplierVendor;
import com.positivity.accounting.internal.exception.IdempotencyConflictException;
import com.positivity.accounting.internal.exception.TaxServiceUnavailableException;
import com.positivity.accounting.internal.exception.VendorBillException;
import com.positivity.accounting.internal.repository.AccountingAuditLogRepository;
import com.positivity.accounting.internal.repository.ApVendorSettingsRepository;
import com.positivity.accounting.internal.repository.ExtSupplierVendorRepository;
import com.positivity.accounting.internal.repository.MappingKeyRepository;
import com.positivity.accounting.internal.repository.PostingCategoryRepository;
import com.positivity.accounting.internal.repository.VendorBillRepository;
import com.positivity.security.common.GatewaySecurityConstants;
import com.positivity.web.common.ReplicationPendingException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * The vendor AP hold and the information-return flag on the AP-settings PUT and the vendor reads (CAP:550 #2615): ACs
 * 1, 2, 6, 8, 9, 10, 11, 12 and 13's vendor list, against a mocked pos-tax answering the fixture country {@code ZZ}.
 * Every log line of the service is captured at DEBUG: neither the hold reason nor the fixture {@code last4} may appear
 * in one (ADR-0072).
 */
@DisplayName("Vendor AP hold and information-return flag (#2615)")
class VendorApHoldInformationReturnTest {

    private static final Instant NOW = Instant.parse("2026-10-08T09:00:00Z");
    private static final UUID VENDOR = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f6a01");
    private static final UUID REQUEST = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f6a03");
    private static final String REASON = "Disputed delivery 4471, awaiting credit";
    private static final String LAST4 = "6789";
    private static final String JUSTIFICATION = "Vendor dispute raised by the controller";

    /** The fixture forms of country ZZ (not tax law). */
    private static final InformationReturnFormsResponse ZZ = new InformationReturnFormsResponse(
            "ZZ",
            "STUB",
            List.of(
                    new InformationReturnFormsResponse.Form(
                            "ZZ_FORM_A",
                            "Form A",
                            List.of(
                                    new InformationReturnFormsResponse.Box("1", "Box one"),
                                    new InformationReturnFormsResponse.Box("2", "Box two")),
                            List.of("ZZ_BUSINESS_ID", "ZZ_PERSON_ID")),
                    new InformationReturnFormsResponse.Form(
                            "ZZ_FORM_B",
                            "Form B",
                            List.of(new InformationReturnFormsResponse.Box("7", "Box seven")),
                            List.of("ZZ_BUSINESS_ID"))));

    private final ExtSupplierVendorRepository vendors = mock();
    private final ApVendorSettingsRepository settings = mock();
    private final VendorBillRepository bills = mock();
    private final AccountingAuditLogRepository auditLogs = mock();
    private final InformationReturnFormsService forms = mock();
    private final List<AccountingAuditLog> saved = new ArrayList<>();
    private VendorDirectoryServiceImpl service;
    private ExtSupplierVendor vendor;
    private ApVendorSettings row;

    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final Logger accounting = (Logger) LoggerFactory.getLogger("com.positivity.accounting");
    private Level previousLevel;

    @BeforeEach
    void setUp() {
        service = new VendorDirectoryServiceImpl(
                Clock.fixed(NOW, ZoneOffset.UTC),
                vendors,
                settings,
                bills,
                auditLogs,
                mock(PostingCategoryRepository.class),
                mock(MappingKeyRepository.class),
                forms);
        signIn("q.controller");
        vendor = new ExtSupplierVendor();
        vendor.setVendorId(VENDOR);
        vendor.setVendorNumber("V-000123");
        vendor.setDisplayName("Acme Parts");
        vendor.setStatus("ACTIVE");
        vendor.setCreatedBy("u.creator");
        when(vendors.findById(VENDOR)).thenReturn(Optional.of(vendor));
        when(vendors.lockByVendorId(VENDOR)).thenReturn(Optional.of(vendor));
        row = null;
        when(settings.findByVendorId(VENDOR)).thenAnswer(inv -> Optional.ofNullable(row));
        when(settings.save(any(ApVendorSettings.class))).thenAnswer(inv -> {
            row = inv.getArgument(0);
            return row;
        });
        when(bills.findVendorIdsWithBillsApprovedAtAnotherRemitTo(anyCollection(), any()))
                .thenReturn(List.of());
        when(auditLogs.save(any(AccountingAuditLog.class))).thenAnswer(inv -> {
            saved.add(inv.getArgument(0));
            return inv.getArgument(0);
        });
        when(forms.forms()).thenReturn(ZZ);
        previousLevel = accounting.getLevel();
        accounting.setLevel(Level.DEBUG);
        logs.start();
        accounting.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        accounting.detachAppender(logs);
        accounting.setLevel(previousLevel);
        SecurityContextHolder.clearContext();
    }

    private static void signIn(String username) {
        UsernamePasswordAuthenticationToken caller =
                new UsernamePasswordAuthenticationToken(username, "n/a", List.of());
        caller.setDetails(Map.of(GatewaySecurityConstants.DETAIL_USERNAME, username));
        SecurityContextHolder.getContext().setAuthentication(caller);
    }

    private static VendorApSettingsRequest put(UUID requestId) {
        VendorApSettingsRequest request = new VendorApSettingsRequest();
        request.setJustification(JUSTIFICATION);
        request.setRequestId(requestId);
        return request;
    }

    private static VendorApSettingsRequest hold(Boolean onHold, String reason) {
        VendorApSettingsRequest request = put(REQUEST);
        VendorApHoldRequest hold = new VendorApHoldRequest();
        hold.setOnHold(onHold);
        if (reason != null) {
            hold.setReason(reason);
        }
        request.setApHold(hold);
        return request;
    }

    private static VendorApSettingsRequest informationReturn(
            Boolean reportable, String form, String box, String scheme) {
        VendorApSettingsRequest request = put(UUID.randomUUID());
        VendorInformationReturnRequest given = new VendorInformationReturnRequest();
        given.setReportable(reportable);
        if (form != null) {
            given.setForm(form);
        }
        if (box != null) {
            given.setBox(box);
        }
        if (scheme != null) {
            given.setPayeeTaxRegistrationScheme(scheme);
        }
        request.setInformationReturn(given);
        return request;
    }

    private List<AccountingAuditLog> rows(String operation) {
        return saved.stream().filter(r -> r.getOperation().equals(operation)).toList();
    }

    private List<String> logged() {
        return logs.list.stream()
                .map(event -> event.getFormattedMessage()
                        + (event.getThrowableProxy() == null
                                ? ""
                                : event.getThrowableProxy().getMessage()))
                .toList();
    }

    private void heldAlready() {
        row = new ApVendorSettings();
        row.setVendorId(VENDOR);
        row.setApHold(true);
        row.setApHoldReason(REASON);
        row.setApHoldSetBy("g.manager");
        row.setApHoldSetAt(NOW.minusSeconds(3600));
    }

    private static VendorBillException.Code code(Throwable thrown) {
        return ((VendorBillException) thrown).getCode();
    }

    private static List<String> fields(Throwable thrown) {
        return ((VendorBillException) thrown)
                .getFieldErrors().stream()
                        .map(VendorBillException.FieldError::field)
                        .toList();
    }

    @Nested
    @DisplayName("the AP hold")
    class Hold {

        @Test
        @DisplayName("AC1: sets the hold with the caller as setBy, one AP_VENDOR_HOLD_SET row with the reason,"
                + " justification and requestId; the read shows it; no log line carries the reason")
        void setsAHold() {
            VendorResponse read = service.setApSettings(VENDOR, hold(true, "  " + REASON + " "));

            assertThat(row.isApHold()).isTrue();
            assertThat(row.getApHoldReason()).isEqualTo(REASON);
            assertThat(row.getApHoldSetBy()).isEqualTo("q.controller");
            assertThat(row.getApHoldSetAt()).isEqualTo(NOW);
            assertThat(read.isApHold()).isTrue();
            assertThat(read.getApSettings().apHold().onHold()).isTrue();
            assertThat(read.getApSettings().apHold().reason()).isEqualTo(REASON);
            assertThat(read.getApSettings().apHold().setBy()).isEqualTo("q.controller");
            assertThat(read.getApSettings().apHold().setAt()).isEqualTo(NOW);
            assertThat(rows("AP_VENDOR_HOLD_SET")).singleElement().satisfies(audit -> {
                assertThat(audit.getEntityType()).isEqualTo("VENDOR");
                assertThat(audit.getEntityId()).isEqualTo(VENDOR);
                assertThat(audit.getUserId()).isEqualTo("q.controller");
                assertThat(audit.getJustification()).isEqualTo(JUSTIFICATION);
                assertThat(audit.getOldValue()).isEqualTo("apHold=false");
                assertThat(audit.getNewValue()).isEqualTo("apHold=true;reason=" + REASON + ";requestId=" + REQUEST);
            });
            assertThat(logged()).isNotEmpty().noneMatch(line -> line.contains("4471"));
            verify(forms, never()).forms();
        }

        @Test
        @DisplayName("AC1: the request row carries the reason's SHA-256, never the reason; a replay writes nothing, and"
                + " the same requestId with another reason is 409 IDEMPOTENCY_CONFLICT")
        void fingerprintAndReplay() {
            service.setApSettings(VENDOR, hold(true, REASON));
            AccountingAuditLog marker = rows("AP_VENDOR_SETTINGS_REQUEST").getFirst();
            assertThat(marker.getNewValue())
                    .contains("apHold.onHold=true;apHold.reason=" + VendorDirectoryServiceImpl.sha256(REASON))
                    .doesNotContain("4471");
            when(auditLogs.findFirstByOperationAndEntityId("AP_VENDOR_SETTINGS_REQUEST", REQUEST))
                    .thenReturn(Optional.of(marker));
            saved.clear();
            org.mockito.Mockito.clearInvocations(settings);

            service.setApSettings(VENDOR, hold(true, REASON));
            assertThat(saved).isEmpty();
            verify(settings, never()).save(any());

            assertThatThrownBy(() -> service.setApSettings(VENDOR, hold(true, "Another reason entirely")))
                    .isInstanceOf(IdempotencyConflictException.class);
            assertThatThrownBy(() -> service.setApSettings(VENDOR, hold(false, null)))
                    .isInstanceOf(IdempotencyConflictException.class);
            assertThat(saved).isEmpty();
        }

        @Test
        @DisplayName("a reason containing ';changed=' cannot break the fingerprint: a replay still matches")
        void reasonCannotBreakTheFingerprint() {
            String tricky = "Dispute;changed=0;requestId=x over delivery";
            service.setApSettings(VENDOR, hold(true, tricky));
            AccountingAuditLog marker = rows("AP_VENDOR_SETTINGS_REQUEST").getFirst();
            when(auditLogs.findFirstByOperationAndEntityId("AP_VENDOR_SETTINGS_REQUEST", REQUEST))
                    .thenReturn(Optional.of(marker));
            saved.clear();

            service.setApSettings(VENDOR, hold(true, tricky));

            assertThat(saved).isEmpty();
        }

        @Test
        @DisplayName("the same reason again writes no hold row; a new reason updates reason, setBy and setAt")
        void sameAndNewReason() {
            heldAlready();

            service.setApSettings(VENDOR, hold(true, REASON));
            assertThat(rows("AP_VENDOR_HOLD_SET")).isEmpty();
            assertThat(row.getApHoldSetBy()).isEqualTo("g.manager");

            VendorApSettingsRequest changed = hold(true, "Credit note still missing for 4471");
            changed.setRequestId(UUID.randomUUID());
            service.setApSettings(VENDOR, changed);
            assertThat(rows("AP_VENDOR_HOLD_SET")).singleElement().satisfies(audit -> {
                assertThat(audit.getOldValue()).isEqualTo("apHold=true;reason=" + REASON);
                assertThat(audit.getNewValue()).startsWith("apHold=true;reason=Credit note still missing for 4471;");
            });
            assertThat(row.getApHoldReason()).isEqualTo("Credit note still missing for 4471");
            assertThat(row.getApHoldSetBy()).isEqualTo("q.controller");
            assertThat(row.getApHoldSetAt()).isEqualTo(NOW);
        }

        @Test
        @DisplayName("AC6: a release writes AP_VENDOR_HOLD_CLEARED with the old reason and the justification; the"
                + " read shows onHold false with null reason, setBy and setAt; a reason sent with it is not stored")
        void releases() {
            heldAlready();
            signIn("g.manager");

            VendorResponse read = service.setApSettings(VENDOR, hold(false, "Ignored because releasing"));

            assertThat(row.isApHold()).isFalse();
            assertThat(row.getApHoldReason()).isNull();
            assertThat(row.getApHoldSetBy()).isNull();
            assertThat(row.getApHoldSetAt()).isNull();
            assertThat(read.isApHold()).isFalse();
            assertThat(read.getApSettings().apHold()).isEqualTo(VendorApSettingsResponse.ApHold.NONE);
            assertThat(rows("AP_VENDOR_HOLD_CLEARED")).singleElement().satisfies(audit -> {
                assertThat(audit.getUserId()).isEqualTo("g.manager");
                assertThat(audit.getJustification()).isEqualTo(JUSTIFICATION);
                assertThat(audit.getOldValue()).isEqualTo("apHold=true;reason=" + REASON);
                assertThat(audit.getNewValue()).isEqualTo("apHold=false;requestId=" + REQUEST);
            });
        }

        @Test
        @DisplayName("releasing a vendor not on hold writes no hold row")
        void releaseWhenNotHeld() {
            service.setApSettings(VENDOR, hold(false, null));

            assertThat(rows("AP_VENDOR_HOLD_CLEARED")).isEmpty();
            assertThat(rows("AP_VENDOR_HOLD_SET")).isEmpty();
            verify(settings, never()).save(any());
        }

        @Test
        @DisplayName("AC2: a reason of 9 characters, blank or none is 400 JUSTIFICATION_REQUIRED naming apHold.reason;"
                + " 501 characters is 400 VALIDATION_ERROR; neither echoes the reason, nothing is written")
        void reasonBounds() {
            for (String reason : new String[] {"123456789", "   ", null}) {
                assertThatThrownBy(() -> service.setApSettings(VENDOR, hold(true, reason)))
                        .satisfies(thrown -> {
                            assertThat(code(thrown)).isEqualTo(VendorBillException.Code.JUSTIFICATION_REQUIRED);
                            assertThat(fields(thrown)).containsExactly("apHold.reason");
                            assertThat(thrown.getMessage()).doesNotContain("123456789");
                        });
            }
            String longReason = "Disputed " + "x".repeat(492);
            assertThat(longReason).hasSize(501);
            assertThatThrownBy(() -> service.setApSettings(VENDOR, hold(true, longReason)))
                    .satisfies(thrown -> {
                        assertThat(code(thrown)).isEqualTo(VendorBillException.Code.VALIDATION_ERROR);
                        assertThat(fields(thrown)).containsExactly("apHold.reason");
                        assertThat(thrown.getMessage()).doesNotContain("Disputed");
                        assertThat(((VendorBillException) thrown).getFieldErrors())
                                .allSatisfy(error -> assertThat(error.message()).doesNotContain("Disputed"));
                    });
            String exactly500 = "Disputed " + "x".repeat(491);
            service.setApSettings(VENDOR, hold(true, exactly500));
            assertThat(row.getApHoldReason()).hasSize(500);
        }

        @Test
        @DisplayName("lengths count code points of the stripped reason, as V22's char_length(btrim()) does: 5 emoji"
                + " (10 UTF-16 units) and 10 no-break spaces are refused; 10 emoji and 500 emoji are accepted")
        void reasonLengthInCodePoints() {
            String emoji = "\uD83D\uDE9A";
            for (String reason : new String[] {emoji.repeat(5), "\u00A0".repeat(10), "\u2003\u00A0\u202F".repeat(4)}) {
                assertThatThrownBy(() -> service.setApSettings(VENDOR, hold(true, reason)))
                        .satisfies(thrown -> {
                            assertThat(code(thrown)).isEqualTo(VendorBillException.Code.JUSTIFICATION_REQUIRED);
                            assertThat(fields(thrown)).containsExactly("apHold.reason");
                        });
            }
            assertThat(saved).isEmpty();
            assertThatThrownBy(() -> service.setApSettings(VENDOR, hold(true, emoji.repeat(501))))
                    .satisfies(thrown -> assertThat(code(thrown)).isEqualTo(VendorBillException.Code.VALIDATION_ERROR));

            service.setApSettings(VENDOR, hold(true, "  " + emoji.repeat(10) + "\n"));
            assertThat(row.getApHoldReason()).isEqualTo(emoji.repeat(10));
            VendorApSettingsRequest longest = hold(true, emoji.repeat(500));
            longest.setRequestId(UUID.randomUUID());
            service.setApSettings(VENDOR, longest);
            assertThat(row.getApHoldReason()
                            .codePointCount(0, row.getApHoldReason().length()))
                    .isEqualTo(500);
        }

        @Test
        @DisplayName("an unknown key is refused by the service too, naming the key and never its value")
        void serviceRefusesUnknownKeys() throws Exception {
            VendorApSettingsRequest request = new tools.jackson.databind.ObjectMapper()
                    .readValue(
                            "{\"tin\":\"123-45-6789\",\"apHold\":{\"onHold\":true,\"pin\":\"0000\"},"
                                    + "\"justification\":\"Vendor dispute raised\",\"requestId\":\"" + REQUEST + "\"}",
                            VendorApSettingsRequest.class);

            assertThatThrownBy(() -> service.setApSettings(VENDOR, request)).satisfies(thrown -> {
                assertThat(code(thrown)).isEqualTo(VendorBillException.Code.VALIDATION_ERROR);
                assertThat(fields(thrown)).containsExactly("tin", "apHold.pin");
                assertThat(thrown.getMessage()).doesNotContain("6789").doesNotContain("0000");
            });
            assertThat(saved).isEmpty();
            verify(vendors, never()).lockByVendorId(any());
        }

        @Test
        @DisplayName("AC2: apHold null, or onHold missing or null, is 400 VALIDATION_ERROR naming the field")
        void nullShapes() {
            VendorApSettingsRequest nullHold = put(REQUEST);
            nullHold.setApHold(null);
            assertThatThrownBy(() -> service.setApSettings(VENDOR, nullHold)).satisfies(thrown -> {
                assertThat(code(thrown)).isEqualTo(VendorBillException.Code.VALIDATION_ERROR);
                assertThat(fields(thrown)).containsExactly("apHold");
            });
            VendorApSettingsRequest noOnHold = put(REQUEST);
            VendorApHoldRequest empty = new VendorApHoldRequest();
            empty.setReason(REASON);
            noOnHold.setApHold(empty);
            assertThatThrownBy(() -> service.setApSettings(VENDOR, noOnHold))
                    .satisfies(thrown -> assertThat(fields(thrown)).containsExactly("apHold.onHold"));
            assertThatThrownBy(() -> service.setApSettings(VENDOR, hold(null, REASON)))
                    .satisfies(thrown -> assertThat(fields(thrown)).containsExactly("apHold.onHold"));
            verify(settings, never()).save(any());
            assertThat(saved).isEmpty();
        }

        @Test
        @DisplayName("AC2: a vendor not in the copy is 503 VENDOR_REPLICATION_PENDING; an inactive vendor may be held")
        void copyRules() {
            when(vendors.lockByVendorId(VENDOR)).thenReturn(Optional.empty());
            assertThatThrownBy(() -> service.setApSettings(VENDOR, hold(true, REASON)))
                    .isInstanceOf(ReplicationPendingException.class);
            assertThat(saved).isEmpty();

            vendor.setStatus("INACTIVE");
            when(vendors.lockByVendorId(VENDOR)).thenReturn(Optional.of(vendor));
            service.setApSettings(VENDOR, hold(true, REASON));
            assertThat(row.isApHold()).isTrue();
        }

        @Test
        @DisplayName("AC10: a PUT of only the hold, or only S24's defaults, never calls pos-tax")
        void holdNeverCallsPosTax() {
            when(forms.forms()).thenThrow(new TaxServiceUnavailableException("The tax service is unavailable"));

            service.setApSettings(VENDOR, hold(true, REASON));
            VendorApSettingsRequest defaults = put(UUID.randomUUID());
            defaults.setDefaultDebitClass("GOODS");
            service.setApSettings(VENDOR, defaults);

            verify(forms, never()).forms();
            assertThat(row.isApHold()).isTrue();
        }
    }

    @Nested
    @DisplayName("the information-return flag")
    class InformationReturn {

        @Test
        @DisplayName("AC8: reportable in ZZ_FORM_A box 1 under ZZ_BUSINESS_ID: four AP_VENDOR_SETTINGS_SET rows old to"
                + " new; the read relays last4 6789, which no audit row and no log line carries")
        void reportableAndMasked() {
            vendor.setTaxRegistrations(new ArrayList<>(List.of(registration("ZZ_BUSINESS_ID", null, LAST4))));

            VendorResponse read =
                    service.setApSettings(VENDOR, informationReturn(true, "ZZ_FORM_A", "1", "ZZ_BUSINESS_ID"));

            var flag = read.getApSettings().informationReturn();
            assertThat(flag.reportable()).isTrue();
            assertThat(flag.form()).isEqualTo("ZZ_FORM_A");
            assertThat(flag.box()).isEqualTo("1");
            assertThat(flag.payeeTaxRegistrationScheme()).isEqualTo("ZZ_BUSINESS_ID");
            assertThat(flag.payeeTinOnFile()).isTrue();
            assertThat(flag.payeeTinLast4()).isEqualTo(LAST4);
            assertThat(rows("AP_VENDOR_SETTINGS_SET"))
                    .extracting(AccountingAuditLog::getOldValue)
                    .containsExactly(
                            "informationReturnReportable=false",
                            "informationReturnForm=",
                            "informationReturnBox=",
                            "informationReturnPayeeScheme=");
            assertThat(rows("AP_VENDOR_SETTINGS_SET"))
                    .extracting(AccountingAuditLog::getNewValue)
                    .allSatisfy(value -> assertThat(value).contains(";requestId="))
                    .anySatisfy(value -> assertThat(value).startsWith("informationReturnForm=ZZ_FORM_A;"));
            assertThat(saved).allSatisfy(audit -> {
                assertThat(String.valueOf(audit.getOldValue())).doesNotContain(LAST4);
                assertThat(String.valueOf(audit.getNewValue())).doesNotContain(LAST4);
            });
            assertThat(String.valueOf(read)).doesNotContain(LAST4);

            service.getVendorById(VENDOR);
            service.searchVendors(null, null, 20);
            assertThat(logged()).isNotEmpty().noneMatch(line -> line.contains(LAST4));
        }

        @Test
        @DisplayName("[M] AC8: last4 comes only from a registration of the chosen scheme")
        void last4OfTheChosenSchemeOnly() {
            vendor.setTaxRegistrations(new ArrayList<>(
                    List.of(registration("ZZ_PERSON_ID", null, "1111"), registration("ZZ_BUSINESS_ID", null, LAST4))));

            VendorResponse read =
                    service.setApSettings(VENDOR, informationReturn(true, "ZZ_FORM_A", "1", "ZZ_BUSINESS_ID"));

            assertThat(read.getApSettings().informationReturn().payeeTinLast4()).isEqualTo(LAST4);
        }

        @Test
        @DisplayName("AC12: none of the scheme → not on file; one with last4 null → on file, null; two → on file, null")
        void notOnFileOrAmbiguous() {
            vendor.setTaxRegistrations(new ArrayList<>(List.of(registration("ZZ_BUSINESS_ID", null, LAST4))));
            var none = service.setApSettings(VENDOR, informationReturn(true, "ZZ_FORM_A", "1", "ZZ_PERSON_ID"))
                    .getApSettings()
                    .informationReturn();
            assertThat(none.payeeTinOnFile()).isFalse();
            assertThat(none.payeeTinLast4()).isNull();

            vendor.setTaxRegistrations(new ArrayList<>(List.of(registration("ZZ_PERSON_ID", null, null))));
            var nullLast4 = service.getVendorById(VENDOR).getApSettings().informationReturn();
            assertThat(nullLast4.payeeTinOnFile()).isTrue();
            assertThat(nullLast4.payeeTinLast4()).isNull();

            vendor.setTaxRegistrations(new ArrayList<>(
                    List.of(registration("ZZ_PERSON_ID", "R1", "1111"), registration("ZZ_PERSON_ID", "R2", "2222"))));
            var two = service.getVendorById(VENDOR).getApSettings().informationReturn();
            assertThat(two.payeeTinOnFile()).isTrue();
            assertThat(two.payeeTinLast4()).isNull();
        }

        @Test
        @DisplayName("AC9: a form, box or scheme the stub does not configure, reportable without box, or not"
                + " reportable with a form: 400 VALIDATION_ERROR naming the field, nothing written")
        void validatesAgainstTheStub() {
            assertField(informationReturn(true, "XX_OTHER", "1", null), "informationReturn.form");
            assertField(informationReturn(true, "ZZ_FORM_A", "99", null), "informationReturn.box");
            assertField(informationReturn(true, "ZZ_FORM_A", null, null), "informationReturn.box");
            assertField(informationReturn(false, "ZZ_FORM_A", null, null), "informationReturn.form");
            assertField(
                    informationReturn(true, "ZZ_FORM_B", "7", "ZZ_PERSON_ID"),
                    "informationReturn.payeeTaxRegistrationScheme");
            assertField(informationReturn(null, "ZZ_FORM_A", "1", null), "informationReturn.reportable");
            VendorApSettingsRequest nullObject = put(UUID.randomUUID());
            nullObject.setInformationReturn(null);
            assertField(nullObject, "informationReturn");
            verify(settings, never()).save(any());
            assertThat(saved).isEmpty();
        }

        @Test
        @DisplayName(
                "AC9: a tax country without a configured form refuses reportable true on informationReturn.reportable")
        void countryWithoutForms() {
            when(forms.forms()).thenReturn(new InformationReturnFormsResponse("MX", "STUB", List.of()));

            assertField(informationReturn(true, "ZZ_FORM_A", "1", null), "informationReturn.reportable");
        }

        @Test
        @DisplayName(
                "AC10: pos-tax down: a PUT making the vendor reportable is 503 SERVICE_UNAVAILABLE, nothing written")
        void posTaxDown() {
            when(forms.forms()).thenThrow(new TaxServiceUnavailableException("The tax service is unavailable"));

            assertThatThrownBy(() ->
                            service.setApSettings(VENDOR, informationReturn(true, "ZZ_FORM_A", "1", "ZZ_BUSINESS_ID")))
                    .isInstanceOf(TaxServiceUnavailableException.class);
            verify(settings, never()).save(any());
            assertThat(saved).isEmpty();
        }

        @Test
        @DisplayName("AC11: reportable false clears the form, box and scheme, audited old to new; pos-tax not asked")
        void clears() {
            service.setApSettings(VENDOR, informationReturn(true, "ZZ_FORM_A", "2", "ZZ_PERSON_ID"));
            saved.clear();
            org.mockito.Mockito.clearInvocations(forms);

            service.setApSettings(VENDOR, informationReturn(false, null, null, null));

            assertThat(row.isInformationReturnReportable()).isFalse();
            assertThat(row.getInformationReturnForm()).isNull();
            assertThat(row.getInformationReturnBox()).isNull();
            assertThat(row.getInformationReturnPayeeScheme()).isNull();
            assertThat(rows("AP_VENDOR_SETTINGS_SET"))
                    .extracting(AccountingAuditLog::getOldValue)
                    .containsExactly(
                            "informationReturnReportable=true",
                            "informationReturnForm=ZZ_FORM_A",
                            "informationReturnBox=2",
                            "informationReturnPayeeScheme=ZZ_PERSON_ID");
            verify(forms, never()).forms();
        }

        @Test
        @DisplayName("the object replaces the stored flag as a whole: a scheme left out of a later PUT is cleared")
        void replacedAsAWhole() {
            service.setApSettings(VENDOR, informationReturn(true, "ZZ_FORM_A", "1", "ZZ_BUSINESS_ID"));
            service.setApSettings(VENDOR, informationReturn(true, "ZZ_FORM_A", "2", null));

            assertThat(row.getInformationReturnBox()).isEqualTo("2");
            assertThat(row.getInformationReturnPayeeScheme()).isNull();
        }

        @Test
        @DisplayName("[M] AC10: an unchanged reportable value does not call pos-tax again; a changed box does")
        void callsPosTaxOnlyOnChange() {
            service.setApSettings(VENDOR, informationReturn(true, "ZZ_FORM_A", "1", null));
            service.setApSettings(VENDOR, informationReturn(true, "ZZ_FORM_A", "1", null));
            verify(forms, times(1)).forms();
            service.setApSettings(VENDOR, informationReturn(true, "ZZ_FORM_A", "2", null));
            verify(forms, times(2)).forms();
        }

        private void assertField(VendorApSettingsRequest request, String field) {
            assertThatThrownBy(() -> service.setApSettings(VENDOR, request)).satisfies(thrown -> {
                assertThat(code(thrown)).isEqualTo(VendorBillException.Code.VALIDATION_ERROR);
                assertThat(fields(thrown)).contains(field);
            });
        }
    }

    @Nested
    @DisplayName("the vendor list")
    class VendorList {

        @Test
        @DisplayName("AC13: rows carry apHold from one settings query for the page")
        void listFlags() {
            ExtSupplierVendor other = new ExtSupplierVendor();
            UUID otherId = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f6a09");
            other.setVendorId(otherId);
            other.setVendorNumber("V-000999");
            other.setDisplayName("Other Parts");
            other.setStatus("ACTIVE");
            when(vendors.search(any(), any(), any())).thenReturn(List.of(vendor, other));
            heldAlready();
            when(settings.findByVendorIdIn(anyCollection())).thenReturn(List.of(row));

            List<VendorResponse> page = service.searchVendors(null, null, 20);

            assertThat(page).extracting(VendorResponse::isApHold).containsExactly(true, false);
            assertThat(page).allSatisfy(v -> assertThat(v.getApSettings()).isNull());
            verify(settings, times(1))
                    .findByVendorIdIn(org.mockito.ArgumentMatchers.argThat(
                            ids -> ids.size() == 2 && ids.contains(VENDOR) && ids.contains(otherId)));
            verify(settings, times(1)).findByVendorIdIn(anyCollection());
            verify(settings, never()).findByVendorId(any());
        }
    }

    private static Map<String, String> registration(String scheme, String region, String last4) {
        Map<String, String> registration = new java.util.HashMap<>();
        registration.put("scheme", scheme);
        registration.put("region", region);
        registration.put("last4", last4);
        return registration;
    }

    @Nested
    @DisplayName("S43 AC10: acceptTaxOnResaleGoods on the settings PUT and the vendor read")
    class AcceptTaxOnResaleGoods {

        private VendorApSettingsRequest accept(Boolean value) {
            VendorApSettingsRequest request = put(REQUEST);
            request.setAcceptTaxOnResaleGoods(value);
            return request;
        }

        @Test
        @DisplayName("true writes one AP_VENDOR_SETTINGS_SET row old -> new with the requestId, and the vendor read"
                + " shows it")
        void setsAndReads() {
            var read = service.setApSettings(VENDOR, accept(true));

            assertThat(read.getApSettings().acceptTaxOnResaleGoods()).isTrue();
            assertThat(row.isAcceptTaxOnResaleGoods()).isTrue();
            assertThat(rows("AP_VENDOR_SETTINGS_SET")).singleElement().satisfies(r -> {
                assertThat(r.getOldValue()).isEqualTo("acceptTaxOnResaleGoods=false");
                assertThat(r.getNewValue()).isEqualTo("acceptTaxOnResaleGoods=true;requestId=" + REQUEST);
                assertThat(r.getJustification()).isEqualTo(JUSTIFICATION);
            });
            assertThat(rows("AP_VENDOR_SETTINGS_REQUEST").getFirst().getNewValue())
                    .contains(";acceptTaxOnResaleGoods=true;changed=1");
        }

        @Test
        @DisplayName("absent leaves it unchanged (~ in the fingerprint); the same value again writes no setting row")
        void absentOrUnchanged() {
            service.setApSettings(VENDOR, put(REQUEST));
            assertThat(rows("AP_VENDOR_SETTINGS_REQUEST").getFirst().getNewValue())
                    .contains(";acceptTaxOnResaleGoods=~");
            assertThat(rows("AP_VENDOR_SETTINGS_SET")).isEmpty();

            saved.clear();
            service.setApSettings(VENDOR, accept(false));
            assertThat(rows("AP_VENDOR_SETTINGS_SET")).isEmpty();
        }

        @Test
        @DisplayName("null is 400 VALIDATION_ERROR with fieldErrors[acceptTaxOnResaleGoods]; nothing is written")
        void nullIsRefused() {
            assertThatThrownBy(() -> service.setApSettings(VENDOR, accept(null)))
                    .isInstanceOfSatisfying(VendorBillException.class, e -> {
                        assertThat(e.getCode()).isEqualTo(VendorBillException.Code.VALIDATION_ERROR);
                        assertThat(e.getFieldErrors())
                                .extracting(VendorBillException.FieldError::field)
                                .containsExactly("acceptTaxOnResaleGoods");
                    });
            assertThat(saved).isEmpty();
        }

        @Test
        @DisplayName("a reused requestId with another value is 409 IDEMPOTENCY_CONFLICT; the same value replays")
        void reusedRequestId() {
            service.setApSettings(VENDOR, accept(true));
            AccountingAuditLog marker = rows("AP_VENDOR_SETTINGS_REQUEST").getFirst();
            when(auditLogs.findFirstByOperationAndEntityId("AP_VENDOR_SETTINGS_REQUEST", REQUEST))
                    .thenReturn(Optional.of(marker));
            saved.clear();

            service.setApSettings(VENDOR, accept(true));
            assertThat(saved).isEmpty();
            assertThatThrownBy(() -> service.setApSettings(VENDOR, accept(false)))
                    .isInstanceOf(IdempotencyConflictException.class);
            assertThatThrownBy(() -> service.setApSettings(VENDOR, put(REQUEST)))
                    .isInstanceOf(IdempotencyConflictException.class);
        }
    }
}

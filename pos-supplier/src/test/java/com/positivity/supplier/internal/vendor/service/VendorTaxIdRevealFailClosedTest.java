package com.positivity.supplier.internal.vendor.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.positivity.supplier.internal.entity.SupplierVendorEntity;
import com.positivity.supplier.internal.entity.VendorTaxIdCipher;
import com.positivity.supplier.internal.entity.VendorTaxRegistration;
import com.positivity.supplier.internal.enums.TaxIdRevealOutcome;
import com.positivity.supplier.internal.repository.SupplierVendorRepository;
import com.positivity.supplier.internal.repository.SupplierVendorTaxIdRevealRepository;
import com.positivity.supplier.internal.vendor.service.model.TaxIdRevealRequest;
import com.positivity.supplier.internal.vendor.service.model.TaxIdRevealResult;
import com.positivity.supplier.internal.vendor.service.model.TaxIdRevealView;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * #2621 AC 13 (Security ruling on #2617, ruling 4): the reveal fails CLOSED. The audit row is written before
 * the number is returned, in the reveal's own transaction; when it cannot be written, nothing is returned.
 */
@DisplayName("VendorTaxIdRevealService fails closed (#2621 AC 13)")
class VendorTaxIdRevealFailClosedTest {

    private static final UUID TENANT = UUID.fromString("01900000-0000-7000-8000-00000000000a");
    private static final UUID VENDOR = UUID.fromString("01980000-0000-7000-8000-000000000c01");
    private static final UUID REGISTRATION = UUID.fromString("01980000-0000-7000-8000-000000000c02");
    private static final String NUMBER = "000-00-1234";
    private static final String REASON = "Verifying W-9 received 2026-10-08";

    private final SupplierVendorRepository vendors = mock(SupplierVendorRepository.class);
    private final SupplierVendorTaxIdRevealRepository reveals = mock(SupplierVendorTaxIdRevealRepository.class);
    private final VendorTaxIdRevealRecorder recorder = mock(VendorTaxIdRevealRecorder.class);
    private final VendorTaxIdCipher cipher = mock(VendorTaxIdCipher.class);
    private final VendorTaxIdRevealServiceImpl service =
            new VendorTaxIdRevealServiceImpl(vendors, reveals, recorder, cipher);

    private VendorTaxRegistration registration() {
        VendorTaxRegistration registration =
                new VendorTaxRegistration(REGISTRATION, "SSN", null, "1234", "sealed-envelope");
        SupplierVendorEntity vendor = SupplierVendorEntity.builder()
                .vendorId(VENDOR)
                .taxRegistrations(new ArrayList<>(List.of(registration)))
                .build();
        ReflectionTestUtils.setField(vendor, "tenantId", TENANT);
        when(vendors.findById(VENDOR)).thenReturn(Optional.of(vendor));
        when(cipher.open(TENANT, VENDOR, REGISTRATION, "sealed-envelope")).thenReturn(NUMBER);
        return registration;
    }

    @Test
    @DisplayName("an audit insert that fails fails the reveal: no view, no number")
    void auditFailureRevealsNothing() {
        VendorTaxRegistration registration = registration();
        doThrow(new DataIntegrityViolationException("audit insert refused"))
                .when(recorder)
                .record(eq(VENDOR), eq(registration), eq(REASON), any());
        AtomicReference<TaxIdRevealResult> returned = new AtomicReference<>();

        assertThatThrownBy(() -> returned.set(service.reveal(VENDOR, REGISTRATION, new TaxIdRevealRequest(REASON))))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageNotContaining(NUMBER);
        assertThat(returned.get()).as("nothing returned").isNull();
    }

    @Test
    @DisplayName("the row is recorded REVEALED before the view is built")
    void recordsBeforeReturning() {
        VendorTaxRegistration registration = registration();

        TaxIdRevealView view = service.reveal(VENDOR, REGISTRATION, new TaxIdRevealRequest(REASON))
                .view();

        assertThat(view.number()).isEqualTo(NUMBER);
        InOrder order = inOrder(cipher, recorder);
        order.verify(cipher).open(TENANT, VENDOR, REGISTRATION, "sealed-envelope");
        order.verify(recorder).record(VENDOR, registration, REASON, TaxIdRevealOutcome.REVEALED);
    }

    /**
     * The recorder joins the reveal's transaction and refuses to run outside one. {@code REQUIRES_NEW} would
     * commit the row on its own, so a reveal could roll back with its row kept, or the reverse.
     */
    @Test
    @DisplayName("the recorder is MANDATORY, never REQUIRES_NEW; the reveal rolls back on any exception and so never"
            + " throws after a row (ADR-0072 Decision 4)")
    void transactionShape() throws NoSuchMethodException {
        Method record = VendorTaxIdRevealRecorder.class.getMethod(
                "record", UUID.class, VendorTaxRegistration.class, String.class, TaxIdRevealOutcome.class);
        assertThat(record.getAnnotation(Transactional.class).propagation()).isEqualTo(Propagation.MANDATORY);

        Method reveal = VendorTaxIdRevealServiceImpl.class.getMethod(
                "reveal", UUID.class, UUID.class, TaxIdRevealRequest.class);
        Transactional revealTransaction = reveal.getAnnotation(Transactional.class);
        assertThat(revealTransaction.propagation()).isEqualTo(Propagation.REQUIRED);
        assertThat(revealTransaction.noRollbackFor())
                .as("no exception is allowed to commit: refused and unreadable are returned outcomes")
                .isEmpty();
        assertThat(reveal.getReturnType()).isEqualTo(TaxIdRevealResult.class);
    }

    @Test
    @DisplayName("the reason check ignores separators and case, and never matches an empty number")
    void reasonCarriesIgnoresSeparatorsAndCase() {
        assertThat(VendorTaxIdRevealServiceImpl.reasonCarries("checking 000-00-1234 per W-9", NUMBER))
                .isTrue();
        assertThat(VendorTaxIdRevealServiceImpl.reasonCarries("W-9 lists 000001234", NUMBER))
                .isTrue();
        assertThat(VendorTaxIdRevealServiceImpl.reasonCarries("bn fake1234rt on file", "FAKE-1234-RT"))
                .isTrue();
        assertThat(VendorTaxIdRevealServiceImpl.reasonCarries("Verifying W-9 received 2026-10-08", NUMBER))
                .isFalse();
        assertThat(VendorTaxIdRevealServiceImpl.reasonCarries("anything at all here", "--"))
                .isFalse();
    }
}

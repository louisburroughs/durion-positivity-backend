package com.positivity.supplier.service.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.positivity.supplier.internal.service.model.CommercialAccountRequest;
import com.positivity.supplier.internal.service.model.EndpointBindingRequest;
import com.positivity.supplier.internal.service.model.RetryBackoff;
import com.positivity.supplier.internal.service.model.SupplierAccountRole;
import com.positivity.supplier.internal.service.model.VendorProfileRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Invariants of the cross-module contract DTOs in {@code service.model} (ADR-0026,
 * ADR-0049 §4). These records are the public API later capability slices (CAP-319/321/322)
 * and other modules program against, so their constructor contracts are load-bearing:
 * role/location coupling on commercial accounts, line minimums on stock inquiries, and the
 * null-not-zero degradation contract on stock answers.
 */
class ServiceModelInvariantsTest {

    @Nested
    class CommercialAccountRequestInvariants {

        @Test
        void deliveryRoleRequiresDeliveryLocation() {
            // ADR-0050 §5: DELIVERY rows ARE the pos-location -> vendor-account mapping;
            // a DELIVERY account without a location maps nothing.
            assertThatThrownBy(() -> new CommercialAccountRequest(SupplierAccountRole.DELIVERY, "ACC-9", null, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("deliveryLocationId is required for DELIVERY");
        }

        @Test
        void billingRoleForbidsDeliveryLocation() {
            assertThatThrownBy(() -> new CommercialAccountRequest(
                            SupplierAccountRole.BILLING, "ACC-9", "GLN", UUID.randomUUID()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("deliveryLocationId must be null for BILLING");
        }

        @Test
        void validRoleLocationCombinationsConstruct() {
            UUID location = UUID.randomUUID();
            CommercialAccountRequest delivery =
                    new CommercialAccountRequest(SupplierAccountRole.DELIVERY, "ACC-9", null, location);
            CommercialAccountRequest billing =
                    new CommercialAccountRequest(SupplierAccountRole.BILLING, "ACC-1", "GLN", null);

            assertThat(delivery.deliveryLocationId()).isEqualTo(location);
            assertThat(billing.deliveryLocationId()).isNull();
        }

        @Test
        void rejectsBlankAccountNumber() {
            assertThatThrownBy(() ->
                            new CommercialAccountRequest(SupplierAccountRole.DELIVERY, " ", null, UUID.randomUUID()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("accountNumber");
        }
    }

    @Nested
    class StockInquiryRequestInvariants {

        private static final StockInquiryRequest.Line VALID_LINE =
                new StockInquiryRequest.Line("4019238001234", null, 1);

        @Test
        void rejectsEmptyLines() {
            assertThatThrownBy(() ->
                            new StockInquiryRequest(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), List.of()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("lines must not be empty");
        }

        @Test
        void rejectsNullIdentityFields() {
            assertThatNullPointerException()
                    .isThrownBy(() ->
                            new StockInquiryRequest(null, UUID.randomUUID(), UUID.randomUUID(), List.of(VALID_LINE)))
                    .withMessageContaining("inquiryId");
            assertThatNullPointerException()
                    .isThrownBy(() ->
                            new StockInquiryRequest(UUID.randomUUID(), UUID.randomUUID(), null, List.of(VALID_LINE)))
                    .withMessageContaining("deliveryLocationId");
        }

        @Test
        void lineRejectsQuantityBelowOne() {
            assertThatThrownBy(() -> new StockInquiryRequest.Line("4019238001234", null, 0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(">= 1");
        }

        @Test
        void lineRequiresAtLeastOneProductIdentity() {
            assertThatThrownBy(() -> new StockInquiryRequest.Line(null, "  ", 1))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("product identity");
            assertThat(new StockInquiryRequest.Line(null, "MICH-123", 1).supplierArticleCode())
                    .isEqualTo("MICH-123");
        }

        @Test
        void linesAreDefensivelyCopied() {
            List<StockInquiryRequest.Line> source = new ArrayList<>(List.of(VALID_LINE));
            StockInquiryRequest request =
                    new StockInquiryRequest(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), source);

            source.clear();

            assertThat(request.lines()).hasSize(1);
            assertThatThrownBy(() -> request.lines().clear()).isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Nested
    class StockInquiryResponseContract {

        @Test
        void unstatedQuantityAndPriceStayNullNeverZero() {
            // Degradation contract of the single approved synchronous supplier read
            // (ADR-0049 §4): "vendor did not state" must remain distinguishable from
            // "vendor stated zero".
            StockInquiryResponse.Line unstated = new StockInquiryResponse.Line(
                    "4019238001234", null, StockInquiryResponse.LineStatus.NOT_ANSWERED, null, null, null, null);

            assertThat(unstated.availableQuantity()).isNull();
            assertThat(unstated.quotedUnitPrice()).isNull();
        }

        @Test
        void refusesAQuantityOnALineTheVendorNeverAnswered() {
            // The public DTO is the one callers outside this module construct, so the guard has to
            // live here too and not only on the canonical model. NOT_LISTED with a number attached
            // is the shape a consumer would render as fact.
            assertThatThrownBy(() -> new StockInquiryResponse.Line(
                            "4019238001234", null, StockInquiryResponse.LineStatus.NOT_LISTED, 0, null, null, null))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new StockInquiryResponse.Line(
                            "4019238001234", null, StockInquiryResponse.LineStatus.NOT_ANSWERED, 3, null, null, null))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void aVendorStatedZeroIsDistinguishableFromAVendorSayingNothing() {
            // The pair the whole degradation contract turns on: only the first justifies telling a
            // customer the article is out of stock.
            StockInquiryResponse.Line statedNone = new StockInquiryResponse.Line(
                    "4019238001234", null, StockInquiryResponse.LineStatus.UNAVAILABLE, 0, null, null, null);
            StockInquiryResponse.Line saidNothing = new StockInquiryResponse.Line(
                    "4019238001234", null, StockInquiryResponse.LineStatus.NOT_ANSWERED, null, null, null, null);

            assertThat(statedNone.availableQuantity()).isZero();
            assertThat(saidNothing.availableQuantity()).isNull();
        }

        @Test
        void echoesInquiryIdAndCopiesLines() {
            UUID inquiryId = UUID.randomUUID();
            List<StockInquiryResponse.Line> source = new ArrayList<>();
            source.add(new StockInquiryResponse.Line(
                    null, "MICH-123", StockInquiryResponse.LineStatus.UNAVAILABLE, 0, null, null, null));
            StockInquiryResponse response =
                    new StockInquiryResponse(inquiryId, StockInquiryResponse.Status.OK, source, null);

            source.clear();

            assertThat(response.inquiryId()).isEqualTo(inquiryId);
            assertThat(response.lines()).hasSize(1);
            // An explicitly stated zero survives as zero — the inverse of the null contract.
            assertThat(response.lines().getFirst().availableQuantity()).isZero();
        }
    }

    @Nested
    class VendorProfileRequestInvariants {

        @Test
        void nullProtocolDefaultsMeanDeploymentDefault() {
            VendorProfileRequest request =
                    new VendorProfileRequest("michelin-eu", "Michelin EU", true, false, null, null, null, null, null);

            assertThat(request.connectTimeoutMillis()).isNull();
            assertThat(request.maxRetries()).isNull();
            assertThat(request.sandboxBaseUrlOverride()).isNull();
            assertThat(request.retryBackoff()).isNull();
        }

        @Test
        void rejectsBlankSandboxBaseUrlOverride() {
            // A blank override is not "absent": it would resolve to no host at call time.
            assertThatThrownBy(() -> new VendorProfileRequest("m", "M", true, true, null, null, null, "  ", null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("sandboxBaseUrlOverride");
        }

        @Test
        void acceptsSandboxOverlayFields() {
            VendorProfileRequest request = new VendorProfileRequest(
                    "m", "M", true, true, null, null, null, "https://sandbox.example/api", RetryBackoff.FIXED);

            assertThat(request.sandboxBaseUrlOverride()).isEqualTo("https://sandbox.example/api");
            assertThat(request.retryBackoff()).isEqualTo(RetryBackoff.FIXED);
        }

        @Test
        void rejectsNonPositiveTimeoutsAndNegativeRetries() {
            assertThatThrownBy(() -> new VendorProfileRequest("m", "M", true, false, 0, null, null, null, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("connectTimeoutMillis");
            assertThatThrownBy(() -> new VendorProfileRequest("m", "M", true, false, null, -1, null, null, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("readTimeoutMillis");
            assertThatThrownBy(() -> new VendorProfileRequest("m", "M", true, false, null, null, -1, null, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("maxRetries");
            // Zero retries is a legal budget (no pre-send retries), unlike zero timeouts.
            assertThat(new VendorProfileRequest("m", "M", true, false, null, null, 0, null, null).maxRetries())
                    .isZero();
        }
    }

    @Nested
    class EndpointBindingRequestInvariants {

        @Test
        void rejectsBlankKeysNamingTheOffendingField() {
            assertThatThrownBy(() -> new EndpointBindingRequest(
                            "STOCK_INQUIRY",
                            "EDIWHEEL_A25",
                            " ",
                            "https://vendor",
                            "/stock",
                            "basic-main",
                            null,
                            true,
                            null,
                            null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("version must not be blank");
        }
    }
}

package com.positivity.supplier.internal.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Invariants of the canonical supplier model records (ADR-0051 §2). These records are the
 * foundation every later capability slice builds on: construction must reject invalid state
 * (fail at the boundary, not deep in an adapter), collections must be defensively copied, and
 * vendor-unstated numeric fields must stay {@code null} — never coerced to zero.
 */
class DomainModelInvariantsTest {

    @Nested
    class ProtocolVersionInvariants {

        @Test
        void rejectsNullAndBlankValue() {
            assertThatNullPointerException().isThrownBy(() -> new ProtocolVersion(null));
            assertThatThrownBy(() -> new ProtocolVersion("  "))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("blank");
        }

        @Test
        void versionsAreDataNotEnum_constantsEqualFreshInstancesWithSameKey() {
            // Registry triples key on value equality: a codec registered under a constant must
            // resolve for a version key read from vendor-profile data (ADR-0051 §3).
            assertThat(new ProtocolVersion("C1_1")).isEqualTo(ProtocolVersion.C1_1);
            assertThat(List.of(
                            ProtocolVersion.A2_5,
                            ProtocolVersion.B2_1,
                            ProtocolVersion.B3_3,
                            ProtocolVersion.B4_0,
                            ProtocolVersion.C1_0,
                            ProtocolVersion.C1_1,
                            ProtocolVersion.C1_2,
                            ProtocolVersion.S2S_V1))
                    .extracting(ProtocolVersion::value)
                    .containsExactly("A2_5", "B2_1", "B3_3", "B4_0", "C1_0", "C1_1", "C1_2", "S2S_V1");
        }
    }

    @Nested
    class SupplierRefInvariants {

        @Test
        void rejectsNullAndBlankAlias() {
            assertThatNullPointerException().isThrownBy(() -> new SupplierRef(null));
            assertThatThrownBy(() -> new SupplierRef(""))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("blank");
        }

        @Test
        void keepsAliasVerbatim() {
            assertThat(new SupplierRef("michelin-eu").value()).isEqualTo("michelin-eu");
        }
    }

    @Nested
    class PartyContextInvariants {

        @Test
        void rejectsNullAndBlankBillingAccountNumber() {
            assertThatNullPointerException().isThrownBy(() -> new PartyContext(null, null, null));
            assertThatThrownBy(() -> new PartyContext(" ", null, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("billingAccountNumber");
        }

        @Test
        void deliveryLocationIsOptional_nullMeansAccountLevelBatchRead() {
            // PRICAT / invoice fetch are account-level reads with no receiving location
            // (ADR-0050 §5): null deliveryLocationId must be a legal canonical state.
            PartyContext accountLevel = new PartyContext("ACC-1", null, null);
            assertThat(accountLevel.deliveryLocationId()).isNull();

            UUID location = UUID.randomUUID();
            assertThat(new PartyContext("ACC-1", "GLN", location).deliveryLocationId())
                    .isEqualTo(location);
        }
    }

    @Nested
    class SupplierStockInquiryInvariants {

        @Test
        void rejectsEmptyLines() {
            assertThatThrownBy(() -> new SupplierStockInquiry(UUID.randomUUID(), List.of()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("lines must not be empty");
        }

        @Test
        void lineRejectsQuantityBelowOne() {
            assertThatThrownBy(() -> new SupplierStockInquiry.Line("4019238001234", null, 0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(">= 1");
        }

        @Test
        void lineRejectsMissingProductIdentity_blankCountsAsAbsent() {
            assertThatThrownBy(() -> new SupplierStockInquiry.Line("  ", "", 1))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("product identity");
            // Either identity alone is sufficient.
            assertThat(new SupplierStockInquiry.Line(null, "MICH-123", 2).supplierArticleCode())
                    .isEqualTo("MICH-123");
        }

        @Test
        void linesAreDefensivelyCopiedAndImmutable() {
            List<SupplierStockInquiry.Line> source = new ArrayList<>();
            source.add(new SupplierStockInquiry.Line("4019238001234", null, 1));
            SupplierStockInquiry inquiry = new SupplierStockInquiry(UUID.randomUUID(), source);

            source.add(new SupplierStockInquiry.Line(null, "LATE", 1));

            assertThat(inquiry.lines()).hasSize(1);
            assertThatThrownBy(() -> inquiry.lines().clear()).isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Nested
    class SupplierStockInquiryResultContract {

        @Test
        void unstatedQuantityStaysNullNeverZero() {
            // A vendor that did not answer availability must not be mistaken for a vendor
            // with no stock: null is "not stated", 0 is "none available".
            SupplierStockInquiryResult.Line unstated = new SupplierStockInquiryResult.Line(
                    "4019238001234", null, SupplierStockInquiryResult.LineStatus.NOT_ANSWERED, null, null, null, null);

            assertThat(unstated.availableQuantity()).isNull();
            assertThat(unstated.quotedUnitPrice()).isNull();
        }

        @Test
        void anUnansweredLineCannotCarryAQuantity() {
            // The type refuses the combination rather than trusting every caller to avoid it: a
            // quantity on a line the vendor never answered is a number with no author.
            assertThatThrownBy(() -> new SupplierStockInquiryResult.Line(
                            "4019238001234",
                            null,
                            SupplierStockInquiryResult.LineStatus.NOT_LISTED,
                            0,
                            null,
                            null,
                            null))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void anExplicitlyUnavailableLineMayStateZero() {
            // "I have none" is an answer, and the only case where zero is a fact rather than an
            // assumption.
            SupplierStockInquiryResult.Line none = new SupplierStockInquiryResult.Line(
                    "4019238001234", null, SupplierStockInquiryResult.LineStatus.UNAVAILABLE, 0, null, null, null);

            assertThat(none.availableQuantity()).isZero();
        }

        @Test
        void linesAreDefensivelyCopied() {
            List<SupplierStockInquiryResult.Line> source = new ArrayList<>();
            source.add(new SupplierStockInquiryResult.Line(
                    null, "MICH-123", SupplierStockInquiryResult.LineStatus.AVAILABLE, 4, null, null, null));
            SupplierStockInquiryResult result =
                    new SupplierStockInquiryResult(SupplierStockInquiryResult.Status.OK, source, null);

            source.clear();

            assertThat(result.lines()).hasSize(1);
        }
    }

    @Nested
    class ExchangeMetadataInvariants {

        @Test
        void unmappedFieldsAreDefensivelyCopiedAndImmutable() {
            // ADR-0051 §4: unmapped canonical fields are recorded per exchange so nothing is
            // silently dropped — the recorded list must not be mutable after the fact.
            List<String> source = new ArrayList<>(List.of("liner.notes"));
            ExchangeMetadata metadata = new ExchangeMetadata(
                    UUID.randomUUID(), "corr-1", ProtocolFamily.EDIWHEEL_C1, ProtocolVersion.C1_1, source);

            source.add("late.field");

            assertThat(metadata.unmappedFields()).containsExactly("liner.notes");
            assertThatThrownBy(() -> metadata.unmappedFields().add("x"))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }
}
